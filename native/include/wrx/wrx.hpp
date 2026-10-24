// SPDX-License-Identifier: MIT
//
// wrx.hpp — the byte-layout contract of the Java ↔ native exchange.
//
// Design (see docs/NATIVE_VULKAN.md for the full rationale):
//
//   * JNI is gone. The single native library is loaded with System.load purely
//     for its constructor-time boot (POSIX) / DllMain boot (Windows); every byte
//     exchanged with Java travels through memory-mapped files created by Java
//     before the library loads.
//
//   * The transport that maps those files and drains the queues is the Rust
//     core (native/rust/wrxr, protocol.rs mirrors this header byte for byte);
//     the C++ side (wrcore_domain.cpp) owns the registry + wrobj + wrvk and
//     answers through the wrcore_* C ABI.
//   * Two transports share one protocol:
//       - SHARED_MEMORY (default): a C++ poller thread drains the message
//         queues and records frames; Java rings a sequence doorbell and waits
//         a bounded time for the ack.
//       - PANAMA_FFM (JDK 22+): Java calls the wr_* C-ABI entry points
//         directly; the same slots are processed synchronously on the calling
//         thread. No poller, no races.
//   * Synchronisation is sequence counters with acquire/release ordering
//     (seqlock for the single-slot frame buffer, SPSC bump queues with
//     sequence counters for builds and results). No locks are shared across
//     the boundary.
//
// Byte layout contract (little-endian). Java mirrors every constant in
// org.mtr.nativeobj.WrxRegion, and the Rust core mirrors them in
// rust/wrxr/src/protocol.rs — three sides, one contract.
//
// PROTOCOL VERSION 2 — "zero-Java model rendering under Vulkan":
//   * The per-frame texture table is gone. Textures live in a native pool
//     (Vulkan images wrvk owns itself), uploaded once through
//     MSG_UPLOAD_TEXTURE; only animated (mcmeta) textures stay external and
//     travel as per-frame handle overrides.
//   * Frame items carry the RAW pose matrix; the native side computes the
//     model-view (header view × pose), the inverse-transpose normal matrix
//     and the Kd×brightness colour itself. Java's per-item work is one
//     16-float copy.
//   * MSG_UPLOAD_MESH lets Java-side mesh sources (Blockbench, the javagl
//     fallback) feed the same native GPU pipeline, so EVERY optimised-model
//     draw under Vulkan is recorded by C++.

#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// The Vulkan recorder (defined in wrvk.hpp) attaches itself to the server.
// Declared at global scope so it resolves to ::wrvk::WrvkRecorder everywhere.
namespace wrvk {
class WrvkRecorder;
}

