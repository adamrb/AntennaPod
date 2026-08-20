#!/usr/bin/env bash
# Generates real-audio integration test fixtures for ad segment detection.
#
# Composes "mini episodes" from real podcast audio: speech content taken from a
# conversational section of an episode, and real ad audio taken from a section
# confirmed to be an ad (via embedded chapter markers or cross-episode
# validation). The composition offsets are exact, giving ground truth labels.
#
# Usage:
#   ./scripts/generateAdDetectionFixtures.sh <content-source.mp3> <content-offset-sec> \
#       <ad-source.mp3> <ad-offset-sec> <output-dir>
#
# Example (Lex Fridman: sponsor block starts 1:29, conversation after 10:00):
#   ./scripts/generateAdDetectionFixtures.sh \
#       /tmp/lex/khabib_nurmagomedov.mp3 1200 \
#       /tmp/lex/khabib_nurmagomedov.mp3 120 \
#       net/download/service/src/test/fixtures/addetection
#
# Output: raw PCM files (16 kHz mono s16le, the analyzer's native format) and
# manifest.json with expected ad segment boundaries. The integration test
# (AdSegmentAnalyzerIntegrationTest) reads these and skips if absent.
set -euo pipefail

CONTENT_SRC="$1"
CONTENT_OFFSET="$2"
AD_SRC="$3"
AD_OFFSET="$4"
OUT_DIR="$5"
AD_OFFSET2="${6:-$((AD_OFFSET + 70))}"

mkdir -p "$OUT_DIR"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

wav() { # src offset duration out — fade edges to avoid splice discontinuities
    local dur="$3"
    ffmpeg -v quiet -ss "$2" -t "$dur" -i "$1" -ac 1 -ar 16000 \
        -af "afade=t=in:d=0.5,afade=t=out:st=$((dur - 1)):d=0.5" \
        -y "$4"
}

concat() { # out in...
    local out="$1"; shift
    local list="$TMP/list_$$.txt"
    : > "$list"
    for f in "$@"; do echo "file '$f'" >> "$list"; done
    ffmpeg -v quiet -f concat -safe 0 -i "$list" -c copy -y "$TMP/joined.wav"
    ffmpeg -v quiet -i "$TMP/joined.wav" -f s16le -y "$out"
}

echo "Extracting clips..."
wav "$CONTENT_SRC" "$CONTENT_OFFSET" 600 "$TMP/content_a.wav"
wav "$CONTENT_SRC" $((CONTENT_OFFSET + 700)) 600 "$TMP/content_b.wav"
wav "$CONTENT_SRC" $((CONTENT_OFFSET + 1400)) 600 "$TMP/content_c.wav"
wav "$AD_SRC" "$AD_OFFSET" 60 "$TMP/ad_60.wav"
wav "$AD_SRC" "$AD_OFFSET2" 30 "$TMP/ad_30.wav"

echo "Composing fixtures..."
# Fixture 1: content(600s) + ad(60s) + content(600s)  -> ad at 600-660
concat "$OUT_DIR/single_ad.pcm" "$TMP/content_a.wav" "$TMP/ad_60.wav" "$TMP/content_b.wav"

# Fixture 2: content(600s) + ad(30s) + content(600s) + ad(60s) + content(600s)
#   -> ads at 600-630 and 1230-1290
concat "$OUT_DIR/two_ads.pcm" "$TMP/content_a.wav" "$TMP/ad_30.wav" "$TMP/content_b.wav" \
    "$TMP/ad_60.wav" "$TMP/content_c.wav"

# Fixture 3: pure content, no ads -> expect no segments
concat "$OUT_DIR/no_ads.pcm" "$TMP/content_a.wav" "$TMP/content_b.wav" "$TMP/content_c.wav"

cat > "$OUT_DIR/manifest.json" <<EOF
{
  "sampleRate": 16000,
  "contentSource": "$(basename "$CONTENT_SRC")@${CONTENT_OFFSET}s",
  "adSource": "$(basename "$AD_SRC")@${AD_OFFSET}s",
  "fixtures": [
    {"file": "single_ad.pcm", "expected": [{"startMs": 600000, "endMs": 660000}]},
    {"file": "two_ads.pcm", "expected": [
      {"startMs": 600000, "endMs": 630000},
      {"startMs": 1230000, "endMs": 1290000}]},
    {"file": "no_ads.pcm", "expected": []}
  ]
}
EOF

ls -la "$OUT_DIR"
echo "Done. Run: ./gradlew --console=plain :net:download:service:testFreeDebugUnitTest --tests '*IntegrationTest*'"
