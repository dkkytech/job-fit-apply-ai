# ENV_LLM_TUNER_SKILL

You are reviewing the LLM model assignments for a Kotlin LangGraph job-fit
pipeline. Work through the sections below in order:

1. **Section C** — Pipeline self-scan: read the live source, reconcile against
   the node registry below, and update this skill file before doing anything else.
2. **Section D** — Research: fetch current model catalogues.
3. **Section E** — Selection & output: write the four env files.
4. **Section F** — Summary: print the comparison table.

---

## Section A — Pipeline Node Registry (AUTO-MAINTAINED — do not hand-edit)

This section is rewritten by Section C on every run. All hand edits will be
overwritten the next time the skill executes.

### Config variables and the nodes they drive

| Config var               | Node(s)                                                         | Temp | JSON  | Thinking    | Typical output tokens |
|--------------------------|-----------------------------------------------------------------|------|-------|-------------|-----------------------|
| `SCAN_MODEL`             | ScanEmailNode                                                   | 0.0  | yes   | no          | 200–400               |
| `SCRAPE_MODEL`           | ScrapeJdNode (defaults to SCAN_MODEL)                           | 0.0  | yes   | no          | 300–600               |
| `SCORE_MODEL`            | ScoreFitNode, JdExtractionNode, GapAnalysisNode, AtsScoringNode | 0.0  | yes   | no          | 400–900               |
| `RESUME_REASONING_MODEL` | SummaryRewriteNode, BulletRewriteNode                           | 0.4  | mixed | yes (local) | 300–800               |
| `SKILLS_MODEL`           | SkillsRestructureNode (defaults to RESUME_REASONING_MODEL)      | 0.2  | yes   | no          | 200–500               |
| `COVER_LETTER_MODEL`     | GenerateCoverLetterNode                                         | 0.4  | no    | no          | 300–600               |
| `DRAFT_REPLY_MODEL`      | CreateDraftReplyNode                                            | 0.3  | no    | no          | 100–200               |
| `RESUME_GEN_MODEL`       | GenerateResumeHtmlNode                                          | 0.0  | no    | no          | 1500–4000             |
| `PROFILE_GEN_MODEL`      | GenerateCandidateProfileNode (defaults to RESUME_GEN_MODEL)     | 0.0  | yes   | no          | 200–800               |

### Per-node task descriptions

- **SCAN_MODEL**: Classifies incoming email as job posting or not; extracts
  partial JD fields (company, role, URL, location, remote policy, YOE,
  tech stack). Input is raw email body text. Strict JSON at temp=0. Speed is
  a priority — this is the pipeline's front door.

- **SCRAPE_MODEL**: Parses a full HTML job-posting page into structured JD
  fields. Input can be large (10k–80k tokens). Long-context support is
  important. Strict JSON at temp=0.

- **SCORE_MODEL**: The most critical node. Combined call: (1) rubric-based
  fit-scoring 0–100 with chain-of-thought reasoning, and (2) structured JD
  extraction. Also drives JdExtractionNode, GapAnalysisNode, AtsScoringNode.
  All temp=0, JSON required. Accuracy determines whether a job is processed.
  Reasoning depth beats raw speed here.

- **RESUME_REASONING_MODEL**: Rewrites professional summary (4 sentences, ATS
  phrases, no fabrication) and experience bullets (preserve all metrics,
  strong action verb, weave JD keywords). Creative task at temp=0.4; prose
  quality and constraint-following are the key metrics. Thinking mode preferred.

- **SKILLS_MODEL**: Reorders and groups the skills section so JD-matched
  skills appear first. Categorises by group (Languages, Frameworks, Cloud,
  etc.). Balanced judgment task at temp=0.2, JSON required. Must not add
  skills absent from the original resume.

- **COVER_LETTER_MODEL**: Generates a 3–4 paragraph professional-but-friendly
  cover letter for jobs scoring above `FIT_THRESHOLD`. Inputs: role title,
  company, JD text, candidate strengths, signing name. Plain prose at temp=0.4,
  jsonMode=false. Prose quality and constraint-following (no fabrication, no
  placeholders) are the key metrics; speed is secondary.

- **DRAFT_REPLY_MODEL**: Short professional email reply to a recruiter
  attaching the tailored resume. ~150 words, temp=0.3, prose. Speed matters;
  deep reasoning does not.

