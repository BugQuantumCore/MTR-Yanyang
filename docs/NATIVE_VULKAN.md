# Native Vulkan pipeline (protocol v2 — "zero-Java model rendering")

This document describes how MTR-Yanyang renders its optimised models (OBJ
rail/vehicle/station models and Blockbench geometry) **entirely through
Rust + C++** whenever [VulkanMod](https://github.com/xCollateral/VulkanMod)
is driving the frame. The Java side is a thin data bridge: it provides
resource bytes at load time, one small frame header per frame, and one
16-float pose copy per queued draw. Every rendering computation — mesh
building, GPU upload, texture objects, model-view/normal matrices, colours,
pipeline state, command-buffer recording — happens natively.

The byte contract lives in `native/include/wrx/wrx.hpp` and is mirrored in
`native/rust/wrxr/src/protocol.rs` (Rust transport core) and
`org.mtr.nativeobj.WrxRegion` (Java): **three sides, one contract**.

## Activation and gating

| environment                         | what renders MTR models                                        |
|-------------------------------------|----------------------------------------------------------------|
| no VulkanMod (OpenGL)               | the original Java/OpenGL renderer — byte-identical, untouched  |
| VulkanMod, no shader pipeline       | **native pipeline** (this document)                            |
| VulkanMod + Beryl, shaders off      | **native pipeline** (this document) — Beryl is dormant          |
| VulkanMod + Beryl, shaders on       | Java renderer through Beryl's own pipelines (see below)        |

Activation is automatic and conservative (`NativeRuntime.initialise`): the
pipeline only boots when VulkanMod is detected and every Vulkan handle
resolves; any failure latches the session back to the Java renderer with one
log line. There is no user toggle — the graphics API being Vulkan *is* the
switch.

**Beryl gating is a runtime check, not mod presence.** Beryl being
*installed* is not the same as Beryl *driving the frame*: with its shaders
disabled, Beryl's `RenderingPipeline.beginRender` returns before touching
anything and the frame is plain VulkanMod. `VulkanCompat.isBerylActive()`
reads Beryl's own runtime flag (`RenderingPipeline.useShaderPipeline`,
fallback `BerylMod.CONFIG.shadersOn`) so:

* the boot defers — without latching — while Beryl's shaders are on, and
  boots the moment they are off (the deferral is one cached-field reflection
  read per frame);
* the render drain re-checks every pass, so Beryl's keybind toggle takes
  effect immediately;
* a mid-session toggle rebuilds the models for the new path
  (`MainRenderer.render` watches the state and calls
  `CustomResourceLoader.reload()`), because models are built for one path at
  load time — dual-path models (native mesh **and** GL vertex buffer, see
  `NewOptimizedModelGroup.build`) cover the transition window;
* `CustomResourceLoader.reload()` boots the runtime *before* parsing models,
  so every rebuild picks the native path from its very first model.

The original class-presence-only check disabled the native pipeline
permanently for anyone with the Beryl jar in their mods folder — even with
shaders off — which silently routed every model through the Java GL path
under VulkanMod (mispositioned/stretched geometry, missing parts).

**Beryl exception (by design):** Beryl swaps every entity-family shader for
its own G-buffer pipelines and composes the swapchain itself at the end of
the frame. Geometry recorded straight into VulkanMod's command buffer (the
native path) would either bypass Beryl's G-buffers entirely or be overwritten
by Beryl's fullscreen compose — so while Beryl's shaders are on, the Java
renderer draws MTR models through Beryl's own pipelines (with the
`VulkanFrameState` per-draw bridge keeping positions correct). Models are
shaded by Beryl there; the native path cannot offer that.

## The data flow (v2)

```
load time (Java → native, build queue)
  MSG_LOAD_OBJ        OBJ + MTL text ── wrobj parses/builds GPU meshes (C++)
  MSG_UPLOAD_MESH     Blockbench quads / javagl-fallback triangles (Java-side
                      vertex lists, 24 B GPU layout) ── same native pipeline
  MSG_UPLOAD_TEXTURE  RGBA8 pixels ── wrvk's own VkImage/view/sampler pool
  MSG_INSTANCE        translate/flip placements, GPU data aliased

per frame (Java → native, seqlocked frame slot)
  FrameHeader  view + projection (one 16-float copy each), fog, directional
               lights, viewport, VulkanMod's current cmd buffer + render pass
  FrameItem ×N meshId, textureKey, stage, RAW packed light, RAW pose (16 f32)
  FrameExternal ×≤64  animated (mcmeta) textures: VulkanMod's image/view/
               sampler handles for this frame only

native (Rust + C++)
  Rust wrxr    maps the region, drains the queues, snapshots the frame slot,
               drives the poller / wr_* FFM entry points
  wrcore (C++) model/mesh/texture registry, reply payloads
  wrvk (C++)   pipelines, per-mesh VkBuffers, the texture pool, per-item
               MV = view × pose, inverse-transpose normal matrix,
               Kd × brightness colour, push constants, draw recording into
               VulkanMod's command buffer
```

### What Java still does (the irreducible bridge)

