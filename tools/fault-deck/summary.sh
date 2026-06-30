#!/usr/bin/env bash
#
# summary.sh
#
# Aggregates results.csv by benchmark arm (overlay vs control) and prints the
# headline numbers: trials, accuracy, and time-to-diagnose (mean + median).
# This is the benchmark output: does the StreamProbe overlay make people faster
# and more accurate at diagnosing the fault than raw tools?

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS="$SCRIPT_DIR/results.csv"

if [[ ! -s "$RESULTS" ]]; then
  echo "No results yet. Play some rounds: ./run-card.sh then ./guess.sh <card>." >&2
  exit 1
fi

# Per-arm aggregation in awk. Columns: ts,card,arm,guess,correct,seconds
awk -F',' '
  NR == 1 { next }                       # skip header
  {
    arm = $3; correct = $5; secs = $6
    n[arm]++
    hits[arm] += correct
    sum[arm] += secs
    times[arm] = times[arm] secs " "     # collect for median
  }
  function median(list,   a, c, i) {
    c = split(list, a, " ")
    # split leaves a trailing empty field from the trailing space; trim it
    if (a[c] == "") c--
    if (c == 0) return 0
    # insertion sort (small n)
    for (i = 2; i <= c; i++) {
      v = a[i]; j = i - 1
      while (j >= 1 && a[j] + 0 > v + 0) { a[j+1] = a[j]; j-- }
      a[j+1] = v
    }
    if (c % 2 == 1) return a[(c+1)/2] + 0
    return (a[c/2] + a[c/2+1]) / 2.0
  }
  END {
    printf "%-9s %6s %9s %10s %10s\n", "ARM", "N", "ACCURACY", "MEAN_SEC", "MED_SEC"
    printf "%-9s %6s %9s %10s %10s\n", "---", "-", "--------", "--------", "-------"
    split("overlay control", order, " ")
    for (k = 1; k <= 2; k++) {
      arm = order[k]
      if (!(arm in n)) continue
      acc = (hits[arm] / n[arm]) * 100.0
      mean = sum[arm] / n[arm]
      med = median(times[arm])
      printf "%-9s %6d %8.0f%% %10.1f %10.1f\n", arm, n[arm], acc, mean, med
    }
    # any other arms (just in case)
    for (arm in n) {
      if (arm == "overlay" || arm == "control") continue
      acc = (hits[arm] / n[arm]) * 100.0
      mean = sum[arm] / n[arm]
      med = median(times[arm])
      printf "%-9s %6d %8.0f%% %10.1f %10.1f\n", arm, n[arm], acc, mean, med
    }
  }
' "$RESULTS"
