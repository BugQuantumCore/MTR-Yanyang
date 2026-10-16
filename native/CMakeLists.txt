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

# Both artefacts land directly in the build root whatever the generator.
#
# This matters more than it looks. CMake chooses a generator per platform: Ninja on Linux
# and macOS, and on the GitHub Windows runners the default is a multi-config generator,
# which appends a per-configuration directory to the output path. The CI upload step and
# fabric/build.gradle both look the library up by name, so an implicit subdirectory turns a
# successful build into "the artefact was not produced".
#
# These are set as variables rather than with set_target_properties on purpose: CMake
# initialises each target's output directory properties from these variables *after* the
# target is created, so setting the variable before the targets is what actually takes
# effect, and setting them afterwards for every configuration is what covers the
# multi-config generators.
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
foreach(_config IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set("CMAKE_ARCHIVE_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_LIBRARY_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
    set("CMAKE_RUNTIME_OUTPUT_DIRECTORY_${_config}" "${CMAKE_BINARY_DIR}")
endforeach()

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

# The Android NDK does not register with CMake's JNI find module, but it ships the headers
# in its sysroot. Without this the cross build would silently produce no shared library,
# which the packaging step would then report as a missing artefact.
if(ANDROID AND NOT JNI_FOUND)
    set(_wrobj_android_jni_include
            "${ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot/usr/include")
    if(EXISTS "${_wrobj_android_jni_include}/jni.h")
        set(JNI_FOUND TRUE)
        set(JNI_INCLUDE_DIRS "${_wrobj_android_jni_include}")
        message(STATUS "wrobj: using the NDK's JNI headers at ${_wrobj_android_jni_include}")
    else()
        message(STATUS "wrobj: the NDK's JNI headers were not found at ${_wrobj_android_jni_include}")
    endif()
endif()

if(JNI_FOUND)
    add_library(wrobj SHARED
            jni/wrobj_jni.cpp
            jni/wrgl_jni.cpp)
    target_link_libraries(wrobj PRIVATE wrobj::core)
    target_include_directories(wrobj PRIVATE
            ${JNI_INCLUDE_DIRS}
            ${CMAKE_CURRENT_SOURCE_DIR}/include)

    if(ANDROID)
        # Android has no libstdc++ shared object to link against: the platform ships only
        # the C runtime, so the C++ runtime has to be inside the library or the loader will
        # refuse it with an unresolved symbol. This is the standard recipe for an NDK shared
        # library and it is also why the tests are not built for Android by default.
        target_link_options(wrobj PRIVATE
                "-Wl,-z,max-page-size=16384"
                "-static-libstdc++")
        # The GL calls still go through the host - see wrgl_emit.hpp - so nothing here links
        # GLES. The launcher's JVM already has a binding, and on a translation layer such as
        # gl4ES that binding is what routes to the driver.
        target_compile_definitions(wrobj PRIVATE WROBJ_ANDROID=1)
    endif()

    if(MSVC)
        target_compile_options(wrobj PRIVATE /W4 /permissive- /utf-8 /fp:precise)
        target_compile_definitions(wrobj PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN)
    else()
        target_compile_options(wrobj PRIVATE -Wall -Wextra)
        if(NOT ANDROID)
            # Not needed on Android, where the C++ runtime is static, and a plain -lm is
            # required on Linux for the sqrt and cos the model pipeline uses.
            target_link_libraries(wrobj PRIVATE m)
        endif()
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
