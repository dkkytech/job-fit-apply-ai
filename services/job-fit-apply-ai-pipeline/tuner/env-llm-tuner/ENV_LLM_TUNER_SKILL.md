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
| `SCAN_MODEL`             | ScanEmailNode, LlmDigestStrategy                                | 0.0  | yes   | no          | 200–400               |
| `SCRAPE_MODEL`           | ScrapeJdNode (defaults to SCAN_MODEL)                           | 0.0  | yes   | no          | 300–600               |
| `SCORE_MODEL`            | ScoreFitNode, JdExtractionNode, GapAnalysisNode, AtsValidationNode | 0.0  | yes   | no          | 400–900               |
| `RESUME_REASONING_MODEL` | SummaryRewriteNode, BulletRewriteNode                           | 0.25 | mixed | qwen3 /no_think | 300–800           |
| `SKILLS_MODEL`           | SkillsRestructureNode (defaults to RESUME_REASONING_MODEL)      | 0.2  | yes   | no          | 200–500               |
| `COVER_LETTER_MODEL`     | GenerateCoverLetterNode                                         | 0.4  | no    | no          | 300–600               |
| `DRAFT_REPLY_MODEL`      | DraftReplyComposer                                             | 0.3  | no    | no          | 100–200               |

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
  extraction. Also drives JdExtractionNode, GapAnalysisNode, AtsValidationNode
  (those three via `orchestrationClient`; ScoreFitNode via `fromModelString`).
  All temp=0, JSON required. Accuracy determines whether a job is processed.
  Reasoning depth beats raw speed here.

- **RESUME_REASONING_MODEL**: Rewrites professional summary (4 sentences, ATS
  phrases, no fabrication) and experience bullets (preserve all metrics,
  strong action verb, weave JD keywords). Creative task at temp=0.25 (lowered
  from 0.4 in `reasoningClient` to cut drift/fabrication on dense-local models);
  prose quality and constraint-following are the key metrics. Thinking is OFF by
  default on local (`RESUME_REASONING_THINKING=false` → `/no_think` for qwen3).

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
  attaching the tailored resume. ~150 words, temp=0.3, prose (jsonMode=false).
  Speed matters; deep reasoning does not. Driven by `DraftReplyComposer` (the
  LLM/templating half of the former `CreateDraftReplyNode`, since split).

### Removed nodes

- **RESUME_GEN_MODEL** and **PROFILE_GEN_MODEL** are no longer pipeline config
  vars. Resume HTML is rendered deterministically from `resume.yaml` (no LLM),
  and the candidate profile is authored as structured YAML. The nodes
  `GenerateResumeHtmlNode` and `GenerateCandidateProfileNode` are gone.

### Backend routing rules (auto-updated from LlmClient.kt)

