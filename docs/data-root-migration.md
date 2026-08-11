# JFAA persistent data-root migration

`JFAA_DATA_ROOT` defines the host root for JFAA's six durable Docker bind mounts:

```text
${JFAA_DATA_ROOT}/bridge
${JFAA_DATA_ROOT}/jsearch-state
${JFAA_DATA_ROOT}/notifier-state
${JFAA_DATA_ROOT}/poller-secrets
${JFAA_DATA_ROOT}/pipeline-output
${JFAA_DATA_ROOT}/pipeline-state
```

The Compose fallback for a fresh deployment is `${HOME}/.local/share/jfaa`.

> **Upgrade required:** changing a Compose bind source does not move existing data. Before recreating
> affected services, migrate legacy `~/.openclaw/jd-*` state and the Pipeline's repo-local `output/`
> and `state/` directories. Otherwise Compose creates empty directories at the new locations.

## Recommended roots

| Host | `JFAA_DATA_ROOT` |
|---|---|
| macOS | `/Users/<user>/Library/Application Support/JFAA` |
| Ubuntu service host | `/var/lib/jfaa` |
| Ubuntu user-managed development | `/home/<user>/.local/share/jfaa` |
| GitHub Actions E2E | Not used; the E2E overlay binds `./.e2e/` instead |

## Mount precedence and sensitivity

Each service-specific override wins over `JFAA_DATA_ROOT`, which wins over the portable fallback:

| Child | Override | Container use | Sensitivity |
|---|---|---|---|
| `bridge` | `JD_BRIDGE_STORE_HOST` | Bridge `/data` RW | Queue database and artifacts |
| `jsearch-state` | `JD_JSEARCH_STATE_HOST` | JSearch `/state` RW | API scheduling state |
| `notifier-state` | `JD_NOTIFIER_STATE_HOST` | Notifier `/state` RW | Notification cursor |
| `poller-secrets` | `JD_POLLER_SECRETS_HOST` | Poller `/secrets` RW | Gmail OAuth credentials/tokens |
| `pipeline-output` | `JD_PIPELINE_OUTPUT_HOST` | Processor `/app/output` RW; Markserv `/data` RO | Resumes, cover letters, and reports |
| `pipeline-state` | `JD_PIPELINE_STATE_HOST` | Processor `/app/state` RW | Steel/browser authenticated storage state |

`pipeline-state` must never be mounted into Markserv or served as an artifact. Keep the root,
`poller-secrets`, and `pipeline-state` private on the host.

## Safe migration

The following example migrates a macOS deployment. Substitute the paths for another host.

### 1. Record the baseline

From the repository root, record which services are running and resolve the old mount sources before
changing `.env` or Compose:

```bash
docker compose ps --services --status running
docker inspect jobfit-processor jobfit-markserv \
  --format '{{.Name}}{{range .Mounts}} {{.Source}}:{{.Destination}}:rw={{.RW}}{{end}}'
```

Recreate only services that were running before the migration. Do not incidentally enable a disabled
Poller or JSearch service.

### 2. Choose and configure the root

Set the gitignored repository-root `.env`:

```env
JFAA_DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
```

Use `JD_PIPELINE_OUTPUT_HOST` or `JD_PIPELINE_STATE_HOST` only when that service needs an exceptional
location. Quoted Compose mount expressions support macOS paths containing spaces.

### 3. Create private target directories

```bash
DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
mkdir -p \
  "$DATA_ROOT/bridge" \
  "$DATA_ROOT/jsearch-state" \
  "$DATA_ROOT/notifier-state" \
  "$DATA_ROOT/poller-secrets" \
  "$DATA_ROOT/pipeline-output" \
  "$DATA_ROOT/pipeline-state"
chmod 700 "$DATA_ROOT" "$DATA_ROOT/poller-secrets" "$DATA_ROOT/pipeline-state"
```

### 4. Quiesce writers

For a deployment whose first four mounts are already under `JFAA_DATA_ROOT`, stop only Processor for
this Pipeline cutover. Markserv is read-only and may continue serving the stable old output tree until
its mount is recreated:

```bash
docker compose stop processor
```

For a full migration from all legacy locations, stop every writer. Stop Processor first so it cannot
submit work while Bridge is unavailable:

```bash
docker compose stop processor poller jsearch notifier bridge
```

