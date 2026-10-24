# Changelog

Concise, newest first. Tag scheme changed at `4.1.0-beta-2.yanyang.4` (previously `YYMTR-4.1.0-beta.N`); the jars are drop-in upgrades of one another.

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
