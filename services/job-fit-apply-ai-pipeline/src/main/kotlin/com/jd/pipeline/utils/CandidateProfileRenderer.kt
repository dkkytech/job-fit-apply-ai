package com.jd.pipeline.utils

import com.jd.pipeline.models.CandidateProfile

/**
 * Renders a [CandidateProfile] as a Markdown block for LLM prompts.
 *
 * Two flavours:
 *  - [renderForScoring] — identity, summary, career-history table (no bullets),
 *    projects table, strengths, skills, languages, domain, **and** the
 *    Preferences section. Used by `ScoreFitNode` for rubric-based fit scoring
 *    and hard-gate evaluation.
 *  - [renderForTailoring] — identity, summary, **per-role bullet lists**,
 *    strengths, skills, languages, domain. **No preferences** — tailor nodes
 *    don't need (and shouldn't see) compensation, visa, or relocation context.
 *  - [renderForReply] — the fullest grounding: identity, summary, **per-role
 *    bullet lists**, projects with bullets, strengths, skills, the evidence
 *    bank, **and** preferences. Used by `DraftReplyComposer` so recruiter
 *    replies only answer questions the résumé/profile actually support.
 *
 * The shared `Background` portion (identity + summary + skills + languages
 * + domain + core strengths) is identical between the flavours; only the
 * career-history / projects detail and preferences differ.
 */
object CandidateProfileRenderer {

    fun renderForScoring(profile: CandidateProfile): String = buildString {
        appendHeader(profile)
        appendCareerHistoryTable(profile)
        appendProjectsTable(profile)
        appendStrengthsAndSkills(profile)
        appendPreferences(profile)
    }.trimEnd() + "\n"

    fun renderForReply(profile: CandidateProfile): String = buildString {
        appendHeader(profile)
        appendCareerHistoryWithBullets(profile)
        appendProjectsWithBullets(profile)
        appendStrengthsAndSkills(profile)
        appendEvidenceBank(profile)
        appendPreferences(profile)
    }.trimEnd() + "\n"

    fun renderForTailoring(profile: CandidateProfile): String = buildString {
        appendHeader(profile)
        appendCareerHistoryWithBullets(profile)
        appendProjectsWithBullets(profile)
        appendStrengthsAndSkills(profile)
        appendEvidenceBank(profile)
    }.trimEnd() + "\n"

    // ── header (identity + summary) ──────────────────────────────────────────

    private fun StringBuilder.appendHeader(profile: CandidateProfile) {
        val id = profile.identity
        val bg = profile.background

        append("**Name:** ${id.fullName}\n")
        append("**Target Title:** ${bg.targetTitle.trim()}\n")
        append("**Location:** ${id.location}\n")
        append("**Total Experience:** ${bg.yearsExperience}+ years\n")
        if (bg.education.isNotEmpty()) {
            append("**Education:** ")
            append(bg.education.joinToString("; ") {
                val loc = it.location?.let { loc -> ", $loc" } ?: ""
                "${it.degreeLine}, ${it.school}${loc} (${it.year})"
            })
            append("\n")
        }
        if (bg.summary.isNotBlank()) {
            append("\n**Summary:** ${bg.summary}\n")
        }
        append("\n")
    }

    // ── career history & projects ────────────────────────────────────────────

    private fun StringBuilder.appendCareerHistoryTable(profile: CandidateProfile) {
        val ch = profile.background.careerHistory
        if (ch.isEmpty()) return
        append("### Career History (most recent first)\n\n")
        append("| Role | Company | Location | Dates |\n|---|---|---|---|\n")
        ch.forEach { e ->
            append("| ${e.role} | ${e.company} | ${e.location.ifBlank { "—" }} | ${e.dateRange} |\n")
        }
        append("\n")
    }

    private fun StringBuilder.appendCareerHistoryWithBullets(profile: CandidateProfile) {
        val ch = profile.background.careerHistory
        if (ch.isEmpty()) return
        append("### Career History (most recent first)\n\n")
        ch.forEach { e ->
            append("**${e.role} — ${e.company}** (${e.dateRange})")
            if (e.location.isNotBlank()) append(" · ${e.location}")
            append("\n")
            e.bullets.forEach { b ->
                if (b.category.isNotBlank()) append("- ${b.category}: ${b.text}\n") else append("- ${b.text}\n")
            }
            append("\n")
        }
    }

