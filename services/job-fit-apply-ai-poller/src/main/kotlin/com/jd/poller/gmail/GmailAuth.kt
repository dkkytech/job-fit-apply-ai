package com.jd.poller.gmail

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.auth.oauth2.CredentialRefreshListener
import com.google.api.client.auth.oauth2.TokenErrorResponse
import com.google.api.client.auth.oauth2.TokenResponse
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.jd.poller.config.PollerConfig
import com.jd.poller.health.Heartbeat
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object GmailAuth {

    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.compose"
    )

    private const val REDIRECT_URI = "http://localhost"

    // Startup refresh retries on transient failures (network blip, DNS, 5xx): 2s, 4s, 8s. After
    // that the stored token is kept and the Credential refreshes lazily on the first Gmail call,
    // so the poll loops keep retrying every tick instead of intake stopping for a manual --reauth.
    internal const val REFRESH_ATTEMPTS = 4
    internal const val REFRESH_BACKOFF_MS = 2_000L

    enum class TokenStatus {
        VALID,
        EXPIRED,
        INVALID,
        MISSING,
        /** The token endpoint could not be reached (network / 5xx) — the token itself may be fine. */
        UNREACHABLE
    }

    /** Outcome of one refresh attempt: only [Dead] means the stored token must be replaced. */
    internal sealed interface RefreshResult {
        object Refreshed : RefreshResult
        data class Dead(val reason: String) : RefreshResult
        data class Transient(val reason: String) : RefreshResult
    }

    /**
     * Refresh [credential] once and classify the outcome. Only a genuine rejection from the token
     * endpoint (invalid_grant, or a 400/401) is [RefreshResult.Dead]; I/O errors, timeouts, 429 and
     * 5xx are [RefreshResult.Transient] and must never cost us the stored refresh token.
     */
    internal fun refresh(credential: Credential): RefreshResult {
        if (credential.refreshToken.isNullOrBlank()) {
            return RefreshResult.Dead("stored token has no refresh_token")
        }
        return try {
            // Credential.refreshToken() swallows 5xx token-endpoint errors and returns false.
            if (credential.refreshToken()) RefreshResult.Refreshed
            else RefreshResult.Transient("token endpoint returned a server error")
        } catch (e: TokenResponseException) {
            val reason = "HTTP ${e.statusCode} ${e.details?.error ?: ""}: ${e.details?.errorDescription ?: e.message}"
            if (isDeadGrant(e)) RefreshResult.Dead(reason) else RefreshResult.Transient(reason)
        } catch (e: IOException) {
            RefreshResult.Transient("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    internal fun isDeadGrant(e: TokenResponseException): Boolean =
        e.details?.error == "invalid_grant" || e.statusCode == 400 || e.statusCode == 401

    data class TokenCheckResult(
        val status: TokenStatus,
        val message: String,
        val emailAddress: String? = null
    )

    fun generateToken(
        credentialsFile: String = PollerConfig.GMAIL_CREDENTIALS_FILE,
        tokenFile: String = PollerConfig.GMAIL_TOKEN_FILE
    ) {
        val flow = buildAuthorizationCodeFlow(credentialsFile)
        val authUrl = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build()

        // Browser-free (container-friendly): print the URL, the operator authorizes in their own
        // browser and pastes the redirect URL back. No Playwright / headful Chrome.
        val code = captureCodeManually(authUrl)

        val response = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute()
        val tokenPath = Path.of(tokenFile)
        tokenPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))
        println("[GmailAuth] Token saved to: $tokenFile")
    }

    private fun captureCodeManually(authUrl: String): String {
        println()
        println("Open this URL in your browser:")
        println("  $authUrl")
        println()
        println("After being redirected to http://localhost, paste the full URL and press Enter:")
        print("> ")
        System.out.flush()

        val redirectUrl = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
            .readLine()?.trim()
            ?: throw IllegalStateException("No URL provided — stdin was closed")

        return Regex("""[?&]code=([^&\s]+)""").find(redirectUrl)?.groupValues?.get(1)
            ?: throw IllegalStateException("No authorization code found in URL")
    }

    fun checkTokenStatus(
        credentialsFile: String = PollerConfig.GMAIL_CREDENTIALS_FILE,
        tokenFile: String = PollerConfig.GMAIL_TOKEN_FILE,
        transport: HttpTransport = NetHttpTransport(),
        authHealth: Heartbeat = Heartbeat.fromConfig(PollerConfig.HEARTBEAT_FILE),
    ): TokenCheckResult {
        val tokenPath = Path.of(tokenFile)

        if (!Files.exists(tokenPath)) {
            return TokenCheckResult(
                status = TokenStatus.MISSING,
                message = "Token file MISSING - run with --reauth to obtain a new token"
            )
        }

        println("[GmailAuth] Token file found: $tokenFile")

        val credential = try {
            val flow = buildAuthorizationCodeFlow(credentialsFile, transport, refreshListener(tokenPath, authHealth))
            loadStoredCredential(flow, tokenPath)
        } catch (e: Exception) {
            return TokenCheckResult(
                status = TokenStatus.INVALID,
                message = "Token corrupted or INVALID - run with --reauth to obtain a new token"
            )
        }

        return when (val result = refresh(credential)) {
            is RefreshResult.Refreshed -> {
                val email = try {
                    Gmail.Builder(transport, GsonFactory.getDefaultInstance(), credential)
                        .setApplicationName("JD Poller").build()
                        .users().getProfile("me").execute().emailAddress
                } catch (e: Exception) {
                    null
                }
                TokenCheckResult(
                    status = TokenStatus.VALID,
                    message = "Token is VALID (refresh successful)",
                    emailAddress = email
                )
            }
            is RefreshResult.Dead -> TokenCheckResult(
                status = TokenStatus.EXPIRED,
                message = "Token EXPIRED or REVOKED (${result.reason}) - run with --reauth to obtain a new token"
            )
            is RefreshResult.Transient -> TokenCheckResult(
                status = TokenStatus.UNREACHABLE,
                message = "Could not reach the Google token endpoint (${result.reason}) - token kept; retry later"
            )
        }
    }

    fun initiateOAuthFlow(): String {
        val flow = buildAuthorizationCodeFlow()
        return flow.newAuthorizationUrl()
            .setRedirectUri(REDIRECT_URI)
            .build()
    }

    fun exchangeRedirectUrlForToken(redirectUrl: String): Boolean {
        val code = parseAuthCodeFromUrl(redirectUrl)
        if (code == null) {
            println("[ERROR] No authorization code found in URL")
            return false
        }

        return try {
            exchangeCodeForToken(code)
            true
        } catch (e: Exception) {
            println("[ERROR] Failed to exchange authorization code: ${e.message}")
            false
        }
    }

    fun verifyCredentials(): Boolean {
        return try {
            val credential = getCredentials()
            val tempService = Gmail.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("JD Poller").build()
            tempService.users().labels().list("me").execute()
            true
        } catch (e: Exception) {
            println("[ERROR] Credential verification failed: ${e.message}")
            false
        }
    }

    fun deleteToken(): Boolean {
        val tokenPath = Path.of(PollerConfig.GMAIL_TOKEN_FILE)
        return Files.deleteIfExists(tokenPath)
    }

    private fun parseAuthCodeFromUrl(url: String): String? {
        val pattern = Regex("""code=([^&\s]+)""")
        return pattern.find(url)?.groupValues?.getOrNull(1)
    }

    private fun exchangeCodeForToken(authorizationCode: String) {
        val flow = buildAuthorizationCodeFlow()
        val response = flow.newTokenRequest(authorizationCode)
            .setRedirectUri(REDIRECT_URI)
            .execute()
        val tokenPath = Path.of(PollerConfig.GMAIL_TOKEN_FILE)
        tokenPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))
        flow.createAndStoreCredential(response, null)
        println("[GmailAuth] Token stored at: ${PollerConfig.GMAIL_TOKEN_FILE}")
    }

    private fun buildAuthorizationCodeFlow(
        credentialsFile: String = PollerConfig.GMAIL_CREDENTIALS_FILE,
        transport: HttpTransport = NetHttpTransport(),
        refreshListener: CredentialRefreshListener? = null,
    ): GoogleAuthorizationCodeFlow {
        val clientSecrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            InputStreamReader(FileInputStream(credentialsFile))
        )

        val builder = GoogleAuthorizationCodeFlow.Builder(
            transport,
            GsonFactory.getDefaultInstance(),
            clientSecrets,
            SCOPES
        )
            // Request a refresh token so cached tokens can auto-renew; force the
            // consent screen so Google re-issues one even on repeat authorizations.
            .setAccessType("offline")
            .setApprovalPrompt("force")
        refreshListener?.let { builder.addRefreshListener(it) }
        return builder.build()
    }

    private fun loadStoredCredential(flow: GoogleAuthorizationCodeFlow, tokenPath: Path): Credential {
        val response = Files.newInputStream(tokenPath).use {
            GsonFactory.getDefaultInstance().fromInputStream(it, GoogleTokenResponse::class.java)
        }
        return flow.createAndStoreCredential(response, null)
    }

    /**
     * Observes every refresh, including the lazy ones the Credential performs mid-run:
     *  - a rejected grant marks auth as failed so `--health` (and so `make doctor`) reports it even
     *    though the loops keep beating;
     *  - a success clears that mark and persists a rotated refresh token (Google normally keeps the
     *    same one, in which case the file is left untouched).
     */
    private fun refreshListener(tokenPath: Path, authHealth: Heartbeat) = object : CredentialRefreshListener {
        override fun onTokenResponse(credential: Credential, tokenResponse: TokenResponse) {
            authHealth.clearAuthFailure()
            if (tokenResponse.refreshToken != null) persistRotatedRefreshToken(tokenPath, tokenResponse)
        }

        override fun onTokenErrorResponse(credential: Credential, tokenErrorResponse: TokenErrorResponse?) {
            if (tokenErrorResponse?.error == "invalid_grant") {
                authHealth.markAuthFailed(
                    "Gmail refresh token rejected (invalid_grant: ${tokenErrorResponse.errorDescription}) - run --reauth"
                )
            }
        }
    }

    /**
     * Write a rotated refresh token back to [tokenPath]. Only rotation is persisted — rewriting on
     * every access-token refresh would be pointless (startup always refreshes) — and the file's
     * mtime is preserved so it keeps meaning "time of consent", which scripts/doctor.sh shows as
     * the token's consent date. That date is context only: the OAuth app is in Production, so the
     * refresh token has no fixed lifetime, and doctor judges auth health by the poller's
     * auth-failure mark ([Heartbeat.markAuthFailed]) instead.
     */
    internal fun persistRotatedRefreshToken(tokenPath: Path, tokenResponse: TokenResponse) {
        try {
            val json = GsonFactory.getDefaultInstance()
            val stored = Files.newInputStream(tokenPath).use { json.fromInputStream(it, GoogleTokenResponse::class.java) }
            if (stored.refreshToken == tokenResponse.refreshToken) return
            stored.refreshToken = tokenResponse.refreshToken
            tokenResponse.accessToken?.let { stored.accessToken = it }
            tokenResponse.expiresInSeconds?.let { stored.expiresInSeconds = it }
            val mtime = Files.getLastModifiedTime(tokenPath)
            val tmp = tokenPath.resolveSibling("${tokenPath.fileName}.tmp")
            Files.writeString(tmp, json.toPrettyString(stored))
            Files.move(tmp, tokenPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Files.setLastModifiedTime(tokenPath, mtime)
            println("[GmailAuth] Persisted rotated refresh token to: $tokenPath")
        } catch (e: Exception) {
            System.err.println("[GmailAuth] WARN could not persist rotated refresh token: ${e.message}")
        }
    }

    /**
     * Load the stored token and prove it by refreshing. Only a rejected grant (see [refresh]) deletes
     * the token and falls through to [reauthenticate]; transient failures are retried with bounded
     * backoff and then the stored credential is returned as-is (it refreshes itself on first use).
     */
    fun getCredentials(
        credentialsFile: String = PollerConfig.GMAIL_CREDENTIALS_FILE,
        tokenFile: String = PollerConfig.GMAIL_TOKEN_FILE,
        transport: HttpTransport = NetHttpTransport(),
        authHealth: Heartbeat = Heartbeat.fromConfig(PollerConfig.HEARTBEAT_FILE),
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        reauthenticate: (GoogleAuthorizationCodeFlow) -> Credential = {
            AuthorizationCodeInstalledApp(it, REDIRECT_URI, Path.of(tokenFile)).run()
        },
    ): Credential {
        val tokenPath = Path.of(tokenFile)
        // Built outside any catch: a missing/unreadable client-secrets file is a config error, not a
        // reason to throw away the token.
        val flow = buildAuthorizationCodeFlow(credentialsFile, transport, refreshListener(tokenPath, authHealth))

        if (!Files.exists(tokenPath)) {
            println("[GmailAuth] No stored token found at: $tokenFile")
            return reauth(flow, authHealth, reauthenticate)
        }

        println("[GmailAuth] Found stored token at: $tokenFile")
        val credential = try {
            loadStoredCredential(flow, tokenPath)
        } catch (e: Exception) {
            println("[GmailAuth] Failed to load stored token: ${e.message}")
            return discardAndReauth(tokenPath, "stored token unreadable: ${e.message}", flow, authHealth, reauthenticate)
        }

        println("[GmailAuth] Testing stored token by refreshing...")
        var attempt = 1
        while (true) {
            when (val result = refresh(credential)) {
                is RefreshResult.Refreshed -> {
                    println("[GmailAuth] Stored token is valid")
                    authHealth.clearAuthFailure()
                    return credential
                }
                is RefreshResult.Dead -> {
                    println("[GmailAuth] Stored token expired or revoked: ${result.reason}")
                    return discardAndReauth(tokenPath, result.reason, flow, authHealth, reauthenticate)
                }
                is RefreshResult.Transient -> {
                    if (attempt >= REFRESH_ATTEMPTS) {
                        System.err.println(
                            "[GmailAuth] WARN token refresh still failing after $attempt attempts (${result.reason}); " +
                                "keeping $tokenFile — the poll loops will retry the refresh on their next tick"
                        )
                        return credential
                    }
                    val delayMs = REFRESH_BACKOFF_MS shl (attempt - 1)
                    System.err.println(
                        "[GmailAuth] WARN transient token refresh failure (${result.reason}); " +
                            "retry $attempt/${REFRESH_ATTEMPTS - 1} in ${delayMs}ms"
                    )
                    sleep(delayMs)
                    attempt++
                }
            }
        }
    }

    private fun discardAndReauth(
        tokenPath: Path,
        reason: String,
        flow: GoogleAuthorizationCodeFlow,
        authHealth: Heartbeat,
        reauthenticate: (GoogleAuthorizationCodeFlow) -> Credential,
    ): Credential {
        authHealth.markAuthFailed("Gmail token rejected ($reason) - run --reauth")
        println("[GmailAuth] Deleting token file and re-authenticating...")
        Files.deleteIfExists(tokenPath)
        return reauth(flow, authHealth, reauthenticate)
    }

    private fun reauth(
        flow: GoogleAuthorizationCodeFlow,
        authHealth: Heartbeat,
        reauthenticate: (GoogleAuthorizationCodeFlow) -> Credential,
    ): Credential {
        println("[GmailAuth] Initiating OAuth flow for new credentials...")
        return reauthenticate(flow).also { authHealth.clearAuthFailure() }
    }

    private class AuthorizationCodeInstalledApp(
        private val flow: GoogleAuthorizationCodeFlow,
        private val redirectUri: String,
        private val tokenPath: Path,
    ) {
        fun run(): Credential {
            val url = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .build()
            println("Please open the following URL in your browser:")
            println(url)
            println("Waiting for authorization code...")

            val reader = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))
            val code = reader.readLine()

            if (code.isNullOrBlank()) {
                throw IllegalStateException(
                    "Authorization code is empty or stdin was closed. " +
                    "Please run the application interactively and paste the code from the browser."
                )
            }

            val response = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute()

            tokenPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))

            return flow.createAndStoreCredential(response, null)
        }
    }
}
