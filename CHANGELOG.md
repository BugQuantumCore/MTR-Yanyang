# Changelog

Concise, newest first. Tag scheme changed at `4.1.0-beta-2.yanyang.4` (previously `YYMTR-4.1.0-beta.N`); the jars are drop-in upgrades of one another.

## 4.1.0-beta-2.yanyang.6 (2026-10-26)

**Hard crash when turning Beryl shaders off — fixed**
- Attaching the native Vulkan recorder **mid-session** — in the very frame Beryl hands the frame back to VulkanMod, while its render passes and command buffers are still mid-transition — crashed the game at the driver level with **no Minecraft crash report and no JVM hs_err** (VulkanMod 0.6.7 + Beryl 0.2.0-alpha). The native boot is now **latched off for the whole session when Beryl's shaders are driving the frame**: a session that starts with shaders on keeps the Java renderer after they are toggled off, instead of dying. Reconnecting with shaders off boots the native pipeline cleanly, as always on plain VulkanMod.
- The Beryl-toggle model rebuild now fires on **renderer-owner transitions only** (Java GL ↔ native Vulkan): with the native pipeline latched off, a toggle reuses the already-correct GL models instead of a multi-second full rebuild, and the log line names the renderer that actually owns the next rebuild.
- JDK 22+ on the shared-memory transport no longer logs a scary `wr_shutdown downcall unavailable` NPE on every boot — the FFM arena is created for the shutdown lookup too (the queue shutdown path remains the fallback).

**AI passengers glowing under shaders — fixed**
- On-board riders (AI passengers **and** real players) were drawn with a constant full-bright lightmap (`DEFAULT_LIGHT`): vanilla renders that as flat lighting, but shader pipelines (Beryl) read max lightmap coordinates as emissive — passengers visibly **glowed in tunnels and at night** while the car body around them dimmed correctly. Riders now sample the packed world light at their actual position (+1 Y), matching the platform/walking passenger path.

## 4.1.0-beta-2.yanyang.5 (2026-10-26)

**AI passenger skins — every passenger wears a genuine premium account's skin**
- The skin source is now a bundled pool of **102 genuine Mojang premium accounts**, each verified live against `api.mojang.com` + `sessionserver.mojang.com` (name + immutable UUID pair, skin dimensions checked); no more skin-site guesswork.
- Each AI passenger deterministically hashes to one account, so a passenger keeps "its" premium skin across frames and sessions.
- Skins are fetched **UUID-first** (rename-proof, one profile request per account instead of two) by a 4-worker pool sharing one `HttpClient`, politely spaced for the Mojang rate limits.
- Fixed-size **slot pool** with in-place upgrades: every slot starts on a vanilla default and is swapped to its account's live skin as fetches land — no skin hopping, lock-free reads on the render thread.
- Disk cache (12 h TTL by default) with **stale-cache fallback** — offline clients still wear premium skins from the last successful fetch.
- `config/mtr_passenger_skins.json` is auto-created with documented defaults; online skins are **ON by default** and can be disabled, pointed at a CustomSkinLoader site, or replaced by an explicit name list.
- Legacy **64x32 skins are converted with the exact vanilla recipe** (verified instruction-for-instruction against the 1.21.1 `HttpTexture.processLegacySkin` and 1.21.4 `SkinTextureDownloader.processLegacySkin` bytecode — the twelve mirrored `copyRect` calls, the transparent bottom-half fill, the opaque-region fixups and the Notch hat hack), so classic accounts like the bundled Mojang originals render correctly.

**Native pipeline (critical)**
- Fixed the Java→native message wire format: `publish()` was passing the whole `[u32 type][body]` array as the payload while the header already carries the type, shifting **every** native message parse by 4 bytes (garbage requestIds → 60 s timeouts per model; garbage Vulkan handles in `INIT_VULKAN`). The native path could never have worked end-to-end from Java; the payload now matches the Rust reader byte-for-byte.
- Deferred teardown was dead code (`disable()`/`shutdown()` nulled the runtime before `tickTeardown()` could read it) — poller threads, mappings, native models and GPU texture pools leaked per disable/reconnect. Teardown now parks the runtime in a dedicated slot; `shutdown()` re-arms the boot latch so reconnects re-boot cleanly.
- `initialise()` failures between region creation and publication no longer leak the shared-memory region + a live poller thread; the MTL loader sizes UTF-8 byte-exactly (no more `BufferOverflowException` fallback on non-ASCII paths); per-frame property/reflection probes hoisted to class init; dead `VulkanFrameState` removed.

