package com.jd.notifier.notify

import com.jd.notifier.config.Config
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.slf4j.LoggerFactory

/**
 * Resolves the View Report / View Resume links for a completed job.
 *
 * The bridge event carries `artifact_url` (the markserv directory) but **not** the resume
 * filename, and the name is title-derived (`Richard_Hatcher_Staff_SDET.pdf`) so it cannot be
 * guessed. The pipeline writes `metadata.json` beside the artifacts with the exact
 * `tailored_resume_url`, so one small fetch is the reliable source.
 *
 * Failure is never fatal: a missing link just drops that one button. The notification is worth
 * more than its buttons, so nothing here may throw into the delivery path.
 */
open class ArtifactLinks(
    private val enabled: Boolean = Config.ARTIFACT_LINKS_ENABLED,
    private val timeoutMs: Int = Config.ARTIFACT_LINKS_TIMEOUT_MS,
    private val bridgeBase: String = Config.BRIDGE_PUBLIC_URL,
) {
    private val log = LoggerFactory.getLogger(ArtifactLinks::class.java)
    private val http = HttpClients.createDefault()

    data class Links(val reportUrl: String?, val resumeUrl: String?)

    /**
     * @param artifactUrl the job's markserv directory URL, or null when the pipeline produced
     *   no artifacts (then there is nothing to link to).
     * @param resumePdf the bridge-relative resume path (e.g. `/api/jobs/<id>/resume.pdf`), when
     *   the event carries `artifacts`. Preferred over the metadata lookup: it needs no fetch and
     *   stays correct even if the output directory is renamed.
     */
    open fun resolve(artifactUrl: String?, resumePdf: String? = null): Links {
        val base = artifactUrl?.trim()?.takeIf { it.isNotBlank() }
        val report = base?.let { it.trimEnd('/') + "/report.md" }

        // A bridge-relative path is authoritative — use it as-is.
        resumePdf?.trim()?.takeIf { it.isNotBlank() }?.let {
            return Links(report, absolute(it))
        }

        if (base == null) return Links(null, null)
        if (!enabled) return Links(report, null)

        val resume = try {
            fetchResumeUrl(base)
        } catch (e: Exception) {
            // A dead markserv, a 404, malformed JSON — all mean "no resume button", not "no ping".
            log.warn("resume link lookup failed for {}: {}", base, e.message)
            null
        }
        return Links(report, resume)
    }

    /** Make a bridge-relative artifact path absolute so Telegram will accept it as a URL. */
    internal fun absolute(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else bridgeBase.trimEnd('/') + "/" + path.trimStart('/')

    private fun fetchResumeUrl(base: String): String? {
        val url = base.trimEnd('/') + "/metadata.json"
        val req = HttpGet(url).apply { setConfig(requestConfig()) }
        return http.execute(req) { resp ->
            if (resp.code != 200) {
                log.warn("metadata.json for {} → {}", base, resp.code)
                return@execute null
            }
            val body = EntityUtils.toString(resp.entity, Charsets.UTF_8)
            resumeUrlFrom(body)
        }
    }

    private fun requestConfig() =
        org.apache.hc.client5.http.config.RequestConfig.custom()
            .setConnectionRequestTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .setResponseTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

    /** Pull `tailored_resume_url` out of a metadata.json body. Null when absent or blank. */
    internal fun resumeUrlFrom(body: String): String? =
        runCatching {
            val node = MAPPER.readTree(body).get("tailored_resume_url")
            node?.asText()?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private companion object {
        val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()
    }
}
