package com.jd.pipeline.config

data class JSearchConfig(
    val queries: List<String>,
    val numPages: Int = 1,
    val datePosted: String = "today", // all, today, 3days, week, month
    val location: String? = "Seattle, WA",
    val radius: Int = 30,
    val remoteJobsOnly: Boolean = false
) {
    companion object {
        val DEFAULT = JSearchConfig(
            queries = listOf(
                """SDET OR "test engineer" OR "QA engineer" mobile OR iOS OR Android""",
                """SDET OR "test engineer" OR "QA engineer" -mobile OR -iOS OR -Android"""
            )
        )
        val DEFAULT_LIST = listOf<JSearchConfig>(
            JSearchConfig(
                location = "Seattle, WA",
                radius = 30,
                queries = listOf(
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" mobile OR iOS OR Android""",
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" -mobile -iOS -Android"""
                )
            ),
            JSearchConfig(
                location = null,
                remoteJobsOnly = true,
                queries = listOf(
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" mobile OR iOS OR Android""",
                    """SDET OR "test engineer" OR "QE" OR "QA engineer" -mobile -iOS -Android"""
                ),
            )

        )
    }
}
