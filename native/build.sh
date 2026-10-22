#!/usr/bin/env bash
# Local native build/test runner (no CMake needed; CI uses CMakeLists.txt).
#
# The exchange transport is the Rust core now (rust/wrxr), so this script
# builds its staticlib first and links it into every target that needs the
# wr_* entry points — exactly the composition the final library uses.
#
# Usage: ./build.sh [target]   e.g. ./build.sh wrvk_tests
#        ./build.sh run       # builds everything and runs all suites
set -e
cd "$(dirname "$0")"
OUT=build-local
mkdir -p "$OUT"
CXX="${CXX:-g++}"
FLAGS="-std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -Iinclude"

# The Rust transport core (zero dependencies → builds fully offline). The
# dedicated target dir keeps the build-script cache separate from `cargo
# test` runs in the same tree.
export WRXR_SKIP_CPP_BUNDLE=1
cargo build --release --offline --target-dir "$OUT/rust-target" --manifest-path rust/wrxr/Cargo.toml
RUSTLIB="$(pwd)/$OUT/rust-target/release/libwrxr.a"
RUSTLIBS="$RUSTLIB -lstdc++ -lm -lpthread -ldl"

# The C++ half of the server + the renderer + the boot shim.
WRVK_SRC="src/wrcore_domain.cpp src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp src/wrvk.cpp src/wrvk_loader.cpp src/wrvk_stub.cpp src/wrmain.cpp"

case "${1:-all}" in
  wrobj_tests)
    $CXX $FLAGS tests/wrobj_tests.cpp tests/golden_format.cpp src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp -o "$OUT/wrobj_tests"
    ;;
  wrvk_tests)
    $CXX $FLAGS tests/wrvk_tests.cpp $WRVK_SRC $RUSTLIBS -o "$OUT/wrvk_tests" -pthread -ldl
    ;;
  libwrvk)
    $CXX $FLAGS -shared -fPIC $WRVK_SRC $RUSTLIBS -o "$OUT/libwrvk.so" -pthread -ldl
    ;;
  wrctor_tests)
    ./build.sh libwrvk
    $CXX $FLAGS tests/wrctor_tests.cpp -o "$OUT/wrctor_tests" -pthread -ldl
    ;;
  all|*)
    ./build.sh wrobj_tests
    ./build.sh wrvk_tests
    ./build.sh wrctor_tests
    exit 0
    ;;
esac

if [ "${2:-}" = "run" ]; then
  "./$OUT/$1"
fi
