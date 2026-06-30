# Fault Deck

A debug-only **benchmark harness** for measuring observability: how fast and how
accurately can someone diagnose a streaming fault **with** the StreamProbe
overlay versus **without** it (raw video + `adb logcat`).

Six cards each break video quality for a different reason, but all look the same
from outside (video plays low). You draw a card blind, diagnose it under one of
two arms, submit your guess, and the harness records time + correctness. Run many
rounds, then compare the arms.

This is a debug-only test harness. Nothing here ships in a release build.

## The two arms

| Arm | Flag | What the tester has |
|---|---|---|
| overlay | `--overlay` (default) | StreamProbe overlay is shown |
| control | `--no-overlay` | Overlay hidden; only the video + `adb logcat` |

Playback is identical in both arms. Only `streamProbe.show()` is skipped in the
control arm, so the comparison isolates the overlay's diagnostic value.

## Pieces

| File | Role |
|---|---|
| `make-ladder.sh` | Builds one HLS ladder (240p/480p/720p AVC + 1080p HEVC) into `content/`. |
| `nginx/` | Docker nginx serving the ladder under four profiles (`full`, `cap480`, `throttle`, `cdnmiss`). |
| `run-card.sh` | Draws a random card, picks the arm, starts the timer, launches the app. |
| `guess.sh` | Stops the timer, records the trial to `results.csv`, reveals correctness. |
| `summary.sh` | Aggregates `results.csv` by arm: trials, accuracy, mean/median time. |
| `reveal.sh` | Peek at the last drawn card without recording (escape hatch). |
| `case-study/capture.sh` | Launches one named card with the overlay ON and screenshots a tab (for `DRAFT.md`). |
| `case-study/capture-eventlogger.sh` | Launches one named card with EventLogger ON and dumps its logcat to `raw/` (raw-arm captures). |
| `EXPECTED.md` | Answer key: expected overlay fingerprint per card, plus known limits. |
| `.secret-log.txt` | Where draws are recorded. Git-ignored. Do not peek. |
| `results.csv` | Trial results. Git-ignored. |

## One-time setup

```bash
# Tools: ffmpeg (content), docker (server), Android SDK platform-tools (adb).
brew install ffmpeg                       # if not already present
# (Docker Desktop must be running.)

./make-ladder.sh                          # build the ladder (synthesizes a 4K test source)
docker compose -f nginx/docker-compose.yml up -d   # start the server
(cd ../.. && ./gradlew :app:installDebug)          # install DEBUG app on a running emulator/device
```

## Running a benchmark round

```bash
# Overlay arm:
./run-card.sh --overlay
# ...read the StreamProbe overlay, decide which card it is...
./guess.sh decode_fail        # stops timer, records, reveals if you were right

# Control arm:
./run-card.sh --no-overlay
# ...diagnose from the video + `adb logcat` only...
./guess.sh manifest_cap

# After many rounds across both arms:
./summary.sh
```

`summary.sh` prints something like:

```
ARM            N  ACCURACY   MEAN_SEC    MED_SEC
overlay        8       88%       21.4       19.0
control        8       38%       82.5       78.0
```

The timer runs from `run-card.sh` (app launch) to `guess.sh`, so it includes a
near-constant launch+buffer overhead that affects both arms equally.

Cards: `manifest_cap constrained bw_misconfig network_throttle cdn_miss decode_fail`.

## Device-to-host networking

`run-card.sh` defaults to `http://localhost:8080` and automatically runs
`adb reverse tcp:8080 tcp:8080`, which tunnels the device's localhost to the host
over the adb channel. Use this; it works for both emulators and adb-connected
physical devices.

Do **not** use the old emulator alias `10.0.2.2` here. On macOS with Docker
Desktop, app traffic to `10.0.2.2:8080` routes through QEMU SLIRP to the host
loopback and times out at connect, even though shell `nc`/`ping` and host `curl`
to the same port succeed. The `adb reverse` tunnel sidesteps that entirely.

