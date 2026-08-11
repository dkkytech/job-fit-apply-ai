# Top-level orchestration for the Dockerized stack.
#
# Docker Compose is the source of truth — every service (db, bridge, frontend,
# markserv, poller, jsearch, notifier, processor) is a compose service. Host-side
# prerequisites the processor needs: the CDP Chrome (launch-chrome-cdp.sh + its
# launchd watchdog) and the local LLM servers (oMLX :11436, Ollama :11434).
# Tailnet exposure is via Tailscale Serve on the host — see docs/tailscale-serve.md.

.DEFAULT_GOAL := help
SHELL := /bin/bash

.PHONY: help up down restart status serve doctor logs replay check-env-file data-root-check compose-data-root-test e2e e2e-up e2e-run e2e-down e2e-logs e2e-multi e2e-src-up e2e-src-check e2e-src-down e2e-smoke processor-test

# ── Instance selection (multi-instance — see docs/multi-instance.md) ─────────
# `make up INSTANCE=test` drives a second stack from .env.test. INSTANCE=prod (the
# default) keeps today's invocation byte-identical: a plain `docker compose`, which
# reads the repo-root .env on its own. For named instances `--env-file` REPLACES
# .env entirely, so .env.<name> must restate everything the instance needs.
INSTANCE ?= prod
ifeq ($(INSTANCE),prod)
ENV_FILE := .env
COMPOSE  := docker compose
else
ENV_FILE := .env.$(INSTANCE)
COMPOSE  := docker compose --env-file $(ENV_FILE)
endif

# Internal guard: named instances need their env file to exist before compose runs.
check-env-file:
ifneq ($(INSTANCE),prod)
	@test -f "$(ENV_FILE)" || { \
	  echo "ERROR: $(ENV_FILE) not found for INSTANCE=$(INSTANCE)."; \
	  echo "       cp .env.test.example $(ENV_FILE)   # then edit — see docs/multi-instance.md"; \
	  exit 1; }
endif

# ── E2E suite (services/job-fit-apply-ai-e2e) ────────────────────────────────
# Isolated compose project: own container names, host ports (bridge 18765,
# markserv 18082, postgres 15433) and ./.e2e state — safe to run while the
# production stack is up. REAL_LLM=1 skips the fake LLM and lets the processor
# hit the real local models on host :11436 (Tier B exact-value tests skip).
#
# The project name carries a short hash of this checkout's path so two worktrees
# don't share containers or a Postgres volume — `e2e-down` runs `down -v`, and a
# fixed name would let one worktree delete the other's in-flight run.
E2E_PROJECT     := jobfit-e2e-$(shell pwd | cksum | cut -d' ' -f1)
E2E_CONTAINERS  := $(addprefix $(E2E_PROJECT)-,db bridge markserv processor notifier)
E2E_SERVICES    := db bridge markserv processor notifier
REAL_LLM        ?= 0
# 21436 by default so the fake LLM never shadows the production oMLX on 11436 (see
# docker-compose.e2e.yml). Under REAL_LLM=1 the container must reach the real oMLX,
# so it becomes 11436. Both `e2e-up` (compose interpolation) and `e2e-run` (the test
# JVM) must agree, so it is exported once here.
E2E_FAKE_LLM_PORT ?= $(if $(filter 1,$(REAL_LLM)),11436,21436)
E2E_SINK_PORT     ?= 18099
# COMPOSE_PROFILES pinned empty: the repo-root .env sets COMPOSE_PROFILES=intake for
# prod, and compose reads that .env regardless of project name — without the pin an
# e2e `down`/`logs` (no explicit service list) would also consider poller/jsearch.
E2E_ENV         := COMPOSE_PROJECT_NAME=$(E2E_PROJECT) \
                   COMPOSE_PROFILES= \
                   E2E_FAKE_LLM_PORT=$(E2E_FAKE_LLM_PORT) \
                   E2E_SINK_PORT=$(E2E_SINK_PORT)
COMPOSE_E2E     := $(E2E_ENV) docker compose -f docker-compose.yml -f docker-compose.e2e.yml

