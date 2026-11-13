# Native Vulkan pipeline — transport protocol v3

The pipeline combines Java resource/session integration, Rust shared-memory
transport and a C++ OBJ/Vulkan recorder. It records model draws into VulkanMod's
active command buffer. It does not replace the host's frame graph, texture
manager or GPU submission/fence ownership.

The byte contract is mirrored by `native/include/wrx/wrx.hpp`,
`native/rust/wrxr/src/protocol.rs` and `org.mtr.nativeobj.WrxRegion`.
All three must ship from the same revision. Transport **v3** rejects older
native binaries; the existing **FRM2** frame payload layout remains unchanged.

## Renderer selection

| Environment | Model submission |
|---|---|
| OpenGL | Java VBO path, with explicit binding and shader-state restoration |
| Plain VulkanMod | Native recorder when the host bridge resolves successfully |
| VulkanMod with active Beryl pipeline | Java path through the host's shader pipelines |
| Active Radiance | Model/stage batches through `RenderType.draw(MeshData)` so Radiance captures the geometry |

Backend transitions require model resources to be rebuilt; runtime-failure
fallback coverage still needs validation. The Radiance path transforms
positions and normals into the submitted vertex stream and retains packed
lighting. This is capture-compatible batching, not a claim of hardware
instancing or a measured frame-rate improvement.

The host bridge checks the native handles and pipeline-cache invalidation
fields before activation. Unknown host APIs fail closed to the Java path.
Compatibility still needs game-level validation for each supported renderer
version, resource pack and driver; unit tests cannot establish visual parity.

## Ownership and synchronisation

- Shared memory carries model-build commands and replies. The Rust poller may
  process this work asynchronously.
- `FLAG_RENDER_THREAD_FRAMES` (`1 << 5`) prohibits the poller from recording
  frames. Every frame submission completes synchronously on the Java render
  thread, before Minecraft records another command into that buffer.
- The default shared-memory mode uses two small JNI entry points for frame
  submission and shutdown on **JDK 21+**. No geometry crosses JNI arguments.
- The optional **JDK 22+** FFM mode uses the final `FunctionDescriptor` API.
  The two modes share the same native handlers and shared-memory payload.
- Java publishes shared counters with acquire/release VarHandles, paired with
  Rust atomic accesses. Rust bounds every queue against its actual mapping.
- `FrameHeader.reserved1a` bit 0 (`FRAME_FLAG_HOST_DYNAMIC_STATE`) preserves
  the active host viewport and scissor. Native draws do not overwrite them.
- On return, Java invalidates VulkanMod's pipeline-binding cache. It does not
  reset the host's descriptor pools or take over command submission.

## Resources and draw flow

At load time, `MSG_LOAD_OBJ` carries OBJ/MTL bytes and group filters;
`MSG_UPLOAD_MESH` carries Java-generated Blockbench/fallback vertex lists;
`MSG_INSTANCE` requests placements. Group filters use their real length,
per-material indices are rebased into each mesh, and placements retain the
source material index. One placement transforms the entire model once, rather
than duplicating the complete model once per material.

Production textures are **owned by Minecraft/VulkanMod**. Java assigns stable
keys and provides current image/view/sampler handles for each distinct texture
used in a pass. This covers static, dynamic and animated textures, preserving
the host's filtering, upload and reload behaviour. It avoids PNG re-decoding,
CPU pixel staging and duplicate GPU texture ownership.

`MSG_UPLOAD_TEXTURE` remains an internal, recording-test-covered API. Java does
not use it: buffer-to-image copies cannot be recorded inside the active render
pass. Do not re-enable it without a separate transfer phase and explicit host
fence/queue coordination.

For each draw, C++ prepares the model-view/normal matrix and material/light
colour, then records a GPU vertex/index draw. Consecutive equal poses reuse
matrix calculations; consecutive equal meshes reuse bindings. A reusable,
generation-stamped hash table provides texture/descriptor lookup. Rust retains
frame-snapshot storage without clearing it before overwriting the payload.
Vertex transformation, lighting and rasterisation execute in the shaders.
No new compute-shader AI or collision path is introduced by this audit.

## Frame payload

After the 8-byte seqlock word:

```
FrameHeader (240 bytes)
  u32 magic = 0x46524D32 (FRM2), itemCount, externalCount, reserved0
  f32 view[16], projection[16]
  f32 fogColor[4], fogStart, fogEnd, lightDir0[3], lightDir1[3]
  u32 viewportWidth, viewportHeight, reserved1a (flags), reserved1b
  u64 cmdBuffer, renderPass, reserved2, reserved3
FrameExternal (32 bytes each)
  u32 key, reserved; u64 image, view, sampler
FrameItem (80 bytes each)
  u32 meshId, textureKey, stage, packedLight; f32 pose[16]
```

Budgets are 65,536 draw items and 4,096 texture references. The frame mapping
reserves `8 + 240 + 4096 * 32 + 65536 * 80 = 5,374,200` bytes. Actual externals
and items are packed consecutively, and only their occupied span is copied.

The std140 frame UBO has offsets `0/64/80/84/96/112` for projection, fog colour,
fog start/end and two light vectors. Its payload is 128 bytes; slot stride
respects `minUniformBufferOffsetAlignment`. Buffer allocation filters memory
types using both required property flags and `memoryTypeBits`.

## Verification and remaining boundaries

Run builds/tests through `.github/workflows/native_1.21.x.yml`. The workflow
builds desktop and cross-platform libraries, runs C++/Rust regressions on
supported runners, compiles/validates GLSL, and verifies every packaged JAR.
No build or test was executed locally during the 2026-11-14 audit.

Recording-dispatch tests cover layouts, handle widths, memory selection,
pipeline creation, texture/state behaviour and domain meshes. Transport tests
cover mapping bounds, publication, ownership flags, startup and teardown.
Java tests cover placement selection, region layout and real FFM binding.
Successful results must be read from the matching GitHub Actions revision.

Remaining architectural limits include descriptor/UBO ring reuse without an
explicit host fence-token contract, the 24-byte uploaded vertex layout's lack
of a per-vertex colour channel, native/Java fallback rebuilds after runtime
failure, and visual validation of translucent stages and renderer transitions.
See [the audit ledger](AUDIT_2026-11-14.md) for scope and follow-up work.