- **RESUME_GEN_MODEL**: Converts a DOCX or PDF resume into styled HTML by filling
  the project's base_resume.html template. Input: template HTML + plain-text source;
  output: a complete HTML document (~1500–4000 tokens). Strict structure-following
  is the key metric — the CSS block must not change. temp=0.0, jsonMode=false,
  thinking disabled. Long-context support helps (template + source can be 8k–20k tokens).

- **PROFILE_GEN_MODEL**: Parses a candidate's resume (PDF, DOCX, HTML, MD) into a
  structured `CandidateProfile` JSON. One-shot onboarding call (`--init-profile` only);
  strict JSON at temp=0, no thinking. Strong instruction-following and JSON schema
  adherence are the key metrics. Long-context support helps (multi-page resumes).
  Defaults to RESUME_GEN_MODEL if not set.

### Backend routing rules (auto-updated from LlmClient.kt)

| Suffix             | Backend         | Endpoint used                                                                  |
|--------------------|-----------------|--------------------------------------------------------------------------------|
| *(none)*           | `OLLAMA_LOCAL`  | `OLLAMA_LOCAL_BASE_URL` (default: http://localhost:11434)                      |
| `:ollama-cloud`    | `OLLAMA_CLOUD`  | `OLLAMA_CLOUD_BASE_URL` + `OLLAMA_API_KEY` (default base: https://ollama.com)  |
| `minimax*:cloud`   | `MINIMAX_CLOUD` | `MINIMAX_BASE_URL` + `MINIMAX_API_KEY`                                         |
| `<other>:cloud`    | `DEEPSEEK_CLOUD`| `DEEPSEEK_BASE_URL` + `DEEPSEEK_API_KEY`                                       |

Both Ollama backends share the same `/api/chat` wire format and `/no_think` injection for qwen3 models. `thinkingEnabled=true` is set by `reasoningClient()` for both `OLLAMA_LOCAL` and `OLLAMA_CLOUD`.

Examples:
- Local Ollama:    `qwen3:32b` (no suffix)
- Ollama Cloud:   `glm-5.1:ollama-cloud`
- DeepSeek direct: `deepseek-v4-pro:cloud`
- MiniMax direct:  `MiniMax-M2.7:cloud`

---

## Section B — Hardware Reference

MacBook Max 64 GB memory budget (leave ~8 GB for OS):
- Available for model weights: ~56 GB
- Max at Q4_K_M: ~70B params (~38 GB)
- Max at Q8_0:   ~32B params (~34 GB)
- Max at fp16:   ~28B params (~56 GB)

Ollama token-generation speeds on M-series Max (tokens/sec):
| Model size | Q4_K_M | Q8_0  |
|------------|--------|-------|
| 7B         | 55–70  | 35–50 |
| 14B        | 28–40  | 18–26 |
| 32B        | 13–20  | 8–14  |
| 70B        | 6–10   | 4–7   |

Use these figures and the typical output tokens in Section A to estimate
wall-clock time per node. Report as `~Xs`.

---

## Section C — Pipeline Self-Scan (run this first)

Read the live source code and update this skill file to reflect the current
pipeline before any model research begins.

### C.1 — Read the source-of-truth files

1. `src/main/kotlin/com/jd/pipeline/config/Config.kt`
   Extract every `val *_MODEL` property: variable name, default value,
   and inline comment.

2. `src/main/kotlin/com/jd/pipeline/client/LlmClient.kt`
   Extract:
   - `LlmBackend` enum values (detect new backends)
   - Every factory method: which Config var it reads, temperature, thinking
     enabled, JSON mode, timeout
   - `backendFor()` routing logic

3. Run: `grep -rl "LlmClient" src/`
   For each file found, read enough to determine:
   - Node class name
   - Which factory method or `fromModelString` call it uses
   - Any non-default temperature, jsonMode, or thinking overrides
   - One-sentence task description (infer from class name and prompt text)
   - Estimated output token count (infer from task type and output schema)

### C.2 — Reconcile against Section A

Classify each config variable with one of four states:

| State     | Meaning                                              |
|-----------|------------------------------------------------------|
| `MATCH`   | In Config.kt and Section A; all fields agree         |
| `CHANGED` | In Config.kt and Section A; fields differ            |
| `NEW`     | In Config.kt but missing from Section A              |
| `REMOVED` | In Section A but no longer in Config.kt              |

Also check:
- New `LlmBackend` enum values not covered by the routing rules → add rules.
- Changes to `backendFor()` routing logic → update the routing rules block.

### C.3 — Update this skill file

If any state is `CHANGED`, `NEW`, or `REMOVED`, edit
`tuner/env-llm-tuner/ENV_LLM_TUNER_SKILL.md` (this file):

- **Config variable table**: add, update, or remove rows.
- **Per-node task descriptions**: add, rewrite, or remove bullets.
- **Backend routing rules**: rewrite if routing logic changed.
- **Variable list in Section E** ("Variables to include in every file"):
  add new vars, remove removed vars.

Then print a **Self-Scan Changelog**:

```
## Self-Scan Changelog
- [NEW]     VAR_NAME: NodeClass — one-line task description
- [CHANGED] VAR_NAME: what changed (e.g. "temp 0.0→0.2, JSON now required")
- [REMOVED] VAR_NAME: node class no longer present
- [MATCH]   VAR_NAME: no changes
...
ENV_LLM_TUNER_SKILL.md updated: N changes.   (or "unchanged.")
```

If nothing changed, print `Self-Scan: pipeline unchanged.` and skip to Section D.

---

## Section D — Model Research

### Provider Catalogue URLs

For each provider key listed in `CLOUD_SUBSCRIPTIONS`, fetch the corresponding
URL(s) below. Always fetch the `ollama_local` row for local-file targets.

| Provider key      | Catalogue URL(s) to fetch                                                          |
|-------------------|------------------------------------------------------------------------------------|
| `ollama_cloud`    | https://ollama.com/search?c=cloud                                                  |
| `ollama_local`    | https://ollama.com/search  *(always fetch for local files)*                        |
| `deepseek_direct` | https://platform.deepseek.com/  and  https://api.deepseek.com/models               |
| `minimax_direct`  | https://platform.minimaxi.com/document/Models  and  https://www.minimaxi.com/en/news |
| `openai`          | https://platform.openai.com/docs/models                                            |
| `anthropic`       | https://docs.anthropic.com/en/docs/about-claude/models/overview                    |
| `google_vertex`   | https://cloud.google.com/vertex-ai/generative-ai/docs/learn/models                 |
| `groq`            | https://console.groq.com/docs/models                                               |
| `together_ai`     | https://docs.together.ai/docs/inference-models                                     |

---

### D.1 — Parse active providers

Read `CLOUD_SUBSCRIPTIONS` from the prompt. Collect every provider key whose
value is `true`. Always add `ollama_local` to the active set (required for
local env files regardless of subscription settings).

### D.2 — Fetch model catalogues

For each active provider key, fetch the URL(s) from the table above. For each
model found, extract:

- Model name and version / release date
- Parameter count and quantisation options (local models)
- Key capabilities: thinking/reasoning, function-calling/tools, long-context, vision
- Context-window size
- Pricing tier (cloud models)

If a URL is unreachable or returns no useful content, note it in your output
and substitute a web search for `"<provider> latest models April 2026"` to
recover current information. **Do not silently fall back to training-data
knowledge without noting the fallback.**

### D.3 — Cross-reference live quality benchmarks

Fetch at least one of the leaderboards below and record each shortlisted
model's rank or score. This step is what prevents stale training-data quality
estimates — do not skip it.

- **General quality (Elo):** https://lmarena.ai/
- **Open-weights overall:** https://huggingface.co/spaces/open-llm-leaderboard/open_llm_leaderboard
- **Coding / instruction-following:** https://evalplus.github.io/leaderboard.html

Record each model's leaderboard position alongside its catalogue entry so
Section D.4 can rank candidates by current, measured quality rather than
training-data impressions.

### D.4 — Shortlist 2–3 candidates per config var

Cross-reference catalogue capabilities and live leaderboard scores against the
node requirements in Section A. For each config variable, identify 2–3
candidates and note the quality delta between them.

### D.5 — Estimate wall-clock time and select winners

Use Section B's speed table and Section A's typical output token counts to
estimate wall-clock time per node per candidate. Then select the winning model
per config var per output file using:

- `.env.quality`: best cloud model, cost no object. Approved providers only.
- `.env.local-llm-quality`: best local model ≤56 GB; prefer Q8_0. No cloud.
- `.env.local-llm-good-enough`: local model finishing each node in ≤60 s;
  prefer Q4_K_M; favour smaller where quality is sufficient.
- `.env.recommended`: best everyday mix — cloud for high-value nodes
  (SCORE, RESUME_REASONING), local for cheaper nodes; optimise
  quality/cost/speed.

---

## Section E — Output File Format

Write all four files to `tuner/env-llm-tuner/`. Overwrite if they exist.

### File header (every file)

```
# ── <filename> ──────────────────────────────────────────────────────────────
# <one-line strategy description>
#
# Approach:
#   <2–4 sentences: selection strategy, constraints, notable trade-offs.>
#
# Generated: <today's date>
# Hardware (local files): <machine from prompt Section B>
# Cloud access (cloud files): <approved providers used>
#
# Catalogues fetched:
#   <provider key>: <URL> (fetched <today's date>)
#   <provider key>: <URL> (fetched <today's date>)
#   ... one line per provider active in this run; note any fallbacks
# ─────────────────────────────────────────────────────────────────────────────
```

### Per-variable comment block (every variable)

```
# ── <CONFIG_VAR> ─────────────────────────────────────────────────────────────
# Model: <chosen model>
# Why best / why good-enough:
#   <2–3 sentences.>
# Quality delta vs runner-up: <e.g. "R1 ~8% better on JSON evals; worth it">
# Estimated node time: ~<N>s  (<output tokens> tokens @ ~<tok/s> tok/s)
# Runner-up: <model> — <one-line reason not chosen>
<VAR>=<value>
```

### Output file paths

- `tuner/env-llm-tuner/.env.quality`
- `tuner/env-llm-tuner/.env.local-llm-quality`
- `tuner/env-llm-tuner/.env.local-llm-good-enough`
- `tuner/env-llm-tuner/.env.recommended`

### Variables to include in every file

```
SCAN_MODEL=
SCRAPE_MODEL=
SCORE_MODEL=
RESUME_REASONING_MODEL=
SKILLS_MODEL=
COVER_LETTER_MODEL=
DRAFT_REPLY_MODEL=
RESUME_GEN_MODEL=
PROFILE_GEN_MODEL=
```

Local files also include:
```
OLLAMA_LOCAL_BASE_URL=http://localhost:11434
# OLLAMA_API_KEY intentionally not set (local)
```

Cloud quality file also includes:
```
OLLAMA_CLOUD_BASE_URL=https://ollama.com
OLLAMA_API_KEY=<your-ollama-cloud-key>
```

---

## Section F — Summary Table

After writing all four files, print:

```
| Config Var               | .env.quality | .env.local-quality | .env.local-good-enough | .env.recommended |
|--------------------------|--------------|--------------------|------------------------|------------------|
| SCAN_MODEL               | deepseek-v4-flash:ollama-cloud | qwen3.6:27b        | qwen3:8b               | qwen3:8b         |
| SCRAPE_MODEL             | deepseek-v4-flash:ollama-cloud | qwen3.6:27b        | qwen3:8b               | qwen3:8b         |
| SCORE_MODEL              | kimi-k2.6:ollama-cloud | deepseek-r1:70b    | qwen3:8b               | minimax-m3:ollama-cloud |
| RESUME_REASONING_MODEL   | kimi-k2.6:ollama-cloud | deepseek-r1:70b    | qwen3:8b               | minimax-m3:ollama-cloud |
| SKILLS_MODEL             | deepseek-v4-flash:ollama-cloud | qwen3.6:27b        | qwen3:8b               | qwen3:8b         |
| COVER_LETTER_MODEL       | kimi-k2.6:ollama-cloud | gemma4:31b         | qwen3:8b               | qwen3:14b        |
| DRAFT_REPLY_MODEL        | deepseek-v4-flash:ollama-cloud | qwen3:8b           | qwen3:8b               | qwen3:8b         |
| Est. full-pipeline time  | ~154s        | ~734s              | ~173s                  | ~190s            |
```

The last row is the sum of all node estimates for one job reaching the
tailoring subgraph (the worst-case hot path).
