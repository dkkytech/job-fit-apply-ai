Use the tuner dataset in /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/tuner/scrape-jd-url-tuner/data-set as the corpus for ScrapeJdNode tuning.

Goal:
Improve /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/src/main/kotlin/com/jd/pipeline/nodes/ScrapeJdNode.kt and /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/src/main/resources/skills/SCRAPE_SKILL.md until ScrapeJdNode accurately and efficiently scrapes clean job description data from job URLs across multiple job boards.

Instructions:
1. Treat every file in /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/tuner/scrape-jd-url-tuner/data-set as a tuner case.
2. Each tuner file contains structured fields (job_url, company, role_title, location, salary_range, email_jd_text, expected_jd_text).
   - Dataset files are now in JSON format (preferred). Legacy `.txt` files are still supported for backward compatibility.
   - Copy `template.json` when adding a new dataset file.
3. For each tuner file, run:
   ./gradlew run --args='--scrapetuner <absolute-file-path> --max-iterations 5'
4. Review the generated output under /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/output/scrape_tuner/.
5. Compare the scraped jd_text, role_title, company, location, and salary_range against:
   - The expected_jd_text in the dataset file (if populated).
   - The dataset company/role_title/location as a sanity check.
6. Update ScrapeJdNode.kt to improve:
   - URL redirect handling (tracking links, job board redirect URLs)
   - Page content extraction quality (noise removal, jd_text completeness)
   - LinkedIn Playwright flow (selector coverage, auth detection)
   - HTTP scrape quality for non-LinkedIn boards (headers, redirect following)
   - JSON extraction from PAGE_JSON_DATA (structured job schema blocks)
7. Update SCRAPE_SKILL.md to improve:
   - LLM extraction accuracy for jd_text
   - Field extraction for salary_range, yoe_required, tech_stack
   - Handling of noisy/bloated page content
8. If a URL redirects through a tracking link (e.g. sendgrid, monster click, lensa email), follow the redirect chain to the actual job page before scraping.
9. For LinkedIn URLs, use the Playwright path. For all others, use HTTP. Verify the correct path is chosen.
10. After each run, check these files in the output dir:
    - `scrape_comparison_report.md` — summary of what was scraped vs expected
    - `scrape_result_dump.txt` — raw field values and jd_text
    - `scraped_content.txt` — the raw page content returned before LLM processing (only present when content was received)

    Diagnose what the page actually returned:
    - If `error` contains "CAPTCHA", "bot-blocked", "403", or "429" — the site is actively blocking scraping. Note it, skip it, do not attempt workarounds.
    - If `error` contains "captcha" but `scraped_content.txt` exists — inspect the raw content to confirm it is really a CAPTCHA page and not a false positive.
    - If `jd_text` is empty but `scraped_content.txt` has content — the content extraction or LLM step failed, not the fetch. Improve the SCRAPE_SKILL.md prompt or ScrapeJdNode extraction logic.
    - If `jd_text` is very short (< 200 chars) and the page has content — check if a popup or modal is blocking the description. The popup dismissal logic in ScrapeJdNode already handles common LinkedIn popups; if a new pattern appears, add a selector.
    - If `jd_text` is populated but fields (company, role_title, location) are wrong — improve SCRAPE_SKILL.md.
11. Do not process just one file and stop. Continue through the whole dataset directory.
12. After changes, rerun all tuner cases and summarize:
    - which files now pass cleanly
    - which fields are missing or inaccurate
    - what code/skill changes were made
13. Also run:
    ./gradlew compileKotlin
    before finishing.

--max-iterations default: 5

Pipeline behavior requirements:
- ScrapeJdNode must always fall back gracefully if the URL is unreachable or the page is not a job posting.
- If scraping succeeds, jd_text should be clean, complete, and free of navigation chrome, cookie banners, sign-in prompts, recommended-job rails, and unsubscribe/legal footers.
- If scraping fails, the existing email-sourced fields in JDState must not be overwritten.
- email_jd_text in the dataset may be partial fallback data from ScanEmailNode — treat it as context, not as the gold standard.
- expected_jd_text is the gold standard for comparison when populated.

