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
//   frame.bin    [u64 seq][FrameHeader][FrameExternal*used][FrameItem*used]
//
// The frame payload is CONTIGUOUS: the externals start at
// FRAME_EXTERNAL_OFF and the items follow IMMEDIATELY after the last
// FrameExternal. FRAME_MAX_EXTERNAL only bounds the region budget
// (FRAME_ITEMS_OFF_BASE), never the item placement.

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
pub const PROTOCOL_VERSION: u32 = 2;
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
// of the protocol entirely — build traffic is batchy, so waiting for a full
// drain before reset is fine.
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
// v2: the native texture pool. Java uploads RGBA8 pixel rows (top-left
// origin); wrvk owns the VkImage/View/Sampler + a staging buffer and executes
// the upload inside the next frame's command buffer. A repeated key
// re-uploads (dynamic textures: PIDS displays, route maps).
pub const MSG_UPLOAD_TEXTURE: u32 = 7;
// v2: retire textures whose models were freed (resource reload).
pub const MSG_FREE_TEXTURE: u32 = 8;
// v2: upload a mesh built on the Java side (Blockbench quads, javagl fallback
// triangles) into the native GPU pipeline. Reply mirrors RES_INSTANCE.
pub const MSG_UPLOAD_MESH: u32 = 9;

// results.bin message types
pub const RES_MODELS: u32 = 1; // reply to LOAD_OBJ
pub const RES_INSTANCE: u32 = 2; // reply to INSTANCE
pub const RES_ERROR: u32 = 3; // reply to any failed request

// ---------------------------------------------------------------------------
// frame.bin — single-slot seqlocked draw list (v2).
//
// Java writes: seq = seq|1 (odd, release), payload, seq+1 (even, release).
// The reader samples seq until stable and even, snapshots the payload,
// re-samples, then records from the snapshot (never from the live mapping).
//
// v2: the draw items carry RAW pose matrices and reference textures by
// native pool key; the texture table is replaced by a small per-frame list
// of EXTERNAL texture handles (animated textures VulkanMod still owns).
// ---------------------------------------------------------------------------

pub const FRAME_MAGIC: u32 = 0x46524D32; // "FRM2"

// Budgets; the overflow error path keeps a run-away pack from wedging the game.
pub const FRAME_MAX_ITEMS: usize = 65536;
// External (animated) textures resolved through VulkanMod per frame. The
// static pool has no per-frame cap — it is bounded by MSG budget checks at
// upload time (see wrcore: MAX_TEXTURES).
pub const FRAME_MAX_EXTERNAL: usize = 64;

// GPU vertex layout = wrobj's upload layout, used verbatim: float x,y,z,u,v
// (20 bytes) + 4 packed snorm8 normal bytes = 24-byte stride.
pub const VERTEX_BYTES: usize = 24;

// Frame payload right after the seq word.
#[repr(C)]
pub struct FrameHeader {
	pub magic: u32,
	pub item_count: u32,
	pub external_texture_count: u32, // entries in the override table below
	pub reserved0: u32,
	pub view: [f32; 16], // camera view (RenderSystem snapshot), column-major
	pub projection: [f32; 16], // column-major, JOML get(float[]) order
	pub fog_color: [f32; 4],
	pub fog_start: f32,
	pub fog_end: f32,
	pub light_dir0: [f32; 3], // view space, vanilla entity-light convention
	pub light_dir1: [f32; 3],
	pub viewport_width: u32,
	pub viewport_height: u32,
	pub reserved1a: u32,
	pub reserved1b: u32,
	pub cmd_buffer: u64, // VulkanMod's current primary command buffer
	pub render_pass: u64, // the render pass the cmd buffer is inside
	pub reserved2: u64,
	pub reserved3: u64,
	// followed by: external[externalTextureCount], items[itemCount]
}

// One animated texture resolved through VulkanMod for this frame only:
// items reference it by key, and the recorder substitutes these handles for
// the pool entry (a pool entry with the same key, if any, is shadowed).
#[repr(C)]
pub struct FrameExternal {
	pub key: u32,
	pub reserved: u32,
	pub image: u64,
	pub view: u64,
	pub sampler: u64,
}

