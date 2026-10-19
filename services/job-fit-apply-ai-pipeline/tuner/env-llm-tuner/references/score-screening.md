# SCORE_MODEL / gap_analysis screening — 2026-09-25

## Why this screen exists

`--test-resume` on the `.env.recommended` selections **FAILED**, and the failure was in a
node upstream of the one being tested:

```
[gap_analysis] WARN: LLM call to https://ollama.com/api/chat failed: request timed out
              — keeping base content, continuing
[summary_rewrite]  WARN: gapAnalysis is null — keeping base content, continuing
[bullet_rewrite]   WARN: gapAnalysis is null — keeping base content, continuing
[skills_restructure] WARN: gapAnalysis is null — keeping base content, continuing
[tailor_subgraph] Complete (short-circuited — fell back on: gap_analysis, summary_rewrite,
                  bullet_rewrite, skills_restructure)
```

`gapAnalysis` is a **hard dependency** for SummaryRewriteNode, BulletRewriteNode,
SkillsRestructureNode and AtsValidationNode — each returns early with
`"<node>: gapAnalysis is null"`. So a single slow node silently disables the entire
tailoring subgraph. The RESUME_REASONING model was **never exercised** by that run.

`gap_analysis` runs on `orchestrationClient` → **`SCORE_MODEL`**, temp 0.0, jsonMode true,
**`timeoutSeconds = 180`** (LlmClient.kt). The candidate was `glm-5.3`.

## Screening method

The real node's prompt shape: partition 8 must-have + 3 nice-to-have JD requirements
against a candidate profile, return strict JSON. ~384 input tokens. Each model run twice,
to expose variance. Node budget = 180s.

## Results

| Model | trial 1 | trial 2 | output tokens | valid JSON | verdict |
|---|---|---|---|---|---|
| `glm-5.3` | 44.7s | 51.8s | 8,624 | **FALSE** | ❌ over-thinks, invalid JSON |
| `glm-5.2` | 12.8s | 13.1s | 1,351 | **FALSE** | ❌ invalid JSON, but fast |
| `deepseek-v4.1-flash` | 12.2s | 13.2s | 3,476 | **TRUE** | ✅ fast + valid |
| `deepseek-v4-pro:0813` | **8.4s** | **10.2s** | 1,471 | **TRUE** | ✅ fastest + valid |
| `glm-5.3-flash` | 49.6s | 44.8s | 13,078 | TRUE | ⚠ slow, 13k tokens to answer |

**An earlier isolated run of `glm-5.3` on a longer variant of this prompt produced 19,285
output tokens in 125.4s with invalid JSON** — i.e. glm-5.3 sits close to the 180s wall and
its behaviour varies with prompt length. That is the timing that actually killed the run.

## Conclusion

**`glm-5.3` is the wrong pick for SCORE.** It burns 8.6k–19k output tokens to answer a
question that `deepseek-v4-pro` answers correctly in 1.5k, and it intermittently emits
unparseable JSON. Reasoning-index ranking (where glm-5.3 leads at 51.5 vs v4-pro's 49.6)
**mis-predicts this node** — the same trap the skill already documents for
RESUME_REASONING ("top LMArena rank is NOT sufficient").

**Winner: `deepseek-v4-pro:0813`** — fastest (8.4/10.2s), smallest correct output
(1,471 tok), valid JSON both trials, and it is the model the live config already used.

`deepseek-v4.1-flash` is the runner-up and is 4x cheaper; it also produced valid JSON
(12–13s, 3.5k tok). Prefer it if cost matters more than the ~3s difference.

## Rule added to the skill

Screen any JSON node for **time-to-valid-JSON and variance**, not just leaderboard rank.
Two trials minimum — a single pass would have rated glm-5.3 acceptable (44.7s < 180s).
Watch output-token count as the leading indicator: a model emitting >10k tokens for a
sub-1k-token answer is over-thinking and will eventually hit the wall.
