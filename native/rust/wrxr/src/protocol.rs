// SPDX-License-Identifier: MIT
//
// protocol.rs — the byte-layout mirror of include/wrx/wrx.hpp.
//
// This file and the C++ header are the two halves of one contract; Java
// mirrors the same constants in org.mtr.nativeobj.WrxRegion. Every offset
// below is verified against the C++ static_asserts by the size checks at
// the bottom of this file, and the golden rule for any change is: all three
// sides move together or not at all.
//
// Layout summary (little-endian):
//   control.bin  fixed 4096-byte page of counters and flags
//   build.bin    [u64 writePos][u64 readPos][u64 capacity][messages...]
//   results.bin  same bump-queue layout, C++/Rust writes, Java reads
//   frame.bin    [u64 seq][FrameHeader][FrameTexture*1024][FrameItem*65536]

#![allow(dead_code)]

use core::ffi::c_char;

// ---------------------------------------------------------------------------
// Region file names (inside the per-session temp directory).
// ---------------------------------------------------------------------------

pub const CONTROL_FILE: &[u8] = b"control.bin";
pub const BUILD_FILE: &[u8] = b"build.bin";
pub const RESULTS_FILE: &[u8] = b"results.bin";
pub const FRAME_FILE: &[u8] = b"frame.bin";
pub const CONFIG_FILE: &[u8] = b"wrx.conf";

// ---------------------------------------------------------------------------
// control.bin — fixed 4096-byte page of counters and status.
// ---------------------------------------------------------------------------

pub const CONTROL_MAGIC: u32 = 0x57525831; // "WRX1"
pub const PROTOCOL_VERSION: u32 = 1;
pub const CONTROL_SIZE: usize = 4096;

pub const FLAG_TRANSPORT_FFM: u32 = 1 << 0; // 0 = shm poller, 1 = ffm calls
pub const FLAG_POLLER_STARTED: u32 = 1 << 1;
pub const FLAG_VULKAN_READY: u32 = 1 << 2;
pub const FLAG_ERROR: u32 = 1 << 3;
pub const FLAG_SHUTDOWN: u32 = 1 << 4;

// Field offsets inside the control page.
pub const OFF_CONTROL_MAGIC: usize = 0;
pub const OFF_CONTROL_PROTOCOL: usize = 4;
pub const OFF_CONTROL_FLAGS: usize = 8;
pub const OFF_CONTROL_ERROR_CODE: usize = 12;
pub const OFF_CONTROL_NATIVE_HEARTBEAT: usize = 16;
pub const OFF_CONTROL_JAVA_HEARTBEAT: usize = 24;
pub const OFF_CONTROL_BUILD_SEQ: usize = 32;
pub const OFF_CONTROL_BUILD_ACK: usize = 40;
pub const OFF_CONTROL_BUILD_RESULT_SEQ: usize = 48;
pub const OFF_CONTROL_BUILD_RESULT_ACK: usize = 56;
pub const OFF_CONTROL_FRAME_SEQ: usize = 64;
pub const OFF_CONTROL_FRAME_ACK: usize = 72;
pub const OFF_CONTROL_FRAME_ERROR_COUNT: usize = 80;
pub const OFF_CONTROL_RESERVED: usize = 88; // 9 u64s, 72 bytes, total 160

pub const ERR_NONE: u32 = 0;
pub const ERR_BAD_MAGIC: u32 = 1;
pub const ERR_BAD_PROTOCOL: u32 = 2;
pub const ERR_OVERFLOW: u32 = 3; // a message/stream exceeded its budget
pub const ERR_INCONSISTENT: u32 = 4; // malformed message
pub const ERR_VULKAN: u32 = 5; // the draw path reported a Vulkan error
pub const ERR_TIMEOUT: u32 = 6; // the other side stopped heartbeating