* **Boot once**: extract the library, map the region, resolve VulkanMod's
  instance/device/queue/render-pass handles through reflection
  (`VulkanHostAccess`).
* **Load time**: read resource bytes (OBJ/MTL text, PNG pixels, Blockbench
  vertex lists) and publish them as build messages. `NativeTextureManager`
  assigns each texture a session-stable pool key, uploads static textures
  once, keeps animated (`.mcmeta`) textures external, and re-uploads dynamic
  textures (PIDS displays, route maps) when MTR's `DynamicTextureCache`
  regenerates them.
* **Per frame**: write ONE frame header (≈60 floats + two handle queries)
  and append raw draw items (a 16-float `Matrix4f.get` copy each). No JOML
  math, no reflection, no colour arithmetic per item.

### Matrix and colour semantics (byte-compatible with v1)

The v1 pipeline computed, in Java per item: `MV = RenderSystem view × pose`,
`N = transpose(inverse(MV₃ₓ₃))` and `colour = Kd × brightness` where
`brightness = isFullBrightness ? 1 : light / 0xF` (the RAW packed lightmap
int divided by 15, unclamped — anything ≥ 15 saturates). Protocol v2 moves
exactly these computations into the C++ recorder; the push-constant layout
(vec4 colour + mat4 MV + 3×vec4 N, 128 bytes) and the SPIR-V shaders are
byte-identical, and the wrvk suite unit-tests the math against hand-computed
JOML-semantics cases (rotation×translation, mirrors, non-uniform scale).

The full-brightness table follows `org.mtr.resource.RenderStage`:
`LIGHT`, `ALWAYS_ON_LIGHT`, `INTERIOR`, `INTERIOR_TRANSLUCENT` are full-bright;
only `EXTERIOR` uses the lightmap value.

### The native texture pool

Textures are wrvk-owned `VkImage`s (R8G8B8A8_UNORM, LINEAR filtering, REPEAT
addressing) fed by host-visible staging buffers. `MSG_UPLOAD_TEXTURE` carries
RGBA8 rows (top-left origin); the copy + layout transitions execute inside
the next frame's command buffer, so uploads never stall the render thread
beyond the standard build-ack handshake. A repeated key re-uploads (dynamic
textures reuse this path). `MSG_FREE_TEXTURE` retires entries at resource
reload with the same device-idle discipline as mesh frees.

Animated textures (a `.mcmeta` companion exists) never enter the pool:
VulkanMod animates them itself, so they are resolved per frame through
VulkanMod's GL-texture emulation layer and travel as `FrameExternal` handle
overrides; items reference them by the same stable key and the recorder
substitutes the per-frame handles.

## Frame slot layout (v2)

After the 8-byte seqlock word (`WrxRegion` owns the seq protocol):

```
FrameHeader (240 bytes)
  u32 magic 0x46524D32 "FRM2"   u32 itemCount   u32 externalCount   u32 r0
  f32 view[16]  (camera view, column-major, JOML get order)
  f32 projection[16]
  f32 fogColor[4]  f32 fogStart  f32 fogEnd
  f32 lightDir0[3] f32 lightDir1[3]
  u32 viewportWidth u32 viewportHeight  u32 r1a u32 r1b
  u64 cmdBuffer  u64 renderPass  u64 r2 u64 r3
FrameExternal × externalCount (32 bytes each, back-to-back)
  u32 key u32 reserved  u64 image  u64 view  u64 sampler
FrameItem × itemCount (80 bytes each, immediately after the last external)
  u32 meshId  u32 textureKey  u32 stage  u32 light  f32 pose[16]
```

Budgets: 65 536 items, 64 externals per frame; the region reserves
`8 + 240 + 64·32 + 65536·80` bytes (≈5.2 MB, down from v1's 8.4 MB).
`FRAME_MAX_EXTERNAL` bounds the budget only — the externals and items are
written back-to-back and the Rust reader snapshots exactly
`externalCount·32 + itemCount·80` contiguous bytes.

## Testing

* `wrvk_tests` (C++, 172 checks): texture-pool lifecycle (create/stage/
  re-upload dedup/free/leak-zero), native matrix math against hand-computed
  JOML-semantics cases, brightness/colour semantics, v2 frame end-to-end
  recording, external-handle overrides, plus the original pipeline/leak
  suite.
* `wrxr` (Rust, 11 tests): transport round-trips for the three new
  messages, v2 frame validation (old magic rejected, count overflows
  rejected), plus the original queue/poller/shutdown protocol suite.
* `wrctor_tests` (22 checks) and `wrobj_tests` (198 checks) — unchanged
  green.
* Java: the `org.mtr.nativeobj` package, the Blockbench/javagl integration
  points and the drain were compile-verified against the official 1.21.1
  and 1.21.4 Mojang mappings; CI builds all four loader/version targets.

## Historical note

v1 (4.1.0-beta.2/3) computed model-view matrices, normal matrices, colours
and per-frame texture tables in Java (`NativeFrameRecorder`), and resolved
every texture through VulkanMod's `VkGlTexture` reflection chain per frame.
v2 removes all of it — that Java code no longer exists on the Vulkan path.
