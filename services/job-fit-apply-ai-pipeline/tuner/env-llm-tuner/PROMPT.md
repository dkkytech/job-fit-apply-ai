Review the pipeline LLM assignments and update the env tuner output files.

## Cloud subscriptions (edit before each run)

```
CLOUD_SUBSCRIPTIONS:
  - ollama_cloud: true          # https://ollama.com/search?c=cloud
  - openai: false
  - anthropic: false
  - google_vertex: false
  - deepseek_direct: false       # api.deepseek.com — pay-per-token
  - minimax_direct: false        # api.minimaxi.chat — pay-per-token
  - groq: false
  - together_ai: false
```

## Local hardware (edit if machine changes)

```
LOCAL_HARDWARE:
  machine: MacBook Pro (M-series Max chip)
  unified_memory: 64 GB
  memory_bandwidth: ~400 GB/s
  cpu_cores: 16
  gpu_cores: 40
  ollama_version: latest
```

---

Read and execute `tuner/env-llm-tuner/ENV_LLM_TUNER_SKILL.md`.