| Suffix             | Backend         | Endpoint used                                                                  |
|--------------------|-----------------|--------------------------------------------------------------------------------|
| *(none)*           | `MLX_LOCAL`     | `MLX_LOCAL_BASE_URL` + `MLX_API_KEY` (default: http://127.0.0.1:11436/v1) — **oMLX** |
| `:ollama-local`    | `OLLAMA_LOCAL`  | `OLLAMA_LOCAL_BASE_URL` (default: http://localhost:11434) — legacy escape hatch |
| `:ollama-cloud`    | `OLLAMA_CLOUD`  | `OLLAMA_CLOUD_BASE_URL` + `OLLAMA_API_KEY` (default base: https://ollama.com)  |
| `minimax*:cloud`   | `MINIMAX_CLOUD` | `MINIMAX_BASE_URL` + `MINIMAX_API_KEY`                                         |
| `<other>:cloud`    | `DEEPSEEK_CLOUD`| `DEEPSEEK_BASE_URL` + `DEEPSEEK_API_KEY`                                       |

**Local models run on oMLX (no suffix).** oMLX is an OpenAI-compatible MLX server
(`/v1/chat/completions`); local model names are bare MLX model ids from
`mlx-community` / LM Studio (e.g. `Qwen3.5-9B-OptiQ-4bit`), **not** Ollama GGUF tags.
`/no_think` is still prepended for qwen3-family models to suppress thinking, and
output reasoning (`<think>`/`<thinking>`) is stripped centrally in `LlmClient.call()`.
`:ollama-local` remains only as a legacy escape hatch; do not use it for recommendations.

Examples:
- Local oMLX:       `Qwen3.5-9B-OptiQ-4bit` (no suffix)
- Ollama Cloud:     `glm-5.1:ollama-cloud`
- DeepSeek direct:  `deepseek-v4-pro:cloud`
- MiniMax direct:   `MiniMax-M2.7:cloud`

---

## Section A2 — Analyzer model (`RUN_ANALYZER_MODEL`) — evaluated, but NOT a pipeline node

This tuner also selects `RUN_ANALYZER_MODEL`, but it is **not** a pipeline `Config.kt` node — it
belongs to the run-analyzer tool (`tuner/run-analyzer/`, read from `.env` by `run_analyzer.sh`).
Section C's self-scan will NOT find it in `Config.kt`/`LlmClient.kt`; **this subsection is manually
maintained — preserve it across self-scans** (do not delete it when rewriting Section A).

- **Task:** analyzes the JD pipeline's recently-completed jobs. Two model calls: (1) a **health
  analysis** over a window of job records/metrics → strict JSON (`health`, `metrics`, `regressions`,
  `findings[]`, each finding carrying a self-contained `agent_prompt`); (2) a per-job scoring **audit**
  → strict JSON verdict (`justified|too_low|too_high|ungrounded`, `cause`, `confidence`). It also
  authors root-cause narration and target file paths in findings.
- **Requirements:** strong structured-JSON adherence + reasoning + file-path accuracy. It runs at most
  hourly and is batch-gated, so it is **latency-tolerant — quality ≫ speed.** The deep audit is the
  hard part: weak local models fail it (Qwen3.5-9B returns malformed output such as `[1]`);
  `DeepSeek-R1-Distill-Qwen-32B-4bit` and cloud reasoning models (e.g. `deepseek-v4-pro`) produce clean
  verdicts. **Pick a genuinely capable model** — this is the one var where a too-small model silently
  produces garbage rather than merely lower quality.
- **Backend constraint (narrower than the pipeline — do not violate):** the analyzer's own `llm.py`
  supports only **oMLX-local (no suffix)**, **`:ollama-cloud`**, and `:ollama-local`. It does **NOT**
  implement the `:cloud` (DeepSeek/MiniMax-direct) backends. A cloud pick therefore MUST use the
  `:ollama-cloud` suffix — e.g. `deepseek-v4-pro:ollama-cloud`, **never** `deepseek-v4-pro:cloud`.
- **Cloud-cap efficiency:** a `:ollama-cloud` value counts toward the ≤3-distinct-cloud cap (Section
  D.5). **Prefer reusing a cloud model already selected for a pipeline node** (e.g. the SCORE or
  RESUME_REASONING cloud model) so the analyzer consumes **zero** extra slots. Only spend a distinct
  slot on it if none of the selected cloud models is capable enough for the audit.

---

## Section B — Hardware Reference

Local models run on **oMLX** (MLX format). MacBook Max 64 GB memory budget (leave ~8 GB for OS):
- Available for model weights: ~56 GB
- Max at MLX 4-bit: ~70B dense (~38 GB) — but prefer MoE for speed (see below)
- Max at MLX 8-bit: ~32B dense (~34 GB)
- MoE models (e.g. `*-A3B-*`) load the full weights but only activate ~3B params per
  token, so they run at small-model speed with large-model breadth — preferred for the
  **extraction/classification/scoring** nodes (SCAN, SCRAPE, SCORE, PROFILE_GEN) where
  throughput matters and per-token reasoning depth is light.
- **DENSE models beat MoE for resume CONTENT writing.** RESUME_REASONING (SummaryRewrite +
  BulletRewrite) and SKILLS (SkillsRestructure) are dense multi-constraint tasks — map JD
  requirements onto real candidate facts, inject ATS keywords, frame impact, never fabricate —
  and depend on *single-token quality*, which scales with **active** params, not total. A
  ~3B-active MoE (e.g. `Qwen3.6-35B-A3B`) measurably degraded resume PDFs (2026-06 regression);
  switching RESUME_REASONING + SKILLS to a dense model fixed it.
  **Rule: prefer dense for RESUME_REASONING + SKILLS; MoE is fine for the lighter JSON/extraction nodes.**
- **THREE constraints the dense pick MUST satisfy (all verified 2026-06-24 via `--test-resume`):**
  1. **NOT multimodal.** `gemma-4-*` is image-text → oMLX runs it on `VLMBatchedEngine`, brutally slow
     for text: `gemma-4-31B-it-qat-8bit` ~1.8 tok/s (timeout); `gemma-4-12B-it-qat-4bit` HUNG on the real
     long-prompt summary_rewrite (315s, zero tokens — fine only on tiny test prompts). Multimodal is OUT
     for the resume hot path at any size; check the model card for image/vision support before choosing.
  2. **Capable enough for strict structured output.** bullet_rewrite expects a JSON *array* of RoleRewrite;
     `Qwen3.5-9B-OptiQ-4bit` (dense text) returned a wrapping *object* → deserialization failure. Sub-~27B
     dense models may be too weak for the schema. Prefer ≥27B dense.
  3. **`/no_think` must fire** or qwen3 models leak chain-of-thought. The check is
     `model.substringAfterLast("--").startsWith("qwen3")` (LlmClient.kt) — prefix-tolerant, so HF-cache ids
     like `mlx-community--Qwen3.6-27B-4bit` work. (gemma is not a thinking model, so this only matters for qwen.)
  **Chosen pick: `mlx-community--Qwen3.6-27B-4bit`** (dense, text engine, ~13 tok/s) — the only installed
  model meeting all three; full TAILOR run ~10 min/job. Cover-letter/draft prose is single-pass and does
  well on `gemma-4-12B-it-qat-4bit` (multimodal but those prompts are short, so the VLM engine is tolerable).

- **CLOUD picks for RESUME_REASONING have a FOURTH constraint the local rule doesn't surface — max OUTPUT
  tokens (verified 2026-07-05 via `--test-resume` + isolated Ollama Cloud screening).** `bullet_rewrite` is
  the pipeline's single LARGEST-output call: it returns a JSON *array* of RoleRewrite (≈8 roles × ~4–5
  rewritten bullets = **~25k–34k output tokens**). Cloud models silently TRUNCATE it if their generation cap
  is too low, or waste the budget thinking. Measured on the real node + a faithful array-shaped probe:
  | Cloud model         | done_reason | out tokens | result |
  |---------------------|-------------|------------|--------|
  | `deepseek-v4-pro`   | stop        | ~26k       | ✅ full 8-role array, real run: 33 bullets, ATS 74→**86** after refine |
  | `deepseek-v4-flash` | stop        | ~34k       | ✅ full array (rank 66 → lower quality; 1M ctx; fallback) |
  | `glm-5.1`           | **length**  | 32768 cap  | ❌ over-thinks, burns the whole 32k budget → returns EMPTY content |
  | `kimi-k2.6`         | **length**  | 16384 cap  | ❌ hard 16k cap → truncated JSON → node nulls tailoredBullets AND cascades to null ATS |
  A truncated `bullet_rewrite` doesn't just lose bullets — it nulls `tailoredBullets`, which cascades to a
  null ATS score and disables the ATS refinement pass (the whole point of the tailor subgraph). So for the
  CLOUD RESUME_REASONING pick: **verify the model completes this array with `done_reason=stop` (not `length`),
  and prefer a model that doesn't over-think.** Top LMArena rank is NOT sufficient — glm-5.1 (rank 22) and
  kimi-k2.6 (rank 34) both FAIL this node despite outranking deepseek-v4-pro (rank 38), which is why
  `deepseek-v4-pro:ollama-cloud` is the chosen cloud RESUME_REASONING model. NOTE: `LlmClient.callOllama`
  sets no `num_predict`, so these caps are the models'/Ollama-Cloud defaults, not ours — raising an explicit
  cap might rescue kimi but NOT glm-5.1 (it emits no JSON at all). glm-5.1 stays fine for SCORE/RESUME_GEN
  (smaller outputs).

oMLX/MLX token-generation speeds on M-series Max (tokens/sec, 4-bit):
| Model class            | 4-bit | 8-bit |
|------------------------|-------|-------|
| 7–9B dense             | 60–85 | 38–55 |
| 12–14B dense           | 40–55 | 24–34 |
| 27–32B dense           | 16–24 | 9–15  |
| 30–35B MoE (~3B active)| 45–70 | —     |

MLX is generally ~15–30% faster than Ollama/GGUF on Apple Silicon for the same model.
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

**Exclude `RUN_ANALYZER_MODEL` from this reconciliation.** It is NOT a `Config.kt` node var
(Section A2) — do not classify it as `REMOVED` for being absent from `Config.kt`, and do not delete
Section A2 or its Section E line. Only revise A2 if the run-analyzer's own model handling
(`tuner/run-analyzer/analyzer/llm.py`) changes its supported backends.

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

## Self-Scan Changelog (2026-07-18 run)
- [MATCH]   SCAN_MODEL: ScanEmailNode, LlmDigestStrategy (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCRAPE_MODEL: ScrapeJdNode (fromModelString, temp 0.0, jsonMode true; defaults to SCAN_MODEL)
- [CHANGED] SCORE_MODEL: node class `AtsScoringNode` → **`AtsValidationNode`**
            (AtsValidationNode.kt:28, `orchestrationClient`, nodeKey still "ats_scoring").
            Driver unchanged: SCORE_MODEL, temp 0.0, jsonMode true, thinking off.
            ScoreFitNode / JdExtractionNode / GapAnalysisNode unchanged.
- [MATCH]   RESUME_REASONING_MODEL: SummaryRewriteNode, BulletRewriteNode (reasoningClient, temp 0.25;
            BulletRewriteNode overrides timeoutSeconds=480 for the large array output)
- [MATCH]   SKILLS_MODEL: SkillsRestructureNode (skillsClient, temp 0.2, jsonMode true)
- [MATCH]   COVER_LETTER_MODEL: GenerateCoverLetterNode (fromModelString, temp 0.4, jsonMode false)
- [MATCH]   DRAFT_REPLY_MODEL: DraftReplyComposer (fromModelString, temp 0.3, jsonMode false)
- [MATCH]   RESUME_GEN_MODEL / PROFILE_GEN_MODEL: remain absent from Config.kt (removed 2026-07-16).
Backend enum (MLX_LOCAL, OLLAMA_LOCAL, OLLAMA_CLOUD, DEEPSEEK_CLOUD, MINIMAX_CLOUD) and
backendFor() routing (LlmClient.kt:382-391) verified unchanged vs the routing-rules table.
Section A2 re-verified against tuner/run-analyzer/analyzer/llm.py:29 — still oMLX-local /
`:ollama-cloud` / `:ollama-local` only, no `:cloud`. Unchanged.
NOTE: Config.kt:91 still comments "Creative (temp=0.4)" for RESUME_REASONING, but
reasoningClient uses 0.25 (LlmClient.kt:321). Stale source comment only — no behaviour impact.
ENV_LLM_TUNER_SKILL.md updated: 1 change (SCORE_MODEL node-class rename).

## Self-Scan Changelog (2026-07-16 run, superseded)
- [MATCH]   SCAN_MODEL: ScanEmailNode, LlmDigestStrategy (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCRAPE_MODEL: ScrapeJdNode (fromModelString, temp 0.0, jsonMode true)
- [MATCH]   SCORE_MODEL: ScoreFitNode, JdExtractionNode, GapAnalysisNode, AtsScoringNode
            (ScoreFitNode now uses fromModelString instead of orchestrationClient —
             functionally equivalent: temp 0.0, jsonMode true, thinking disabled)
- [MATCH]   RESUME_REASONING_MODEL: SummaryRewriteNode, BulletRewriteNode (reasoningClient, temp 0.25)
- [MATCH]   SKILLS_MODEL: SkillsRestructureNode (skillsClient, temp 0.2, jsonMode true)
- [MATCH]   COVER_LETTER_MODEL: GenerateCoverLetterNode (fromModelString, temp 0.4, jsonMode false)
- [MATCH]   DRAFT_REPLY_MODEL: DraftReplyComposer (fromModelString, temp 0.3, jsonMode false)
- [REMOVED] RESUME_GEN_MODEL: GenerateResumeHtmlNode — node removed; resume HTML now rendered
            deterministically from resume.yaml (no LLM). Config.kt line 261-262.
- [REMOVED] PROFILE_GEN_MODEL: GenerateCandidateProfileNode — node removed; candidate profile
            now authored as structured YAML (no LLM). Config.kt line 261-262.
Backend enum (MLX_LOCAL, OLLAMA_LOCAL, OLLAMA_CLOUD, DEEPSEEK_CLOUD, MINIMAX_CLOUD) and
backendFor() routing verified unchanged vs the routing-rules table.
ENV_LLM_TUNER_SKILL.md updated: 2 removals (RESUME_GEN_MODEL, PROFILE_GEN_MODEL).


---

## Section D — Model Research

### Provider Catalogue URLs

For each provider key listed in `CLOUD_SUBSCRIPTIONS`, fetch the corresponding
URL(s) below. Always handle the `mlx_local` row for local-file targets.

| Provider key      | Catalogue URL(s) to fetch                                                          |
|-------------------|------------------------------------------------------------------------------------|
| `ollama_cloud`    | https://ollama.com/search?c=cloud                                                  |
| `mlx_local`       | **First** query the live oMLX server for installed models: `curl -s -H "Authorization: Bearer $MLX_API_KEY" $MLX_LOCAL_BASE_URL/models`. Recommendations MUST come from this installed set. To research/expand the pool: https://huggingface.co/mlx-community (and https://lmstudio.ai/models) — MLX 4-bit/8-bit builds only. |
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
value is `true`. Always add `mlx_local` to the active set (required for the
local env files regardless of subscription settings). Local candidates are
restricted to MLX models actually installed in the oMLX server (query it first);
never recommend an Ollama GGUF tag for a local file.

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

**Also shortlist `RUN_ANALYZER_MODEL` (Section A2).** Screen for structured-JSON reliability at
temp 0 (the audit verdict + findings schema) and reasoning depth, not speed. Only `:ollama-cloud`
or local-oMLX candidates are valid (no `:cloud`). Reject any model that can't reliably return a JSON
object for the audit prompt (small dense/MoE locals tend to emit a bare array/scalar).

**For RESUME_REASONING specifically, leaderboard rank is NOT sufficient — screen the
max-output-token behaviour on the bullet_rewrite array (see Section B's cloud-cap table).**
A candidate must complete the ~25k–34k-token 8-role array with `done_reason=stop`, not
`length`. A quick screen: POST an 8-role/5-bullet `format:json` array request to the model
and check `done_reason` + that the content parses. Reject any that truncate (kimi-k2.6) or
over-think to empty (glm-5.1), regardless of Elo.

### D.5 — Estimate wall-clock time and select winners

Use Section B's speed table and Section A's typical output token counts to
estimate wall-clock time per node per candidate. Then select the winning model
per config var per output file using:

- `.env.quality`: the best we can possibly do — best cloud model per node, cost AND
  model-count no object. Approved providers only. **NOT limited by the 3-model subscription
  cap** — this is an aspirational reference, so pick the single best model for each node's
  actual demand even if the result uses more distinct cloud models than a live subscription
  can load at once. Default to the highest-Arena-Elo model for every node and deviate only
  where a node has a disqualifying constraint (e.g. RESUME_REASONING needs a high output-token
  cap; SCRAPE needs a huge context window; prose nodes may prefer a dedicated writer). Note in
  the file header when the distinct-cloud-model count exceeds 3 (so the reader knows it is not
  directly runnable under the cap — .env.recommended is the runnable ≤3 profile).
- `.env.local-llm-quality`: best installed oMLX model ≤56 GB. For RESUME_REASONING + SKILLS
  pick a **dense, non-multimodal, ≥27B** text model (`mlx-community--Qwen3.6-27B-4bit`) — NOT a
  multimodal model (any `gemma-4-*` → VLM engine → hangs/timeout on real prompts) and NOT a sub-27B
  dense (too weak for bullet_rewrite's JSON-array schema); for SCAN/SCRAPE/SCORE/PROFILE_GEN a large
  MoE (`*-A3B-*`) is fine for speed. No cloud.
- `.env.local-llm-good-enough`: installed oMLX model finishing each node in ≤60 s;
  prefer 4-bit MLX; MoE is acceptable here even for content nodes if dense is too slow,
  but note resume quality will suffer vs a dense pick.
- `.env.recommended`: best everyday mix — cloud for high-value nodes
  (SCORE, RESUME_REASONING), local for cheaper nodes; optimise
  quality/cost/speed.
  **HARD CONSTRAINT — ≤3 distinct Ollama Cloud models.** The Ollama Cloud subscription
  only permits **3 different models loaded at a time**, so `.env.recommended` must use
  **at most 3 distinct `:ollama-cloud` model names** across ALL nine vars (a model reused
  on multiple nodes counts once). Before writing the file, list the distinct cloud model
  names and confirm the count is ≤3; if a 4th is tempting, either reuse an already-selected
  cloud model or push that node to local. **`RUN_ANALYZER_MODEL` (Section A2) is now a
  tuner-selected var and COUNTS toward this cap when it is `:ollama-cloud`.** So the ≤3 count spans
  all nine node vars PLUS `RUN_ANALYZER_MODEL`. Keep it free: **set `RUN_ANALYZER_MODEL` to a cloud
  model already chosen for a node** (it must be audit-capable — e.g. the SCORE model if that's a
  strong reasoner) so it adds **zero** distinct models; only spend a distinct slot on the analyzer if
  no selected cloud model is capable enough. This ≤3-distinct-cloud-model cap applies ONLY to
  `.env.recommended` (the everyday runnable profile). `.env.quality` is EXEMPT — it is the
  aspirational best-possible reference and may use as many distinct cloud models as the
  best-per-node choices require (it just flags in its header when it exceeds 3). The
  local-only files have no cloud models, so the cap is moot for them.

  **Per-file `RUN_ANALYZER_MODEL` selection:**
  - `.env.quality`: the single best `:ollama-cloud` reasoning model for structured audit (cap-exempt).
  - `.env.recommended`: reuse an audit-capable cloud model already selected for a node (0 extra slots);
    only pick a distinct one if none qualifies.
  - `.env.local-llm-quality`: the strongest local reasoner that returns valid JSON (e.g.
    `DeepSeek-R1-Distill-Qwen-32B-4bit`); note in the comment that the deep audit is weaker locally.
  - `.env.local-llm-good-enough`: a local model that still returns a valid JSON object; note the audit
    degrades and can be bounded via `RUN_ANALYZER_AUDIT_MAX` (it is best-effort, never fatal).

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
RUN_ANALYZER_MODEL=      # analyzer tool (Section A2) — :ollama-cloud or local-oMLX only, never :cloud
```

Local files also include (oMLX endpoint — no Ollama):
```
MLX_LOCAL_BASE_URL=http://127.0.0.1:11436/v1
MLX_API_KEY=11436
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
| SCAN_MODEL               | glm-5.1:ollama-cloud | Qwen3.6-35B-A3B-OptiQ-4bit | Qwen3.5-9B-OptiQ-4bit | Qwen3.6-35B-A3B-OptiQ-4bit |
| SCRAPE_MODEL             | deepseek-v4-flash:ollama-cloud (1M ctx) | Qwen3.6-35B-A3B-OptiQ-4bit | Qwen3.5-9B-OptiQ-4bit | Qwen3.6-35B-A3B-OptiQ-4bit |
| SCORE_MODEL              | glm-5.1:ollama-cloud | DeepSeek-R1-Distill-Qwen-32B-4bit | Qwen3.6-35B-A3B-OptiQ-4bit | glm-5.1:ollama-cloud |
| RESUME_REASONING_MODEL   | deepseek-v4-pro:ollama-cloud (kimi/glm TRUNCATE) | **mlx-community--Qwen3.6-27B-4bit (DENSE)** | Qwen3.6-35B-A3B-OptiQ-4bit (MoE, quality tradeoff) | deepseek-v4-pro:ollama-cloud |
| SKILLS_MODEL             | glm-5.1:ollama-cloud | **mlx-community--Qwen3.6-27B-4bit (DENSE)** | Qwen3.6-35B-A3B-OptiQ-4bit (MoE, quality tradeoff) | **mlx-community--Qwen3.6-27B-4bit (DENSE)** |
| COVER_LETTER_MODEL       | kimi-k2.6:ollama-cloud | gemma-4-12B-it-qat-4bit | gemma-4-12B-it-qat-4bit | gemma-4-12B-it-qat-4bit |
| DRAFT_REPLY_MODEL        | kimi-k2.6:ollama-cloud | gemma-4-12B-it-qat-4bit | Qwen3.5-9B-OptiQ-4bit | gemma-4-12B-it-qat-4bit |
| RUN_ANALYZER_MODEL       | deepseek-v4-pro:ollama-cloud | DeepSeek-R1-Distill-Qwen-32B-4bit (audit weaker) | DeepSeek-R1-Distill-Qwen-32B-4bit (audit weaker) | deepseek-v4-pro:ollama-cloud (reuses RESUME slot) |
| Distinct cloud models    | 4 (glm-5.1, deepseek-v4-pro, deepseek-v4-flash, kimi-k2.6 — analyzer reuses deepseek-v4-pro) — exceeds 3-model cap by design | 0 | 0 | 2 (glm-5.1, deepseek-v4-pro — analyzer REUSES deepseek-v4-pro, 0 extra) |
| Est. hot-path time       | ~58s         | ~138s              | ~92s                   | ~103s            |

Hot-path = SCAN→SCRAPE→SCORE→RESUME_REASONING→SKILLS→COVER_LETTER→DRAFT_REPLY (one job reaching
the tailoring subgraph). RESUME_GEN_MODEL and PROFILE_GEN_MODEL are no longer pipeline vars
(resume HTML and candidate profile are now produced deterministically, no LLM).
gemma-4-12B local timings use the corrected ~13.5 tok/s VLM-engine rate (live-measured 2026-07-05),
not the old ~19 tok/s tiny-prompt figure.
```

The last row is the sum of all node estimates for one job reaching the
tailoring subgraph (the worst-case hot path).

**RESUME_REASONING_MODEL and SKILLS_MODEL must be DENSE (not `*-A3B-*` MoE), non-multimodal,
≥27B (for the JSON-array schema), and `/no_think`-clean** in every local profile — they write the
resume content and depend on single-token quality (see the dense-vs-MoE rule + the three constraints
in the memory budget section). MoE is fine for the other (extraction/JSON) nodes. Re-estimate local
timings after any swap — measured: Qwen3.6-27B-4bit ~13 tok/s (full TAILOR ~10 min/job), vs ~45–70
for a 3B-active MoE (and any multimodal `gemma-4-*` hangs/times out on real long prompts).
