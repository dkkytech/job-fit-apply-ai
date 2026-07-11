package com.jd.pipeline.nodes

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.utils.OutputUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

/**
 * Node: save_jd
 * 
 * Saves the job description to the output directory as job_description.txt.
 */
class SaveJobDescriptionNode : Node<JDState> {

    override fun process(input: JDState): JDState {
        // Prefer jd_text over scraped_content
        var content = input.jdText
        if (content.isNullOrEmpty()) {
            content = input.scrapedContent
        }
        
        if (content.isNullOrEmpty()) {
            println("[save_jd] No job description to save - passing through")
            return input
        }

        val company = input.company ?: "unknown"
        val roleTitle = input.roleTitle ?: "unknown"

        // Create output directory
        val outputDir = OutputUtils.createOutputPath(company, roleTitle)
        try {
            Files.createDirectories(outputDir)
            println("[save_jd] Created output directory: $outputDir")
        } catch (e: Exception) {
            return input.copy(error = "save_jd: failed to create output directory - ${e.message}")
        }

        // Write job_description.txt
        val jdPath = outputDir.resolve("job_description.txt")
        try {
            Files.writeString(jdPath, content, StandardCharsets.UTF_8)
            println("[save_jd] Saved job description to: $jdPath")
        } catch (e: Exception) {
            return input.copy(error = "save_jd: failed to write file - ${e.message}")
        }

        // Write raw page content if available
        val rawPage = input.rawPageContent
        if (!rawPage.isNullOrEmpty()) {
            val rawPagePath = outputDir.resolve("raw_page_contents.txt")
            try {
                Files.writeString(rawPagePath, rawPage, StandardCharsets.UTF_8)
                println("[save_jd] Saved raw page content to: $rawPagePath")
            } catch (e: Exception) {
                System.err.println("[save_jd] WARN: failed to write raw_page_contents.txt: ${e.message}")
            }
        }

        // Write raw email content if available
        val rawEmail = input.emailIntake?.rawBody ?: ""
        if (!rawEmail.isNullOrEmpty()) {
            val rawEmailPath = outputDir.resolve("raw_email_contents.txt")
            try {
                Files.writeString(rawEmailPath, rawEmail, StandardCharsets.UTF_8)
                println("[save_jd] Saved raw email content to: $rawEmailPath")
            } catch (e: Exception) {
                System.err.println("[save_jd] WARN: failed to write raw_email_contents.txt: ${e.message}")
            }
        }

        // Update state with output path
        val existingOutputPath = input.outputPath
        return if (existingOutputPath.isNullOrEmpty()) {
            println("[save_jd] Set outputPath in state: $outputDir")
            input.copy(outputPath = outputDir.toString())
        } else {
            println("[save_jd] Using existing outputPath: $existingOutputPath")
            input
        }
    }
}