### 5. Exact-mirror authoritative data

`rsync -a` alone leaves destination-only files behind. For queue, cursor, OAuth, artifact, and browser
state, those stale files can become active after cutover. Use the following helpers to validate both
paths, preview deletions, apply an exact mirror, and verify it:

```bash
mirror_validate() {
  src="${1%/}"; dst="${2%/}"
  [ -n "$src" ] && [ -n "$dst" ] && [ -d "$src" ] && [ -d "$dst" ] \
    || { echo "missing mirror pair: '$src' -> '$dst'" >&2; return 1; }
  src_real="$(cd "$src" && pwd -P)" || return 1
  dst_real="$(cd "$dst" && pwd -P)" || return 1
  [ "$src_real" != / ] && [ "$dst_real" != / ] && [ "$src_real" != "$dst_real" ] \
    || { echo "unsafe mirror pair: '$src_real' -> '$dst_real'" >&2; return 1; }
}
mirror_preview() {
  mirror_validate "$1" "$2" && rsync -ani --checksum --delete "${1%/}/" "${2%/}/"
}
mirror_apply() {
  mirror_validate "$1" "$2" && rsync -a --checksum --delete "${1%/}/" "${2%/}/"
}

REPO_ROOT="$(git rev-parse --show-toplevel)"
DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
LEGACY_BRIDGE="$HOME/.openclaw/jd-bridge"
LEGACY_JSEARCH="$HOME/.openclaw/jd-jsearch-state"
LEGACY_NOTIFIER="$HOME/.openclaw/jd-notifier-state"
LEGACY_POLLER="$HOME/.openclaw/jd-poller-secrets"
LEGACY_OUTPUT="$REPO_ROOT/services/job-fit-apply-ai-pipeline/output"
LEGACY_PIPELINE_STATE="$REPO_ROOT/services/job-fit-apply-ai-pipeline/state"
```

Choose exactly one workflow:

- **Pipeline-only cutover** when the first four root children are already authoritative:

  ```bash
  mirror_preview "$LEGACY_OUTPUT"         "$DATA_ROOT/pipeline-output"
  mirror_preview "$LEGACY_PIPELINE_STATE" "$DATA_ROOT/pipeline-state"
  ```

- **Full legacy cutover** when all six legacy sources are authoritative and all writers were stopped:

  ```bash
  mirror_preview "$LEGACY_BRIDGE"         "$DATA_ROOT/bridge"
  mirror_preview "$LEGACY_JSEARCH"        "$DATA_ROOT/jsearch-state"
  mirror_preview "$LEGACY_NOTIFIER"       "$DATA_ROOT/notifier-state"
  mirror_preview "$LEGACY_POLLER"         "$DATA_ROOT/poller-secrets"
  mirror_preview "$LEGACY_OUTPUT"         "$DATA_ROOT/pipeline-output"
  mirror_preview "$LEGACY_PIPELINE_STATE" "$DATA_ROOT/pipeline-state"
  ```

Review every preview line, especially every `*deleting` entry. Stop if a deletion is unexpected or a
source is not authoritative. After review, apply the matching workflow by replacing `mirror_preview`
with `mirror_apply`. Then rerun the original `mirror_preview` commands while writers remain stopped;
**all six previews for a full migration, or both previews for a Pipeline-only migration, must produce
no output**.

Finally, restore restrictive modes that source metadata may have changed:

```bash
chmod 700 "$DATA_ROOT" "$DATA_ROOT/poller-secrets" "$DATA_ROOT/pipeline-state"
```

### 6. Validate resolved mounts before recreation

```bash
make compose-data-root-test
make data-root-check
docker compose config
./scripts/doctor.sh
```

Confirm:

- Processor `/app/output` and Markserv `/data` use the exact same `pipeline-output` source;
- Processor output is RW and Markserv output is RO;
- Processor `/app/state` alone uses `pipeline-state`;
- no production source remains under the repository checkout.

`make data-root-check` is the automated version of the failure this guide exists to prevent: it
exits non-zero when a legacy tree still holds data and its root-derived counterpart is empty.
`make up` and `make restart` run it first, so an unmigrated host refuses to start instead of coming
up with empty artifact and session directories. Set `JFAA_SKIP_DATA_ROOT_CHECK=1` only when you
genuinely intend to start fresh. `./scripts/doctor.sh` reports the same condition as a failed check.

