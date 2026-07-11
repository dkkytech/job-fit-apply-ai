package com.jd.pipeline.client.gmail

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.jd.pipeline.config.Config
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object GmailAuth {

    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/gmail.compose"
    )

    private const val REDIRECT_URI = "http://localhost"

    enum class TokenStatus {
        VALID,
        EXPIRED,
        INVALID,
        MISSING
    }

    data class TokenCheckResult(
        val status: TokenStatus,
        val message: String,
        val emailAddress: String? = null
    )

    fun generateToken(
        credentialsFile: String = Config.GMAIL_CREDENTIALS_FILE,
        tokenFile: String = Config.GMAIL_TOKEN_FILE
    ) {
        val clientSecrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            InputStreamReader(FileInputStream(credentialsFile))
        )
        val flow = GoogleAuthorizationCodeFlow.Builder(
            NetHttpTransport(), GsonFactory.getDefaultInstance(), clientSecrets, SCOPES
        ).build()
        val authUrl = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build()

        val code = captureCodeWithPlaywright(authUrl) ?: captureCodeManually(authUrl)

        val response = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute()
        val tokenPath = Path.of(tokenFile)
        tokenPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))
        println("[GmailAuth] Token saved to: $tokenFile")
    }

    private fun captureCodeWithPlaywright(authUrl: String): String? {
        return try {
            println("[GmailAuth] Launching browser for OAuth — complete the consent screen...")
            var capturedCode: String? = null

            val tempDir = Files.createTempDirectory("gmail-oauth-")

            Playwright.create().use { playwright ->
                val launchOptions = BrowserType.LaunchPersistentContextOptions()
                    .setExecutablePath(Paths.get(Config.CHROME_EXECUTABLE_PATH))
                    .setHeadless(false)

                playwright.chromium().launchPersistentContext(
                    tempDir, launchOptions
                ).use { context ->
                    context.route("http://localhost/**") { route ->
                        val url = route.request().url()
                        capturedCode = Regex("""[?&]code=([^&\s]+)""").find(url)?.groupValues?.get(1)
                        route.abort()
                    }

                    val page = context.newPage()
                    page.navigate(authUrl, Page.NavigateOptions().setTimeout(30_000.0))

                    val deadline = System.currentTimeMillis() + 120_000
                    while (capturedCode == null) {
                        if (System.currentTimeMillis() > deadline) {
                            throw IllegalStateException("OAuth timed out — no authorization received within 2 minutes")
                        }
                        page.waitForTimeout(500.0)
                    }
                }
            }
            capturedCode
        } catch (e: Exception) {
            println("[GmailAuth] Browser-based OAuth failed (${e.message}), falling back to manual flow...")
            null
        }
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

    fun checkTokenStatus(): TokenCheckResult {
        val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)

        if (!Files.exists(tokenPath)) {
            return TokenCheckResult(
                status = TokenStatus.MISSING,
                message = "Token file MISSING - run with --reauth to obtain a new token"
            )
        }

        println("[GmailAuth] Token file found: ${Config.GMAIL_TOKEN_FILE}")

        try {
            val clientSecrets = GoogleClientSecrets.load(
                GsonFactory.getDefaultInstance(),
                InputStreamReader(FileInputStream(Config.GMAIL_CREDENTIALS_FILE))
            )

            val tokenStream = Files.newInputStream(tokenPath)
            val response = GsonFactory.getDefaultInstance()
                .fromInputStream(tokenStream, GoogleTokenResponse::class.java)

            val flow = GoogleAuthorizationCodeFlow.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                clientSecrets,
                SCOPES
            ).build()

            val credential = flow.createAndStoreCredential(response, null)

            try {
                credential.refreshToken()
                val email = try {
                    val tempService = Gmail.Builder(
                        NetHttpTransport(),
                        GsonFactory.getDefaultInstance(),
                        credential
                    ).setApplicationName("JD Pipeline").build()
                    tempService.users().getProfile("me").execute().emailAddress
                } catch (e: Exception) {
                    null
                }
                return TokenCheckResult(
                    status = TokenStatus.VALID,
                    message = "Token is VALID (refresh successful)",
                    emailAddress = email
                )
            } catch (e: com.google.api.client.auth.oauth2.TokenResponseException) {
                return TokenCheckResult(
                    status = TokenStatus.EXPIRED,
                    message = "Token EXPIRED - run with --reauth to obtain a new token"
                )
            } catch (e: java.io.IOException) {
                return TokenCheckResult(
                    status = TokenStatus.INVALID,
                    message = "Token REVOKED or INVALID - run with --reauth to obtain a new token"
                )
            }
        } catch (e: Exception) {
            return TokenCheckResult(
                status = TokenStatus.INVALID,
                message = "Token corrupted or INVALID - run with --reauth to obtain a new token"
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
            ).setApplicationName("JD Pipeline").build()
            tempService.users().labels().list("me").execute()
            true
        } catch (e: Exception) {
            println("[ERROR] Credential verification failed: ${e.message}")
            false
        }
    }

    fun deleteToken(): Boolean {
        val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)
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
        val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)
        tokenPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))
        flow.createAndStoreCredential(response, null)
        println("[GmailAuth] Token stored at: ${Config.GMAIL_TOKEN_FILE}")
    }

    private fun buildAuthorizationCodeFlow(): GoogleAuthorizationCodeFlow {
        val clientSecrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            InputStreamReader(FileInputStream(Config.GMAIL_CREDENTIALS_FILE))
        )

        return GoogleAuthorizationCodeFlow.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            clientSecrets,
            SCOPES
        ).build()
    }

    fun getCredentials(): Credential {
        val clientSecrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            InputStreamReader(FileInputStream(Config.GMAIL_CREDENTIALS_FILE))
        )

        val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)
        if (Files.exists(tokenPath)) {
            println("[GmailAuth] Found stored token at: ${Config.GMAIL_TOKEN_FILE}")
            try {
                val tokenStream = Files.newInputStream(tokenPath)
                val response = GsonFactory.getDefaultInstance()
                    .fromInputStream(tokenStream, GoogleTokenResponse::class.java)

                val flow = GoogleAuthorizationCodeFlow.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    clientSecrets,
                    SCOPES
                ).build()

                val credential = flow.createAndStoreCredential(response, null)

                try {
                    println("[GmailAuth] Testing stored token by refreshing...")
                    credential.refreshToken()
                    println("[GmailAuth] Stored token is valid")
                    return credential
                } catch (e: com.google.api.client.auth.oauth2.TokenResponseException) {
                    println("[GmailAuth] Stored token expired or revoked (TokenResponseException): ${e.message}")
                    println("[GmailAuth] Deleting token file and re-authenticating...")
                    Files.deleteIfExists(tokenPath)
                } catch (e: java.io.IOException) {
                    println("[GmailAuth] Token refresh failed (IOException): ${e.message}")
                    println("[GmailAuth] Deleting token file and re-authenticating...")
                    Files.deleteIfExists(tokenPath)
                }
            } catch (e: Exception) {
                println("[GmailAuth] Failed to load stored token: ${e.message}")
                println("[GmailAuth] Deleting token file and re-authenticating...")
                Files.deleteIfExists(tokenPath)
            }
        } else {
            println("[GmailAuth] No stored token found at: ${Config.GMAIL_TOKEN_FILE}")
        }

        println("[GmailAuth] Initiating OAuth flow for new credentials...")
        val flow = GoogleAuthorizationCodeFlow.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            clientSecrets,
            SCOPES
        ).build()

        val redirectUri = "http://localhost"
        val app = AuthorizationCodeInstalledApp(flow, redirectUri)
        return app.run()
    }

    private class AuthorizationCodeInstalledApp(
        private val flow: GoogleAuthorizationCodeFlow,
        private val redirectUri: String
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

            val tokenPath = Path.of(Config.GMAIL_TOKEN_FILE)
            tokenPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(tokenPath, GsonFactory.getDefaultInstance().toPrettyString(response))

            return flow.createAndStoreCredential(response, null)
        }
    }
}
