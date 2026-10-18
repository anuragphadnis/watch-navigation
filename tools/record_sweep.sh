#!/usr/bin/env bash
# Records the mic while the watch rings, then saves the phone's call log next to the recording so
# tools/sound-scope.html can mark where each fake call started and ended.
#
#   tools/record_sweep.sh [seconds] [arecord device]     e.g. tools/record_sweep.sh 90 plughw:2,0
#   arecord -l                                           lists capture devices
set -euo pipefail

SECONDS_TO_RECORD="${1:-90}"
DEVICE="${2:-default}"
ADB="${ADB:-adb}"
# Set SERIAL when more than one device is connected (adb devices lists them).
ADB_TARGET=(${SERIAL:+-s "$SERIAL"})
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs/sound"
mkdir -p "$OUT_DIR"
NAME="$OUT_DIR/sweep_$(date +%Y%m%d_%H%M%S)"

# Phone clock minus laptop clock, so phone log times can be placed on the recording's timeline.
phone_offset() {
    local before phone after
    before=$(date +%s.%N)
    phone=$("$ADB" "${ADB_TARGET[@]}" shell date +%s.%N | tr -d '\r')
    after=$(date +%s.%N)
    python3 -I -c "print(round($phone - ($before + $after) / 2, 3))"
}

OFFSET=$(phone_offset)
# Stream the log while recording: the phone's buffer is busy and drops old lines within minutes.
"$ADB" "${ADB_TARGET[@]}" logcat -T 1 -v epoch -s MorseNav > "$NAME.log" &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null || true' EXIT

START=$(date +%s.%N)
echo "Recording $SECONDS_TO_RECORD s from '$DEVICE' to $NAME.wav — tap Ring sweep now."
arecord -q -D "$DEVICE" -f S16_LE -r 48000 -c 1 -d "$SECONDS_TO_RECORD" "$NAME.wav" || true  # stopping it early (pkill -x arecord) still saves markers
kill $LOGCAT 2>/dev/null || true

{
    echo "# sound-scope markers"
    echo "record_start_epoch $START"
    echo "phone_minus_laptop_s $OFFSET"
    grep -E "CALL .*(ringing|ended)|MARK |WATCH received" "$NAME.log" || true
} > "$NAME.markers.txt"
echo "$(grep -c ringing "$NAME.markers.txt" || true) calls and $(grep -c MARK "$NAME.markers.txt" || true) marks logged."

echo "Saved $NAME.wav and $NAME.markers.txt — drop both onto tools/sound-scope.html."