    private fun StringBuilder.appendProjectsTable(profile: CandidateProfile) {
        val pj = profile.projects
        if (pj.isEmpty()) return
        append("### Projects\n\n")
        append("| Role | Project | Location | Dates |\n|---|---|---|---|\n")
        pj.forEach { e ->
            append("| ${e.role} | ${e.company} | ${e.location.ifBlank { "—" }} | ${e.dateRange} |\n")
        }
        append("\n")
    }

    private fun StringBuilder.appendProjectsWithBullets(profile: CandidateProfile) {
        val pj = profile.projects
        if (pj.isEmpty()) return
        append("### Projects\n\n")
        pj.forEach { e ->
            append("**${e.role} — ${e.company}** (${e.dateRange})")
            if (e.location.isNotBlank()) append(" · ${e.location}")
            append("\n")
            e.bullets.forEach { b ->
                if (b.category.isNotBlank()) append("- ${b.category}: ${b.text}\n") else append("- ${b.text}\n")
            }
            append("\n")
        }
    }

    // ── strengths + skills + languages + domain ──────────────────────────────

    private fun StringBuilder.appendStrengthsAndSkills(profile: CandidateProfile) {
        val bg = profile.background

        if (bg.coreStrengths.isNotEmpty()) {
            append("### Core Strengths\n")
            bg.coreStrengths.forEach { append("- $it\n") }
            append("\n")
        }

        append("### Skills\n")
        profile.skills.forEach { group ->
            if (group.items.isNotEmpty()) append("- **${group.label}:** ${group.items.joinToString(", ")}\n")
        }
        append("\n")

        if (bg.languages.isNotEmpty()) {
            append("**Languages:** ${bg.languages.joinToString(", ")}\n")
        }
        if (bg.domainExpertise.isNotEmpty()) {
            append("**Domain Expertise:** ${bg.domainExpertise.joinToString(", ")}\n")
        }
        append("\n")
    }

    // ── evidence bank (tailoring only) ───────────────────────────────────────

    /**
     * Candidate-curated facts that are true but not on the résumé — rendered so gap
     * analysis can quote them as evidence (the tailor nodes may only claim what this
     * markdown supports).
     */
    private fun StringBuilder.appendEvidenceBank(profile: CandidateProfile) {
        val bank = profile.tailoring.evidenceBank
        if (bank.isEmpty()) return
        append("### Additional Verified Evidence (candidate-provided facts not on the résumé — valid evidence, same integrity rules)\n")
        bank.forEach { append("- $it\n") }
        append("\n")
    }

    // ── preferences (scoring only) ───────────────────────────────────────────

    private fun StringBuilder.appendPreferences(profile: CandidateProfile) {
        val pr = profile.preferences
        append("### Preferences\n")
        append("- **Preferred work arrangement:** ${pr.preferredWorkArrangement.ifBlank { "(not specified)" }}\n")
        append("- **Willing to relocate:** ${if (pr.willingToRelocate) "yes" else "no"}")
        pr.relocationNotes?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
        append("\n")
        append("- **Visa status:** ${pr.visaStatus.ifBlank { "(not specified)" }}; sponsorship required: ${if (pr.visaSponsorshipRequired) "yes" else "no"}\n")
        pr.minimumTotalCompensation?.takeIf { it.isNotBlank() }?.let { tc ->
            append("- **Target total compensation:** $tc")
            pr.compensationNotes?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
            append("\n")
        }
        append("- **Open to contract roles:** ${if (pr.openToContractRoles) "yes" else "no"}")
        if (pr.openToContractRoles) {
            val rate = pr.minimumContractRateHourly
            if (rate != null && rate > 0) append(" (target rate $$rate/hr)")
        }
        append("\n")
        append("- **Open to equity-only roles:** ${if (pr.openToEquityOnlyRoles) "yes" else "no"}\n")
        append("- **Travel:** ${if (pr.willingToTravel) "willing" else "not willing"}")
        if (pr.willingToTravel) {
            pr.travelPercentage?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        }
        append("\n")
    }
}
