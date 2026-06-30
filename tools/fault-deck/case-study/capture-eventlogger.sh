#!/usr/bin/env bash
#
# capture-eventlogger.sh <card>
#
# EventLogger counterpart to ./capture.sh, used to produce the *raw-arm*
# captures for the case study. Where capture.sh screenshots the StreamProbe
# overlay, this launches the ONE card you name with Media3's EventLogger
# attached (sp_fault_eventlogger on), clears logcat, lets playback settle, then
# writes the EventLogger output to raw/<card>-eventlogger.txt.
#
# EventLogger is the strongest in-player raw baseline a competent ExoPlayer dev
# actually uses: its logcat output includes every track-selection change with the
# selected format, the full Tracks dump (each rung with [X]/[ ] + supported=),
# the bandwidth estimate, and per-segment loadCompleted timing/size. This script
# captures that for the raw arm so the comparison is not a strawman.
#
# Like capture.sh, it does NOT import the guessing-game scripts; the card ->
# (nginx profile, app mode) map is duplicated here on purpose to keep that
# boundary. The overlay is left OFF (we want logcat, not a screenshot).
#
# Usage:
#   ./capture-eventlogger.sh manifest_cap
#   ./capture-eventlogger.sh network_throttle
#   ./capture-eventlogger.sh constrained
#   ./capture-eventlogger.sh bw_misconfig
#
# Cards (same set as capture.sh / run-card.sh):
#   manifest_cap      cap480   + normal         (1080p missing from manifest)
#   constrained       full     + constrained    (player hard-capped to 480p)
#   bw_misconfig      full     + bw_misconfig   (ABR trusts only 10% of bandwidth)
#   network_throttle  throttle + normal         (link too slow for top rung)
#   cdn_miss          cdnmiss  + normal         (fake X-Cache: MISS headers)
#   decode_fail       full     + normal         (ABR climbs to hard 1080p HEVC)
#
# Configurable via env (same knobs as capture.sh):
#   SP_HOST    host the device reaches nginx on (default localhost + adb reverse)
#   SP_PORT    nginx port (default 8080)
#   SP_SERIAL  adb device serial (default: the only connected device)
#   ADB        path to adb (default: from PATH, else Android SDK default)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RIG_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RAW_DIR="$SCRIPT_DIR/raw"
APP_ID="com.streamprobe.android"

# ---- parse args -------------------------------------------------------------
CARDS="manifest_cap constrained bw_misconfig network_throttle cdn_miss decode_fail"
CARD="${1:-}"
if [[ -z "$CARD" ]]; then
  echo "Usage: $0 <card>" >&2
  echo "Cards: $CARDS" >&2
  exit 2
fi
if [[ " $CARDS " != *" $CARD "* ]]; then
  echo "ERROR: '$CARD' is not a known card. Cards: $CARDS" >&2
  exit 2
fi

# ---- env / adb --------------------------------------------------------------
SP_HOST="${SP_HOST:-localhost}"
SP_PORT="${SP_PORT:-8080}"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
ADB_ARGS=()
[[ -n "${SP_SERIAL:-}" ]] && ADB_ARGS=(-s "$SP_SERIAL")

# Forward device localhost:PORT -> host PORT over adb (see capture.sh rationale).
if [[ "$SP_HOST" == "localhost" || "$SP_HOST" == "127.0.0.1" ]]; then
  if ! "$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} reverse tcp:"$SP_PORT" tcp:"$SP_PORT" >/dev/null 2>&1; then
    echo "WARNING: 'adb reverse' failed. The device may not reach nginx on :${SP_PORT}." >&2
  fi
fi

# ---- bring nginx up (idempotent, non-fatal) ---------------------------------
if command -v docker >/dev/null 2>&1; then
  echo "Ensuring nginx is up..."
  if ! docker compose -f "$RIG_DIR/nginx/docker-compose.yml" up -d >/dev/null 2>&1; then
    echo "WARNING: could not start nginx via docker. Make sure it is serving on :${SP_PORT}." >&2
  fi
else
  echo "WARNING: docker not found. Start the nginx server yourself before capturing." >&2
fi

# ---- map card -> (nginx profile, app mode) ----------------------------------
case "$CARD" in
  manifest_cap)     PROFILE="cap480";   MODE="normal" ;;
  constrained)      PROFILE="full";     MODE="constrained" ;;
  bw_misconfig)     PROFILE="full";     MODE="bw_misconfig" ;;
  network_throttle) PROFILE="throttle"; MODE="normal" ;;
  cdn_miss)         PROFILE="cdnmiss";  MODE="normal" ;;
  decode_fail)      PROFILE="full";     MODE="normal" ;;
esac

URL="http://${SP_HOST}:${SP_PORT}/${PROFILE}/master.m3u8"

# ---- launch the app fresh with EventLogger ON, overlay OFF -------------------
echo "Launching card '$CARD' (profile=$PROFILE mode=$MODE) with EventLogger ON..."
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
# Clear logcat before launch so the dump contains only this run.
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat -c >/dev/null 2>&1 || true
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am start -n "${APP_ID}/.MainActivity" \
  -e sp_fault_url "$URL" \
  -e sp_fault_mode "$MODE" \
  -e sp_fault_overlay "off" \
  -e sp_fault_eventlogger "on" \
  -e sp_fault_title "Fault Deck" >/dev/null

# ---- let playback settle, then dump EventLogger -----------------------------
mkdir -p "$RAW_DIR"
OUT="$RAW_DIR/${CARD}-eventlogger.txt"
echo
echo "Card is playing with EventLogger ON (overlay hidden)."
echo "  1. Let playback run long enough for the ladder + several segments to log."
echo "  2. Press Enter here to dump 'adb logcat -s EventLogger', or Ctrl-C to abort."
read -r _
# -d dumps the buffered log and exits (no streaming).
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} logcat -d -s EventLogger > "$OUT"

if [[ -s "$OUT" ]]; then
  echo "Saved $OUT"
else
  echo "ERROR: logcat dump produced an empty file at $OUT" >&2
  echo "       (Did EventLogger attach? Check sp_fault_eventlogger wiring and that this is a DEBUG build.)" >&2
  rm -f "$OUT"
  exit 1
fi
