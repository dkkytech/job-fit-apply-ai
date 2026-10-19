package com.jd.poller.gmail

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.jd.poller.cli.Main
import com.jd.poller.health.Heartbeat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * GmailAuth's startup refresh must only discard the stored token when Google actually rejects the
 * grant. The token endpoint is driven through a scripted [HttpTransport], so the real
 * google-api-client refresh code (Credential.refreshToken → TokenResponseException parsing, 5xx
 * swallowing, refresh listeners) runs exactly as in production — only the wire is faked.
 */
@DisplayName("GmailAuthTest")
class GmailAuthTest {

    /** Serves one scripted token-endpoint outcome per request, in order. */
    private class ScriptedTransport(vararg steps: () -> LowLevelHttpResponse) : HttpTransport() {
        private val queue = ArrayDeque(steps.toList())
        var requests = 0

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest =
            object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    requests++
                    return queue.removeFirst()()
                }
            }
    }

    private fun json(status: Int, body: String): () -> LowLevelHttpResponse = {
        MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json; charset=UTF-8").setContent(body)
    }

    private val refreshed = json(200, """{"access_token":"at-new","expires_in":3599,"token_type":"Bearer"}""")
    private val invalidGrant = json(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
    private val serverError = json(503, """{"error":"backend_error"}""")
    private val dnsFailure: () -> LowLevelHttpResponse = { throw UnknownHostException("oauth2.googleapis.com") }

    private class Rig(dir: Path) {
        val credentialsFile: Path = dir.resolve("credentials.json").also {
            Files.writeString(
                it,
                """{"installed":{"client_id":"cid","client_secret":"secret",""" +
                    """"auth_uri":"https://accounts.google.com/o/oauth2/auth",""" +
                    """"token_uri":"https://oauth2.googleapis.com/token","redirect_uris":["http://localhost"]}}"""
            )
        }
        val tokenFile: Path = dir.resolve("tokens/gmail_token.json").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, """{"access_token":"at-old","refresh_token":"rt-1","expires_in":3599,"token_type":"Bearer"}""")
        }
        val originalToken: String = Files.readString(tokenFile)
        val heartbeat = Heartbeat(dir.resolve("hb"))
        val sleeps = mutableListOf<Long>()
        val reauthCredential: Credential = mock()
        val reauth: (GoogleAuthorizationCodeFlow) -> Credential = mock<(GoogleAuthorizationCodeFlow) -> Credential>().also {
            whenever(it.invoke(any())).thenReturn(reauthCredential)
        }

        fun getCredentials(transport: HttpTransport): Credential = GmailAuth.getCredentials(
            credentialsFile = credentialsFile.toString(),
            tokenFile = tokenFile.toString(),
            transport = transport,
            authHealth = heartbeat,
            sleep = { sleeps += it },
            reauthenticate = reauth,
        )

        fun checkTokenStatus(transport: HttpTransport) = GmailAuth.checkTokenStatus(
            credentialsFile = credentialsFile.toString(),
            tokenFile = tokenFile.toString(),
            transport = transport,
            authHealth = heartbeat,
        )
    }

    // ── getCredentials: transient failures keep the token ─────────────────────

    @Test
    @DisplayName("an IOException on every refresh keeps the token file, backs off, and never re-auths")
    fun ioExceptionKeepsTokenFile(@TempDir dir: Path) {
        val rig = Rig(dir)
        val transport = ScriptedTransport(dnsFailure, dnsFailure, dnsFailure, dnsFailure)

        val credential = rig.getCredentials(transport)

        assertTrue(Files.exists(rig.tokenFile), "token file must survive a network failure")
        assertEquals(rig.originalToken, Files.readString(rig.tokenFile))
        assertEquals("rt-1", credential.refreshToken, "the stored credential is returned for lazy refresh")
        assertEquals(GmailAuth.REFRESH_ATTEMPTS, transport.requests)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), rig.sleeps, "bounded exponential backoff")
        verifyNoInteractions(rig.reauth)
        assertNull(rig.heartbeat.authFailure(), "a network blip is not an auth failure")
    }

    @Test
    @DisplayName("a 5xx then a success recovers after one retry and clears a stale auth-failure mark")
    fun serverErrorThenSuccessRecovers(@TempDir dir: Path) {
        val rig = Rig(dir)
        rig.heartbeat.markAuthFailed("stale mark from a previous run")
        val transport = ScriptedTransport(serverError, refreshed)

        val credential = rig.getCredentials(transport)

        assertEquals("at-new", credential.accessToken)
        assertEquals(listOf(2_000L), rig.sleeps)
        assertEquals(rig.originalToken, Files.readString(rig.tokenFile), "no rotation → file untouched")
        verifyNoInteractions(rig.reauth)
        assertNull(rig.heartbeat.authFailure())
    }

    @Test
    @DisplayName("a 429 from the token endpoint is transient, not a dead grant")
    fun rateLimitIsTransient(@TempDir dir: Path) {
        val rig = Rig(dir)
        val transport = ScriptedTransport(json(429, """{"error":"rate_limit_exceeded"}"""), refreshed)

        rig.getCredentials(transport)

        assertTrue(Files.exists(rig.tokenFile))
        verifyNoInteractions(rig.reauth)
    }

    // ── getCredentials: a rejected grant takes the re-auth path ────────────────

    @Test
    @DisplayName("invalid_grant deletes the token, marks auth failed, and runs the re-auth flow")
    fun invalidGrantTriggersReauth(@TempDir dir: Path) {
        val rig = Rig(dir)
        val transport = ScriptedTransport(invalidGrant)

        val credential = rig.getCredentials(transport)

        assertFalse(Files.exists(rig.tokenFile), "a revoked token is discarded")
        verify(rig.reauth, times(1)).invoke(any())
        assertSame(rig.reauthCredential, credential)
        assertEquals(1, transport.requests, "a dead grant is never retried")
        assertTrue(rig.sleeps.isEmpty())
    }

    @Test
    @DisplayName("invalid_grant whose re-auth cannot complete (no stdin in the container) leaves auth marked failed")
    fun invalidGrantWithFailedReauthStaysMarked(@TempDir dir: Path) {
        val rig = Rig(dir)
        whenever(rig.reauth.invoke(any())).thenThrow(IllegalStateException("stdin was closed"))

        assertThrows<IllegalStateException> { rig.getCredentials(ScriptedTransport(invalidGrant)) }

        assertFalse(Files.exists(rig.tokenFile))
        val failure = assertNotNull(rig.heartbeat.authFailure())
        assertContains(failure, "invalid_grant")
    }

    @Test
    @DisplayName("a 401 from the token endpoint is treated as a dead grant")
    fun unauthorizedIsDead(@TempDir dir: Path) {
        val rig = Rig(dir)

        rig.getCredentials(ScriptedTransport(json(401, """{"error":"invalid_client"}""")))

        assertFalse(Files.exists(rig.tokenFile))
        verify(rig.reauth).invoke(any())
    }

    @Test
    @DisplayName("a missing client-secrets file is a config error and never deletes the token")
    fun missingClientSecretsKeepsToken(@TempDir dir: Path) {
        val rig = Rig(dir)
        Files.delete(rig.credentialsFile)

        assertThrows<java.io.FileNotFoundException> { rig.getCredentials(ScriptedTransport()) }

        assertTrue(Files.exists(rig.tokenFile))
        verifyNoInteractions(rig.reauth)
    }

    // ── runtime (lazy) refresh + rotation ──────────────────────────────────────

    @Test
    @DisplayName("an invalid_grant during a mid-run lazy refresh marks auth failed so --health reports it")
    fun runtimeInvalidGrantMarksHealth(@TempDir dir: Path) {
        val rig = Rig(dir)
        // Startup refresh succeeds; the next (lazy, mid-run) refresh is rejected.
        val credential = rig.getCredentials(ScriptedTransport(refreshed, invalidGrant))
        rig.heartbeat.beat(now = 1_000L)
        assertNull(Main.healthProblem(rig.heartbeat, maxAgeMs = 5_000L, now = 2_000L), "healthy after startup")

        assertThrows<TokenResponseException> { credential.refreshToken() }

        val problem = assertNotNull(Main.healthProblem(rig.heartbeat, maxAgeMs = 5_000L, now = 2_000L))
        assertContains(problem, "invalid_grant")
        assertTrue(Files.exists(rig.tokenFile), "mid-run the file is left for the next startup to judge")
    }

    @Test
    @DisplayName("a rotated refresh token is persisted, keeping the file's mtime as the consent date")
    fun rotatedRefreshTokenPersisted(@TempDir dir: Path) {
        val rig = Rig(dir)
        val consentTime = FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(rig.tokenFile, consentTime)
        val rotated = json(200, """{"access_token":"at-new","refresh_token":"rt-2","expires_in":3599,"token_type":"Bearer"}""")

        rig.getCredentials(ScriptedTransport(rotated))

        val saved = Files.readString(rig.tokenFile)
        assertContains(saved, "\"rt-2\"")
        assertContains(saved, "\"at-new\"")
        assertEquals(consentTime, Files.getLastModifiedTime(rig.tokenFile))
    }

    // ── checkTokenStatus (--check-token / startup log) ─────────────────────────

    @Test
    @DisplayName("checkTokenStatus reports UNREACHABLE (not INVALID) on a network failure and keeps the token")
    fun checkTokenStatusNetworkFailure(@TempDir dir: Path) {
        val rig = Rig(dir)

        val result = rig.checkTokenStatus(ScriptedTransport(dnsFailure))

        assertEquals(GmailAuth.TokenStatus.UNREACHABLE, result.status)
        assertTrue(Files.exists(rig.tokenFile))
    }

    @Test
    @DisplayName("checkTokenStatus reports EXPIRED on invalid_grant")
    fun checkTokenStatusInvalidGrant(@TempDir dir: Path) {
        val rig = Rig(dir)

        val result = rig.checkTokenStatus(ScriptedTransport(invalidGrant))

        assertEquals(GmailAuth.TokenStatus.EXPIRED, result.status)
        assertContains(result.message, "--reauth")
    }
}
