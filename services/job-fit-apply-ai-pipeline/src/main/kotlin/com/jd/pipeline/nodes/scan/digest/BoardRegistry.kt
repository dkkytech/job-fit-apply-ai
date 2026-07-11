package com.jd.pipeline.nodes.scan.digest

data class JobBoardGroup(val key: String, val domains: Set<String>, val serialScrape: Boolean = false)

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
        JobBoardGroup("workday", setOf("workday.com", "myworkdayjobs.com")),
        JobBoardGroup("ats", setOf("lever.co", "greenhouse.io", "smartrecruiters.com", "jobvite.com", "icims.com", "taleo.net", "ashbyhq.com", "rippling.com")),
        JobBoardGroup("generic", setOf("indeed.com", "ziprecruiter.com", "dice.com")),
    )

    val DOMAINS: Set<String> = GROUPS.flatMap { it.domains }.toSet()

    val STRATEGIES: Map<String, BoardDigestStrategy> = mapOf(
        "linkedin" to LinkedInDigestStrategy,
        "jobleads" to JobLeadsDigestStrategy,
        "lensa" to LensaDigestStrategy,
        "jobright" to JobrightDigestStrategy,
        "wellfound" to WellfoundDigestStrategy,
        "welcome_to_the_jungle" to WelcomeToTheJungleDigestStrategy,
        "monster" to MonsterDigestStrategy,
        "glassdoor" to GlassdoorDigestStrategy,
        "workday" to WorkdayDigestStrategy,
        "ats" to AtsDigestStrategy,
        "generic" to GenericDigestStrategy,
    )

    fun groupFor(domain: String?): JobBoardGroup? {
        if (domain.isNullOrBlank()) return null
        val normalized = domain.lowercase()
        return GROUPS.firstOrNull { group ->
            normalized in group.domains || group.domains.any { normalized.endsWith(".$it") }
        }
    }
}
