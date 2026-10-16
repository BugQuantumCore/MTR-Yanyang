# SPDX-License-Identifier: MIT
#
# Native OBJ model pipeline for Minecraft Transit Railway.
#
# Two targets, with names chosen so the Windows import libraries do not collide:
#
#   wrobj_core   static core library, no JVM dependency. Built and tested on its
#                own so the equivalence harness can link it without a JVM.
#   wrobj        shared library loaded by the mod. Its OUTPUT_NAME is "wrobj"
#                because that is the name the Java side passes to System.load.
#
# Nothing here depends on JNI headers except the bridge, and the bridge is only
# built when a JDK is found. That keeps `cmake --build` usable on a machine with
# no JDK, which is how the pure-native tests run in CI.

cmake_minimum_required(VERSION 3.16)

project(wrobj
        VERSION 1.0.0
        DESCRIPTION "Native OBJ model pipeline for Minecraft Transit Railway"
        LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# The internal headers are private; only include/wrobj/wrobj.hpp is public.
set(WROBJ_PUBLIC_HEADERS
        include/wrobj/wrobj.hpp)

set(WROBJ_PRIVATE_HEADERS
        include/wrobj/wrobj_model.hpp
        include/wrobj/wrobj_math.hpp
        include/wrobj/wrgl_emit.hpp
        include/wrobj/wrgl_cache.hpp
        include/wrobj/wrgl_draw.hpp)

set(WROBJ_SOURCES
        src/wrobj.cpp
        src/wrobj_mesh.cpp
        src/wrobj_parse.cpp
        src/wrobj_pipeline.cpp
        src/wrgl_cache.cpp
        src/wrgl_draw.cpp)

add_library(wrobj_core STATIC ${WROBJ_SOURCES} ${WROBJ_PUBLIC_HEADERS} ${WROBJ_PRIVATE_HEADERS})

target_include_directories(wrobj_core
        PUBLIC $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

set_target_properties(wrobj_core PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

# A stable alias so dependents do not need to know the target was renamed to keep
# the JNI import library clear of it.
add_library(wrobj::core ALIAS wrobj_core)

if(MSVC)
    # /fp:precise is the default and is required: the whole point of this library
    # is bit-identical floating point results against the JVM. Never enable
    # /fp:fast here.
    target_compile_options(wrobj_core PRIVATE /W4 /permissive- /utf-8 /fp:precise)
    target_compile_definitions(wrobj_core PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
else()
    target_compile_options(wrobj_core PRIVATE -Wall -Wextra -Wpedantic)
    if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        # Contracting a*b+c into fma changes the last bit of the result.
        target_compile_options(wrobj_core PRIVATE -ffp-contract=off)
    endif()
endif()

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
#
# find_package(JNI) honours JAVA_HOME. Building the bridge without the JNI
# headers is impossible, so a missing JDK disables the shared library instead of
# failing the configure step.

find_package(JNI QUIET)

if(JNI_FOUND)
    add_library(wrobj SHARED
            jni/wrobj_jni.cpp
            jni/wrgl_jni.cpp)
    target_link_libraries(wrobj PRIVATE wrobj::core)
    target_include_directories(wrobj PRIVATE
            ${JNI_INCLUDE_DIRS}
            ${CMAKE_CURRENT_SOURCE_DIR}/include)

    if(MSVC)
        target_compile_options(wrobj PRIVATE /W4 /permissive- /utf-8 /fp:precise)
        target_compile_definitions(wrobj PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
    else()
        target_compile_options(wrobj PRIVATE -Wall -Wextra)
    endif()

    set_target_properties(wrobj PROPERTIES
            PREFIX ""
            OUTPUT_NAME "wrobj")

    message(STATUS "wrobj: JNI bridge enabled")
else()
    message(STATUS "wrobj: JNI headers not found, building the core library only")
endif()

# ---------------------------------------------------------------------------
# Tests and benchmarks
# ---------------------------------------------------------------------------

option(WROBJ_BUILD_TESTS "Build the native OBJ pipeline tests" ON)

if(WROBJ_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------

include(GNUInstallDirs)
install(TARGETS wrobj_core ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR})
install(FILES ${WROBJ_PUBLIC_HEADERS} DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/wrobj)
if(JNI_FOUND)
    install(TARGETS wrobj LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR})
endif()
