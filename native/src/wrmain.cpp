// SPDX-License-Identifier: MIT
//
// wrmain.cpp — the library's public C ABI and its constructor-time boot.
//
// Java loads the library with System.load. There is no JNI anywhere: the POSIX
// constructor (or the Windows DllMain, with the loader-lock-safe pattern of
// returning before the thread is needed) locates the wrx.conf sidecar next to
// the library, boots the exchange server, and starts the poller thread when
// the transport is shared memory. The Panama FFM transport instead drives the
// same server through the wr_* entry points below, which take and return
// plain integers so Linker.downcallHandle can call them without any native
// metadata ceremony.

#include "wrx/wrx.hpp"
#include "wrvk/wrvk.hpp"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#include <pthread.h>
#endif

namespace {

std::atomic<bool> g_bootAttempted{false};
std::atomic<bool> g_booted{false};
std::mutex g_mutex;
std::unique_ptr<wrvk::WrvkRecorder> g_recorder;
std::string g_configPath;

// Function-local static: the constructor-time boot can run before this
// translation unit's namespace-scope objects are initialised, so the server
// must never be one. First use constructs it, thread-safely.
wrx::Server& theServer() {
        static wrx::Server server;
        return server;
}

std::string selfDirectory() {
#if defined(_WIN32)
        char path[MAX_PATH + 1]{};
        GetModuleFileNameA(nullptr, path, MAX_PATH);
        const std::string full(path);
#else
        Dl_info info{};
        if (dladdr(reinterpret_cast<void*>(&selfDirectory), &info) == 0 || info.dli_fname == nullptr) {
                return {};
        }
        const std::string full(info.dli_fname);
#endif
        const size_t slash = full.find_last_of("/\\");
        return slash == std::string::npos ? std::string(".") : full.substr(0, slash);
}

// Read the transport flag (a shared-memory boot must start the poller; an FFM
// boot must not, Java drives everything synchronously). Returns 1 for FFM.
int transportIsFfm(const std::string& directory) {
        FILE* file = std::fopen((directory + "/" + wrx::CONFIG_FILE).c_str(), "rb");
        if (file == nullptr) {
                return 0;
        }
        char buffer[512]{};
        const size_t n = std::fread(buffer, 1, sizeof(buffer) - 1, file);
        std::fclose(file);
        buffer[n] = 0;
        return std::strstr(buffer, "transport=ffm") != nullptr ? 1 : 0;
}

std::unique_ptr<wrvk::IVkDispatch> g_dispatchKeepAlive;

void hostInitCallback(const wrx::HostHandles& host, void*) {
        // The Vulkan loader is resolved with the instance handle carried by the
        // init message; if it or any symbol is missing, the recorder stays absent
        // and the native path reports not-ready instead of crashing.
        auto dispatch = wrvk::createRealDispatch("", host.instance);
        if (dispatch != nullptr) {
                g_recorder = std::make_unique<wrvk::WrvkRecorder>(*dispatch);
                theServer().setRecorder(g_recorder.get());
                g_dispatchKeepAlive = std::move(dispatch);
        }
}

} // namespace

extern "C" {

// Boot with an explicit config path (Panama FFM entry point). Idempotent.
int32_t wr_boot(const char* configPathUtf8) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_bootAttempted.store(true, std::memory_order_relaxed);
        if (g_booted.load(std::memory_order_relaxed)) {
                return 0;
        }
        if (configPathUtf8 == nullptr) {
                return -1;
        }
        g_configPath = configPathUtf8;
        theServer().setHostInitCallback(&hostInitCallback, nullptr);
        if (!theServer().boot(g_configPath)) {
                return -2;
        }
        g_booted.store(true, std::memory_order_relaxed);
        return 0;
}

// Process pending build messages synchronously (Panama FFM transport).
int32_t wr_submit_builds() {
        return static_cast<int32_t>(theServer().processBuilds());
}

// Record the latest frame synchronously (Panama FFM transport).
int32_t wr_submit_frame() {
        return static_cast<int32_t>(theServer().processFrame());
}

// Liveness: the poller's heartbeat count, or -1 when not booted.
int64_t wr_status() {
        if (!g_booted.load(std::memory_order_relaxed)) {
                return -1;
        }
        theServer().pulse();
        return static_cast<int64_t>(theServer().booted() ? 1 : 0);
}

int32_t wr_shutdown() {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!g_booted.load(std::memory_order_relaxed)) {
                return 0;
        }
        wrx::Poller::instance().stop();
        theServer().shutdown();
        g_recorder.reset();
        g_dispatchKeepAlive.reset();
        g_booted.store(false, std::memory_order_relaxed);
        return 0;
}

int32_t wr_abi_version() {
        return static_cast<int32_t>(wrx::PROTOCOL_VERSION);
}

const char* wr_version_string() {
        return "wrvk 1.0.0 (native Vulkan OBJ pipeline)";
}

} // extern "C"

#if defined(_WIN32)
BOOL WINAPI DllMain(HINSTANCE, DWORD reason, LPVOID) {
        if (reason == DLL_PROCESS_ATTACH) {
                // Windows loader-lock discipline: never wait on the new thread here.
                // The poller thread is started detached; it touches only file mapping
                // APIs and never loads libraries, which keeps the attach sequence
                // deadlock-free with a static CRT. Boot always: Java wrote the
                // wrx.conf sidecar before System.load; only the poller is
                // transport-gated (FFM drives the wr_* entry points instead).
                const std::string directory = selfDirectory();
                if (!directory.empty() && theServer().boot(directory + "/" + wrx::CONFIG_FILE)) {
                        g_booted.store(true, std::memory_order_relaxed);
                        theServer().setHostInitCallback(&hostInitCallback, nullptr);
                        if (transportIsFfm(directory) == 0) {
                                wrx::Poller::instance().start(theServer());
                        }
                }
        }
        return TRUE;
}
#else
__attribute__((constructor)) static void wrConstructor() {
	if (g_bootAttempted.exchange(true, std::memory_order_relaxed)) {
		return;
	}
	// Boot always: Java wrote the wrx.conf sidecar before System.load, so
	// the server comes up for both transports; only the poller is
	// transport-gated (FFM submits through the wr_* entry points instead).
	const std::string directory = selfDirectory();
	if (directory.empty()) {
		return;
	}
	if (theServer().boot(directory + "/" + wrx::CONFIG_FILE)) {
		g_booted.store(true, std::memory_order_relaxed);
		theServer().setHostInitCallback(&hostInitCallback, nullptr);
		if (transportIsFfm(directory) == 0) {
			wrx::Poller::instance().start(theServer());
		}
	}
}
#endif
