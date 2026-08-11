#!/usr/bin/env bash
# Fetch a GGUF model and push it to the connected device.
#
# Weights are not committed (see .gitignore) and are not downloaded by the app:
# AXON holds no INTERNET permission at all (decision D7), which is what makes
# "screen contents cannot leave the device" an OS-enforced property rather than a
# promise. The cost is that weights are side-loaded, and this script is that path.
#
#   ./tools/fetch-model.sh                 # default: Gemma 4 E2B Q4_K_M
#   ./tools/fetch-model.sh --push          # also push to the connected device
#   ./tools/fetch-model.sh --quant Q4_K_S  # a different quantisation
#
# Model choice is decision D2: Gemma 4 E2B supersedes the spec's Gemma 3n E2B
# (released 2026-04-02, ~1.3 GB at Q4_K_M, fits a 6 GB phone). §11 requires
# versions to be resolved at implementation time rather than copied from the doc.

set -euo pipefail

REPO="${AXON_MODEL_REPO:-unsloth/gemma-4-E2B-it-GGUF}"
QUANT="Q4_K_M"
PUSH=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --push)  PUSH=1; shift ;;
        --quant) QUANT="$2"; shift 2 ;;
        --repo)  REPO="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="$ROOT/models"
FILE="gemma-4-E2B-it-${QUANT}.gguf"
URL="https://huggingface.co/${REPO}/resolve/main/${FILE}"
DEST="$MODELS_DIR/$FILE"

mkdir -p "$MODELS_DIR"

if [[ -f "$DEST" ]]; then
    echo "already present: $DEST ($(du -h "$DEST" | cut -f1))"
else
    echo "fetching $FILE from $REPO ..."
    # -C - resumes a partial download; a 1.3 GB pull over a student connection
    # should not have to restart from zero.
    curl -L -C - --fail --progress-bar -o "$DEST.part" "$URL"
    mv "$DEST.part" "$DEST"
    echo "saved: $DEST ($(du -h "$DEST" | cut -f1))"
fi

if [[ $PUSH -eq 1 ]]; then
    ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
    # /data/local/tmp is readable by the app without any storage permission, and
    # survives app reinstall — so reinstalling the APK during development does
    # not mean re-pushing 1.3 GB over USB.
    TARGET="/data/local/tmp/axon/$FILE"
    echo "pushing to device: $TARGET"
    "$ADB" shell mkdir -p /data/local/tmp/axon
    "$ADB" push "$DEST" "$TARGET"
    "$ADB" shell chmod 644 "$TARGET"
    echo "done."
fi
