# Native Vulkan OBJ pipeline (1.21.x)

The 1.20.x `wrgl` (OpenGL flat pass) pipeline has been rebuilt for 1.21.x as
**wrvk**: the same battle-tested `wrobj` parser/builder, a **Rust + C++**
memory-exchange core, and a C++ Vulkan draw path that records into
**VulkanMod's** frame.

The exchange transport is Rust (`rust/wrxr`, zero external dependencies):
region mapping, the control page, the bump queues, the seqlocked frame slot,
the poller thread and the `wr_*` C ABI. The C++ half (`wrcore_domain.cpp`)
owns the model registry and calls into `wrobj`/`wrvk`; the two halves talk
through the `wrcore_*` C ABI in `include/wrcore/wrcore.h`. Byte layouts stay
defined once — `include/wrx/wrx.hpp` is mirrored by `rust/wrxr/src/protocol.rs`
and by Java (`org.mtr.nativeobj.WrxRegion`).

## Architecture

```
Java (org.mtr.nativeobj)         Rust (rust/wrxr)                C++ (native/)
┌─────────────────────┐   ┌──────────────────────────┐   ┌──────────────────────┐
│ ObjModelLoader ─────┼──▶│ region + control page,   │──▶│ wrcore_domain:        │
│                     │   │ bump queues, seqlocked   │   │  model/mesh registry, │
│ MainRenderer drain ─┼──▶│ frame slot, poller,      │   │  reply payloads;      │
│                     │   │ wr_* C ABI (no JNI)      │   │  wrobj parse/build;   │
│ NativeRuntime ──────┼──▶│                          │──▶│ wrvk recorder:       │
│ (detect, transports,│   │ (system.load ctor boot   │   │  pipelines/descriptors/│
│  registry)          │   │  or wr_boot downcalls)   │   │  push constants (128 B)│
└─────────────────────┘   └──────────────────────────┘   └──────────────────────┘
```

* **Activation** — automatic: the pipeline only turns on when VulkanMod is
  loaded and every Vulkan handle resolves (`NativeRuntime.initialise`,
  feature-detected through reflection; the fork's Java renderer stays exactly
  as-is everywhere else, including every GL-based install).
* **Transports** — the "Java 与 C++ 通信方式" setting:
  * *共享内存* (default): the Rust poller thread drains the queues; works on
    any JDK (Unsafe-level access before JDK 22). Highest throughput, tiny
    bounded wait per frame.
  * *Panama FFM* (JDK 22+): `Linker.downcallHandle` calls
    `wr_submit_builds`/`wr_submit_frame` directly — deterministic, no poller.
* **Protocol** — see `include/wrx/wrx.hpp`; mirrored in
  `rust/wrxr/src/protocol.rs` and `org.mtr.nativeobj.WrxRegion`
  (three sides, one contract — the Rust port caught and fixed one
  absolute/relative offset drift during migration through its layout test). Queue wraps are lossless (writer wraps only
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
native/build.sh wrvk_tests  run   # 40 checks (command stream, leaks)
native/build.sh wrctor_tests run  # 22 checks (dlopen ctor boot smoke)
(cd native/rust/wrxr && cargo test --release --offline)
                                   # 60+ checks (transport protocol round-trips,
                                   #  poison advancement, wr_* ABI, poller)
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
