# Token economy of the env-llm-tuner

Measured 2026-09-25. One full tuner run costs **~92,600 tokens**, of which **~48% is
waste**. This file records the measurements and the cheap-path design.

## Measured cost breakdown (one full run, current design)

| Source | chars | ~tokens | Why it is read |
|---|---:|---:|---|
| 13 node `.kt` files | 155,321 | 43,145 | Section C.1 step 3 ("read enough to determine…") |
| bullet-array output-cap screens | 40,000 | 11,111 | Section D.4, 13 models x full JSON response |
| skill file itself | 34,030 | 9,453 | always in context |
| 4 existing env files | 33,280 | 9,244 | Section E ("overwrite if they exist") |
| Config.kt (full) | 25,855 | 7,182 | Section C.1 step 1 |
| leaderboard fetch (rendered) | 19,000 | 5,278 | Section D.3 |
| LlmClient.kt (full) | 18,946 | 5,263 | Section C.1 step 2 |
| ollama cloud tags JSON | 5,000 | 1,389 | Section D.2 |
| oMLX `/v1/models` JSON | 2,000 | 556 | Section D.2 |
| **TOTAL** | **333,432** | **~92,620** | |

Ratio used: 3.6 chars/token (estimate; Kotlin/markdown vary ±15%). Relative shares are
reliable even where absolutes are not.

## Rule 1 — generate the registry; never infer it

Section A is declared "AUTO-MAINTENED — do not hand-edit" and then maintained by reading
~155 KB of Kotlin to recover **427 tokens' worth of facts** (7 config vars, 3 factories,
12 node→factory mappings, the backend enum).

Run `./gen_registry.sh` instead. It emits valid JSON with a fingerprint:

- **1,716 chars vs 155,321 — 99.0% reduction**
- Fingerprint is a SHA-256 (first 16) over Config.kt + LlmClient.kt + all node files,
  verified stable across runs.
- The model reads the JSON (and the fingerprint) and never opens the node files.

**Rule:** Section C becomes "run `./gen_registry.sh`, compare the fingerprint to the one
recorded in Section A". If equal → `Self-Scan: pipeline unchanged.` and move on. Only a
*changed* fingerprint justifies reading source at all, and then only the specific lines
that moved.

## Rule 2 — fast path when nothing changed

Fingerprint match + catalogues already fetched this cycle → emit existing files, skip
Sections C/D/E. That is the common case (Sections A/E were unchanged Jul 18 → Sep 25).

- Full run: ~92,620 tok
- No-change run: ~3,500 tok → **~96% saving**

## Rule 3 — never read a file you are about to overwrite

Section E reads all four env files (9,244 tok) to regenerate them. The *only* information
needed from them is the 8 model values each — ~640 tok total. Render from a template;
diff against the extracted values to report changes.

## Rule 4 — cache the output-cap screens (the one thing worth paying for)

The `bullet_rewrite` array screen (Section D.4) is the highest-value step in the skill: it
is what catches silent, catastrophic breakage. On 2026-09-25 it found that
`deepseek-v4-flash` had been **retired that day** and that glm-5.1's truncation bug
disappeared with the model.

Do not cut it — **cache** it. Key by `model-name + probe-version`; skip re-probing an
unchanged model. Probe only 2–3 candidates per variable rather than all 13.

## Rule 5 — split the monolith

34 KB / 547 lines, of which **1,002 tok is superseded changelog history** that can never
be needed. Split so only the procedure loads by default:

- `SKILL.md` — procedure only (always loaded)
- `references/node-registry.md` — Section A
- `references/hardware.md` — Section B speed tables + the three local constraints
- `references/cloud-caps.md` — output-cap tables + probe method
- `references/changelog.md` — history (never auto-loaded)

## Rule 6 — filter leaderboard payloads

The open-LLM leaderboard is a **45-column, 398-row** rendered table. Only ~28 models are
candidates and ~6 columns matter → ~2,000 tok instead of 5,278.

## Projected result

**~92,620 → ~8,478 tokens per full run (91% reduction)**; ~3,500 tok on a no-change run.

## Why this also raises quality

Token reduction here is not a quality trade — it removes the mechanism behind two known bugs:

- The registry becomes **deterministic**. Re-deriving node→config wiring from prose is why
  the changelog records a mis-classification (`AtsScoringNode` → `AtsValidationNode`).
- The fingerprint makes "did anything change?" a **fact**, not a judgment call — directly
  targeting the stale-comment class of bug (Config.kt:91 says temp=0.4; LlmClient uses 0.25).
- Attention is not diluted across prompt text, JSON schemas, and retry logic that has no
  bearing on model selection.

## picks.yaml → render_env.py pipeline

`picks.yaml` is the committed, machine-readable source of truth: one entry per
config var per profile (`model`, `backend`, `why`, `delta`, `runner_up`, `est`,
`verified`, `probe`). `render_env.py` generates the four `.env.*` files from it
in the Section E format — never hand-edit the env files. A pick whose
`verified` starts with `RESCREEN` renders as a `# FIXME:` comment (not a live
assignment) until the next run re-screens it. `probe_node.py --fixture
<scan|score|resume_reasoning>` verifies a challenger before adoption;
`rejections.yaml` records known-bad model/var combos so failures aren't
re-litigated. `render_env.py --table` prints the Section F summary table
generated from `picks.yaml` — never hand-maintain it.
