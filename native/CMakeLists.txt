# SPDX-License-Identifier: MIT
#
# wrvk — the native Vulkan OBJ pipeline for MTR-YanYang 1.21.x.
#
# One shared library per desktop platform, loaded with System.load:
#
#   wrvk    wrobj (parse/build) + wrx (exchange) + wrvk (Vulkan draw) + wrmain
#
# The library has no build-time Vulkan dependency: the loader is dlopen'd at
# runtime and every entry point is resolved through vkGetInstanceProcAddr with
# the instance handle Java supplies. The vendored Khronos headers provide the
# struct definitions; a machine without any Vulkan runtime still builds and
# runs the full test suite through RecordingDispatch.
#
# Tests (see native/build.sh for the CMake-free equivalents):
#   wrobj_tests   the ported 1.20.x parser/builder suite (198 checks)
#   wrvk_tests    the command-stream / leak suite (40 checks)
#   wrctor_tests  the production boot flow against the real .so (22 checks)
#   wrxr          the Rust transport suite (cargo test; 60+ checks,
#                 including the ported exchange protocol round-trips)

cmake_minimum_required(VERSION 3.16)

project(wrvk
        VERSION 1.0.0
        DESCRIPTION "Native Vulkan OBJ pipeline for Minecraft Transit Railway"
        LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
foreach(_config IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set("CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_LIBRARY_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_RUNTIME_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
endforeach()

if(WIN32)
    # Static CRT keeps DllMain's thread creation inside the loader-lock-safe
    # regime documented in src/wrmain.cpp.
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

set(WRVK_SOURCES
        src/wrcore_domain.cpp
        src/wrobj.cpp
        src/wrobj_parse.cpp
        src/wrobj_mesh.cpp
        src/wrobj_pipeline.cpp
        src/wrvk.cpp
        src/wrvk_loader.cpp
        src/wrvk_stub.cpp
        src/wrmain.cpp)

# ---------------------------------------------------------------------------
# The Rust transport core: build the staticlib with cargo (zero dependencies,
# so --offline always works), then link it in exactly like another object
# library. WRXR_SKIP_CPP_BUNDLE stops the crate's build script from also
# compiling the C++ bundle — that exists for `cargo test` only.
# ---------------------------------------------------------------------------
find_program(CARGO_EXECUTABLE cargo)
if(NOT CARGO_EXECUTABLE)
        message(FATAL_ERROR "cargo is required to build the Rust transport core (rustup.rs)")
endif()
set(WRXR_MANIFEST ${CMAKE_CURRENT_SOURCE_DIR}/rust/wrxr/Cargo.toml)
set(WRXR_TARGET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rust/wrxr/target)
if(WIN32)
        set(WRXR_STATICLIB ${WRXR_TARGET_DIR}/release/wrxr.lib)
else()
        set(WRXR_STATICLIB ${WRXR_TARGET_DIR}/release/libwrxr.a)
endif()
if(WIN32)
        # The C++ objects are built against the static CRT (see the MSVC
        # runtime selection above), so the Rust core must match: crt-static
        # keeps one CRT in the DLL — required for the DllMain thread-start
        # discipline documented in src/wrmain.cpp.
        add_custom_target(wrxr_rust
                COMMAND ${CMAKE_COMMAND} -E env WRXR_SKIP_CPP_BUNDLE=1
                        "RUSTFLAGS=-C target-feature=+crt-static"
                        cargo build --release --offline --manifest-path ${WRXR_MANIFEST}
                BYPRODUCTS ${WRXR_STATICLIB}
                WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
                COMMENT "building the Rust transport core (wrxr)")
else()
        add_custom_target(wrxr_rust
                COMMAND ${CMAKE_COMMAND} -E env WRXR_SKIP_CPP_BUNDLE=1
                        cargo build --release --offline --manifest-path ${WRXR_MANIFEST}
                BYPRODUCTS ${WRXR_STATICLIB}
                WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
                COMMENT "building the Rust transport core (wrxr)")
endif()

add_library(wrvk SHARED ${WRVK_SOURCES})
target_include_directories(wrvk PRIVATE include)
target_compile_options(wrvk PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wno-unused-parameter>)
add_dependencies(wrvk wrxr_rust)
if(WIN32)
    target_link_libraries(wrvk PRIVATE gdi32 user32 ${WRXR_STATICLIB})
else()
    find_package(Threads REQUIRED)
    target_link_libraries(wrvk PRIVATE Threads::Threads ${CMAKE_DL_LIBS} m ${WRXR_STATICLIB})
endif()

# ---------------------------------------------------------------------------
# Optional: compile the GLSL to SPIR-V at build time. The mod also carries the
# committed blobs under shaders/spirv/, so CI machines without glslang still
# produce a complete artefact; when glslang IS present the fresh compile must
# match byte-for-byte, which guards against accidental shader drift.
# ---------------------------------------------------------------------------
find_program(GLSLANG_VALIDATOR glslangValidator)
if(GLSLANG_VALIDATOR)
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/spirv")
    set(WRVK_SPIRV_KEYS entity_cutout entity_translucent beacon_beam)
    set(WRVK_SPIRV_PAIRS
            "0=entity.vert=cutout.frag"
            "1=entity.vert=translucent.frag"
            "2=beacon.vert=beacon.frag")
    add_custom_target(wrvk_shaders
            COMMAND ${CMAKE_COMMAND} -E echo "compiling SPIR-V")
    foreach(pair ${WRVK_SPIRV_PAIRS})
        string(REPLACE "=" ";" parts "${pair}")
        list(GET parts 0 key)
        list(GET parts 1 vert)
        list(GET parts 2 frag)
        set(vertSpv "${CMAKE_BINARY_DIR}/spirv/${key}.vert.spv")
        set(fragSpv "${CMAKE_BINARY_DIR}/spirv/${key}.frag.spv")
        add_custom_command(OUTPUT ${vertSpv}
                COMMAND ${GLSLANG_VALIDATOR} --target-env vulkan1.1 -V -o ${vertSpv} "${CMAKE_SOURCE_DIR}/shaders/${vert}"
                DEPENDS "${CMAKE_SOURCE_DIR}/shaders/${vert}")
        add_custom_command(OUTPUT ${fragSpv}
                COMMAND ${GLSLANG_VALIDATOR} --target-env vulkan1.1 -V -o ${fragSpv} "${CMAKE_SOURCE_DIR}/shaders/${frag}"
                DEPENDS "${CMAKE_SOURCE_DIR}/shaders/${frag}")
        add_custom_target(wrvk_shader_${key} DEPENDS ${vertSpv} ${fragSpv})
        add_dependencies(wrvk_shaders wrvk_shader_${key})
    endforeach()
endif()

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
if(NOT DEFINED WRVK_BUILD_TESTS)
    set(WRVK_BUILD_TESTS ON)
endif()

if(WRVK_BUILD_TESTS)
    enable_testing()

    set(WRVK_TEST_LIBS wrobj_core)
    add_library(wrobj_core STATIC
            src/wrobj.cpp
            src/wrobj_parse.cpp
            src/wrobj_mesh.cpp
            src/wrobj_pipeline.cpp)
    target_include_directories(wrobj_core PUBLIC include)

    add_executable(wrobj_tests tests/wrobj_tests.cpp tests/golden_format.cpp)
    target_link_libraries(wrobj_tests PRIVATE wrobj_core)

    set(WRVK_TEST_EXTRA ${WRVK_SOURCES})
    list(REMOVE_ITEM WRVK_TEST_EXTRA src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp)

    find_package(Threads REQUIRED)
    add_executable(wrvk_tests tests/wrvk_tests.cpp ${WRVK_TEST_EXTRA})
    target_include_directories(wrvk_tests PRIVATE include)
    add_dependencies(wrvk_tests wrxr_rust)
    if(WIN32)
            target_link_libraries(wrvk_tests PRIVATE wrobj_core gdi32 user32 ${WRXR_STATICLIB})
    else()
            target_link_libraries(wrvk_tests PRIVATE wrobj_core Threads::Threads ${CMAKE_DL_LIBS} m ${WRXR_STATICLIB})
    endif()

    # The production boot flow against the real shared object: write the
    # region files, place a library copy next to the conf, dlopen, and watch
    # the constructor boot + poller + FFM entry points do their thing.
    if(NOT WIN32)
            add_executable(wrctor_tests tests/wrctor_tests.cpp)
            target_include_directories(wrctor_tests PRIVATE include)
            target_link_libraries(wrctor_tests PRIVATE ${CMAKE_DL_LIBS})
            add_dependencies(wrctor_tests wrvk)
    endif()

    # The golden .case files are generated by the Java differential test
    # (NativeObjGoldenTest) in CI; without them the suite still validates the
    # full parser semantics against its built-in expectations.
    add_test(NAME wrobj COMMAND wrobj_tests)
    add_test(NAME wrvk COMMAND wrvk_tests)
    if(NOT WIN32)
            add_test(NAME wrctor COMMAND wrctor_tests $<TARGET_FILE:wrvk>)
    endif()

    # The Rust transport suite runs from the workflow (Linux job) with
    # `cargo test --release --offline`; here it would duplicate the same
    # compilation CMake just performed.
endif()