# ── Source slice for the multi-instance scenarios (#56 sc. 9/10, gated on #51) ──
# A second, prod-shaped slice (db/bridge/markserv/notifier — deliberately NO processor
# and no intake) with its own project name, ports, and ./.e2e-src state. The suite's
# MultiInstanceE2ETest runs only when E2E_SOURCE_* are exported (E2E_MULTI=1).
E2E_SRC_PROJECT := jobfit-e2e-src-$(shell pwd | cksum | cut -d' ' -f1)
E2E_SRC_SERVICES := db bridge markserv notifier
E2E_SRC_BRIDGE_PORT ?= 19765
E2E_SRC_PG_PORT     ?= 15434
E2E_SRC_MARKSERV_PORT ?= 19082
E2E_SRC_ENV     := COMPOSE_PROJECT_NAME=$(E2E_SRC_PROJECT) \
                   COMPOSE_PROFILES= \
                   E2E_STATE_DIR=./.e2e-src \
                   E2E_POSTGRES_PORT=$(E2E_SRC_PG_PORT) \
                   E2E_BRIDGE_PORT=$(E2E_SRC_BRIDGE_PORT) \
                   E2E_MARKSERV_PORT=$(E2E_SRC_MARKSERV_PORT) \
                   E2E_SINK_PORT=$(E2E_SINK_PORT)
COMPOSE_E2E_SRC := $(E2E_SRC_ENV) docker compose -f docker-compose.yml -f docker-compose.e2e.yml

help: ## Show available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-9s\033[0m %s\n", $$1, $$2}'

up: check-env-file data-root-check ## Start containers + configure Tailscale Serve (INSTANCE=test for the test stack)
	$(COMPOSE) up -d
	ENV_FILE=$(ENV_FILE) ./scripts/setup-tailscale-serve.sh

down: check-env-file ## Stop & remove containers (named volumes / data are kept)
	$(COMPOSE) down --remove-orphans

restart: check-env-file data-root-check ## Recreate containers from current compose config
	$(COMPOSE) up -d --force-recreate

status: check-env-file ## Show container status + Tailscale Serve config
	$(COMPOSE) ps
	@echo
	@TS=$${TAILSCALE_BIN:-tailscale}; command -v $$TS >/dev/null 2>&1 || TS=/usr/local/bin/tailscale; $$TS serve status || true

serve: check-env-file ## (Re)configure Tailscale Serve only
	ENV_FILE=$(ENV_FILE) ./scripts/setup-tailscale-serve.sh

doctor: check-env-file ## Check prerequisites & health (read-only)
	ENV_FILE=$(ENV_FILE) ./scripts/doctor.sh

replay: ## Replay prod bridge jobs into the test instance: make replay ARGS="--last 1"
	./scripts/replay-jobs.sh $(ARGS)

data-root-check: ## Refuse to start on an unmigrated Pipeline data root (#67)
ifeq ($(INSTANCE),prod)
	@./scripts/check-data-root-migration.sh
else
	@echo "[data-root-check] skipped for INSTANCE=$(INSTANCE) — the legacy-tree migration guard applies to the prod data root only; a fresh instance root is empty by design"
endif

compose-data-root-test: ## Validate production/E2E data-root mount contracts
	python3 ./scripts/test-compose-data-root.py

e2e: ## Full e2e cycle: up + run + down (REAL_LLM=1 for real local models)
	@trap '$(MAKE) e2e-down' INT TERM; \
	  $(MAKE) e2e-up && $(MAKE) e2e-run REAL_LLM=$(REAL_LLM); s=$$?; $(MAKE) e2e-down; exit $$s

e2e-up: ## Build + start the isolated e2e slice with fresh state, wait for health
	./scripts/e2e-ci-prepare.sh --fresh
	$(COMPOSE_E2E) up -d --build $(E2E_SERVICES)
	@echo "[e2e] waiting for containers to report healthy…"
	@for c in $(E2E_CONTAINERS); do \
	  deadline=$$((SECONDS + 240)); \
	  until [ "$$(docker inspect -f '{{.State.Health.Status}}' $$c 2>/dev/null)" = "healthy" ]; do \
	    state=$$(docker inspect -f '{{.State.Status}}' $$c 2>/dev/null || echo missing); \
	    case "$$state" in \
	      exited|dead|missing) \
	        echo "[e2e] $$c is '$$state' — it will never become healthy:"; \
	        docker logs --tail 50 $$c 2>&1 || true; exit 1;; \
	    esac; \
	    [ $$SECONDS -ge $$deadline ] && { echo "[e2e] $$c not healthy after 240s:"; docker logs --tail 50 $$c 2>&1 || true; exit 1; }; \
	    sleep 3; \
	  done; echo "[e2e] $$c healthy"; \
	done

