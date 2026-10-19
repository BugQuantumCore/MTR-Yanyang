#!/usr/bin/env bash
# Local native build/test runner (no CMake needed; CI uses CMakeLists.txt).
# Usage: ./build.sh [test-name]   e.g. ./build.sh wrobj_tests
set -e
cd "$(dirname "$0")"
OUT=build-local
mkdir -p "$OUT"
CXX="${CXX:-g++}"
FLAGS="-std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -Iinclude"

case "${1:-wrobj_tests}" in
  wrobj_tests)
    $CXX $FLAGS tests/wrobj_tests.cpp tests/golden_format.cpp src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp -o "$OUT/wrobj_tests"
    ;;
  wrx_tests)
    $CXX $FLAGS tests/wrx_tests.cpp src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp src/wrx.cpp src/wrx_region.cpp src/wrx_server.cpp src/wrvk.cpp src/wrvk_loader.cpp src/wrvk_stub.cpp src/wrmain.cpp -o "$OUT/wrx_tests" -pthread -ldl
    ;;
  wrvk_tests)
    $CXX $FLAGS tests/wrvk_tests.cpp src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp src/wrx.cpp src/wrx_region.cpp src/wrx_server.cpp src/wrvk.cpp src/wrvk_loader.cpp src/wrvk_stub.cpp src/wrmain.cpp -o "$OUT/wrvk_tests" -pthread -ldl
    ;;
  all|*)
    ./build.sh wrobj_tests
    ./build.sh wrx_tests
    ./build.sh wrvk_tests
    exit 0
    ;;
esac

if [ "${2:-}" = "run" ]; then
  "./$OUT/${1:-wrobj_tests}"
fi
