#!/usr/bin/env bash
#
# run-card.sh
#
# Draws ONE random "fault card", records it secretly, then launches the demo app
# configured for that card. It does NOT tell you which card it drew. You look at
# the StreamProbe overlay (or, in the control arm, raw tools), then run
# ./guess.sh <your-guess> to stop the timer and record the result.
#
# Benchmark arm (you choose; the card stays blind):
#   --overlay      (default) StreamProbe overlay is shown
#   --no-overlay   control arm: overlay hidden, diagnose from video + `adb logcat`
#
# Each card = a (nginx profile, app config mode) pair:
#
#   manifest_cap      cap480   + normal         (1080p missing from manifest)
#   constrained       full     + constrained    (player hard-capped to 480p)
#   bw_misconfig      full     + bw_misconfig   (ABR trusts only 10% of bandwidth)
#   network_throttle  throttle + normal         (link too slow for top rung)
#   cdn_miss          cdnmiss  + normal          (fake X-Cache: MISS headers)
#   decode_fail       full     + normal          (ABR climbs to hard 1080p HEVC)
#
# Prerequisites:
#   - make-ladder.sh has been run (content/ exists)
#   - Docker is running (this script brings nginx up via docker compose)
#   - An Android emulator/device is connected (adb)
#   - The DEBUG app is installed (./gradlew :app:installDebug)
#
# Configurable via env:
#   SP_HOST    host the device reaches nginx on (default 10.0.2.2 = emulator->host)
#   SP_PORT    nginx port (default 8080)
#   SP_SERIAL  adb device serial (default: the only connected device)
#   ADB        path to adb (default: from PATH, else Android SDK default)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRET_LOG="$SCRIPT_DIR/.secret-log.txt"
APP_ID="com.streamprobe.android"

# ---- parse arm flag ---------------------------------------------------------
ARM="overlay"          # overlay | control
OVERLAY_EXTRA="on"
case "${1:-}" in
  --no-overlay) ARM="control"; OVERLAY_EXTRA="off" ;;
  --overlay|"") ARM="overlay"; OVERLAY_EXTRA="on" ;;
  *) echo "Usage: $0 [--overlay|--no-overlay]" >&2; exit 2 ;;
esac

# Default to localhost + an adb reverse tunnel (see below). This is the reliable
# path for both emulators and adb-connected physical devices. The old emulator
# alias 10.0.2.2 routes app traffic through QEMU SLIRP -> host loopback, which
# times out against Docker Desktop on macOS. Override SP_HOST only for a device
# that reaches the host another way (e.g. your machine's LAN IP over Wi-Fi).
SP_HOST="${SP_HOST:-localhost}"
SP_PORT="${SP_PORT:-8080}"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"
ADB_ARGS=()
[[ -n "${SP_SERIAL:-}" ]] && ADB_ARGS=(-s "$SP_SERIAL")

# Forward the device's localhost:PORT to the host's PORT over the adb channel.
# Skip when SP_HOST was overridden to something other than localhost/127.0.0.1.
if [[ "$SP_HOST" == "localhost" || "$SP_HOST" == "127.0.0.1" ]]; then
  if ! "$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} reverse tcp:"$SP_PORT" tcp:"$SP_PORT" >/dev/null 2>&1; then
    echo "WARNING: 'adb reverse' failed. The device may not reach nginx on :${SP_PORT}." >&2
  fi
fi

# ---- bring nginx up (idempotent) -------------------------------------------
# Non-fatal: if docker is down or you serve nginx another way, we warn and go on.
if command -v docker >/dev/null 2>&1; then
  echo "Ensuring nginx is up..."
  if ! docker compose -f "$SCRIPT_DIR/nginx/docker-compose.yml" up -d >/dev/null 2>&1; then
    echo "WARNING: could not start nginx via docker. Make sure it is serving on :${SP_PORT}." >&2
  fi
else
  echo "WARNING: docker not found. Start the nginx server yourself before testing." >&2
fi

# ---- draw a random card -----------------------------------------------------
CARDS=(manifest_cap constrained bw_misconfig network_throttle cdn_miss decode_fail)
CARD="${CARDS[$((RANDOM % ${#CARDS[@]}))]}"

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

# ---- record the draw secretly (append; guess.sh / reveal.sh read the last line)
# epoch is the timer start; guess.sh subtracts it from "now" for elapsed seconds.
EPOCH="$(date +%s)"
TS="$(date '+%Y-%m-%d %H:%M:%S')"
echo "epoch=${EPOCH} ts=${TS} card=${CARD} arm=${ARM} profile=${PROFILE} mode=${MODE} url=${URL}" >> "$SECRET_LOG"

# ---- launch the app fresh with the card's intent extras ---------------------
# force-stop first so onCreate runs again and re-reads the intent.
# The ${ARR[@]+...} guard keeps an empty array safe under `set -u` (macOS bash 3.2).
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
"$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} shell am start -n "${APP_ID}/.MainActivity" \
  -e sp_fault_url "$URL" \
  -e sp_fault_mode "$MODE" \
  -e sp_fault_overlay "$OVERLAY_EXTRA" \
  -e sp_fault_title "Fault Deck" >/dev/null

# ---- neutral output (do NOT reveal the card) --------------------------------
echo
echo "Kart yuklendi. Arm: ${ARM}. Timer basladi."
if [[ "$ARM" == "control" ]]; then
  echo "(Overlay KAPALI. Video + 'adb logcat' ile teshis et.)"
else
  echo "(Overlay ACIK. Overlay'e bak ve teshis et.)"
fi
echo "Tahminini bitirince:  ./guess.sh <kart-adi>"
