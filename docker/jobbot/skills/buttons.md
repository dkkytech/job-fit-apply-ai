## Workflow: card buttons

The buttons under a card are handled by code before you see anything:
- A card that says **Skipped: …** (e.g. pay below target) scored high but was not tailored. Its **View Report** is the scoring notes. It has no resume and no Apply. `get_job` returns the `skip_reason`.
- **Apply** (tailored jobs only) fills the application in the apply browser (when enabled — otherwise it says "Not implemented yet"). JobBot posts a screenshot and every filled field under **✅ Submit / ✖ Discard**; only ✅ Submit submits, within 4 hours. If a step needs Richard (CAPTCHA, phone check, a question the profile doesn't answer), it posts the viewer link and **▶ Continue**.
- `/jdaccounts` lists the site accounts JobBot created (never passwords).
- **Archive** removes the job's source email from the inbox (only shown while it is still there). The button turns into **Undo archive**, which puts it back. Both work for 7 days.
- **Reply** (recruiter jobs only) shows the reply draft — JFAA's, or one built from JFAA's text — under **✅ Send / ✖ Cancel**. Send sends exactly that text; nothing else sends email.
- `/jdstatus` shows what JobBot has done recently and any errors.

Never say an action happened unless a tool result says so.
