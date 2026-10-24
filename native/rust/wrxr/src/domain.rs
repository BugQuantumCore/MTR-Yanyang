// SPDX-License-Identifier: MIT
//
// domain.rs — the Rust-side declarations of the C++ domain entry points
// (defined in native/src/wrcore_domain.cpp, declared in
// native/include/wrcore/wrcore.h). The reply struct mirrors WrcoreReply
// field for field; server.rs owns the canonical copy used by the transport
// and re-exports it here for readability.

use crate::server::WrcoreReply;

extern "C" {
	pub fn wrcore_init_vulkan(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_load_shaders(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_load_obj(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_make_instance(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_free_models(data: *const u8, size: u32) -> WrcoreReply;

	// v2 — the native texture pool (no reply; the build ack is the
	// handshake) and the Java-side mesh upload (RES_INSTANCE-shaped reply).
	pub fn wrcore_upload_texture(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_free_texture(data: *const u8, size: u32) -> WrcoreReply;
	pub fn wrcore_upload_mesh(data: *const u8, size: u32) -> WrcoreReply;

	// Records one frame through the attached recorder (header + externals +
	// items all point into the caller's verified snapshot; the items sit
	// contiguously after the last external).
	pub fn wrcore_record_frame(header: *const core::ffi::c_void, externals: *const u8, items: *const u8) -> i32;

	// Boot/shutdown hooks owned by the C++ side (wrmain.cpp).
	pub fn wrmain_install_host_init();
	pub fn wrmain_teardown_recorder();

	// Domain teardown + introspection.
	pub fn wrcore_shutdown_domain();
	pub fn wrcore_live_models() -> u32;
	pub fn wrcore_live_meshes() -> u32;
	pub fn wrcore_live_textures() -> u32;
}
