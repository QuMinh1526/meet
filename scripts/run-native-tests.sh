#!/usr/bin/env bash
# Host-side unit tests for the pure native helpers (ABS probing + log JSON).
# No Android SDK/NDK is needed: only <linux/input.h> from the host.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CXX="${CXX:-g++}"
OUT="${TMPDIR:-/tmp}/sensitune-native-host-test"

"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror \
    -I "$ROOT/app/src/main/cpp" \
    "$ROOT/app/src/test/cpp/native_host_test.cpp" \
    -o "$OUT"
"$OUT"
