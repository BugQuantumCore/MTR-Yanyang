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
#   wrx_tests     the exchange protocol round-trips (35 checks)
#   wrvk_tests    the command-stream / leak suite (40 checks)

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
        src/wrobj.cpp
        src/wrobj_parse.cpp
        src/wrobj_mesh.cpp
        src/wrobj_pipeline.cpp
        src/wrx.cpp
        src/wrx_region.cpp
        src/wrx_server.cpp
        src/wrvk.cpp
        src/wrvk_loader.cpp
        src/wrvk_stub.cpp
        src/wrmain.cpp)

add_library(wrvk SHARED ${WRVK_SOURCES})
target_include_directories(wrvk PRIVATE include)
target_compile_options(wrvk PRIVATE
        $<$<CXX_COMPILER_ID:GNU,Clang,AppleClang>:-Wall -Wextra -Wno-unused-parameter>)
if(WIN32)
    target_link_libraries(wrvk PRIVATE gdi32 user32)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(wrvk PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
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
    target_include_directories(wrobj_core PRIVATE include)

    add_executable(wrobj_tests tests/wrobj_tests.cpp tests/golden_format.cpp)
    target_link_libraries(wrobj_tests PRIVATE wrobj_core)

    set(WRVK_TEST_EXTRA ${WRVK_SOURCES})
    list(REMOVE_ITEM WRVK_TEST_EXTRA src/wrobj.cpp src/wrobj_parse.cpp src/wrobj_mesh.cpp src/wrobj_pipeline.cpp)

    find_package(Threads REQUIRED)
    add_executable(wrx_tests tests/wrx_tests.cpp ${WRVK_TEST_EXTRA})
    target_include_directories(wrx_tests PRIVATE include)
    target_link_libraries(wrx_tests PRIVATE wrobj_core Threads::Threads ${CMAKE_DL_LIBS})

    add_executable(wrvk_tests tests/wrvk_tests.cpp ${WRVK_TEST_EXTRA})
    target_include_directories(wrvk_tests PRIVATE include)
    target_link_libraries(wrvk_tests PRIVATE wrobj_core Threads::Threads ${CMAKE_DL_LIBS})

    # The golden .case files are generated by the Java differential test
    # (NativeObjGoldenTest) in CI; without them the suite still validates the
    # full parser semantics against its built-in expectations.
    add_test(NAME wrobj COMMAND wrobj_tests)
    add_test(NAME wrx COMMAND wrx_tests)
    add_test(NAME wrvk COMMAND wrvk_tests)
endif()
