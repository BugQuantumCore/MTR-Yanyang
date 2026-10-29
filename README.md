# MTR-Yanyang

**Minecraft Transit Railway — YanYang 优化分支** · A [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) fork, optimised and extended by the YanYang Technology Group.

MTR lets you build your own transport network with automated trains, boats, cable cars, and planes. This fork carries the YanYang performance work onto the modern `stonecutter` codebase and adds a native Vulkan rendering pipeline and living station crowds.

| | |
|---|---|
| **Minecraft** | 1.21.1 / 1.21.4 |
| **Loaders** | Fabric · NeoForge |
| **Version scheme** | `<upstream>-yanyang.N` (e.g. `4.1.0-beta-2.yanyang.4`) |
| **Downloads** | [Releases](../../releases) — four jars per release, one per version × loader |

## Highlights

- **Performance series** — client FPS and server TPS improvements ported from the YanYang 4.0.5 releases: split dynamic-data sync, block-entity culling, rail geometry and route-map caches, bounded parallel path computation, render-queue pooling.
- **Native Vulkan model pipeline (Vulkan-gated)** — when [VulkanMod](https://modrinth.com/mod/vulkanmod) is detected, OBJ/BlockBench models render through a Rust + C++ pipeline over shared memory (Panama FFM exchange, no JNI): all model transform math runs natively; the original OpenGL path is untouched otherwise. Ships 8 prebuilt natives: Windows x64/ARM64, Linux x64/ARM64, macOS ARM64, Android ARM64, iOS/iPadOS ARM64.
- **VulkanMod & Beryl compatible** — conditional mixins keep MTR/Elementa GUIs and Beryl-shaded world models correct under VulkanMod 0.6.x; fully inert when it's absent.
- **AI passenger crowds** — deterministic client-side passengers walk between homes, landmarks and platforms and ride trains: vanilla-accurate walk cycles, idle head movement, phone pose **with a phone in hand**, mixed ages (mostly adults, some children), 9 vanilla skins plus optional online skins.
- **BVE sound system fixes** — scheduled tracks no longer stack N× per tick, and the flange (curve squeal) track is implemented for the first time.

## Requirements

- Minecraft **1.21.1** or **1.21.4**, with **Fabric Loader + Fabric API** or **NeoForge**.
- Optional: [VulkanMod](https://modrinth.com/mod/vulkanmod) 0.6.x for the native pipeline.

## Documentation

- [Changelog](CHANGELOG.md)
- [Per-module performance](docs/PERFORMANCE.md) · [Native Vulkan pipeline](docs/NATIVE_VULKAN.md) · [Rendering pipeline](docs/RENDERING_PIPELINE.md)
- [Architecture](docs/ARCHITECTURE.md) · [Build](docs/BUILD.md) · [YanYang porting notes](docs/YANYANG_PORT.md)
- Upstream wiki (gameplay): <https://wiki.minecrafttransitrailway.com/start>

## Building from source

Requires JDK 21+ and Node.js 24.

```bash
# 1) Build the Transport-Simulation-Core fork into mavenLocal (org.mtr:transport-simulation-core)
#    The TSC fork lives on the tsc-1.21.x branch of this repository — based on the
#    Yanyang Technology Group optimized TSC, with all patches (build plumbing and the
#    rail easement curves) as real commits. No patch step needed any more.
git clone -b tsc-1.21.x https://github.com/BugQuantumCore/MTR-Yanyang Transport-Simulation-Core
cd Transport-Simulation-Core
./gradlew -p buildSrc publishToMavenLocal && ./gradlew publishToMavenLocal

# 2) Build the mod (each version × loader is a stonecutter project)
cd ../MTR-Yanyang
./gradlew :1.21.1-fabric:build :1.21.1-neoforge:build :1.21.4-fabric:build :1.21.4-neoforge:build
```

Jars are produced under `build/release/`.

## Credits & Licence

Upstream **Minecraft Transit Railway** by [Jonathan Ho](https://github.com/jonafanho) and contributors — this fork tracks upstream and merges its changes. Mod code is [MIT](LICENSE); bundled fonts are under the Open Font License ([Noto](https://fonts.google.com/noto), [正風毛筆字體](https://github.com/max32002/masafont)).
