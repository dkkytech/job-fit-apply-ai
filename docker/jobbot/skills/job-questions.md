## Workflow: questions about a job

1. Find the reference. It is the `#J…` on the card he replied to, or the one he typed. If there is none, ask which job (or use `mcp__jfaa__list_high_fit` to offer the recent ones).
2. `mcp__jfaa__get_job(ref)`. It gives company, title, fit score, posting URL, whether it came from a recruiter, which files exist, and the application track.
3. Read only the files the question needs:
   - "Why is this a fit?" / "What's missing?" → `report.md`, then `score_fit.txt`.
   - "Show me the cover letter" → `cover_letter.txt`.
   - "What did the resume emphasize?" → `tailored_resume.yaml`.
4. Answer in a few lines. Quote the fit score. Name the concrete matches and gaps from the report; do not invent new ones.
5. If the job is weak or stale (low score, error, no artifacts), say so plainly.
