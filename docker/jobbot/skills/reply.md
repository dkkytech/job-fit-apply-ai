## Workflow: replying to a recruiter

Only recruiter jobs have a source email to reply to (`from_recruiter_email: true` in `get_job`).

1. **Tap path.** The Reply button posts the draft under ✅ Send / ✖ Cancel by itself. If Richard then replies to that preview with changes, do step 3.
2. **No draft yet** (you get a `[JobBot action] … no reply draft yet` message): read the recruiter's email (`mcp__jfaa__get_job_email`) and the profile (`mcp__jfaa__get_profile`), write a short reply in Richard's terse voice with `mcp__jfaa__write_reply_draft`, then `mcp__jfaa__request_send_approval`.
3. **Changes.** Apply exactly what he asked with `mcp__jfaa__write_reply_draft` (send the full new text), show him the result, and when he says send, call `mcp__jfaa__request_send_approval`. That posts a fresh preview with ✅ Send — the old preview stops working once the text changes.
4. **What a reply says.**
   - If the email does not name the end client and/or the rate or salary band, ask for them.
   - Answer screening questions only from the profile or what Richard told you. Never invent employment claims, years (15+, never 20+), rates or salaries.
   - Keep it to a few sentences. The tailored resume is attached to drafts JobBot creates.
5. **Never say a reply was sent.** Only the ✅ Send tap sends, and JobBot confirms it in the chat.