// ---------------------------------------------------------------------------
// bump queues (build.bin Java→native, results.bin native→Java).
//
// Layout: [u64 writePos][u64 readPos][u64 capacity][payload...]. The writer
// publishes writePos with release after writing bytes; the reader consumes,
// then publishes readPos with release. The queue resets to zero only while
// writePos == readPos (both sides observe it), which keeps wrap-around out
// of the protocol entirely — build traffic is batchy, so waiting for a
// full drain before reset is fine.
// ---------------------------------------------------------------------------

pub const BUMP_HEADER_SIZE: usize = 24;
pub const OFF_BUMP_WRITE_POS: usize = 0;
pub const OFF_BUMP_READ_POS: usize = 8;
pub const OFF_BUMP_CAPACITY: usize = 16;

pub const MESSAGE_HEADER_SIZE: usize = 8; // u32 type + u32 size

// build.bin message types
pub const MSG_INIT_VULKAN: u32 = 1;
pub const MSG_LOAD_SHADERS: u32 = 2;
pub const MSG_LOAD_OBJ: u32 = 3;
pub const MSG_INSTANCE: u32 = 4;
pub const MSG_FREE_MODELS: u32 = 5;
// Shutdown request through the queue itself (the shared-memory transport on
// JDKs without the FFM API, where no wr_shutdown downcall can exist): sets
// the shutdown-requested flag; the poller observes it after the current
// processBuilds pass and performs the teardown outside every lock.
pub const MSG_SHUTDOWN: u32 = 6;

// results.bin message types
pub const RES_MODELS: u32 = 1; // reply to LOAD_OBJ
pub const RES_INSTANCE: u32 = 2; // reply to INSTANCE
pub const RES_ERROR: u32 = 3; // reply to any failed request

// ---------------------------------------------------------------------------
// frame.bin — single-slot seqlocked draw list.
//
// Java writes: seq = seq|1 (odd, release), payload, seq+1 (even, release).
// The reader samples seq until stable and even, snapshots the payload,
// re-samples, then records from the snapshot (never from the live mapping).
// ---------------------------------------------------------------------------

pub const FRAME_MAGIC: u32 = 0x46524D31; // "FRM1"

// Budgets; the overflow error path keeps a run-away pack from wedging the game.
pub const FRAME_MAX_ITEMS: usize = 65536;
pub const FRAME_MAX_TEXTURES: usize = 1024;

// GPU vertex layout = wrobj's upload layout, used verbatim: float x,y,z,u,v
// (20 bytes) + 4 packed snorm8 normal bytes = 24-byte stride.
pub const VERTEX_BYTES: usize = 24;

// Frame payload right after the seq word.
#[repr(C)]
pub struct FrameHeader {
	pub magic: u32,
	pub item_count: u32,
	pub texture_count: u32,
	pub reserved0: u32,
	pub projection: [f32; 16], // column-major, JOML get(float[]) order
	pub fog_color: [f32; 4],
	pub fog_start: f32,
	pub fog_end: f32,
	pub light_dir0: [f32; 3], // view space, vanilla entity-light convention
	pub light_dir1: [f32; 3],
	pub viewport_width: u32,
	pub viewport_height: u32,
	pub depth_format: u32,
	pub reserved1: u32,
	pub cmd_buffer: u64, // VulkanMod's current primary command buffer
	pub render_pass: u64, // the render pass the cmd buffer is inside
	pub reserved2: u64,
	pub reserved3: u64,
	// followed by: textures[textureCount], items[itemCount]
}

#[repr(C)]
pub struct FrameTexture {
	pub image: u64,
	pub view: u64,
	pub sampler: u64,
	pub format: u32,
	pub mip_levels: u32,
	pub layout: u32,
	pub reserved: u32,
}

#[repr(C)]
pub struct FrameItem {
	pub mesh_id: u32,
	pub texture_index: u32,
	pub stage: u32, // org.mtr.resource.RenderStage ordinal (0..4)
	pub color_packed: u32, // r<<24 | g<<16 | b<<8 | a
	pub model_view: [f32; 16], // column-major, = RenderSystem MV * pose
	pub normal_matrix: [f32; 12], // 3 rows padded as vec4
}

