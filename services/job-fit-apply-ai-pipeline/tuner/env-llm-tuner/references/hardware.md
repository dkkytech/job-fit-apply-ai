# Section B — Hardware Reference

Loaded on demand by Section D.5 (wall-clock estimation) and Section D.4 (candidate
shortlisting). **Not needed for Sections C or E** — that is why it lives here rather than in
`SKILL.md`.

Local models run on **oMLX** (MLX format). MacBook Max 64 GB memory budget (leave ~8 GB for OS):

- Available for model weights: ~56 GB
- Max at MLX 4-bit: ~70B dense (~38 GB) — but prefer MoE for speed (see below)
- Max at MLX 8-bit: ~32B dense (~34 GB)
- MoE models (e.g. `*-A3B-*`) load the full weights but only activate ~3B params per
  token, so they run at small-model speed with large-model breadth — preferred for the
  **extraction/classification/scoring** nodes (SCAN, SCRAPE, SCORE) where
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
     **Also check LOADABILITY**: `mlx-community--Muse-Glimmer-30B-4bit` fails outright on oMLX with
     `VLM load failed` (verified 2026-09-25) — an installed model is not necessarily a usable one.
  2. **Capable enough for strict structured output.** bullet_rewrite expects a JSON *array* of RoleRewrite;
     `Qwen3.5-9B-OptiQ-4bit` (dense text) returned a wrapping *object* → deserialization failure. Sub-~27B
     dense models may be too weak for the schema. Prefer ≥27B dense.
  3. **`/no_think` must fire** or qwen3 models leak chain-of-thought. The check is
     `model.substringAfterLast("--").startsWith("qwen3")` (LlmClient.kt) — prefix-tolerant, so HF-cache ids
     like `mlx-community--Qwen3.6-27B-4bit` work. (gemma is not a thinking model, so this only matters for qwen.)
  **Chosen pick: `mlx-community--Qwen3.6-27B-4bit`** (dense, text engine) — the only installed
  model meeting all three; full TAILOR run ~10 min/job. Cover-letter/draft prose is single-pass and does
  well on `gemma-4-12B-it-qat-4bit` (multimodal but those prompts are short, so the VLM engine is tolerable).

- **CLOUD picks for RESUME_REASONING have a FOURTH constraint the local rule doesn't surface — max OUTPUT
  tokens (historically verified 2026-07-05 via `--test-resume` + isolated Ollama Cloud screening).**
  `bullet_rewrite` is the pipeline's single LARGEST-output call: it returns a JSON *array* of RoleRewrite
  (≈8 roles × ~4–5 rewritten bullets). The skill's estimate is **~25k–34k output tokens**; a realistic
  8-role/40-bullet probe (2026-09-25) measured **15,032** output tokens from deepseek-v4.1-flash and
  10,533 from deepseek-v4-pro. Cloud models silently TRUNCATE if their generation cap is too low, or
  waste the budget thinking.

  **HISTORICAL cap table (2026-07-05) — retained for context, now largely OBSOLETE:**
  | Cloud model         | done_reason | out tokens | result |
  |---------------------|-------------|------------|--------|
  | `deepseek-v4-pro`   | stop        | ~26k       | ✅ full 8-role array, real run: 33 bullets, ATS 74→**86** after refine |
  | `deepseek-v4-flash` | stop        | ~34k       | ✅ full array (1M ctx; fallback) — **RETIRED 2026-09-25** |
  | `glm-5.1`           | **length**  | 32768 cap  | ❌ over-thinks, burns the whole 32k budget → EMPTY content — **RETIRED** |
  | `kimi-k2.6`         | **length**  | 16384 cap  | ❌ hard 16k cap → truncated JSON → node nulls tailoredBullets AND cascades to null ATS |

  **⚠ 2026-09-25 RE-SCREEN: the truncation hazard has DISSOLVED.** All 13 cloud models were
  re-probed on an 8-role array and **every one returned `done_reason=stop` with parseable 8-role
  JSON**. The glm-5.1 over-think bug was retired with the model, and kimi-k2.6's 16k cap no longer
  bites. **Do not inherit the old constraint without re-screening** — it is a per-run check, not a
  permanent fact. Three models emit markdown-FENCED JSON (`minimax-m3`, `mistral-large-3:675b`,
  `kimi-k2.7-code`); they parse fine after stripping a ```` ```json ```` fence, which `LlmClient`
  does NOT currently do.

  A truncated `bullet_rewrite` doesn't just lose bullets — it nulls `tailoredBullets`, which cascades to a
  null ATS score and disables the ATS refinement pass (the whole point of the tailor subgraph). So for the
  CLOUD RESUME_REASONING pick: **verify the model completes this array with `done_reason=stop` (not `length`),
  and prefer a model that doesn't over-think.** Top LMArena rank is NOT sufficient — glm-5.1 (rank 22) and
  kimi-k2.6 (rank 34) both FAILED this node historically despite outranking deepseek-v4-pro (rank 38).
  NOTE: `LlmClient.callOllama` sets no `num_predict`, so these caps are the models'/Ollama-Cloud defaults,
  not ours.

## Measured oMLX token-generation speeds

**Prefer MEASURED values over the table below** — run the probe in Section D.5 and record what you
observe. The 2026-09-25 measurements came in materially below this table's estimates.

| Model class                  | 4-bit      | 8-bit |
|------------------------------|------------|-------|
| 7–9B dense                   | 60–85      | 38–55 |
| 12–14B dense                 | 40–55      | 24–34 |
| 27–32B dense                 | 16–24      | 9–15  |
| 30–35B MoE (~3B active)      | 45–70      | —     |

**2026-09-25 live measurements (same 4-role/6-role array probes, M-series Max 64 GB):**

| Model                        | measured tok/s | 8-role worst case |
|------------------------------|----------------|-------------------|
| `Qwen3.6-35B-A3B-OptiQ-4bit` (MoE) | **34.3** | not probed |
| `Qwen3.5-9B-OptiQ-4bit`      | **25.6**       | too weak for schema |
| `mlx-community--Qwen3.6-27B-4bit` | **9.7–10.4** | ✅ 830s, valid 8-role JSON |
| `mlx-community--Qwen3.8-27B-4bit` | **7.9–10.1** | ❌ >900s, non-JSON |
| `DeepSeek-R1-Distill-Qwen-32B-4bit` | **7.1**  | not probed |
| `mlx-community--Muse-Glimmer-30B-4bit` | **FAILS TO LOAD** | n/a |

**Calibration note:** the 27–32B dense row above predicts 16–24 tok/s; measured is **9.7–10.4**.
Treat the table as an upper bound and always prefer a fresh measurement taken on the machine in use.
MLX is generally ~15–30% faster than Ollama/GGUF on Apple Silicon for the same model.
