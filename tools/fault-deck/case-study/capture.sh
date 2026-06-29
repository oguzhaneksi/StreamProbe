#!/usr/bin/env bash
#
# capture.sh <card> [tab-label]
#
# Deterministic counterpart to ../run-card.sh, used to produce the screenshots
# for the case study. Where run-card.sh draws a RANDOM card (correct for the
# blind benchmark game), this launches the ONE card you name, with the overlay
# always ON, then captures the overlay to assets/<card>-<tab-label>.png once you
# have navigated to the relevant tab.
#
# It intentionally does NOT import or modify the guessing-game scripts: the blind
# game and the case-study capture are separate concerns. The card -> (nginx
# profile, app mode) mapping is duplicated here on purpose to keep that boundary.
#
# Usage:
#   ./capture.sh manifest_cap tracks
#   ./capture.sh network_throttle segments
#   ./capture.sh cdn_miss segments
#   ./capture.sh constrained tracks
#   ./capture.sh bw_misconfig tracks
#
# The optional <tab-label> only names the output PNG (e.g. "tracks", "segments");
# it does not drive the UI. The script launches the fault, then pauses so you can
# switch the overlay to the right tab and let playback settle, and screencaps when
# you press Enter.
#
# Cards (same set as run-card.sh):
#   manifest_cap      cap480   + normal         (1080p missing from manifest)
#   constrained       full     + constrained    (player hard-capped to 480p)
#   bw_misconfig      full     + bw_misconfig   (ABR trusts only 10% of bandwidth)
#   network_throttle  throttle + normal         (link too slow for top rung)
#   cdn_miss          cdnmiss  + normal         (fake X-Cache: MISS headers)
#   decode_fail       full     + normal         (ABR climbs to hard 1080p HEVC)
#
# Configurable via env (same knobs as run-card.sh):
#   SP_HOST    host the device reaches nginx on (default localhost + adb reverse)
#   SP_PORT    nginx port (default 8080)
#   SP_SERIAL  adb device serial (default: the only connected device)
#   ADB        path to adb (default: from PATH, else Android SDK default)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RIG_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$SCRIPT_DIR/assets"
APP_ID="com.streamprobe.android"

# ---- parse args -------------------------------------------------------------
CARDS="manifest_cap constrained bw_misconfig network_throttle cdn_miss decode_fail"
CARD="${1:-}"
TAB="${2:-overlay}"
if [[ -z "$CARD" ]]; then
  echo "Usage: $0 <card> [tab-label]" >&2
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

# Forward device localhost:PORT -> host PORT over adb (see run-card.sh rationale).
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

# ---- launch the app fresh, overlay always ON --------------------------------
echo "Launching card '$CARD' (profile=$PROFILE mode=$MODE) with overlay ON..."
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am start -n "${APP_ID}/.MainActivity" \
  -e sp_fault_url "$URL" \
  -e sp_fault_mode "$MODE" \
  -e sp_fault_overlay "on" \
  -e sp_fault_title "Fault Deck" >/dev/null

# ---- pause for the operator to position the overlay, then screencap ---------
mkdir -p "$ASSETS_DIR"
OUT="$ASSETS_DIR/${CARD}-${TAB}.png"
echo
echo "Card is playing with the overlay ON."
echo "  1. Let playback settle so the overlay populates."
echo "  2. Switch the overlay to the tab you want to capture ('$TAB')."
echo "  3. Press Enter here to screencap, or Ctrl-C to abort."
read -r _
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} exec-out screencap -p > "$OUT"

if [[ -s "$OUT" ]]; then
  echo "Saved $OUT"
else
  echo "ERROR: screencap produced an empty file at $OUT" >&2
  rm -f "$OUT"
  exit 1
fi
