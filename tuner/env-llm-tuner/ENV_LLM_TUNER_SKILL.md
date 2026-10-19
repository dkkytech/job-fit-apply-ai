# ENV_LLM_TUNER_SKILL

You are reviewing the LLM model assignments for a Kotlin LangGraph job-fit
pipeline. Work through the sections below in order:

1. **Section P** — Pre-flight: fingerprint check, oMLX reachability, load incumbents.
2. **Section C** — Pipeline self-scan: read the live source, reconcile against
   the node registry below, and update this skill file before doing anything else.
3. **Section D** — Research: fetch current model catalogues; challenger-vs-incumbent
   with probing (D.6).
4. **Section E** — Selection & output: update `picks.yaml`, render the four env files.
5. **Section F** — Summary: print the comparison table.

---

## Section A — Pipeline Node Registry (AUTO-MAINTAINED — do not hand-edit)

This section is rewritten by Section C on every run. All hand edits will be
overwritten the next time the skill executes.

**Registry fingerprint: `e893776e00d237d5`**

Regenerate with `./gen_registry.sh` (see Section C.1). If the emitted fingerprint matches
the value above, the registry below is current — skip straight to Section D.

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
- Ollama Cloud:     `deepseek-v4-pro:0813:ollama-cloud`
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

**Moved to `references/hardware.md`.** Read it before Section D.4 (shortlisting) and
Section D.5 (wall-clock estimation) — it holds the local memory budget, the three dense-model
constraints, the cloud output-cap table and probe history, and the oMLX speed measurements.

It is deliberately not inline: Sections C and E do not need it, and carrying it in the
always-loaded context cost ~1,500 tokens per run. See `README-TOKENS.md`.

## Section P — Pre-flight (cheap gates, run before Section C)

1. **Registry fingerprint.** Run `./gen_registry.sh` and compare its fingerprint
   with the one in Section A. Match → the registry is current; skip to Section D.
   Mismatch → run Section C, then update the fingerprint in Section A.
2. **oMLX reachability.** `curl -s -m 10 $MLX_LOCAL_BASE_URL/models` with the API
   key. If unreachable and a local pick needs (re-)verification: mark it
   unverified in `picks.yaml`, continue cloud-only, and record the fallback in
   the env file headers. Never silently use the static inventory.
3. **Load incumbents.** Read `picks.yaml` (current picks per var per profile) and
   `rejections.yaml` (known-bad model/var combos). These are Section D's starting
   point — not a fresh shortlist.

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

## Self-Scan Changelog

Current history lives in `references/changelog.md` (append there; never here).

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

**Use the helper scripts — do not read raw API payloads.** They print exactly the lines
the decision needs:

```bash
scripts/tuner_tools.py models                 # installed oMLX models (one per line)
scripts/tuner_tools.py cloud                  # ollama-cloud catalogue (one per line)
```

For each model found, extract:

- Model name and version / release date
- Parameter count and quantisation options (local models)
- Key capabilities: thinking/reasoning, function-calling/tools, long-context, vision
- Context-window size
- Pricing tier (cloud models)

**Note:** the Ollama Cloud catalogue is also the authority on RETIREMENT. A model listed in
an existing env file but absent from `cloud` has been withdrawn — check `done_reason` errors
too, since a model can be retired mid-run (observed: `deepseek-v4-flash`, 2026-09-25).

If a URL is unreachable or returns no useful content, note it in your output
and substitute a web search for `"<provider> latest models <month year>"` to
recover current information. **Do not silently fall back to training-data
knowledge without noting the fallback.**

### D.3 — Cross-reference live quality benchmarks

**Use the trimmed helper — do not fetch the leaderboard page directly.** The rendered
leaderboard is a 45-column × ~400-row table (~5,278 tokens); the helper returns ~140 tokens
for the models you actually care about:

```bash
scripts/tuner_tools.py leaderboard "Kimi K3" "GLM-5.3" "DeepSeek-V4.1-Flash" ...
```

It prints rank, general/reasoning/code/agent indexes, and price for each named model.

> Implementation note: the leaderboard is a client-rendered Next.js app whose served HTML
> contains **zero** table rows. The helper reassembles the `self.__next_f.push` RSC chunks
> and parses the embedded model JSON. If it reports "page structure may have changed",
> the upstream payload changed shape — fix the helper, do not fall back to reading the page.

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
max-output-token behaviour on the bullet_rewrite array (see `references/hardware.md`'s cap
table).** A candidate must complete the ~25k–34k-token 8-role array with `done_reason=stop`,
not `length`. Reject any that truncate or over-think to empty, regardless of Elo.

**For EVERY JSON node, screen time-to-valid-JSON and VARIANCE — not just rank or a single
run.** A node can fail *upstream* of the one you are testing and silently disable the whole
subgraph: `gapAnalysis` is a hard dependency for SummaryRewrite, BulletRewrite,
SkillsRestructure and AtsValidation (each returns `"<node>: gapAnalysis is null"`), so one
slow SCORE call short-circuits the entire tailor run. Run each candidate **at least twice**
and watch output-token count as the leading indicator — a model emitting >10k tokens for a
sub-1k-token answer is over-thinking and will eventually hit its timeout.