Cleartext HTTP to `localhost` / `127.0.0.1` is already permitted in
`app/src/main/res/xml/network_security_config.xml` (debug only).

## Config knobs (env vars for run-card.sh)

- `SP_HOST` host the device reaches nginx on. Default `localhost` (with the adb
  reverse tunnel). Override only for a device that reaches the host another way,
  e.g. your machine's LAN IP over Wi-Fi; then add that IP to the network security
  config too.
- `SP_PORT` nginx port. Default `8080`.
- `SP_SERIAL` adb serial if more than one device is attached.
- `ADB` path to adb if not on `PATH`.

## How a card reaches the app

`run-card.sh` sends an intent with extras `sp_fault_url`, `sp_fault_mode`,
`sp_fault_overlay`, `sp_fault_title`. `MainActivity` reads them only in
`BuildConfig.DEBUG` and jumps straight into the player. The mode (`normal` /
`constrained` / `bw_misconfig`) mis-tunes the ExoPlayer track selector in
`PlayerManager`; `sp_fault_overlay=off` hides the overlay for the control arm.
Release builds ignore all of this.

## Case study: strengthening the raw arm

The blind benchmark above pits the overlay against *video + `adb logcat`*. The
written case study (`case-study/DRAFT.md`) holds the raw arm to a higher bar — the
strongest in-player tools a competent ExoPlayer engineer actually reaches for:

- **ExoPlayer's `EventLogger`** (an `AnalyticsListener`). Its logcat output already
  includes the runtime ladder (full `Tracks` dump with each rung's `[X]`/`[ ]`
  selection and `supported=` flag), the bandwidth estimate, per-segment
  `loadCompleted` timing/size, and dropped frames.
- **A proxy** (mitmproxy) for the `cdn_miss` case, which captures the player's
  *actual* segment traffic — and therefore the real `X-Cache` / `CF-Cache-Status`
  headers across every segment — which a one-off `curl -I` does not represent.

These produce the real raw captures pasted into `DRAFT.md`. They are debug-only
capture aids; nothing here ships.

### Capturing EventLogger output

`EventLogger` is attached to the demo player only when launched with
`sp_fault_eventlogger on` (a debug-only intent-extra; release builds ignore it).
`case-study/capture-eventlogger.sh` does this for one named card, clears logcat,
lets playback settle, and dumps `adb logcat -s EventLogger` to
`case-study/raw/<card>-eventlogger.txt`:

```bash
cd case-study
./capture-eventlogger.sh manifest_cap       # -> raw/manifest_cap-eventlogger.txt
./capture-eventlogger.sh network_throttle   # -> raw/network_throttle-eventlogger.txt
./capture-eventlogger.sh constrained        # -> raw/constrained-eventlogger.txt
./capture-eventlogger.sh bw_misconfig       # -> raw/bw_misconfig-eventlogger.txt
```

### Capturing real CDN cache headers for `cdn_miss` (mitmproxy)

The rig is plain HTTP, so mitmproxy needs **no CA certificate**. Run `mitmdump`
as a reverse proxy in front of nginx on a second port, point the card at that
port, and the player's real segment traffic — including the `X-Cache` /
`CF-Cache-Status` headers on every segment — flows through it:

```bash
# Terminal A — reverse-proxy nginx (:8080) on :8081 and log response headers:
mitmdump --mode reverse:http://localhost:8080 -p 8081 --flow-detail 2 \
  | tee case-study/raw/cdn_miss-proxy.txt

# Terminal B — launch the cdn_miss card through the proxy port:
cd case-study
SP_PORT=8081 ./capture.sh cdn_miss segments    # adb reverse + launch via :8081
# Let several segments load, then stop mitmdump (Ctrl-C in Terminal A).
```

`raw/cdn_miss-proxy.txt` now contains every segment's real cache headers. Filter
with `grep -iE 'GET .*\.ts|x-cache|cf-cache' case-study/raw/cdn_miss-proxy.txt`.
