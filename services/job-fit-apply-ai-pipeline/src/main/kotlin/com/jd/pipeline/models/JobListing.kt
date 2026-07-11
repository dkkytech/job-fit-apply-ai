package com.jd.pipeline.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class JobListing(
    @JsonProperty("job_id")                     val jobId: String,
    @JsonProperty("job_title")                  val jobTitle: String,
    @JsonProperty("employer_name")              val employerName: String,
    @JsonProperty("job_city")                   val jobCity: String? = null,
    @JsonProperty("job_state")                  val jobState: String? = null,
    @JsonProperty("job_is_remote")              val jobIsRemote: Boolean = false,
    @JsonProperty("job_description")            val jobDescription: String? = null,
    @JsonProperty("job_apply_link")             val jobApplyLink: String? = null,
    @JsonProperty("job_posted_at_datetime_utc") val jobPostedAtDatetimeUtc: String? = null,
    @JsonProperty("job_min_salary")             val jobMinSalary: Double? = null,
    @JsonProperty("job_max_salary")             val jobMaxSalary: Double? = null,
    @JsonProperty("job_salary")                 val jobSalary: Double? = null,
    @JsonProperty("job_salary_string")           val jobSalaryString: String? = null,
    @JsonProperty("job_publisher")              val jobPublisher: String? = null
)
