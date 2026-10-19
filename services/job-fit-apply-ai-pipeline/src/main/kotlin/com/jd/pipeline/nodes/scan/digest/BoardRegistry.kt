package com.jd.pipeline.nodes.scan.digest

/**
 * [fallbackToRecruiter]: when the group's strategy finds nothing, read the email as a direct one
 * (LLM extraction) instead of guessing job links — an employer's sender mails application updates
 * as well as alerts.
 */
data class JobBoardGroup(
    val key: String,
    val domains: Set<String>,
    val serialScrape: Boolean = false,
    val fallbackToRecruiter: Boolean = false,
)

object BoardRegistry {
    val GROUPS: List<JobBoardGroup> = listOf(
        JobBoardGroup("linkedin", setOf("linkedin.com"), serialScrape = true),
        JobBoardGroup("jobleads", setOf("jobleads.com")),
        JobBoardGroup("lensa", setOf("lensa.com")),
        JobBoardGroup("jobright", setOf("jobright.ai")),
        JobBoardGroup("wellfound", setOf("wellfound.com", "hi.wellfound.com")),
        JobBoardGroup("welcome_to_the_jungle", setOf("welcometothejungle.com")),
        JobBoardGroup("monster", setOf("monster.com", "notifications.monster.com")),
        JobBoardGroup("glassdoor", setOf("glassdoor.com")),
        JobBoardGroup("builtin", setOf("builtin.com")),
        JobBoardGroup("workday", setOf("workday.com", "myworkdayjobs.com")),
        JobBoardGroup("ats", setOf("lever.co", "greenhouse.io", "smartrecruiters.com", "jobvite.com", "icims.com", "taleo.net", "ashbyhq.com", "rippling.com")),
        JobBoardGroup("generic", setOf("indeed.com", "ziprecruiter.com", "dice.com")),
        // Employers' own job alerts: Microsoft Careers (Eightfold), Eightfold-hosted career sites,
        // SuccessFactors career sites (jobs2web) and Workday tenants (<tenant>@myworkday.com, which
        // also sends application updates — the strategy's alert check keeps those direct).
        JobBoardGroup(
            "employer_alerts",
            setOf("email.careers.microsoft.com", "eightfold.ai", "jobs2web.com", "myworkday.com"),
            fallbackToRecruiter = true,
        ),
    )

    val DOMAINS: Set<String> = GROUPS.flatMap { it.domains }.toSet()

    /**
     * Board subdomains that relay one recruiter's own message rather than send the board's alerts.
     * A "Dice Private Email" arrives from `<alias>@user.dice.com` with the whole JD in the body and
     * no job link; read as a Dice digest it became a stub child the bridge rejected (422), so the
     * email was labelled JD_Error. These go through recruiter extraction instead.
     */
    val RECRUITER_RELAY_DOMAINS: Set<String> = setOf("user.dice.com")

    fun isRecruiterRelay(domain: String?): Boolean =
        !domain.isNullOrBlank() && domain.lowercase() in RECRUITER_RELAY_DOMAINS

    val STRATEGIES: Map<String, BoardDigestStrategy> = mapOf(
        "linkedin" to LinkedInDigestStrategy,
        "jobleads" to JobLeadsDigestStrategy,
        "lensa" to LensaDigestStrategy,
        "jobright" to JobrightDigestStrategy,
        "wellfound" to WellfoundDigestStrategy,
        "welcome_to_the_jungle" to WelcomeToTheJungleDigestStrategy,
        "monster" to MonsterDigestStrategy,
        "glassdoor" to GlassdoorDigestStrategy,
        "builtin" to BuiltInDigestStrategy,
        "workday" to EmployerAlertDigestStrategy,
        "ats" to EmployerAlertDigestStrategy,
        "generic" to GenericDigestStrategy,
        "employer_alerts" to EmployerAlertDigestStrategy,
    )

    fun groupFor(domain: String?): JobBoardGroup? {
        if (domain.isNullOrBlank()) return null
        val normalized = domain.lowercase()
        return GROUPS.firstOrNull { group ->
            normalized in group.domains || group.domains.any { normalized.endsWith(".$it") }
        }
    }
}
