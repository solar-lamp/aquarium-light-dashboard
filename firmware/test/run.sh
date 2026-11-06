#!/usr/bin/env bash
# Builds the firmware against the simulated board and runs the scenario tests.
# Usage: firmware/test/run.sh            (VERBOSE=1 for the firmware's serial log)
set -euo pipefail
cd "$(dirname "$0")"
out="${TMPDIR:-/tmp}/aquahome-fw-test"
mkdir -p "$out"

# -fno-gnu-unique lets the library unload, so each simulated boot starts with
# fresh globals, exactly like the real board.
g++ -std=gnu++17 -O1 -g -fPIC -shared -fno-gnu-unique -Wall -Wno-unused-function \
    -Imock -I../Aquarium_Light -x c++ fw_lib.cpp -o "$out/firmware.so"
g++ -std=gnu++17 -O1 -g -rdynamic -Wall test_firmware.cpp -o "$out/test_firmware" -ldl

"$out/test_firmware" "$out/firmware.so"
