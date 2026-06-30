#!/usr/bin/env bash
#
# make-ladder.sh
#
# Builds ONE HLS "ladder" (the same video encoded at several qualities) that the
# fault-deck nginx server will serve. The ladder is the raw material every card
# plays. Output goes to tools/fault-deck/content/.
#
# A "ladder" = one master.m3u8 that points at several variant playlists, each a
# different resolution/bitrate. ABR (adaptive bitrate) climbs and drops between
# these rungs based on measured throughput.
#
# Renditions produced (4 rungs):
#   240p   AVC (H.264)    ~0.4 Mbps   easy to decode, the safe floor
#   480p   AVC (H.264)    ~1.2 Mbps
#   720p   AVC (H.264)    ~3.0 Mbps
#   1080p  HEVC (H.265)   ~8.0 Mbps   TOP rung, intentionally hard to decode
#
# Why the top rung is HEVC Main10 (10-bit):
#   The decode_fail card needs the player to actually SELECT a rung its decoder
#   chokes on. ExoPlayer's default track selector filters out video larger than
#   the display viewport, so a 4K rung would never be picked on a 1080p screen.
#   A 1080p rung DOES fit the viewport, so over a fast local link ABR climbs to
#   it and the weak/absent HEVC-Main10 decoder errors out. That is the point.
#   If your device decodes 1080p HEVC fine you may instead see dropped frames;
#   make it harder by setting INCLUDE_4K=1 to add a 2160p HEVC rung on top, or
#   bump the bitrate / keep 10-bit.
#
# Two master playlists are written:
#   master.m3u8          full ladder (240p..1080p HEVC, plus 2160p if INCLUDE_4K=1)
#   master-cap480.m3u8   trimmed ladder, ONLY up to 480p (used by the cap480 nginx profile)
#
# Usage:
#   ./make-ladder.sh [source-video]
#   INCLUDE_4K=1 ./make-ladder.sh [source-video]   # add an extra hard 2160p HEVC rung
#
# If you pass a source video it is used as the input. If you pass nothing the
# script synthesizes a 60s 4K test pattern with a tone, so the tool is
# self-contained and needs no external asset.
#
# Requires: ffmpeg with libx264 + libx265 (brew install ffmpeg).
# Re-run any time; it overwrites content/. Edit the knobs below to tune.

set -euo pipefail

# ---- paths -----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/content"
SRC="${1:-}"

# ---- tunable knobs ---------------------------------------------------------
DURATION=60               # seconds of synthetic source (ignored if you pass a source)
HLS_TIME=4                # target segment length in seconds
INCLUDE_4K="${INCLUDE_4K:-0}"  # set 1 to add an extra 2160p HEVC rung above 1080p
# ----------------------------------------------------------------------------

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ERROR: ffmpeg not found. Install it first (e.g. brew install ffmpeg)." >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/240p" "$OUT/480p" "$OUT/720p" "$OUT/1080p"
[[ "$INCLUDE_4K" == "1" ]] && mkdir -p "$OUT/2160p"

# If no source was given, synthesize a 4K test pattern + sine tone. We generate
# at 2160p so the optional 2160p rung is a real downscale-free encode.
if [[ -z "$SRC" ]]; then
  echo "No source given. Synthesizing a ${DURATION}s 4K test pattern..."
  SRC="$OUT/_source.mp4"
  ffmpeg -hide_banner -y \
    -f lavfi -i "testsrc2=size=3840x2160:rate=30:duration=${DURATION}" \
    -f lavfi -i "sine=frequency=440:duration=${DURATION}" \
    -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
    -c:a aac -b:a 128k \
    "$SRC"
fi

echo "Source: $SRC"

# ---- helper: encode one AVC (H.264) rung -----------------------------------
# args: name height video-bitrate maxrate bufsize
encode_avc() {
  local name="$1" height="$2" vbr="$3" maxrate="$4" bufsize="$5"
  echo "Encoding ${name} (AVC ${height}p @ ${vbr})..."
  ffmpeg -hide_banner -y -i "$SRC" \
    -vf "scale=-2:${height}" \
    -c:v libx264 -profile:v main -preset veryfast \
    -b:v "$vbr" -maxrate "$maxrate" -bufsize "$bufsize" \
    -g 48 -keyint_min 48 -sc_threshold 0 \
    -c:a aac -b:a 96k -ac 2 \
    -hls_time "$HLS_TIME" -hls_playlist_type vod \
    -hls_segment_filename "$OUT/${name}/seg_%03d.ts" \
    "$OUT/${name}/index.m3u8"
}

