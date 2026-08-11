#!/usr/bin/env bash
# Validate the §10.6 action grammar with llama.cpp's own parser.
#
# `ActionGrammarTest` in :core proves the grammar and the Kotlin model describe
# the same action space. It cannot prove llama.cpp will *accept* the grammar —
# only llama.cpp's parser can do that, and when it refuses one, the sampler is
# simply never installed and generation silently runs unconstrained. Contribution
# C3 would be gone with nothing failing.
#
# That is not hypothetical. On 2026-08-11 the grammar was rejected on-device and
# the only visible diagnostic was "failed to parse grammar" — llama.cpp routes
# the actual reason to stderr, which Android discards. The cause was a layout
# rule worth knowing: a GBNF rule body ends at the newline unless it is inside an
# open "( )". This script exists so that class of failure is a build error with a
# message, not a silent capability loss.
#
#   ./tools/check-grammar.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/tools/gbnf-check/build"
GRAMMAR="$ROOT/bench/build/axon-action.gbnf"

if [[ ! -f "$ROOT/third_party/llama.cpp/include/llama.h" ]]; then
    echo "llama.cpp submodule missing. Run: git submodule update --init --recursive" >&2
    exit 2
fi

echo "==> exporting grammar from :core"
"$ROOT/gradlew" -p "$ROOT" :bench:exportGrammar --console=plain -q

if [[ ! -x "$BUILD/gbnf-check" ]]; then
    echo "==> building gbnf-check (first run only)"
    cmake -S "$ROOT/tools/gbnf-check" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release > /dev/null
    cmake --build "$BUILD" --target gbnf-check -j "$(nproc)" > /dev/null
fi

echo "==> validating"
"$BUILD/gbnf-check" "$GRAMMAR" root
