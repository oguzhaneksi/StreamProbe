#!/bin/bash
# rebuild-streamprobe-core.sh — invoked by the iosApp scheme's build pre-action.
#
# Rebuilds the local StreamProbeCore *debug* XCFramework (which Package.swift's local
# binaryTarget consumes) BEFORE Xcode's build graph runs, so Kotlin core changes propagate
# to the app in the same Build/Run. It lives in a scheme pre-action — not a target build
# phase — because Xcode copies the SPM binary into the build products before target phases
# run, so a build-phase rebuild would land one build late.
#
# Why this script exists instead of inline scheme script text:
#   * Xcode does NOT surface scheme pre-action output in its UI: a Gradle/Kotlin failure
#     shows only a generic "Build canceled / exited 1", burying the real compiler error.
#     So on failure we extract the Kotlin `e:` diagnostics and show them in a macOS dialog,
#     and echo the full Gradle log to the build transcript.
#   * On a rebuild we post a user notification so the (otherwise silent, no-progress)
#     pre-action does not look like Xcode froze.
#
# Guarded by a stamp file so Gradle (≈15 s of startup + configuration even when up-to-date)
# only runs when a .kt under the iOS-relevant source sets is newer than the stamp. The stamp,
# not the framework binary, is the reference: Gradle's up-to-date check is content-based, so a
# touched-but-unchanged .kt would never advance the framework mtime and would wedge an
# mtime guard into rebuilding forever.

# Resolve the repo root from this script's location (works regardless of $SRCROOT).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

XCF="sdk/build/XCFrameworks/debug/StreamProbeCore.xcframework/ios-arm64_x86_64-simulator/StreamProbeCore.framework/StreamProbeCore"
STAMP="sdk/build/.streamprobe-xcf.stamp"

needs_rebuild() {
  [ ! -f "$XCF" ] && return 0
  [ ! -f "$STAMP" ] && return 0
  [ -n "$(find sdk/src/commonMain sdk/src/iosMain -name '*.kt' -newer "$STAMP" 2>/dev/null | head -1)" ]
}

if ! needs_rebuild; then
  echo "StreamProbeCore: debug XCFramework up-to-date -> skipping Gradle."
  exit 0
fi

echo "StreamProbeCore: Kotlin changed (or framework missing) -> rebuilding debug XCFramework…"
osascript -e 'display notification "Rebuilding StreamProbeCore (Kotlin changed)…" with title "StreamProbe"' >/dev/null 2>&1 || true

LOG="$(mktemp -t streamprobe-xcf)"
if ./gradlew :sdk:assembleStreamProbeCoreDebugXCFramework --console=plain > "$LOG" 2>&1; then
  cat "$LOG"
  mkdir -p "$(dirname "$STAMP")" && touch "$STAMP"
  rm -f "$LOG"
  echo "StreamProbeCore: rebuild succeeded."
  exit 0
fi

# Failure: surface the real Kotlin compiler errors (Xcode won't show pre-action output).
cat "$LOG"
ERRFILE="$(mktemp -t streamprobe-xcf-err)"
{
  echo "Gradle failed to build StreamProbeCore (Kotlin core)."
  echo
  if grep -qE '^e: ' "$LOG"; then
    # Kotlin diagnostics: strip the "e: file://" prefix for readability.
    grep -E '^e: ' "$LOG" | sed -e 's#^e: file://##' | head -30
  else
    grep -E 'FAILURE:|What went wrong|Caused by:|^> ' "$LOG" | head -30
  fi
} > "$ERRFILE"

# Show a blocking dialog in interactive (GUI) builds only; never hang CI/headless runs.
if [ -z "${CI:-}" ]; then
  osascript \
    -e 'on run {f}' \
    -e 'set msg to (read POSIX file f)' \
    -e 'display dialog msg with title "StreamProbe — Kotlin build failed" buttons {"OK"} default button "OK" with icon stop giving up after 60' \
    -e 'end run' \
    "$ERRFILE" >/dev/null 2>&1 || true
fi

rm -f "$LOG" "$ERRFILE"
echo "error: StreamProbeCore Gradle build failed — see the Kotlin errors above (and the dialog)."
exit 1
