// SPDX-License-Identifier: MIT
//
// ffi.rs — the library's public C ABI (the wr_* entry points) and the
// constructor-boot latch.
//
// Java's NativeRuntime resolves exactly these symbols through
// SymbolLookup.libraryLookup on JDK 22+, or relies on the constructor boot
// through System.load otherwise. Signatures are plain integers and C
// strings so Linker.downcallHandle can call them without any native
// metadata ceremony. There is no JNI anywhere.

use crate::protocol::PROTOCOL_VERSION;
use crate::server::{start_poller, stop_poller, SERVER};
use crate::shmem::parse_config_file;
use core::ffi::{c_char, CStr};
use core::sync::atomic::{AtomicBool, Ordering};

static BOOT_ATTEMPTED: AtomicBool = AtomicBool::new(false);
static BOOTED: AtomicBool = AtomicBool::new(false);
static BOOT_MUTEX: std::sync::Mutex<()> = std::sync::Mutex::new(());

const VERSION_STRING: &[u8] = b"wrvk 1.0.0 (native Vulkan OBJ pipeline)\0";

#[no_mangle]
pub extern "C" fn wr_boot(config_path_utf8: *const c_char) -> i32 {
	let _guard = BOOT_MUTEX.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
	BOOT_ATTEMPTED.store(true, Ordering::Relaxed);
	if BOOTED.load(Ordering::Relaxed) {
		return 0;
	}
	if config_path_utf8.is_null() {
		return -1;
	}
	let config_path = unsafe { CStr::from_ptr(config_path_utf8) };
	let Ok(config_path) = config_path.to_str() else {
		return -1;
	};

	// The host-init callback creates the Vulkan loader dispatch and the
	// recorder (both C++ objects); install it before boot so
	// MSG_INIT_VULKAN can wire everything up. Idempotent.
	unsafe { crate::domain::wrmain_install_host_init() };

	if !SERVER.boot_with_config(config_path) {
		return -2;
	}
	BOOTED.store(true, Ordering::Relaxed);
	0
}

#[no_mangle]
pub extern "C" fn wr_submit_builds() -> i32 {
	SERVER.process_builds() as i32
}

#[no_mangle]
pub extern "C" fn wr_submit_frame() -> i32 {
	SERVER.process_frame() as i32
}

#[no_mangle]
pub extern "C" fn wr_status() -> i64 {
	if !BOOTED.load(Ordering::Relaxed) {
		return -1;
	}
	SERVER.pulse();
	if SERVER.booted() {
		1
	} else {
		0
	}
}

#[no_mangle]
pub extern "C" fn wr_shutdown() -> i32 {
	let _guard = BOOT_MUTEX.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
	if !BOOTED.load(Ordering::Relaxed) {
		return 0;
	}
	// Order preserved from the old C++ wr_shutdown: stop the poller first
	// (its teardown path may itself call Server::shutdown), then shut the
	// server down (frees every mesh through the recorder, unmaps), then
	// destroy the recorder and its dispatch.
	stop_poller();
	SERVER.set_poller_flag(false);
	SERVER.shutdown();
	unsafe { crate::domain::wrmain_teardown_recorder() };
	BOOTED.store(false, Ordering::Relaxed);
	0
}

#[no_mangle]
pub extern "C" fn wr_abi_version() -> i32 {
	PROTOCOL_VERSION as i32
}

#[no_mangle]
pub extern "C" fn wr_version_string() -> *const c_char {
	VERSION_STRING.as_ptr().cast()
}

/// The constructor-time boot (called from wrmain.cpp's POSIX constructor or
/// DllMain with the wrx.conf path next to the library). Latched: a second
/// constructor run — a re-extracted library copy loaded into the same
/// process — is a no-op (the per-frame Java boot attempts are latched on
/// the Java side as well, this covers the native side).
///
/// Boot always: Java wrote the sidecar before System.load, so the server
/// comes up for both transports; only the poller is transport-gated (the
/// FFM transport drives the wr_* entry points instead).
#[no_mangle]
pub extern "C" fn wrxr_ctor_boot(config_path_utf8: *const c_char) -> i32 {
	if BOOT_ATTEMPTED.swap(true, Ordering::Relaxed) {
		return 0;
	}
	if config_path_utf8.is_null() {
		return -1;
	}
	let config_path = unsafe { CStr::from_ptr(config_path_utf8) };
	let Ok(config_path) = config_path.to_str() else {
		return -1;
	};

	unsafe { crate::domain::wrmain_install_host_init() };

	let Some(parsed) = parse_config_file(std::path::Path::new(config_path)) else {
		return -2;
	};
	if !SERVER.boot_with_config(config_path) {
		return -2;
	}
	BOOTED.store(true, Ordering::Relaxed);
	if !parsed.transport_ffm {
		start_poller(&SERVER);
	}
	0
}