namespace wrx {
// ---------------------------------------------------------------------------
// Region file names (all inside the per-session temp directory; the C++ side
// learns the directory from the wrx.conf sidecar written before System.load).
// ---------------------------------------------------------------------------

inline constexpr const char* CONTROL_FILE = "control.bin";
inline constexpr const char* BUILD_FILE = "build.bin";
inline constexpr const char* RESULTS_FILE = "results.bin";
inline constexpr const char* FRAME_FILE = "frame.bin";
inline constexpr const char* CONFIG_FILE = "wrx.conf";

// ---------------------------------------------------------------------------
// control.bin — fixed 4096-byte page of counters and status.
// ---------------------------------------------------------------------------

inline constexpr uint32_t CONTROL_MAGIC = 0x57525831; // "WRX1"
inline constexpr uint32_t PROTOCOL_VERSION = 2;
inline constexpr uint32_t CONTROL_SIZE = 4096;

inline constexpr uint32_t FLAG_TRANSPORT_FFM = 1u << 0; // 0 = shm poller, 1 = ffm calls
inline constexpr uint32_t FLAG_POLLER_STARTED = 1u << 1;
inline constexpr uint32_t FLAG_VULKAN_READY = 1u << 2;
inline constexpr uint32_t FLAG_ERROR = 1u << 3;
inline constexpr uint32_t FLAG_SHUTDOWN = 1u << 4;

struct ControlBlock {
        std::atomic<uint32_t> magic;
        std::atomic<uint32_t> protocolVersion;
        std::atomic<uint32_t> flags;
        std::atomic<uint32_t> errorCode;
        std::atomic<uint64_t> nativeHeartbeat; // C++ poller/pulse, monotonic
        std::atomic<uint64_t> javaHeartbeat;   // Java frame counter
        std::atomic<uint64_t> buildSeq;         // messages published by Java
        std::atomic<uint64_t> buildAck;         // messages processed by C++
        std::atomic<uint64_t> buildResultSeq;   // result batches published by C++
        std::atomic<uint64_t> buildResultAck;  // result batches consumed by Java
        std::atomic<uint64_t> frameSeq;         // frames published by Java
        std::atomic<uint64_t> frameAck;         // frames recorded by C++
        std::atomic<uint64_t> frameErrorCount;  // frames that failed to record
        std::atomic<uint64_t> reserved[9];
};
static_assert(sizeof(ControlBlock) <= CONTROL_SIZE, "control block must fit one page");

inline constexpr uint32_t ERR_NONE = 0;
inline constexpr uint32_t ERR_BAD_MAGIC = 1;
inline constexpr uint32_t ERR_BAD_PROTOCOL = 2;
inline constexpr uint32_t ERR_OVERFLOW = 3;     // a message/stream exceeded its budget
inline constexpr uint32_t ERR_INCONSISTENT = 4; // malformed message
inline constexpr uint32_t ERR_VULKAN = 5;       // the draw path reported a Vulkan error
inline constexpr uint32_t ERR_TIMEOUT = 6;      // the other side stopped heartbeating

// ---------------------------------------------------------------------------
// bump queues (build.bin Java→C++, results.bin C++→Java).
//
// Layout: [u64 writePos][u64 readPos][u64 capacity][payload...]. The writer
// publishes writePos with release after writing bytes; the reader consumes,
// then publishes readPos with release. The queue resets to zero only while
// writePos == readPos (both sides observe it), which keeps wrap-around out of
// the protocol entirely — build traffic is batchy, so waiting for a full drain
// before reset is fine.
// ---------------------------------------------------------------------------

struct BumpHeader {
        std::atomic<uint64_t> writePos;
        std::atomic<uint64_t> readPos;
        std::atomic<uint64_t> capacity;
};
static_assert(sizeof(BumpHeader) == 24, "bump header layout");

inline constexpr uint32_t MESSAGE_HEADER_SIZE = 8; // u32 type + u32 size

// build.bin message types
inline constexpr uint32_t MSG_INIT_VULKAN = 1;
inline constexpr uint32_t MSG_LOAD_SHADERS = 2;
inline constexpr uint32_t MSG_LOAD_OBJ = 3;
inline constexpr uint32_t MSG_INSTANCE = 4;
inline constexpr uint32_t MSG_FREE_MODELS = 5;
// Shutdown request through the queue itself (used by the shared-memory
// transport on JDKs without the FFM API, where no wr_shutdown downcall can
// exist): sets the server's shutdown-requested flag; the poller observes it
// after the current processBuilds pass and performs the shutdown outside
// every lock. The generic per-message build ack doubles as the handshake, so
// Java can unmap its side safely once buildAck reaches the sent sequence.
inline constexpr uint32_t MSG_SHUTDOWN = 6;
// v2: the native texture pool. Java uploads RGBA8 pixel rows (top-left
// origin); wrvk owns the VkImage/View/Sampler + a staging buffer and executes
// the upload inside the next frame's command buffer. A repeated key
// re-uploads (dynamic textures: PIDS displays, route maps).
inline constexpr uint32_t MSG_UPLOAD_TEXTURE = 7;
// v2: retire textures whose models were freed (resource reload).
inline constexpr uint32_t MSG_FREE_TEXTURE = 8;
// v2: upload a mesh built on the Java side (Blockbench quads, javagl fallback
// triangles) into the native GPU pipeline. Reply mirrors RES_INSTANCE.
inline constexpr uint32_t MSG_UPLOAD_MESH = 9;

// results.bin message types
inline constexpr uint32_t RES_MODELS = 1;   // reply to LOAD_OBJ
inline constexpr uint32_t RES_INSTANCE = 2; // reply to INSTANCE
inline constexpr uint32_t RES_ERROR = 3;    // reply to any failed request

// ---------------------------------------------------------------------------
// frame.bin — single-slot seqlocked draw list (v2).
//
// Java writes: seq = seq|1 (odd, release), payload, seq+1 (even, release).
// C++ reads: sample seq until stable and even, copy payload, re-sample.
// With the FFM transport the call is synchronous so the seqlock never
// actually contends, but the same code runs on both transports.
//
// v2: the draw items carry RAW pose matrices and reference textures by
// native pool key; the texture table is replaced by a small per-frame list
// of EXTERNAL texture handles (animated textures VulkanMod still owns).
// ---------------------------------------------------------------------------

inline constexpr uint32_t FRAME_MAGIC = 0x46524D32; // "FRM2"

// Budgets; the overflow error path keeps a run-away pack from wedging the game.
inline constexpr uint32_t FRAME_MAX_ITEMS = 65536;
// External (animated) textures resolved through VulkanMod per frame. The
// static pool has no per-frame cap — it is bounded by MSG budget checks at
// upload time (see wrcore: MAX_TEXTURES).
inline constexpr uint32_t FRAME_MAX_EXTERNAL = 64;

// GPU vertex layout = wrobj's upload layout, used verbatim: float x,y,z,u,v
// (20 bytes) + 4 packed snorm8 normal bytes (x,y,z + pad) = 24-byte stride.
// SPIR-V bindings: location 0 = vec3 float at 0, location 1 = vec2 float at
// 12, location 2 = vec3 snorm8 at 20. The Kd colour is per-material, so it
// travels in the draw item, not per vertex.
inline constexpr uint32_t VERTEX_BYTES = 24;

// Frame payload right after the seq word.
struct FrameHeader {
        uint32_t magic;
        uint32_t itemCount;
        uint32_t externalTextureCount; // entries in the override table below
        uint32_t reserved0;
        float view[16];           // camera view (RenderSystem snapshot), column-major
        float projection[16];     // column-major, JOML get(float[]) order
        float fogColor[4];
        float fogStart;
        float fogEnd;
        float lightDir0[3];       // view space, vanilla entity-light convention
        float lightDir1[3];
        uint32_t viewportWidth;
        uint32_t viewportHeight;
        uint32_t reserved1a;
        uint32_t reserved1b;
        uint64_t cmdBuffer;       // VulkanMod's current primary command buffer
        uint64_t renderPass;      // the render pass the cmd buffer is inside
        uint64_t reserved2;
        uint64_t reserved3;
        // followed by: external[externalTextureCount], items[itemCount]
};
static_assert(sizeof(FrameHeader) == 240, "frame header v2 layout");

// One animated texture resolved through VulkanMod for this frame only:
// items reference it by key, and the recorder substitutes these handles for
// the pool entry (a pool entry with the same key, if any, is shadowed).
struct FrameExternal {
        uint32_t key;
        uint32_t reserved;
        uint64_t image;
        uint64_t view;
        uint64_t sampler;
};
static_assert(sizeof(FrameExternal) == 32, "frame external layout");

// One draw. Java copies the pose straight out of the PoseStack; every piece
// of rendering math (MV = view×pose, N = transpose(inverse(MV3×3)),
// colour = Kd×brightness) is computed natively at record time.
struct FrameItem {
        uint32_t meshId;
        uint32_t textureKey; // native pool key (or external override key)
        uint32_t stage;      // org.mtr.resource.RenderStage ordinal (0..4)
        uint32_t light;      // packed lightmap int, raw — native derives brightness
        float pose[16];      // column-major raw pose (PoseStack.last().pose())
};
static_assert(sizeof(FrameItem) == 80, "frame item v2 layout");

// Push constants stay byte-identical to v1: vec4 colour + mat4 MV + 3×vec4 N
// = 128 bytes, exactly the Vulkan guaranteed minimum. Only the producer
// moved: C++ computes them at record time.

inline constexpr uint32_t FRAME_EXTERNAL_OFF = sizeof(FrameHeader);
inline constexpr uint32_t FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) + sizeof(FrameExternal) * FRAME_MAX_EXTERNAL;

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
//              u32 kdR, u32 kdG, u32 kdB, u32 kdA (each 0..255),
//              u32 nameByteCount, name bytes (material key incl. options)
//   }
//   The non-split build uses the same group name as the javagl loader's
//   addModel("", ...) call: the empty string.
//
// INSTANCE request: u32 requestId, u32 sourceModelId, u32 flip, f32 dx, dy, dz
// Reply RES_INSTANCE: u32 requestId, u32 instanceModelId, u32 meshCount,
//   per mesh { u32 meshId, float aabb[6] } — one mesh per source material; a
//   placed model may therefore own several mesh ids (Java keeps them all).
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
// ---------------------------------------------------------------------------