> Deploying a single service directly (`docker compose build <svc> && docker compose up -d <svc>`)
> bypasses the Make targets and therefore the guard. Run `make data-root-check` first when you
> deploy that way on a host that has not yet migrated.

### 7. Recreate only affected services

For the Pipeline-only cutover:

```bash
docker compose up -d --force-recreate markserv processor
docker compose ps
```

For a full six-mount migration, recreate the services that were running in the recorded baseline,
then restore Processor last so its dependencies are healthy.

### 8. Verify the cutover

```bash
docker inspect jobfit-processor jobfit-markserv \
  --format '{{.Name}}{{range .Mounts}} {{.Source}}:{{.Destination}}:rw={{.RW}}{{end}}'
curl -fsS http://127.0.0.1:${MARKSERV_PORT:-8081}/ >/dev/null
./scripts/doctor.sh
```

Also verify all of the following before deleting anything:

1. An existing Markserv artifact URL still resolves.
2. A normal Processor cycle creates a new directory under `pipeline-output` and Markserv serves it.
3. Steel/browser authenticated state survives Processor recreation and is not exposed through Markserv.
4. Unrelated services retained their pre-migration running/stopped status.

Keep the old source directories as rollback copies through at least one normal processing, browser
session, and notification cycle.

## Rollback

Before either rollback path, define `mirror_validate`, `mirror_preview`, and `mirror_apply` from Safe
migration step 5 in the current shell. Those guards reject empty, root, or missing directory pairs;
do not replace them with unvalidated `rsync --delete` commands.

### Pipeline-only rollback

1. Stop Processor so output and browser/session state are stable:

   ```bash
   docker compose stop processor
   ```

2. Preserve artifacts and state created after cutover as exact mirrors of the authoritative root
   sources. Preview first, inspect every `*deleting` entry, apply, then require empty previews:

   ```bash
   REPO_ROOT="$(git rev-parse --show-toplevel)"
   DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
   LEGACY_OUTPUT="$REPO_ROOT/services/job-fit-apply-ai-pipeline/output"
   LEGACY_STATE="$REPO_ROOT/services/job-fit-apply-ai-pipeline/state"

   mirror_preview "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_preview "$DATA_ROOT/pipeline-state"  "$LEGACY_STATE"
   # Stop here and investigate any unexpected deletion before applying.

   mirror_apply "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_apply "$DATA_ROOT/pipeline-state"  "$LEGACY_STATE"

   # Verification: both commands must now produce no output.
   mirror_preview "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_preview "$DATA_ROOT/pipeline-state"  "$LEGACY_STATE"
   chmod 700 "$LEGACY_STATE"
   ```

3. Set temporary compatibility overrides in the root `.env`; use absolute paths because Compose does
   not expand `~` in variable values:

   ```env
   JD_PIPELINE_OUTPUT_HOST=/absolute/path/to/services/job-fit-apply-ai-pipeline/output
   JD_PIPELINE_STATE_HOST=/absolute/path/to/services/job-fit-apply-ai-pipeline/state
   ```

4. Run `docker compose config`, verify the old sources and modes, then recreate Markserv and Processor.
5. Verify Processor health, Markserv health, old and new artifact access, and browser session persistence.

### Full six-mount rollback

A full rollback must preserve state created after cutover for **all six** mounts. Restoring old paths
without reverse-syncing can lose queued Bridge jobs, refreshed Gmail OAuth material, JSearch schedule
state, Notifier cursor progress, new artifacts, or authenticated browser state.

1. Record the current running-service baseline, then stop every writer. Processor stops first so it
   cannot submit work while Bridge is unavailable:

   ```bash
   docker compose ps --services --status running
   docker compose stop processor poller jsearch notifier bridge
   ```