pub const FRAME_SEQ_SIZE: usize = 8;
pub const FRAME_HEADER_OFF: usize = 8;
pub const FRAME_TEXTURE_OFF: usize = FRAME_HEADER_OFF + core::mem::size_of::<FrameHeader>();
// Items offset RELATIVE TO THE PAYLOAD (i.e. after the 8-byte seq word) —
// mirrors wrx.hpp's FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) +
// sizeof(FrameTexture) * FRAME_MAX_TEXTURES exactly. The absolute offset
// from the start of frame.bin is FRAME_HEADER_OFF + FRAME_ITEMS_OFF_BASE.
pub const FRAME_ITEMS_OFF_BASE: usize = core::mem::size_of::<FrameHeader>()
	+ core::mem::size_of::<FrameTexture>() * FRAME_MAX_TEXTURES;

// ---------------------------------------------------------------------------
// LOAD_OBJ request payload (after the message header):
//   u32 requestId, u32 flags (bit0 splitModel, bit1 flipTextureV)
//   u32 objByteCount, bytes...
//   u32 mtlCount, per mtl: u32 nameByteCount, bytes, u32 contentByteCount, bytes
//
// Reply RES_MODELS (one reply covers the whole document):
//   u32 requestId, u32 groupCount, per group {
//     u32 nameByteCount, name bytes, u32 modelId, u32 meshCount,
//     per mesh: u32 meshId, u32 vertexCount, u32 indexCount, float aabb[6],
//              u32 kdR, u32 kdG, u32 kdB, u32 kdA, u32 nameByteCount, name bytes }
//
// INSTANCE request: u32 requestId, u32 sourceModelId, u32 flip, f32 dx, dy, dz
// Reply RES_INSTANCE: u32 requestId, u32 instanceModelId, u32 meshCount,
//   per mesh { u32 meshId, float aabb[6] }
//
// FREE_MODELS: u32 requestId, u32 count, u32 modelIds...
// No reply: request ids are monotonic, so no flow-control echo is needed.
//
// INIT_VULKAN payload: u64 instance, physicalDevice, device, queue, renderPass,
//   u32 queueFamily, colorFormat, depthFormat, sampleCount, maxPushConstants
//
// LOAD_SHADERS payload: u32 count, per shader { u32 key, u32 byteCount, bytes }
// ---------------------------------------------------------------------------

#[inline]
pub fn round_up8(value: u32) -> u32 {
	(value + 7) & !7u32
}

// ---------------------------------------------------------------------------
// Compile-time layout verification against the C++ static_asserts
// (wrx.hpp: sizeof(FrameItem) == 128; FrameHeader == 176; FrameTexture == 40).
// ---------------------------------------------------------------------------

const _: () = assert!(core::mem::size_of::<FrameHeader>() == 176, "FrameHeader layout");
const _: () = assert!(core::mem::size_of::<FrameTexture>() == 40, "FrameTexture layout");
const _: () = assert!(core::mem::size_of::<FrameItem>() == 128, "FrameItem layout (push constant budget)");
const _: () = assert!(core::mem::align_of::<FrameHeader>() <= 8);
const _: () = assert!(core::mem::size_of::<c_char>() == 1);

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn frame_offsets_match_wrx_hpp() {
		// FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) + 40 * 1024 = 41136
		assert_eq!(FRAME_ITEMS_OFF_BASE, 41_136);
		assert_eq!(FRAME_TEXTURE_OFF, 8 + 176);
		// the java-side total: 8 + FRAME_ITEMS_OFFSET + 65536*128
		assert_eq!(8 + FRAME_ITEMS_OFF_BASE + FRAME_MAX_ITEMS * 128, 8_429_752);
	}
}
