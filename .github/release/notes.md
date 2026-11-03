MTR-Yanyang 4.1.0-beta-2.yanyang.16 — drop-in upgrade for 4.1.0-beta-2.yanyang.15 (1.21.1 & 1.21.4, Fabric & NeoForge).

The headline: falling onto a stationary train's roof now lands you **on** it. Plus the NeoForge build comes alive under Vulkan, and FML can finally read its dependency declarations.

## The bug you reported 落到车顶被撞飞

You drop from the sky onto a parked train — and instead of standing on the roof, you're flung off sideways. That was the yanyang.15 depenetration's blind spot, and the code explains it exactly: it resolved **every** overlap along the axis of minimum penetration, decided by comparing box centres — it had no way to tell *how* an overlap came to be. A landing penetrates deep in a single tick (up to terminal velocity, ~3.9 blocks); once your feet pass the hull box's centre, the up direction disqualifies itself, a horizontal axis wins, and the correction ejects you off the train while you're still falling. Reading exactly as "knocked flying by a parked train".

## The fix: entry-aware depenetration 按入箱方向解算

`EntityMoveMixin` now snapshots your bounding box at the **head** of `Entity#move` and hands it to the tail depenetration. The overlap is classified before it is resolved:

- **Feet at or above a box's top surface before the move** (within one vanilla step height, so walking between adjacent cars' roofs on a gradient steps up instead of shoving you sideways) — the overlap is the tail of a fall and resolves **straight up onto the surface**: you are snapped to the roof, the fall velocity is cancelled, the fall distance reset and ground contact flagged. You actually *stand* on the roof — jumping, walking friction, step-up and fall damage all behave.
- **Everything entered from the side** — a wall, a crowd at platform level, a train sweeping into a track-level player (the 撞飞 impulse) — keeps the shipped bounded minimum-axis eviction, byte-identical.
- Landing onto an AI passenger's head behaves the same way (they are solid, after all), while the platform-level crowd is untouched (their 1.8-tall boxes never reach the step-height window).

Verified against the real 1.21.1 **and** 1.21.4 official Mojang mappings and simulated across 17 scenarios: deep terminal-velocity falls, roof-edge landings, step-up/down between adjacent cars' roofs, gravity-sink re-landing while standing, jumps, lag-spike deep falls, doorway-gap falls, platform-side blocks and track-level sweeps.

## NeoForge under Vulkan NeoForge 版 Vulkan 兼容

The native wrvk pipeline was silently dead on NeoForge: the "is VulkanMod loaded" probe asked the **Fabric loader** for mod id `vulkanmod` — a probe that soft-fails on NeoForge, where no Fabric loader exists on the class path. The probe now routes through a loader-agnostic class-presence check, verified against both NeoForge ports — yiyuyan/VulkanModNeoForge (1.21.4) and TrulyRin/VulkanMod-Reforged (1.21.1). Both keep the whole `net.vulkanmod.*` tree, so the existing detection, the beacon-beam stage redirect, the UniversalCraft GL-state fabrication and the reflection chain all match them unchanged. The one real port difference — the GL emulation layer ships as `net.vulkanmod.gl.GlTexture` instead of official 0.6.x's `VkGlTexture` — is now a third, optional texture route, so native-model textures resolve under the ports too.

**Radiance** (the Vulkan C++ ray-tracing renderer, 1.21.4 Fabric) is now detected and the native pipeline explicitly stays off under it; its two VulkanMod-specific workarounds are deliberately skipped there, with the evidence recorded in the changelog.

## mods.toml dependencies FML 依赖声明修复

Inherited from upstream's stonecutter branch: the dependency table headers were written `["dependencies.mtr"]` — **quoted**, which TOML defines as a single literal key, not a `dependencies` table containing a `mtr` array. FML's `ModInfo` reads dependencies with a nested path query that matched nothing, so both declared dependencies silently vanished — no version gating, no mis-install warnings. The headers are now the official MDK form `[[dependencies.mtr]]`; the `minecraft` version range is enforced again, so installing the wrong-MC jar produces FML's normal version error instead of a later, harder-to-read crash. The mixin declarations were already correct — the collision-box mixins were never affected.

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.16+<version>-<loader>.jar`. No config changes; all fixes are always on. The roof-landing fix composes with Lithium at its defaults — it lives in the same `Entity#move` fallback Lithium cannot bypass.
