#!/usr/bin/env bash
#
# reveal.sh
#
# Shows which card run-card.sh last drew. Run this AFTER you have written down
# your guess from the StreamProbe overlay.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SECRET_LOG="$SCRIPT_DIR/.secret-log.txt"

if [[ ! -s "$SECRET_LOG" ]]; then
  echo "No card has been drawn yet. Run ./run-card.sh first."
  exit 1
fi

echo "Last drawn card:"
tail -n 1 "$SECRET_LOG"
