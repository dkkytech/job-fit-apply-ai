package com.jd.jobbot.apply

import com.google.common.net.InternetDomainName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Clock

/**
 * Site logins JobBot creates and uses, as dkkytech@gmail.com (Q16–Q18).
 *
 * One plaintext JSON file, owned by jobbot-mcp alone (mode 600). Passwords never leave this
 * class except into a password field on the site they belong to ([passwordFor] is only called by
 * the fill loop's own credential step); everything the model or the chat sees is [Entry.public].
 * A new password is written as `pending` **before** the signup form is submitted, so a crash
 * mid-signup cannot lose the only copy.
 */
class Credentials(
    private val file: Path,
    private val username: String,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    @Serializable
    data class Entry(
        val method: String,
        val username: String,
        val password: String? = null,
        val status: String,
        val login_url: String? = null,
        val created_at: String,
        val updated_at: String,
        val created_for: String? = null,
    ) {
        /** What may be shown anywhere: never the password. */
        fun public(site: String) = mapOf(
            "site" to site, "method" to method, "username" to username, "status" to status,
            "login_url" to login_url, "created_at" to created_at, "created_for" to created_for,
        )
    }

    @Serializable
    data class Store(val version: Int = 1, val accounts: Map<String, Entry> = emptyMap())

    data class PasswordRules(val minLength: Int = 20, val maxLength: Int = 64, val symbols: String = DEFAULT_SYMBOLS)

    @Synchronized
    fun get(site: String): Entry? = load().accounts[site]

    @Synchronized
    fun list(): Map<String, Entry> = load().accounts.toSortedMap()

    /** The stored password for [site] — for the fill loop's password step only. */
    @Synchronized
    fun passwordFor(site: String): String? = load().accounts[site]?.password

    /**
     * Generate and persist a new password for [site] as `pending`. Call this before submitting the
     * signup form; [activate] once the site accepts it.
     */
    @Synchronized
    fun createPending(site: String, loginUrl: String?, createdFor: String?, rules: PasswordRules = PasswordRules()): String {
        val prior = load().accounts[site]
        // Defense in depth: an active password is the only copy of a working login.
        check(!(prior?.status == ACTIVE && prior.method == "password")) { "refusing to replace the active password for $site" }
        val password = generate(rules)
        val now = clock.instant().toString()
        put(site, Entry("password", username, password, PENDING, loginUrl, prior?.created_at ?: now, now, createdFor ?: prior?.created_for))
        return password
    }

    @Synchronized
    fun activate(site: String) {
        val e = load().accounts[site] ?: return
        put(site, e.copy(status = ACTIVE, updated_at = clock.instant().toString()))
    }

    /** An account created with "Sign in with Google": no password, recorded for /jdaccounts. */
    @Synchronized
    fun recordGoogle(site: String, loginUrl: String?, createdFor: String?) {
        if (load().accounts[site]?.method == "google") return
        val now = clock.instant().toString()
        put(site, Entry("google", username, null, ACTIVE, loginUrl, now, now, createdFor))
    }

    internal fun generate(rules: PasswordRules): String {
        val length = rules.minLength.coerceAtMost(rules.maxLength).coerceAtLeast(12)
        val classes = listOf(UPPER, LOWER, DIGITS, rules.symbols.ifEmpty { "" }).filter { it.isNotEmpty() }
        val all = classes.joinToString("")
        // One from each class, the rest from all, then shuffled.
        val chars = classes.map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < length) chars += all[random.nextInt(all.length)]
        chars.shuffle(random)
        return chars.joinToString("")
    }

    private fun load(): Store =
        if (Files.exists(file)) JSON.decodeFromString(Store.serializer(), Files.readString(file)) else Store()

    /** Atomic write (temp + rename), previous version kept as .prev, mode 600 where supported. */
    private fun put(site: String, entry: Entry) {
        val current = load()
        val next = current.copy(accounts = current.accounts + (site to entry))
        Files.createDirectories(file.toAbsolutePath().parent)
        if (Files.exists(file)) {
            val prev = file.resolveSibling(file.fileName.toString() + ".prev")
            Files.copy(file, prev, StandardCopyOption.REPLACE_EXISTING)
            // The backup holds the same passwords: same mode as the store.
            runCatching { Files.setPosixFilePermissions(prev, PosixFilePermissions.fromString("rw-------")) }
        }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, JSON.encodeToString(Store.serializer(), next))
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val PENDING = "pending"
        const val ACTIVE = "active"
        private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
        private const val DIGITS = "23456789"
        const val DEFAULT_SYMBOLS = "!@#%^*-_=+?"
        private val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * Hosts where every company is its own tenant with its own account: the key is the exact
         * host (acme.wd5.myworkdayjobs.com), not the shared registrable domain.
         */
        private val MULTI_TENANT = listOf(
            "myworkdayjobs.com", "myworkdaysite.com", "icims.com", "taleo.net", "successfactors.com",
            "oraclecloud.com", "ultipro.com", "paylocity.com", "applytojob.com", "bamboohr.com",
        )

        /** The credential key for a page URL. */
        fun siteKey(url: String): String? {
            val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return null
            if (MULTI_TENANT.any { host == it || host.endsWith(".$it") }) return host
            return registrableDomain(host)
        }

        fun registrableDomain(host: String): String = runCatching {
            InternetDomainName.from(host).topPrivateDomain().toString()
        }.getOrDefault(host)
    }
}
