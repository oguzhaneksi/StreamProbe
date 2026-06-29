# Fault Deck Case Study — Design

**Date:** 2026-06-29
**Status:** Approved design, pre-implementation
**Topic:** Reframe the `tools/fault-deck/` benchmark harness into a narrative case study suitable for an external blog post (Medium / LinkedIn), backed by real captures from the existing fault-injection rig.

## Background & motivation

`tools/fault-deck/` currently implements a blind "guess the card" RCT: six fault cards, two arms (overlay vs control), a stopwatch, and an accuracy CSV. The goal was to measure how much faster/more accurately someone diagnoses a streaming fault **with** the StreamProbe overlay vs **without** it.

The guessing-game framing is the wrong vehicle for a *published* case study:

1. **Self-administered with a known answer key.** The operator wrote `EXPECTED.md`; recognizing a fingerprint is recall, not diagnosis. Measured "time to diagnose" has no external credibility.
2. **Strawman control arm.** "Video + `adb logcat`" is not a serious baseline for ABR diagnosis, so the headline numbers look manufactured.
3. **Diagnosis is really transcription.** Card fingerprints == what the overlay displays, so "read the overlay" is circular.
4. **Two cards can't be distinguished** by design (`constrained`/`bw_misconfig`), capping accuracy and muddying the metric.

The **most valuable** content — the honest limits in `EXPECTED.md` ("Known limitations") and the concrete SDK fix (`VariantInfo.isSupported`) — is buried under the game scaffolding.

**Decision:** keep the fault-injection rig (content ladder, nginx profiles, intent-based fault injection — all reusable and solid). Drop the measurement layer (blind draw, timer, `results.csv`, accuracy) as the basis of the published artifact. Build a **narrative walkthrough** case study on top of the rig instead.

The existing guessing-game scripts (`run-card.sh`, `guess.sh`, `summary.sh`, `EXPECTED.md`) **stay in place** — they are not deleted; the case study simply no longer builds on them.

## Goals

- Produce a credible, honest, externally-publishable case study draft (Medium / LinkedIn) that shows the StreamProbe overlay's diagnostic value.
- Back every claim with **real captures** from the running rig: real overlay screenshots, real terminal output for the without-tool baseline.
- Be honest about what the overlay **cannot** show, and turn that into a roadmap item.

## Non-goals

- No changes to the SDK (the `isSupported` flag is a *suggested* roadmap item, not implemented here).
- No changes to the existing guessing-game scripts or `EXPECTED.md`.
- `decode_fail` is excluded from the case study (device-decoder-dependent, unreliable as evidence). It remains in the rig untouched.
- No fabricated stopwatch race; time is reported as approximate and secondary.

## Scope: which faults

| Fault | Role in the case study |
|---|---|
| `manifest_cap` | Win case 1 — cause is in the manifest, not the network. Breaks the "low quality == slow internet" intuition. |
| `network_throttle` | Win case 2 — cause genuinely is the network. Deliberate contrast with case 1 (same symptom, opposite root cause). |
| `cdn_miss` | Win case 3 — quality is actually fine; the tell is `X-Cache: MISS` headers. Shows a different overlay capability (CDN header reading). |
| `constrained` + `bw_misconfig` | Honesty section — the overlay renders these **identically**; used to show the tool's honest limit + roadmap (`isSupported`). |
| `decode_fail` | Excluded (see non-goals). |

## Approach: parallel per-case narrative

Chosen over a single detective-story arc (too long, screenshots crowd the flow) and a thematic grouping (weakens the speed-to-diagnose message). The parallel structure is scannable, each case is a self-contained mini-study that can be split into a LinkedIn carousel, and screenshots sit naturally.

### Artifact outline (`DRAFT.md`)

1. **Hook** — "The video is playing at low quality." One symptom, five different root causes. Can you tell which? (The symptom lies.)
2. **The setup** — one paragraph on the rig: a controlled HLS ladder + fault injection, so the reader knows the repro is real. Rig details link to the repo.
3. **Case 1: `manifest_cap`** — cause in the manifest, not the network.
4. **Case 2: `network_throttle`** — cause genuinely is the network (the inverse of case 1).
5. **Case 3: `cdn_miss`** — quality is fine; the problem is CDN cache, the clue is in the headers.
6. **Honesty section** — `constrained` vs `bw_misconfig`: where the overlay can't see, plus the roadmap fix.
7. **Closing** — without-tool vs with-tool: a steps/commands table + an honest "what we still couldn't see."

