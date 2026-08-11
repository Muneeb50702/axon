#!/usr/bin/env bash
# Phase 1 acceptance (spec §13), run in chunks.
#
# TECNO's Griffin memory manager SIGKILLs a sustained-load instrumentation
# process after roughly seven minutes, foreground status notwithstanding —
# reproducibly, twice, on both a 0.8 GB and a 3.1 GB model. At ~50 s per case
# across two ablation arms, that caps one invocation at about four cases.
#
# Fighting an OEM process killer is not a winnable fight, so the run is chunked
# and the host aggregates. Each invocation emits RECORD| lines to logcat as it
# goes, so a kill costs the cases not yet reached rather than the whole run.
#
# This constraint is worth a line in the thesis: long-running on-device agent
# work on budget Android hardware has to be checkpointed, because the platform
# will terminate it. That is a real deployment finding, not a lab artefact.
#
#   ./tools/run-acceptance.sh [total-cases] [chunk-size]

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
PKG="dev.axon.android.inference.test/androidx.test.runner.AndroidJUnitRunner"

TOTAL="${1:-24}"
CHUNK="${2:-4}"
OUT="$ROOT/bench/results"
LOG="$OUT/acceptance-raw.log"

mkdir -p "$OUT"
: > "$LOG"

"$ADB" shell svc power stayon usb >/dev/null 2>&1

echo "Phase 1 acceptance: $TOTAL cases x 2 arms, in chunks of $CHUNK"
echo

offset=0
while [ "$offset" -lt "$TOTAL" ]; do
    echo "--- chunk at offset $offset ---"
    "$ADB" logcat -c >/dev/null 2>&1

    # Capture in the background; the instrumentation may be killed mid-chunk and
    # we still want everything it managed to emit.
    "$ADB" logcat AxonPhase1:I '*:S' > "$OUT/.chunk.log" 2>&1 &
    logcat_pid=$!

    "$ADB" shell am instrument -w \
        -e axonSamples "$TOTAL" -e axonOffset "$offset" -e axonChunk "$CHUNK" \
        -e class dev.axon.android.inference.Phase1AcceptanceTest \
        "$PKG" >/dev/null 2>&1

    sleep 2
    kill "$logcat_pid" 2>/dev/null
    wait "$logcat_pid" 2>/dev/null

    grep -o 'RECORD|.*' "$OUT/.chunk.log" >> "$LOG" 2>/dev/null
    done_now=$(grep -c 'RECORD|B_grammar' "$LOG" 2>/dev/null || echo 0)
    echo "    cases recorded so far: $done_now / $TOTAL"

    offset=$((offset + CHUNK))
done

rm -f "$OUT/.chunk.log"
echo
python3 "$ROOT/tools/aggregate-acceptance.py" "$LOG"
