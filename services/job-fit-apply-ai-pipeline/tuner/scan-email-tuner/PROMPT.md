Use the ScanEmailTuner source-of-truth skill at /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/tuner/scan-email-tuner/SCAN_EMAIL_TUNER_SKILL.md and run the ScanEmailTuner workflow against the dataset in /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/tuner/scan-email-tuner/data-set.

Follow that skill as the canonical instructions. Iterate on /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/src/main/kotlin/com/jd/pipeline/nodes/ScanEmailNode.kt and /Volumes/Git/openclaw/jd/jd-pipeline-kotlin/src/main/resources/skills/SCAN_SKILL.md until the dataset is scanning correctly.

Use --max-iterations 5 unless I specify a different limit. Run verification before finishing and summarize:
- which dataset files pass
- which still fail or are partial
- what changed in ScanEmailNode.kt and SCAN_SKILL.md