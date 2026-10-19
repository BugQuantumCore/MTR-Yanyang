# Native Vulkan OBJ pipeline (1.21.x)

The 1.20.x `wrgl` (OpenGL flat pass) pipeline has been rebuilt for 1.21.x as
**wrvk**: the same battle-tested `wrobj` parser/builder, a new memory-exchange
layer, and a Vulkan draw path that records into **VulkanMod's** frame.

## Architecture

```
Java (org.mtr.nativeobj)                    C++ (native/)
┌──────────────────────────┐               ┌──────────────────────────────┐
│ ObjModelLoader ──write──▶ wrx.conf + 4 mapped files ──▶ wrx::Server       │
│                          │  (shared memory; no JNI)    │  ├─ wrobj parse/build
│ MainRenderer drain ─────▶ frame slot (seqlock) ───────▶│  ├─ mesh store + upload
│                          │                            │  └─ wrvk recorder
│ NativeRuntime (detect,   │◀── results queue ──────────│     pipelines/descriptors/
│  transports, registry)   │                            │     push constants (128 B)
└──────────────────────────┘                            └──────────────────────────────┘
```

* **Activation** — automatic: the pipeline only turns on when VulkanMod is
  loaded and every Vulkan handle resolves (`NativeRuntime.initialise`,
  feature-detected through reflection; the fork's Java renderer stays exactly
  as-is everywhere else, including every GL-based install).
* **Transports** — the "Java 与 C++ 通信方式" setting:
  * *共享内存* (default): a C++ poller thread drains the queues; works on any
    JDK (Unsafe-level access before JDK 22). Highest throughput, tiny bounded
    wait per frame.
  * *Panama FFM* (JDK 22+): `Linker.downcallHandle` calls
    `wr_submit_builds`/`wr_submit_frame` directly — deterministic, no poller.
* **Protocol** — see `include/wrx/wrx.hpp`; every constant is mirrored in
  `org.mtr.nativeobj.WrxRegion`. Queue wraps are lossless (writer wraps only
  after a full drain; the reader resets whenever it sees `readPos > writePos`).
* **Draw semantics** — `FrameItem` IS the 128-byte push constant (colour + MV
  + inverse-transpose normal matrix), the frame UBO carries projection/fog/
  directional lights (read live from VulkanMod's `VRenderSystem`), and the
  emitted GLSL mirrors vanilla's entity/beacon shader chain (0.4+0.6·n·L two
  light accumulation, 0.1 alpha cutoff, linear fog).
* **Leak safety** — per-mesh `VkBuffer`+`VkDeviceMemory` with a refcounted
  alias for identity instances; frees retire after `vkDeviceWaitIdle`;
  `CustomResourceLoader.reload` frees every model before the rebuild; the
  `wrvk_tests` suite asserts every object counter returns to zero.

## Building and testing

```bash
native/build.sh wrobj_tests run   # 198 checks (parser/builder semantics)
native/build.sh wrx_tests   run   # 37 checks (protocol round-trips, wraps)
native/build.sh wrvk_tests  run   # 40 checks (command stream, leaks)
```

CI (`.github/workflows/native_1.21.x.yml`) builds the 3 desktop platforms with
CMake, compiles the GLSL to SPIR-V with glslang (validated by spirv-val),
packages `native/<platform>/wrvk.*` + `shaders/spirv/*.spv` into the mod jar
and runs the full Gradle test suite.

## Vendored headers

`include/vulkan/` + `include/vk_video/` are unmodified KhronosGroup
Vulkan-Headers; the library never links Vulkan at build time — the loader is
dlopen'd and resolved through `vkGetInstanceProcAddr` with the instance handle
Java passes, so the same binary loads on machines without any Vulkan runtime.
