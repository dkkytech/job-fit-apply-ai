package com.jd.jsearch.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import kotlin.math.roundToLong

/** A JSearch API job listing (subset). Duplicated per the project's per-service DTO convention. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JobListing(
    @JsonProperty("job_id")          val jobId: String,
    @JsonProperty("job_title")       val jobTitle: String,
    @JsonProperty("employer_name")   val employerName: String,
    @JsonProperty("job_city")        val jobCity: String? = null,
    @JsonProperty("job_state")       val jobState: String? = null,
    @JsonProperty("job_is_remote")   val jobIsRemote: Boolean = false,
    @JsonProperty("job_description") val jobDescription: String? = null,
    @JsonProperty("job_apply_link")  val jobApplyLink: String? = null,
    @JsonProperty("job_publisher")   val jobPublisher: String? = null,
    // Structured fields the report shows. All optional: JSearch omits or nulls them freely, and
    // older responses use "FULLTIME" where newer ones use "Full-time".
    @JsonProperty("job_employment_type")     val jobEmploymentType: String? = null,
    @JsonProperty("job_employment_types")    val jobEmploymentTypes: List<String>? = null,
    @JsonProperty("job_min_salary")          val jobMinSalary: Double? = null,
    @JsonProperty("job_max_salary")          val jobMaxSalary: Double? = null,
    @JsonProperty("job_salary_period")       val jobSalaryPeriod: String? = null,
    @JsonProperty("job_salary")              val jobSalary: String? = null,
    @JsonProperty("job_required_experience") val jobRequiredExperience: RequiredExperience? = null,
    @JsonProperty("job_required_skills")     val jobRequiredSkills: List<String>? = null,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RequiredExperience(
        @JsonProperty("required_experience_in_months") val requiredExperienceInMonths: Int? = null,
    )

    /** "Full-time" / "Contract" / …, or null when JSearch gave none. */
    fun employmentType(): String? =
        (jobEmploymentType?.takeIf { it.isNotBlank() } ?: jobEmploymentTypes?.firstOrNull { it.isNotBlank() })
            ?.let { raw ->
                when (raw.uppercase().replace("-", "").replace("_", "").replace(" ", "")) {
                    "FULLTIME" -> "Full-time"
                    "PARTTIME" -> "Part-time"
                    "CONTRACTOR", "CONTRACT" -> "Contract"
                    "INTERN", "INTERNSHIP" -> "Internship"
                    "TEMPORARY" -> "Temporary"
                    else -> raw
                }
            }

    fun remotePolicy(): String? = if (jobIsRemote) "remote" else null

    /** "$101K - $127K/yr", "$55 - $65/hr", or JSearch's own display string; null when absent. */
    fun salaryRange(): String? {
        jobSalary?.takeIf { it.isNotBlank() }?.let { return it }
        val min = jobMinSalary?.takeIf { it > 0 }
        val max = jobMaxSalary?.takeIf { it > 0 }
        if (min == null && max == null) return null
        val suffix = when (jobSalaryPeriod?.uppercase()) {
            "YEAR" -> "/yr"
            "HOUR" -> "/hr"
            "MONTH" -> "/mo"
            "WEEK" -> "/wk"
            else -> ""
        }
        fun money(v: Double) = if (v >= 1000) "\$${(v / 1000).roundToLong()}K" else "\$${"%.2f".format(v).removeSuffix(".00")}"
        return when {
            min != null && max != null && min != max -> "${money(min)} - ${money(max)}$suffix"
            else -> "${money(min ?: max!!)}$suffix"
        }
    }

    /** Whole years, rounded up (18 months → 2). Null when JSearch doesn't say. */
    fun yoeRequired(): Int? =
        jobRequiredExperience?.requiredExperienceInMonths?.takeIf { it > 0 }?.let { (it + 11) / 12 }

    fun techStack(): List<String>? = jobRequiredSkills?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
}
