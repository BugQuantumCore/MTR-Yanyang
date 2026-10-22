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
inline constexpr uint32_t PROTOCOL_VERSION = 1;
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

// results.bin message types
inline constexpr uint32_t RES_MODELS = 1;   // reply to LOAD_OBJ
inline constexpr uint32_t RES_INSTANCE = 2; // reply to INSTANCE
inline constexpr uint32_t RES_ERROR = 3;    // reply to any failed request

// ---------------------------------------------------------------------------
// frame.bin — single-slot seqlocked draw list.
//
// Java writes: seq = seq|1 (odd, release), payload, seq+1 (even, release).
// C++ reads: sample seq until stable and even, copy payload, re-sample.
// With the FFM transport the call is synchronous so the seqlock never
// actually contends, but the same code runs on both transports.
// ---------------------------------------------------------------------------

inline constexpr uint32_t FRAME_MAGIC = 0x46524D31; // "FRM1"

// Budgets; the overflow error path keeps a run-away pack from wedging the game.
inline constexpr uint32_t FRAME_MAX_ITEMS = 65536;
inline constexpr uint32_t FRAME_MAX_TEXTURES = 1024;

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
        uint32_t textureCount;
        uint32_t reserved0;
        float projection[16];     // column-major, JOML get(float[]) order
        float fogColor[4];
        float fogStart;
        float fogEnd;
        float lightDir0[3];       // view space, vanilla entity-light convention
        float lightDir1[3];
        uint32_t viewportWidth;
        uint32_t viewportHeight;
        uint32_t depthFormat;
        uint32_t reserved1;
        uint64_t cmdBuffer;       // VulkanMod's current primary command buffer
        uint64_t renderPass;      // the render pass the cmd buffer is inside
        uint64_t reserved2;
        uint64_t reserved3;
        // followed by: textures[textureCount], items[itemCount]
};

struct FrameTexture {
        uint64_t image;
        uint64_t view;
        uint64_t sampler;
        uint32_t format;
        uint32_t mipLevels;
        uint32_t layout;
        uint32_t reserved;
};

struct FrameItem {
        uint32_t meshId;
        uint32_t textureIndex;
        uint32_t stage;       // org.mtr.resource.RenderStage ordinal (0..4)
        uint32_t colorPacked; // r<<24 | g<<16 | b<<8 | a; Java composes Kd * brightness
        float modelView[16];  // column-major, = RenderSystem MV * pose
        // then 48 bytes of normal matrix (3 rows padded as vec4) — see wrvk
        float normalMatrix[12];
};

// Push constants: MV mat4 + colour vec4 + normal matrix 3×vec4 = 128 bytes,
// exactly the Vulkan guaranteed minimum, so no device limit is violated.
static_assert(sizeof(FrameItem) == 128, "frame item layout is the push constant layout");

inline constexpr uint32_t FRAME_TEXTURE_COUNT_OFF = sizeof(FrameHeader);
inline constexpr uint32_t FRAME_ITEMS_OFF_BASE = sizeof(FrameHeader) + sizeof(FrameTexture) * FRAME_MAX_TEXTURES;

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