2. Preview all six reverse mirrors while writers remain stopped. Inspect every `*deleting` entry and
   stop if any deletion is unexpected:

   ```bash
   REPO_ROOT="$(git rev-parse --show-toplevel)"
   DATA_ROOT="/Users/<user>/Library/Application Support/JFAA"
   LEGACY_BRIDGE="$HOME/.openclaw/jd-bridge"
   LEGACY_JSEARCH="$HOME/.openclaw/jd-jsearch-state"
   LEGACY_NOTIFIER="$HOME/.openclaw/jd-notifier-state"
   LEGACY_POLLER="$HOME/.openclaw/jd-poller-secrets"
   LEGACY_OUTPUT="$REPO_ROOT/services/job-fit-apply-ai-pipeline/output"
   LEGACY_PIPELINE_STATE="$REPO_ROOT/services/job-fit-apply-ai-pipeline/state"

   mirror_preview "$DATA_ROOT/bridge"          "$LEGACY_BRIDGE"
   mirror_preview "$DATA_ROOT/jsearch-state"   "$LEGACY_JSEARCH"
   mirror_preview "$DATA_ROOT/notifier-state"  "$LEGACY_NOTIFIER"
   mirror_preview "$DATA_ROOT/poller-secrets"  "$LEGACY_POLLER"
   mirror_preview "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_preview "$DATA_ROOT/pipeline-state"  "$LEGACY_PIPELINE_STATE"
   ```

3. After approving the preview, exact-mirror all six authoritative root children into the rollback
   copies, then rerun all six previews. Every verification command must produce no output:

   ```bash
   mirror_apply "$DATA_ROOT/bridge"          "$LEGACY_BRIDGE"
   mirror_apply "$DATA_ROOT/jsearch-state"   "$LEGACY_JSEARCH"
   mirror_apply "$DATA_ROOT/notifier-state"  "$LEGACY_NOTIFIER"
   mirror_apply "$DATA_ROOT/poller-secrets"  "$LEGACY_POLLER"
   mirror_apply "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_apply "$DATA_ROOT/pipeline-state"  "$LEGACY_PIPELINE_STATE"

   mirror_preview "$DATA_ROOT/bridge"          "$LEGACY_BRIDGE"
   mirror_preview "$DATA_ROOT/jsearch-state"   "$LEGACY_JSEARCH"
   mirror_preview "$DATA_ROOT/notifier-state"  "$LEGACY_NOTIFIER"
   mirror_preview "$DATA_ROOT/poller-secrets"  "$LEGACY_POLLER"
   mirror_preview "$DATA_ROOT/pipeline-output" "$LEGACY_OUTPUT"
   mirror_preview "$DATA_ROOT/pipeline-state"  "$LEGACY_PIPELINE_STATE"

   chmod 700 "$LEGACY_POLLER" "$LEGACY_PIPELINE_STATE"
   ```

4. Point all compatibility overrides at absolute retained paths in the root `.env`:

   ```env
   JD_BRIDGE_STORE_HOST=/absolute/path/to/.openclaw/jd-bridge
   JD_JSEARCH_STATE_HOST=/absolute/path/to/.openclaw/jd-jsearch-state
   JD_NOTIFIER_STATE_HOST=/absolute/path/to/.openclaw/jd-notifier-state
   JD_POLLER_SECRETS_HOST=/absolute/path/to/.openclaw/jd-poller-secrets
   JD_PIPELINE_OUTPUT_HOST=/absolute/path/to/services/job-fit-apply-ai-pipeline/output
   JD_PIPELINE_STATE_HOST=/absolute/path/to/services/job-fit-apply-ai-pipeline/state
   ```

5. Run `docker compose config` and verify all six old sources, exact container targets, and RW/RO modes.
6. Recreate only services from the recorded baseline: Bridge first, then its stateful consumers and
   Markserv, and Processor last after dependencies are healthy. Do not enable a service that was stopped.
7. Verify Bridge queue continuity, Gmail authentication, JSearch last-run state, Notifier cursor/no
   replay, Markserv artifacts, Processor health/new artifact creation, and browser session persistence.

Keep the root-derived directories untouched until rollback has survived a normal operational cycle;
they are the rollback copy for returning to the root-based deployment.

## E2E isolation

`docker-compose.e2e.yml` replaces the base mounts by container target:

```text
./.e2e/output -> Processor /app/output RW and Markserv /data RO
./.e2e/state  -> Processor /app/state RW
```

It does not use `JFAA_DATA_ROOT` or either Pipeline override. Its normal service set excludes Poller
and JSearch; if the intentionally disabled `e2e-disabled` profile is enabled, their mounts still
point only to `./.e2e/`, JSearch has no API key, and notifier credentials remain test-only.

Run `make compose-data-root-test` to assert the production precedence/modes and prove sentinel
production root/override paths do not leak into the merged E2E configuration.
