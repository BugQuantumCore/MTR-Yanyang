// SPDX-License-Identifier: MIT
//
// wrcore.h — the C ABI between the Rust transport core (wrxr) and the C++
// domain (wrcore_domain.cpp).
//
// The split: Rust owns everything that touches the shared-memory files
// (region mapping, control page, build/results queues, the seqlocked frame
// slot, the poller thread and the wr_* entry points Java sees); C++ owns
// the model/mesh registry, the wrobj OBJ builder and the wrvk Vulkan
// recorder. The two halves talk only through this header, so either side
// can be re-verified in isolation (cargo test drives exactly these
// functions; the CMake targets drive the same code through the final
// library).

#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Handler outcomes. The values mirror the old C++ Server::processBuilds
// semantics exactly:
//   OK      — the message was well-formed and processed; ack it and continue
//   POISON  — the stream is broken; publish the error, advance past the
//             message, ack it, and stop the pass
enum {
	WRCORE_OK = 0,
	WRCORE_POISON = 1,
};

// The result of one build-message handler.
//
//   resultPtr/resultLen are valid until the NEXT wrcore_* call (the Rust
//   poller/FFM threads serialise handler invocations, and the reply bytes
//   are published to the results queue before any further handler runs).
//   resultType uses the wrx RES_* values (1 = RES_MODELS, 2 = RES_INSTANCE,
//   3 = RES_ERROR).
//
//   bumpResultSeq is the "a result batch was published" signal that the old
//   C++ handlers gave by incrementing control.buildResultSeq themselves.
//
//   vulkanReady is set when MSG_INIT_VULKAN attached a recorder; the Rust
//   side turns it into FLAG_VULKAN_READY on the control page.
typedef struct WrcoreReply {
	int32_t status;
	uint32_t errorCode;       // control-page error code for POISON (0 = none)
	uint32_t resultType;      // 0 = no reply, else RES_*
	uint32_t bumpResultSeq;   // 1 when the reply bumped buildResultSeq
	uint32_t vulkanReady;     // 1 when the recorder attached during init
	uint32_t _pad;
	const uint8_t* resultPtr;
	uint64_t resultLen;
} WrcoreReply;

// Build-message handlers. Each takes the raw payload (after the 8-byte
// message header) and returns the outcome struct above.
WrcoreReply wrcore_init_vulkan(const uint8_t* data, uint32_t size);
WrcoreReply wrcore_load_shaders(const uint8_t* data, uint32_t size);
WrcoreReply wrcore_load_obj(const uint8_t* data, uint32_t size);
WrcoreReply wrcore_make_instance(const uint8_t* data, uint32_t size);
WrcoreReply wrcore_free_models(const uint8_t* data, uint32_t size);

// Records one frame through the attached recorder. header points to a
// snapshot of the 176-byte wrx::FrameHeader (the Rust side re-declares the
// identical little-endian layout); textures and items point into the same
// verified snapshot. Returns 1 when the frame was recorded.
int32_t wrcore_record_frame(const void* header, const uint8_t* textures, const uint8_t* items);

// The host-init hook: called from inside wrcore_init_vulkan after the
// handles are parsed but before the recorder is attached, so wrmain can
// create the Vulkan loader dispatch and the recorder (both C++ objects).
typedef void (*WrcoreHostInit)(const void* hostHandles, void* user);
void wrcore_set_host_init_callback(WrcoreHostInit callback, void* user);

// Teardown: frees every live mesh through the recorder and clears the
// registry. The Rust Server::shutdown calls this before unmapping its side.
void wrcore_shutdown_domain(void);

// Introspection for tests and diagnostics.
uint32_t wrcore_live_models(void);
uint32_t wrcore_live_meshes(void);

#ifdef __cplusplus
}
#endif
