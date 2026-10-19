#!/bin/sh
# Container entrypoint: run the one-shot JVM (--once) on a coarse tick, then sleep. The JVM
# self-gates on the persisted last-run, so most ticks are cheap no-ops and the JVM only exists for
# the minutes it actually polls employer feeds. Steady state is this sleeping shell (~few MB).
HEARTBEAT_FILE="${HEARTBEAT_FILE:-/tmp/watchlist-heartbeat}"
CHECK_INTERVAL_S="${WATCHLIST_CHECK_INTERVAL_S:-3600}"
MIN_SLEEP_S="${WATCHLIST_MIN_SLEEP_S:-30}"
WATCHLIST_BIN="${WATCHLIST_BIN:-/app/bin/job-fit-apply-ai-watchlist}"   # overridable for the entrypoint test
# --once writes the epoch second the next sweep is due here (WATCHLIST_RUN_AT times, or the interval).
export WATCHLIST_NEXT_DUE_FILE="${WATCHLIST_NEXT_DUE_FILE:-/tmp/watchlist-next-due}"

echo "[watchlist] shell loop starting — waking at the next due sweep, at least every ${CHECK_INTERVAL_S}s; JVM self-gates on last-run"
# A full sweep takes ~10–15 min (Workday detail fetches), longer than the healthcheck's start
# period, so mark the loop alive before the first sweep instead of only after it.
touch "$HEARTBEAT_FILE"
while true; do
  "$WATCHLIST_BIN" --once || echo "[watchlist] --once exited non-zero (continuing)"
  touch "$HEARTBEAT_FILE"                     # liveness for the healthcheck (this loop is alive)
  # Sleep until the next sweep is due, so a 05:30 sweep starts at 05:30 rather than at the next
  # hourly tick; never longer than CHECK_INTERVAL_S (the heartbeat must stay fresh) and never
  # shorter than MIN_SLEEP_S (a stale or bad value must not spin the loop).
  wait_s="$CHECK_INTERVAL_S"
  due="$(cat "$WATCHLIST_NEXT_DUE_FILE" 2>/dev/null)"
  case "$due" in
    ''|*[!0-9]*) ;;
    *)
      wait_s=$((due - $(date +%s)))
      [ "$wait_s" -lt "$MIN_SLEEP_S" ] && wait_s="$MIN_SLEEP_S"
      [ "$wait_s" -gt "$CHECK_INTERVAL_S" ] && wait_s="$CHECK_INTERVAL_S"
      ;;
  esac
  sleep "$wait_s"
done