Success criteria:
- jd_text is populated for every job URL that is publicly accessible
- jd_text is clean and contains responsibilities, qualifications, and requirements
- role_title, company, location, and salary_range are extracted accurately
- tracking/redirect URLs are resolved before scraping
- LinkedIn jobs use Playwright and successfully retrieve jd_text when authenticated
- non-LinkedIn jobs use HTTP and do not require Playwright
- domain-specific logic in ScrapeJdNode is organized and maintainable

Do the work directly in the repo and verify with the tuner runs before reporting back.

---

## Self-Healing Diagnostic Guide

When a tuner case fails, use this table to determine the root cause and where to apply the fix:

| Symptom | Likely Cause | Fix Location |
|---|---|---|
| `jd_text` empty, `scraped_content.txt` has content | LLM extraction failed or returned empty JSON | `SCRAPE_SKILL.md` — tighten extraction rules; ensure schema is complete |
| `jd_text` very short (< 200 chars), `scraped_content.txt` is large | Popup/modal blocking description; or wrong content selector | `ScrapeJdNode.kt` — add popup dismiss selector or expand visible-text selectors |
| Fields (company, role_title, location) wrong but `jd_text` good | LLM mis-extracted structured fields | `SCRAPE_SKILL.md` — add field extraction rules and examples |
| HTTP 403 / 429 / "bot-blocked" | Site actively blocks scraping | Note and skip; do not attempt workarounds |
| "CAPTCHA" / "Cloudflare" in error | Bot detection triggered | Note and skip; or if `PLAYWRIGHT_FALLBACK_ON_CAPTCHA` is true, verify fallback behavior in `ScrapeJdNode.kt` |
| URL not resolved from tracking link | Redirect not followed to actual job page | `ScrapeJdNode.kt` — improve `fetchPageOverHttp()` redirect handling |
| LinkedIn fails with "auth expired" | Chrome profile missing or expired | Re-authenticate Chrome; or if no profile, skip LinkedIn cases |
| LinkedIn `jd_text` missing sections | "Show more" button not expanded | `ScrapeJdNode.kt` — add expand selector in `expandLinkedInJobDescription()` |
| Jobright `jd_text` incomplete | __NEXT_DATA__ not fully parsed | `ScrapeJdNode.kt` — check `applyJobrightStructuredData()` section mappings |
| Salary range not extracted | Not present in visible text or JSON | Verify dataset has salary; if present in page, improve `SCRAPE_SKILL.md` or regex fallbacks |

### Structured Fix Workflow

1. **Read the comparison report**: Open `scrape_comparison_report.md` for a summary of matches and mismatches.
2. **Read the result dump**: Open `scrape_result_dump.txt` for exact field values and the error string.
3. **Inspect raw content**: If `scraped_content.txt` exists, open it. Confirm whether the page returned real job content, a CAPTCHA, or an auth gate.
4. **Classify the failure**: Use the diagnostic table above to determine if the issue is:
   - **Fetch problem** (no content, blocked, wrong URL) → fix `ScrapeJdNode.kt`
   - **Extraction problem** (content present but wrong/missing fields) → fix `SCRAPE_SKILL.md`
5. **Make ONE targeted change** and rerun ONLY the affected tuner case(s).
6. **Verify compilation**: Run `./gradlew compileKotlin` after any code change.
7. **Iterate** until the case passes, then move to the next failing case.

### Key Files and Their Responsibilities

| File | What to Change When |
|---|---|
| `ScrapeJdNode.kt` | Fetch logic, redirects, popup dismissal, LinkedIn selectors, JSON parsing, bot detection, visible-text extraction |
| `SCRAPE_SKILL.md` | LLM prompt, output schema, field extraction rules, noise-removal instructions |
| `data-set/*.json` | Add new test cases; update expected_jd_text when job postings change |
| `data-set/template.json` | Base for creating new dataset files |

### How to Add a New Dataset File

1. Copy `data-set/template.json` to a new file (e.g. `data-set/new-board.json`).
2. Fill in all fields. `expected_jd_text` is optional but recommended for content comparison.
3. Run:
   ./gradlew run --args='--scrapetuner /absolute/path/to/data-set/new-board.json'