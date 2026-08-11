Use the tuner dataset in /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/tuner/scan-email-tuner/data-set as the training and test corpus for ScanEmail tuning.

Goal:
Improve /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/nodes/ScanEmailNode.kt and /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/resources/skills/SCAN_SKILL.md until the scan pipeline correctly extracts all visible company info, role titles, locations, salary/compensation info when present, clean job posting URLs, and any useful hidden job description data embedded in the email.

Instructions:
1. Treat every file in /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/tuner/scan-email-tuner/data-set as a tuner case.
2. Each tuner file’s first line is the email subject. The remaining content is the expected visible job data from Gmail.
3. For each tuner file, run:
   ./gradlew run --args='--scantuner <absolute-file-path>'
4. Review the generated output under `${JFAA_DATA_ROOT}/pipeline-output/scan_tuner/` (resolve with `scripts/jfaa-data-root.sh`; see docs/data-root-migration.md).
5. Compare the extracted results against the expected visible data in the tuner file.
6. Update /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/nodes/ScanEmailNode.kt to improve board-specific parsing and organize the logic by domain/job board.
7. Update /Users/dkkyai/projects/job-fit-apply-ai/services/job-fit-apply-ai-pipeline/src/main/resources/skills/SCAN_SKILL.md to improve direct-email extraction behavior where appropriate.
8. Inspect non-visible email content too, including hidden HTML text, embedded metadata, JSON blobs, schema blocks, and script data. Use that hidden content to recover missing job description text and missing structured fields.
9. Visible Gmail content is the source of truth for company, role title, location, compensation, and the primary job URL. Hidden/non-visible content may enrich or fill gaps, but must not override a clearly visible value.
10. Ignore tracking, analytics, unsubscribe, and unrelated marketing metadata.
11. Iterate until the tuner cases are properly scanned and the extracted data matches the visible expected data as closely as possible.
12. Do not process just one file and stop. Continue through the whole tuner-input directory.
13. After changes, rerun the tuner cases and summarize:
- which files now pass cleanly
- which fields still fail or are partial
- what code/skill changes were made
14. Also run:
    ./gradlew compileKotlin
    before finishing.

Pipeline behavior requirements:
- Partial job description data from ScanEmailNode is valid fallback data.
- If ScanEmailNode finds partial JD text or structured fields in the email, keep them in JDState.
- If ScrapeJdNode successfully scrapes the job page, the scraped JD text should overwrite any JD text that came from the email scan.
- If ScrapeJdNode fails, returns empty content, or cannot authenticate, the pipeline must continue using the data from the email scan instead of dropping the job.
- Digest emails should populate digest children correctly.
- Top-level digest JDState should remain useful and not blank.

Success criteria:
- clean company extraction
- clean role title extraction
- clean location extraction
- clean compensation extraction when present
- correct job posting URLs only
- hidden JD text is used when helpful but does not override visible truth
- digest emails populate digest children correctly
- top-level digest JDState is still useful and not blank
- partial email-scan JD data survives scrape failures
- successful scrape replaces email-scan JD text
- domain-specific logic in ScanEmailNode is organized and maintainable

Do the work directly in the repo and verify with the tuner runs before reporting back.