Measured 2026-09-25 on the real `gap_analysis` shape (8 must-have + 3 nice-to-have, ~384
input tokens, `orchestrationClient` budget **180s** — see `references/score-screening.md`):

| Model | trial 1 | trial 2 | out tokens | valid JSON |
|---|---|---|---|---|
| `glm-5.3` | 44.7s | 51.8s | 8,624 | ❌ |
| `glm-5.2` | 12.8s | 13.1s | 1,351 | ❌ |
| `deepseek-v4.1-flash` | 12.2s | 13.2s | 3,476 | ✅ |
| `deepseek-v4-pro:0813` | **8.4s** | **10.2s** | 1,471 | ✅ |
| `glm-5.3-flash` | 49.6s | 44.8s | 13,078 | ✅ |

A longer variant of this prompt pushed `glm-5.3` to 125.4s / 19,285 tokens / invalid JSON —
close enough to the 180s wall to fail intermittently. **Reasoning-index rank mis-predicts
this node**: glm-5.3 leads v4-pro on the index (51.5 vs 49.6) and is still the worst choice.


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

### D.6 — Challenger-vs-incumbent and probing

The incumbent for each var is its `picks.yaml` entry. The incumbent **keeps its
seat** unless one of these holds:

1. **Disqualified** — listed in `rejections.yaml` for this var, or violates a
   Section B constraint (e.g. multimodal on the resume hot path, output-cap risk
   on RESUME_REASONING, retired from the provider catalogue).
2. **Beaten on a probe** — a challenger passes `probe_node.py` on the var's
   fixture where the incumbent fails, or the probe shows a material quality gain
   with no regression.

Consider a challenger only when: (a) a newer same-family model appears on an
active provider, (b) the leaderboard shows a material gain for the node's demand,
or (c) the incumbent is disqualified. Do not shortlist 2–3 candidates per var by
default — one credible challenger at a time.

**Probe before adopting:**

```bash
python3 probe_node.py --model <value> --fixture <scan|score|resume_reasoning>
```

must return `verdict: PASS` (`done_reason=stop` + output parses to the expected
shape). For RESUME_REASONING the fixture is the full 8-role array screen
(`--roles 8`); a `--roles 3` smoke run is acceptable for triage but not for
adoption. A `done_reason=length` is an automatic FAIL for that node — record it
in `rejections.yaml`, do not retry the same model.

---

## Section E — Output File Format

The four `.env.*` files are **generated, not hand-written**. `picks.yaml` is the
committed source of truth (one entry per var per profile: model, backend, why,
delta, runner_up, est, verified, probe); `render_env.py` renders the files from
it in the format specified below. A tuner run edits `picks.yaml`
(challenger-vs-incumbent, Section D.6), then re-renders — never hand-edit the
`.env.*` files. They remain gitignored working-tree outputs.

**Do NOT read the existing env files to rewrite them.** They are ~33 KB (~9,244 tokens)
of mostly comment text, and the comments are regenerated — not preserved. Get just the
assignments with:

```bash
scripts/tuner_tools.py values <profile>     # profile ∈ quality|local-quality|local-good-enough|recommended
```

To confirm your intended selections are already in place (the common no-op case), diff
them without reading the files:

```bash
scripts/tuner_tools.py values recommended > /tmp/want.json   # after editing to taste
scripts/tuner_tools.py diff recommended /tmp/want.json       # prints only CHANGED vars
```

Author the picks in `picks.yaml`; use `values`/`diff` only to detect
drift cheaply on subsequent runs. A pick whose `verified` field starts with
`RESCREEN` renders as a `# FIXME:` comment instead of a live assignment — the file
stays syntactically valid but the var falls back to the Config.kt default until
the next run re-screens it. See `README-TOKENS.md`.


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

After rendering the four files, print the summary table **generated from
`picks.yaml`** — never hand-maintain it (a static table drifts from the picks;
see the stale-table bug fixed 2026-09-26):

```bash
python3 render_env.py --table
```

Hot-path = SCAN→SCRAPE→SCORE→RESUME_REASONING→SKILLS→COVER_LETTER→DRAFT_REPLY
(one job reaching the tailoring subgraph). The last row is the sum of all node
estimates on that path (the worst-case hot path). RESUME_GEN_MODEL and
PROFILE_GEN_MODEL are no longer pipeline vars (resume HTML and candidate profile
are now produced deterministically, no LLM).

**RESUME_REASONING_MODEL and SKILLS_MODEL must be DENSE (not `*-A3B-*` MoE), non-multimodal,
≥27B (for the JSON-array schema), and `/no_think`-clean** in every local profile — they write the
resume content and depend on single-token quality (see the dense-vs-MoE rule + the three constraints
in the memory budget section). MoE is fine for the other (extraction/JSON) nodes. Re-estimate local
timings after any swap — measured: Qwen3.6-27B-4bit ~13 tok/s (full TAILOR ~10 min/job), vs ~45–70
for a 3B-active MoE (and any multimodal `gemma-4-*` hangs/times out on real long prompts).