e2e-run: ## Run the suite against the already-running e2e slice (ad-hoc loop)
	cd services/job-fit-apply-ai-e2e && \
	  E2E_REAL_LLM=$(REAL_LLM) \
	  E2E_FAKE_LLM_PORT=$(E2E_FAKE_LLM_PORT) \
	  E2E_SINK_PORT=$(E2E_SINK_PORT) \
	  E2E_TIMEOUT_SECONDS=$${E2E_TIMEOUT_SECONDS:-$(if $(filter 1,$(REAL_LLM)),1800,300)} \
	  $(if $(filter 1,$(E2E_MULTI)),\
	    E2E_SOURCE_BRIDGE_URL=http://127.0.0.1:$(E2E_SRC_BRIDGE_PORT) \
	    E2E_SOURCE_DATABASE_URL=postgresql://jobfit:jobfit@127.0.0.1:$(E2E_SRC_PG_PORT)/jobfit \
	    E2E_SOURCE_STATE_DIR=../../.e2e-src \
	    E2E_SOURCE_PROJECT=$(E2E_SRC_PROJECT) \
	    E2E_PROJECT=$(E2E_PROJECT),) \
	  ./gradlew test $(if $(filter 1,$(REAL_LLM)),-PexcludeTags=tier-b)

e2e-multi: ## Dual-slice e2e: source + test stacks, multi-instance scenarios included
	@trap '$(MAKE) e2e-down e2e-src-down' INT TERM; \
	  $(MAKE) e2e-up && $(MAKE) e2e-src-up && $(MAKE) e2e-run REAL_LLM=0 E2E_MULTI=1; s=$$?; \
	  if [ $$s -eq 0 ]; then $(MAKE) e2e-down && $(MAKE) e2e-src-check; s=$$?; else $(MAKE) e2e-down; fi; \
	  $(MAKE) e2e-src-down; exit $$s

e2e-src-up: ## Build + start the source slice for the multi-instance scenarios
	E2E_STATE_DIR=.e2e-src E2E_MARKSERV_PORT=$(E2E_SRC_MARKSERV_PORT) ./scripts/e2e-ci-prepare.sh --fresh
	$(COMPOSE_E2E_SRC) up -d --wait --wait-timeout 240 $(E2E_SRC_SERVICES)

e2e-src-check: ## Scenario 9 clause: stopping the test slice must leave the source healthy
	@curl -sf http://127.0.0.1:$(E2E_SRC_BRIDGE_PORT)/health >/dev/null \
	  && [ "$$(docker inspect -f '{{.State.Health.Status}}' $(E2E_SRC_PROJECT)-bridge)" = healthy ] \
	  && echo "[e2e-multi] source slice healthy after test-slice teardown" \
	  || { echo "[e2e-multi] source slice UNHEALTHY after test-slice teardown"; exit 1; }

e2e-src-down: ## Stop the source slice and remove its volumes + state
	$(COMPOSE_E2E_SRC) down -v --remove-orphans
	rm -rf .e2e-src

e2e-down: ## Stop the e2e slice and remove its volumes
	$(COMPOSE_E2E) down -v --remove-orphans

e2e-logs: ## Tail the e2e slice's container logs
	$(COMPOSE_E2E) logs -f --tail=100

e2e-smoke: check-env-file ## Legacy full-fat smoke against the REAL stack + real local models
	ENV_FILE=$(ENV_FILE) ./scripts/e2e-smoke.sh

processor-test: check-env-file ## In-container pipeline smoke on a sample JD (LLMs + PDF, no bridge)
	$(COMPOSE) run --rm --no-deps processor --test

logs: check-env-file ## Tail container logs
	$(COMPOSE) logs -f --tail=100
