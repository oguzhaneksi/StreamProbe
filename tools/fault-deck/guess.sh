#!/usr/bin/env bash
#
# guess.sh <card-name>
#
# Stops the timer for the current draw, records the trial, and reveals whether
# you were right. Run this once you have made your call from the overlay (or, in
# the control arm, from raw tools).
#
# Appends one row to results.csv:
#   ts,card,arm,guess,correct,seconds
# where `seconds` is the time from run-card.sh to this call (launch -> diagnosis),
# and `arm` is overlay or control. summary.sh aggregates these by arm.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRET_LOG="$SCRIPT_DIR/.secret-log.txt"
RESULTS="$SCRIPT_DIR/results.csv"

CARDS="manifest_cap constrained bw_misconfig network_throttle cdn_miss decode_fail"

GUESS="${1:-}"
if [[ -z "$GUESS" ]]; then
  echo "Usage: $0 <card-name>" >&2
  echo "Cards: $CARDS" >&2
  exit 2
fi
if [[ ! -s "$SECRET_LOG" ]]; then
  echo "No card has been drawn yet. Run ./run-card.sh first." >&2
  exit 1
fi

# Warn (do not block) if the guess is not a known card name.
if [[ " $CARDS " != *" $GUESS "* ]]; then
  echo "WARNING: '$GUESS' is not a known card name. Recording it anyway." >&2
fi

# Parse the last draw. Each value (epoch/card/arm) is a single token with no spaces.
LINE="$(tail -n 1 "$SECRET_LOG")"
START_EPOCH="$(sed -nE 's/.*epoch=([0-9]+).*/\1/p' <<<"$LINE")"
CARD="$(sed -nE 's/.*card=([a-z_]+).*/\1/p' <<<"$LINE")"
ARM="$(sed -nE 's/.*arm=([a-z]+).*/\1/p' <<<"$LINE")"

NOW="$(date +%s)"
ELAPSED=$(( NOW - START_EPOCH ))

CORRECT=0
[[ "$GUESS" == "$CARD" ]] && CORRECT=1

# Append to results.csv (write header once).
[[ -f "$RESULTS" ]] || echo "ts,card,arm,guess,correct,seconds" > "$RESULTS"
echo "$(date '+%Y-%m-%d %H:%M:%S'),$CARD,$ARM,$GUESS,$CORRECT,$ELAPSED" >> "$RESULTS"

# Reveal.
echo
if [[ "$CORRECT" == "1" ]]; then
  echo "CORRECT. Card was '$CARD' (arm: $ARM) in ${ELAPSED}s."
else
  echo "WRONG. You said '$GUESS', card was '$CARD' (arm: $ARM) in ${ELAPSED}s."
fi
echo "Recorded to $(basename "$RESULTS"). Run ./summary.sh for totals."
