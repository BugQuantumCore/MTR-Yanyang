// SPDX-License-Identifier: MIT
//
// wrmain.cpp — the platform boot shim + the recorder's C++ home.
//
// The transport, the queues, the control page and the wr_* entry points all
// live in the Rust core now (native/rust/wrxr). What still has to be C++:
//
//   * the constructor-time boot entry: POSIX has __attribute__((constructor)),
//     Windows has DllMain — this unit owns both, computes the directory the
//     library was loaded from, and hands the wrx.conf path to the Rust core
//     (wrxr_ctor_boot), which boots the server and starts the poller thread
//     when the transport is shared memory;
//   * the host-init callback: creating the Vulkan loader dispatch and the
//     wrvk recorder are C++ objects, so the domain calls back into this unit
//     from inside wrcore_init_vulkan;
//   * recorder ownership: the recorder and dispatch live in unique_ptrs here
//     and are torn down by wrmain_teardown_recorder, called from the Rust
//     wr_shutdown after the domain has freed every mesh through the recorder.
//
// The keepalive table at the bottom forces the linker to pull every wr_*
// object out of the Rust static archive so the shared library exports the
// complete C ABI that Java's SymbolLookup expects.

#include "wrx/wrx.hpp"
#include "wrvk/wrvk.hpp"
#include "wrcore/wrcore.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#endif

// defined in wrcore_domain.cpp — attaches the freshly created recorder so
// wrcore_init_vulkan can wire it up (C++-only wiring, not part of the ABI)
namespace wrcore {
void attachRecorder(wrvk::WrvkRecorder* recorder);
}

namespace {

std::unique_ptr<wrvk::IVkDispatch> g_dispatchKeepAlive;
std::unique_ptr<wrvk::WrvkRecorder> g_recorder;

// The Vulkan loader is resolved with the instance handle carried by the init
// message; if it or any symbol is missing, the recorder stays absent and the
// native path reports not-ready instead of crashing.
extern "C" void wrmainHostInitTrampoline(const void* hostHandles, void*) {
	const auto* host = static_cast<const wrx::HostHandles*>(hostHandles);
	auto dispatch = wrvk::createRealDispatch("", host->instance);
	if (dispatch != nullptr) {
		g_recorder = std::make_unique<wrvk::WrvkRecorder>(*dispatch);
		wrcore::attachRecorder(g_recorder.get());
		g_dispatchKeepAlive = std::move(dispatch);
	}
}

} // namespace

extern "C" {

// Install the host-init trampoline into the domain (idempotent; the Rust
// boot paths call this before booting so MSG_INIT_VULKAN can create the
// recorder).
void wrmain_install_host_init() {
	wrcore_set_host_init_callback(&wrmainHostInitTrampoline, nullptr);
}

// Tear the recorder and its dispatch down (Rust wr_shutdown calls this AFTER
// wrcore_shutdown_domain freed every mesh through the recorder, matching the
// old C++ wr_shutdown order: Poller::stop -> Server::shutdown -> reset()).
void wrmain_teardown_recorder() {
	g_recorder.reset();
	g_dispatchKeepAlive.reset();
}

} // extern "C"

// ---------------------------------------------------------------------------
// The Rust core's C ABI (defined in native/rust/wrxr/src/ffi.rs). Declared
// before the platform boot so the constructor can hand over the conf path,
// and referenced once more in the keepalive table at the bottom so the
// linker pulls every symbol out of the Rust archive into the .so.
// ---------------------------------------------------------------------------

extern "C" {
// defined in the Rust core (wrxr)
int32_t wr_boot(const char* configPathUtf8);
int32_t wr_submit_builds();
int32_t wr_submit_frame();
int64_t wr_status();
int32_t wr_shutdown();
int32_t wr_abi_version();
const char* wr_version_string();
int32_t wrxr_ctor_boot(const char* configPathUtf8);
}