**Rendering & memory**
- Fixed the WorkerThread culling loop re-arm: it spawned an extra never-exiting loop every ~101 frames (~2100/hour, all contending the texture-queue lock).
- `NewOptimizedModel` GL vertex buffers are now closed on resource reload via a live-instance registry (previously **the entire previous model set's VAO+VBO ids leaked per F3+T / pack change / Beryl toggle**); the per-draw shader-colour save/restore reuses a render-thread scratch.
- Phone-pose arm yaw leaked onto every subsequent passenger (twisted arms after any phone watcher) — the shared-model arm yaw is reset in the walk pose.
- Eye-candy OBJ models never dimmed at night/in tunnels (packed light fed into the raw-brightness path); signal node-position scans are cached for 500 ms (were 891 block scans per signal per side per frame); door-open scans are gated by render distance; PIDS row splitting no longer recompiles a regex per row; per-frame `ResourceLocation` allocations hoisted; ground-height cache keys bit-packed (no more rare collisions); passenger animation/render caches clear on reload.

**Sound & client**
- Vehicle looping sounds are distance-gated (48 blocks): far-away cars no longer permanently occupy the ~32-channel OpenAL pool and starve the ones the player can actually hear (missing tracks at busy stations).
- BVE motor sounds no longer crash the render loop on malformed packs (`[Motor]` entries without `train.dat` motor rows → `Math.clamp` on an empty list).
- The Resource Pack Creator's "speed" test no longer leaks a full looping voice-set per click; DynamicTextureCache font loading no longer spins forever on a missing/corrupt font (bounded retries + logical-font fallback — the single worker thread stays alive); reloadable-texture holders release their replaced GL textures; `ServerRailPaths.stop()` can no longer abort the server-stop chain.
- slf4j logging for the shaded simulation core works at **both** build layers (TSC shadowJar keeps its `log4j-slf4j2-impl` provider; the mod's re-shadow no longer minimizes it away) — simulation-core logs were silently swallowed before.

**Simulation core (Transport-Simulation-Core fork)**
- `VehicleExtraData.copy` no longer serializes the entire path per dirty vehicle per client per tick just to discard all but a slice (the #1 CPU hot spot on populated servers).
- The simulator's catch-up loop is bounded (30 s wall + interrupt check) — a slow world can no longer wedge `Main.stop()` forever (the "not responding" hang).
- Stale walking/route edges no longer accumulate across graph refreshes.

**Verification**
- Both stonecutter variants of the new skin manager were javac-type-checked against accurate 1.21.1/1.21.4 signatures; the legacy conversion is cross-verified against both clients' bytecode. Full module audits (nativeobj, render/model, sound/client/mixin/data, TSC) were performed before fixing; every fix was re-checked line-by-line against the audit findings.

## 4.1.0-beta-2.yanyang.4 (2026-10-24)

**AI passengers**
- Age variation: ~85 % adults (±3 % height jitter), ~15 % children (small body, near-adult-sized head) — crowds are no longer identical clones.
- Phone watchers now actually hold a dark phone that materialises as the pose blends in.
- Ground-height scans for platform/street passengers are cached per world column for 500 ms (shared across passengers) — removes thousands of per-frame block lookups at busy stations.

**BVE sound**
- Fixed scheduled tracks (brake, door, compressor, air) playing N× per tick — stacking voices caused phasing distortion and starved the sound engine, dropping tracks. *(Upstream bug since 4.0.)*
- Implemented the flange (curve-squeal) track, previously permanently muted: gain follows the car's yaw rate and speed; the flange entry follows the rail run sound index. All `sound.cfg` [Flange] entries are wired up, not just the first.
- Run/flange fallback indices are now deterministic (lowest defined key) instead of hash-map iteration order.

**Vulkan / Beryl (shipped in dev builds since beta.3, first tagged here)**
- Protocol v2: all model rendering math (OBJ/BlockBench) runs natively — zero per-draw Java work on the Vulkan path; native pipeline activates whenever Beryl's shaders are off.
- UniversalCraft GUI state queries are VulkanMod-safe via conditional mixins (fixes JVM crashes on MTR/Elementa screens under VulkanMod 0.6.x).
- Beryl-shaded models keep their correct world positions (per-draw VRenderSystem sync); toggling Beryl live rebuilds models for the owning renderer.

**Meta**
- New README, this changelog, and a per-module predicted-efficiency table in `docs/PERFORMANCE.md` §6.

## YYMTR-4.1.0-beta.3 (2026-10-23)

- Native Vulkan pipeline shipped for all 8 platforms (Windows x64/ARM64, Linux x64/ARM64, macOS ARM64, Android, iOS, iPadOS); every release jar CI-verified to contain all 8 natives + 6 SPIR-V blobs.
- VulkanMod 0.6.7 compatibility: `[wrvk]` pipeline initialises through the version-tolerant host bridge; falls back to the (also correct) Java renderer if native attach fails.

## YYMTR-4.1.0-beta.2 (2026-10-22)

- Initial 1.21.x release of the YanYang port: upstream 4.1.0-beta-2 (stonecutter, 1.21.1/1.21.4 × Fabric/NeoForge) plus the YanYang 4.0.5 performance series, the AI passenger system (walking animation, phone pose, skins), rail geometry/route-map caches, dynamic-data sync split, bounded parallel path computation, and the first native Vulkan OBJ renderer (3 desktop platforms).
