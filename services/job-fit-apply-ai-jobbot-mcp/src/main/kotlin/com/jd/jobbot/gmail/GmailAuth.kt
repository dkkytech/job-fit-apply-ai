package com.jd.jobbot.gmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Access tokens for the poller's Gmail account, minted from the poller's own refresh token.
 *
 * The token file belongs to the poller: it is mounted read-only here and this class never writes,
 * deletes or re-authorizes it. Access tokens live in memory only. When a refresh fails — or the
 * file changes under us, because the poller re-authed — the file is re-read. A dead grant is
 * reported ("needs re-auth via the poller"), never repaired here.
 */
open class GmailAuth(
    private val tokenFile: Path,
    private val credentialsFile: Path,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val clock: Clock = Clock.systemUTC(),
) {
    sealed interface State {
        data object Ok : State
        data class NeedsReauth(val reason: String) : State
        data class Unavailable(val reason: String) : State
    }

    class GmailAuthException(val state: State) : RuntimeException(
        when (state) {
            is State.NeedsReauth -> "Gmail needs re-auth (${state.reason}): docker compose run --rm poller --reauth"
            is State.Unavailable -> "Gmail token refresh failed: ${state.reason}"
            State.Ok -> "ok"
        },
    )

    private val log = LoggerFactory.getLogger(GmailAuth::class.java)
    private data class FileStamp(val key: Any?, val modified: Long, val size: Long)

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt: Instant = Instant.EPOCH
    @Volatile private var stamp: FileStamp? = null
    @Volatile var state: State = State.Ok
        private set

    @Synchronized
    open fun token(forceRefresh: Boolean = false): String {
        val current = currentStamp()
        val fileChanged = current != stamp
        if (!forceRefresh && !fileChanged && accessToken != null && clock.instant().isBefore(expiresAt.minusSeconds(60))) {
            return accessToken!!
        }
        return refresh(current)
    }

    private fun refresh(current: FileStamp?): String {
        val tokenJson = readJson(tokenFile) ?: fail(State.NeedsReauth("token file missing at $tokenFile"))
        val refreshToken = tokenJson.string("refresh_token") ?: fail(State.NeedsReauth("token file has no refresh_token"))
        val creds = readJson(credentialsFile)?.let { (it["installed"] ?: it["web"])?.jsonObject }
            ?: fail(State.Unavailable("OAuth client file missing or unreadable at $credentialsFile"))
        val tokenUri = creds.string("token_uri") ?: "https://oauth2.googleapis.com/token"
        val form = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to (creds.string("client_id") ?: ""),
            "client_secret" to (creds.string("client_secret") ?: ""),
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val resp = try {
            http.send(
                HttpRequest.newBuilder(URI.create(tokenUri)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            fail(State.Unavailable("${e.javaClass.simpleName}: ${e.message}"))
        }
        val body = runCatching { JSON.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
        if (resp.statusCode() !in 200..299) {
            val error = body?.string("error") ?: "http ${resp.statusCode()}"
            // Same classification as the poller: a rejected grant is dead; anything else is transient.
            val dead = error == "invalid_grant" || resp.statusCode() == 400 || resp.statusCode() == 401
            fail(if (dead) State.NeedsReauth(error) else State.Unavailable(error))
        }
        val access = body?.string("access_token") ?: fail(State.Unavailable("token endpoint returned no access_token"))
        body.string("refresh_token")?.takeIf { it != refreshToken }?.let {
            // The poller persists rotated refresh tokens; we cannot (read-only mount). Loud, not fatal.
            log.error("Google rotated the Gmail refresh token during a jobbot-mcp refresh; the poller must persist it")
        }
        val ttl = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        accessToken = access
        expiresAt = clock.instant().plusSeconds(ttl)
        stamp = current
        state = State.Ok
        return access
    }

    private fun fail(s: State): Nothing {
        state = s
        accessToken = null
        log.warn("Gmail auth: {}", s)
        throw GmailAuthException(s)
    }

    private fun currentStamp(): FileStamp? = runCatching {
        val a = Files.readAttributes(tokenFile, BasicFileAttributes::class.java)
        FileStamp(a.fileKey(), a.lastModifiedTime().toMillis(), a.size())
    }.getOrNull()

    private fun readJson(p: Path): JsonObject? =
        runCatching { JSON.parseToJsonElement(Files.readString(p)).jsonObject }.getOrNull()

    private fun JsonObject.string(k: String): String? =
        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