extern "C" const void* const wrKeepalive[] = {
	reinterpret_cast<const void*>(&wr_boot),
	reinterpret_cast<const void*>(&wr_submit_builds),
	reinterpret_cast<const void*>(&wr_submit_frame),
	reinterpret_cast<const void*>(&wr_status),
	reinterpret_cast<const void*>(&wr_shutdown),
	reinterpret_cast<const void*>(&wr_abi_version),
	reinterpret_cast<const void*>(&wr_version_string),
	reinterpret_cast<const void*>(&wrxr_ctor_boot),
	reinterpret_cast<const void*>(&wrmain_install_host_init),
	reinterpret_cast<const void*>(&wrmain_teardown_recorder),
};

// ---------------------------------------------------------------------------
// Platform boot.
// ---------------------------------------------------------------------------

#if defined(_WIN32)

// The directory the library itself was loaded from (NOT the executable's —
// Java extracts the library into a per-session temp directory and writes the
// wrx.conf sidecar next to it, so the module handle is the only correct
// source). Self-allocated buffer, freed by the caller through wrmainFree.
extern "C" char* wrmainSelfDirectory(HINSTANCE module) {
	char buffer[MAX_PATH + 1]{};
	const DWORD length = GetModuleFileNameA(module, buffer, MAX_PATH);
	if (length == 0 || length >= MAX_PATH) {
		return nullptr;
	}
	buffer[length] = 0;
	std::string full(buffer);
	const size_t slash = full.find_last_of("/\\");
	if (slash == std::string::npos) {
		return nullptr;
	}
	const std::string directory = full.substr(0, slash);
	char* out = static_cast<char*>(std::malloc(directory.size() + 1));
	if (out == nullptr) {
		return nullptr;
	}
	std::memcpy(out, directory.data(), directory.size());
	out[directory.size()] = 0;
	return out;
}

extern "C" void wrmainFree(void* pointer) {
	std::free(pointer);
}

extern "C" int32_t wrmainCtorBoot(HINSTANCE module) {
	char* directory = wrmainSelfDirectory(module);
	if (directory == nullptr) {
		return -1;
	}
	std::string conf(directory);
	wrmainFree(directory);
	conf += "\\" + std::string(wrx::CONFIG_FILE);
	return wrxr_ctor_boot(conf.c_str());
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
	if (reason == DLL_PROCESS_ATTACH) {
		// Windows loader-lock discipline: never wait on the new thread here.
		// The poller thread is started detached by the Rust core; it touches
		// only file mapping APIs and never loads libraries, which keeps the
		// attach sequence deadlock-free with a static CRT. Boot always:
		// Java wrote the wrx.conf sidecar before System.load; only the
		// poller is transport-gated (FFM drives the wr_* entry points).
		wrmainCtorBoot(instance);
	}
	return TRUE;
}

#else

// The directory the shared object itself was loaded from, via dladdr on this
// translation unit's own code address.
static std::string selfDirectory() {
	Dl_info info{};
	if (dladdr(reinterpret_cast<void*>(&selfDirectory), &info) == 0 || info.dli_fname == nullptr) {
		return {};
	}
	const std::string full(info.dli_fname);
	const size_t slash = full.find_last_of("/\\");
	return slash == std::string::npos ? std::string(".") : full.substr(0, slash);
}

__attribute__((constructor)) static void wrConstructor() {
	const std::string directory = selfDirectory();
	if (directory.empty()) {
		return;
	}
	// Boot always: Java wrote the wrx.conf sidecar before System.load, so
	// the server comes up for both transports; only the poller is
	// transport-gated (FFM submits through the wr_* entry points instead).
	// wrxr_ctor_boot latches: a second constructor run (a re-extracted
	// library copy loaded into the same process) is a no-op.
	(void)wrxr_ctor_boot((directory + "/" + wrx::CONFIG_FILE).c_str());
}

#endif

// ---------------------------------------------------------------------------
// Keepalive: force the linker to pull every Rust object that defines a wr_*
// symbol into the shared library, so the exports Java's SymbolLookup
// (libraryLookup → find) needs are always present in the final artifact.
// Without a reference from a C++ translation unit, an unreferenced archive
// member is never extracted, and wr_boot would silently vanish from the .so.
// ---------------------------------------------------------------------------