# ---- helper: encode a HEVC (H.265) Main10 rung (hard to decode) -------------
# args: name height video-bitrate maxrate bufsize
# Main10 / 10-bit on purpose so weak decoders choke. -tag:v hvc1 keeps it
# HLS-friendly. Edit -b:v / -pix_fmt / -level to tune the difficulty.
encode_hevc_hard() {
  local name="$1" height="$2" vbr="$3" maxrate="$4" bufsize="$5"
  echo "Encoding ${name} (HEVC Main10 ${height}p @ ${vbr}, intentionally hard)..."
  ffmpeg -hide_banner -y -i "$SRC" \
    -vf "scale=-2:${height}" \
    -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -preset ultrafast \
    -b:v "$vbr" -maxrate "$maxrate" -bufsize "$bufsize" \
    -x265-params "keyint=48:min-keyint=48:scenecut=0" \
    -tag:v hvc1 \
    -c:a aac -b:a 128k -ac 2 \
    -hls_time "$HLS_TIME" -hls_playlist_type vod \
    -hls_segment_filename "$OUT/${name}/seg_%03d.ts" \
    "$OUT/${name}/index.m3u8"
}

encode_avc 240p 240 400k  428k  600k
encode_avc 480p 480 1200k 1284k 1800k
encode_avc 720p 720 3000k 3210k 4500k
# Top rung: 1080p HEVC Main10 (hard). This is the decode_fail material.
encode_hevc_hard 1080p 1080 8000k 8560k 12000k
[[ "$INCLUDE_4K" == "1" ]] && encode_hevc_hard 2160p 2160 16000k 18000k 24000k

# ---- master playlists ------------------------------------------------------
# CODECS strings are approximate; tune if a strict player rejects them. BANDWIDTH
# is the peak (maxrate) per rung so ABR sees a realistic ceiling.
echo "Writing master.m3u8 (full ladder)..."
{
  echo '#EXTM3U'
  echo '#EXT-X-VERSION:7'
  echo '#EXT-X-STREAM-INF:BANDWIDTH=492000,RESOLUTION=426x240,CODECS="avc1.4d401e,mp4a.40.2"'
  echo '240p/index.m3u8'
  echo '#EXT-X-STREAM-INF:BANDWIDTH=1380000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"'
  echo '480p/index.m3u8'
  echo '#EXT-X-STREAM-INF:BANDWIDTH=3510000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"'
  echo '720p/index.m3u8'
  # 1080p HEVC Main10 — the hard top rung.
  echo '#EXT-X-STREAM-INF:BANDWIDTH=8560000,RESOLUTION=1920x1080,CODECS="hvc1.2.4.L120.90,mp4a.40.2"'
  echo '1080p/index.m3u8'
  if [[ "$INCLUDE_4K" == "1" ]]; then
    echo '#EXT-X-STREAM-INF:BANDWIDTH=18000000,RESOLUTION=3840x2160,CODECS="hvc1.2.4.L153.90,mp4a.40.2"'
    echo '2160p/index.m3u8'
  fi
} > "$OUT/master.m3u8"

# Trimmed master for the cap480 profile: 720p/1080p simply do not exist here, so
# the player can never know about (let alone climb to) them.
echo "Writing master-cap480.m3u8 (only up to 480p)..."
cat > "$OUT/master-cap480.m3u8" <<'EOF'
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-STREAM-INF:BANDWIDTH=492000,RESOLUTION=426x240,CODECS="avc1.4d401e,mp4a.40.2"
240p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1380000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
480p/index.m3u8
EOF

echo
echo "Done. Ladder written to: $OUT"
echo "  full master:    content/master.m3u8        (240p,480p,720p AVC + 1080p HEVC top)"
echo "  cap480 master:  content/master-cap480.m3u8 (240p, 480p only)"
