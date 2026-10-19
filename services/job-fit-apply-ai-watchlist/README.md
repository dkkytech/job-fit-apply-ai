# job-fit-apply-ai-watchlist

Checks the job feeds of greater-Seattle QE/SDET employers once a day (or at fixed times, see below) and submits new QE-titled postings to the bridge. Listing and filtering use no LLM tokens; the processor scores only what reaches the bridge.

## How it runs

- `jobfit-watchlist` is a shell loop (`docker-entrypoint.sh`) that runs `--once` and sleeps. The JVM self-gates on `watchlist.json`'s `last_run`: a full pass runs every `WATCHLIST_INTERVAL_MS` (24h), or at the fixed local times in `WATCHLIST_RUN_AT` (e.g. `05:30,17:30`, in `WATCHLIST_TZ` such as `America/Los_Angeles`; the container itself is UTC). A slot missed while the host slept runs once on wake.
- `--once` writes when the next pass is due, and the loop sleeps until then, waking at least every `WATCHLIST_CHECK_INTERVAL_S` (3600) to refresh the healthcheck heartbeat.
- `--dry-run` lists what would be submitted without calling the bridge.
- Each pass writes `watchlist-health.json` (per-employer listed / matched / queued / deduped counts, plus failing and empty feeds). An employer whose feed fails or lists nothing `WATCHLIST_ALERT_AFTER_RUNS` (2) runs in a row raises one Telegram/Discord alert.

## Employers

`src/main/resources/employers.yaml` is generated from `tools/employers.csv` (the Seattle QE employer research):

```bash
python3 -I tools/gen_employers.py tools/employers.csv > src/main/resources/employers.yaml
```

A sync test fails if the two disagree. `ats` picks the adapter in `adapters/` (Greenhouse, Lever, Ashby, Workday, Eightfold, Oracle HCM, SmartRecruiters, Jibe, iCIMS, Jobvite, RSS and others); platforms without an adapter are skipped, and `enabled: false` marks a feed that did not verify. `may_be_empty: true` marks an employer that normally has no Washington openings, so listing nothing doesn't raise a stale-feed alert.

### Coverage

`ReadmeCoverageTest` checks these counts against `employers.yaml` and the adapter registry.

| Employers | Count |
|---|---|
| Polled | 183 |
| Feed did not verify (`enabled: false`) | 5 |
| No adapter for their platform | 30 |
| Total | 218 |

The employers this service can't poll reach the pipeline, if at all, through their own job-alert emails, which the processor parses (see [Posting identity](#posting-identity-shared-with-employer-job-alert-emails)):

- **Bot walls:** Walmart Global Tech (Workday alerts) and SAP Concur (SuccessFactors alerts) are already parsed.
- **No public feed:** Google, Meta, Cognizant, TCS, Tech Mahindra, UST, Infosys, LTIMindtree, Avanade, IBM, Korry and Randstad need an alert subscription (most require an account on the employer's site). Alerts from an ATS that `PostingLinks` doesn't recognize are still keyed on their cleaned URL.
- **Nothing to poll:** BambooHR (Radian) and JazzHR (Tasso) boards had no openings to build a parser against.

## Filters

| Variable | Default | Description |
|---|---|---|
| `WATCHLIST_TITLE_INCLUDE` | `qa`, `qe`, `sdet`, `quality`, `test`, `tester`, `testing`, `verification`, `validation` | A title must match to be submitted. |
| `WATCHLIST_TITLE_EXCLUDE` | interns, technicians, clinical/lab, chip design verification (ASIC/FPGA/RTL/SoC)… | Dropped even when the include matches. |
| `WATCHLIST_SEARCH_TERMS` | `test,quality,sdet,qa` | Search terms for platforms with no "list everything" call (Workday, Eightfold, Jibe, Amazon). |
| `WATCHLIST_PAGE_DELAY_MS` / `WATCHLIST_EIGHTFOLD_PAGE_DELAY_MS` | `300` / `2000` | Pause between pages; Eightfold rate-limits per IP. |

Locations are filtered to Washington, the Seattle area, remote, or unknown.

## Posting identity (shared with employer job-alert emails)

Every submission is keyed `watchlist:<ats>:<boardKey>:<postingId>` (`WatchlistRunner.idempotencyKey`) with source `CAREER_SITE`, so the bridge dedupes it for 60 days.

Employers also email job alerts (Microsoft Careers, iCIMS, SuccessFactors' jobs2web, Workday, Greenhouse…). The processor parses those links itself (`EmployerAlertDigestStrategy` + `PostingLinks`) and submits each to `/api/urls` under the same key for the ATSes this service polls, or under its cleaned URL otherwise. A posting found by both the feed and an alert is therefore one job.

`contract/posting_keys.json` pins the format: this service's `PostingKeyContractTest` and the processor's `PostingLinksTest` both run every case, so a key change on one side fails the other's suite.