struct MeshInfo {
        uint32_t meshId;
        uint32_t vertexCount;
        uint32_t indexCount;
        float aabb[6];
        uint8_t kd[4];
        std::string name; // material group name; Java parses stage/texture options
};

// ---------------------------------------------------------------------------
// Host handles carried by MSG_INIT_VULKAN:
//   u64 instance, physicalDevice, device, queue, renderPass
//   u32 queueFamily, colorFormat, depthFormat, sampleCount, maxPushConstants
// ---------------------------------------------------------------------------

struct HostHandles {
        uint64_t instance;
        uint64_t physicalDevice;
        uint64_t device;
        uint64_t queue;
        uint32_t queueFamily;
        uint64_t renderPass;
        uint32_t colorFormat;
        uint32_t depthFormat;
        uint32_t sampleCount;
        uint32_t maxPushConstants;
        bool valid;
};

// ---------------------------------------------------------------------------
// GpuMesh — the GPU mesh referenced by draw items: wrobj's 24-byte vertex
// layout, kept as raw bytes so the result of wr_obj_builder_build can be
// adopted without a repack. Shared between a source model and its identity
// instances; freed when the last referencing model is freed. (Lives at
// namespace level — wrvk and the domain both use it; the transport side that
// fills it is now the Rust core.)
// ---------------------------------------------------------------------------

struct GpuMesh {
        std::vector<uint8_t> vertices; // VERTEX_BYTES per vertex
        std::vector<uint32_t> indices;
};

} // namespace wrx