### Per-case anatomy (repeated four blocks)

Each win case is built from the same four blocks:

1. **Symptom** — 1–2 sentences + optional frame grab of the low-quality video.
2. **Without the tool** — a genuinely-executed command + its **real output** (fenced code block):
   - `manifest_cap` → `curl` the master playlist, count the rungs, see that 1080p is absent.
   - `network_throttle` → `adb logcat | grep` for ABR downswitch events + throughput.
   - `cdn_miss` → `curl -I` a segment, see `X-Cache: MISS` / `CF-Cache-Status: MISS`.
   - Annotated with a "how many steps / how much knowledge this took" note.
3. **With StreamProbe** — a single **overlay screenshot** on the relevant tab (Tracks / Segments / Switches / Errors), with an arrow/annotation. Shows the answer is on one screen.
4. **The difference** — one line: "6 manual commands + reading a manifest vs one glance at the overlay."

### Honesty section

Two side-by-side overlay screenshots of `constrained` and `bw_misconfig` that are **visually identical**. Message: the overlay shows what the player *decided*, not *why*. Then the concrete roadmap: adding `VariantInfo.isSupported` (sourced from `Tracks.Group.isTrackSupported`) would let the overlay mark a rung "present but undecodable" and separate these cases directly. (Sourced from `EXPECTED.md` Known limitations 1 & 2.)

## Metric

Primary, defensible metric: **number of steps + number of commands** for the without-tool baseline vs the overlay (one glance / N taps). Time is reported **as approximate and secondary** only — debug time is not deterministic and varies by person, so a precise stopwatch would be dishonest for a single operator.

## Capture mechanics

`run-card.sh` draws randomly (correct for the blind game, wrong for deterministic capture). A **new** helper is added:

- **`tools/fault-deck/case-study/capture.sh <card>`** — reuses the same card → (nginx profile, app mode) mapping as `run-card.sh`, launches the *specific* requested fault via the same intent extras (`sp_fault_url`, `sp_fault_mode`, `sp_fault_overlay=on`, `sp_fault_title`), and captures the overlay with `adb exec-out screencap -p > assets/<name>.png`. It does **not** modify or import the guessing-game scripts (separate concern; preserves the blind game's integrity).
  - Honors the same env knobs as `run-card.sh` (`SP_HOST`, `SP_PORT`, `SP_SERIAL`, `ADB`) and performs the same `adb reverse` tunnel + nginx-up steps, so capture works from the same environment.
  - The operator switches to the correct overlay tab before the screencap (which tab per fault is known from `EXPECTED.md`). The script may pause for the operator to select the tab, or take the tab name as a second arg and document the manual tap — to be decided in the plan.
- **Without-tool output** is captured as **real terminal text** (fenced code blocks), not screenshots — cleaner and copy-pasteable for a blog.

## Repo layout

```
tools/fault-deck/case-study/
  DRAFT.md            # blog draft source (to be moved to Medium/LinkedIn)
  capture.sh          # deterministic fault launcher + screencap helper
  assets/
    manifest_cap-tracks.png
    network_throttle-segments.png
    cdn_miss-segments.png
    constrained-vs-bwmisconfig.png   # side by side
  raw/                # captured raw terminal output, inlined into DRAFT.md
```

The guessing-game files remain at `tools/fault-deck/` untouched.

## Testing / verification

This is a content + tooling artifact, not shippable SDK code, so verification is lightweight:

- `capture.sh` runs end-to-end against the live rig and produces a non-empty PNG for each of the four required captures.
- Every without-tool fenced block in `DRAFT.md` is real captured output (no hand-written terminal output).
- `DRAFT.md` renders correctly on GitHub (images resolve, tables format).
- `shellcheck` clean for `capture.sh` (matches the style of the existing scripts).

## Open questions resolved during brainstorming

- Faults: 3 win cases + 1 honesty pair, drop `decode_fail`. ✓
- Without-tool baseline: actually executed, real output. ✓
- Output venue: external blog draft, source in repo. ✓
- `capture.sh`: new file, not a flag on `run-card.sh`. ✓
- Metric: steps + commands primary, time approximate/secondary. ✓