// One draw. Java copies the pose straight out of the PoseStack; every piece
// of rendering math (MV = view×pose, N = transpose(inverse(MV3×3)),
// colour = Kd×brightness) is computed natively at record time.
#[repr(C)]
pub struct FrameItem {
	pub mesh_id: u32,
	pub texture_key: u32, // native pool key (or external override key)
	pub stage: u32, // org.mtr.resource.RenderStage ordinal (0..4)
	pub light: u32, // packed lightmap int, raw — native derives brightness
	pub pose: [f32; 16], // column-major raw pose (PoseStack.last().pose())
}

pub const FRAME_SEQ_SIZE: usize = 8;
pub const FRAME_HEADER_OFF: usize = 8;
pub const FRAME_EXTERNAL_OFF: usize = FRAME_HEADER_OFF + core::mem::size_of::<FrameHeader>();
// Items offset RELATIVE TO THE PAYLOAD (i.e. after the 8-byte seq word) —
// mirrors wrx.hpp's FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) +
// sizeof(FrameExternal) * FRAME_MAX_EXTERNAL exactly. This is the REGION
// BUDGET, not the item placement: the externals are variable-length and the
// items follow immediately after the last one, so the absolute item offset
// is FRAME_EXTERNAL_OFF + externalTextureCount * 32, never this constant.
pub const FRAME_ITEMS_OFF_BASE: usize = core::mem::size_of::<FrameHeader>()
	+ core::mem::size_of::<FrameExternal>() * FRAME_MAX_EXTERNAL;

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
// UPLOAD_TEXTURE (v2): u32 key, u32 width, u32 height, u32 flags (0),
//   u8 pixels[width*height*4] — RGBA8, row-major, top-left origin.
//   Creates the pool entry (or re-uploads an existing one: same key, same or
//   new dimensions — the image is recreated when they change). No reply.
//
// FREE_TEXTURE (v2): u32 count, u32 keys...
// No reply.
//
// UPLOAD_MESH (v2): u32 requestId, u32 textureKey, u32 stage, u32 mode
//   (0 = triangles, 1 = quads), u32 vertexCount, u8 kd[4] (r, g, b, a;
//   white for Blockbench), then vertexCount vertices × 24 bytes in the GPU
//   layout: f32 x, y, z, u, v (little-endian) + 4 int8 snorm bytes
//   (x, y, z, pad) encoded as Java's (byte)(n * 127.0F) — truncation toward
//   zero. Quads fan-triangulate to (0,1,2)(0,2,3), triangles pass through.
//   Reply: RES_INSTANCE layout — u32 requestId, u32 modelId, u32 meshCount
//   (1), { u32 meshId, float aabb[6] }. A zero modelId means rejection
//   (bad counts / empty mesh) and Java falls back to the GL path.
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
// (wrx.hpp: sizeof(FrameHeader) == 240; FrameExternal == 32;
// FrameItem == 80).
// ---------------------------------------------------------------------------

const _: () = assert!(core::mem::size_of::<FrameHeader>() == 240, "FrameHeader v2 layout");
const _: () = assert!(core::mem::size_of::<FrameExternal>() == 32, "FrameExternal layout");
const _: () = assert!(core::mem::size_of::<FrameItem>() == 80, "FrameItem v2 layout");
const _: () = assert!(core::mem::align_of::<FrameHeader>() <= 8);
const _: () = assert!(core::mem::size_of::<c_char>() == 1);

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn frame_offsets_match_wrx_hpp() {
		// FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) + 32 * 64 = 2288
		assert_eq!(FRAME_ITEMS_OFF_BASE, 2_288);
		assert_eq!(FRAME_EXTERNAL_OFF, 8 + 240);
		// the java-side total: 8 + FRAME_ITEMS_OFFSET + 65536*80
		assert_eq!(8 + FRAME_ITEMS_OFF_BASE + FRAME_MAX_ITEMS * 80, 5_245_176);
	}
}
