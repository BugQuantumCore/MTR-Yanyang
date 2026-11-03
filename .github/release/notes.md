MTR-Yanyang 4.1.0-beta-2.yanyang.17 — drop-in upgrade for 4.1.0-beta-2.yanyang.16 (1.21.1 & 1.21.4, Fabric & NeoForge).

Every Radiance release — including every loader and MC build — is now safe to run with the mod. Pre-0.1.6 builds no longer crash the JVM when an MTR screen opens.

## The audit 全版本审计

Radiance's distribution turned out to be far wider than the "1.21.4 Fabric" the yanyang.16 detection was verified against — Modrinth ships **0.1.6-alpha for 1.21.1/1.21.4/1.20.1 × fabric/forge/neoforge** (9 platform jars), plus 0.1.0–0.1.5 for fabric-1.21.4. Every jar was inspected remotely (HTTP-range zip reads, a few hundred KB each):

- The detection probes (`com.radiance.Radiance` / `com.radiance.client.RadianceClient`) are present in **all 9 releases** — the yanyang.16 detection hits every build.
- No build bundles `net.vulkanmod` classes — no false-positive VulkanMod probe.
- The actual `@Mixin` targets — parsed from each shipped jar's bytecode annotations, both mojang-named (NeoForge) and intermediary-named (Fabric) builds — **never overlap** the mod's own mixin targets (`Entity`, `EntityGetter`, `ServerPlayer`, `EditBox`, the MTR-internal classes, UniversalCraft). No apply-time conflict is possible on any combination.

## The one real incompatibility 0.1.6 之前没有 OpenGL 上下文

Verified against the shipped 0.1.0–0.1.5 jars' bytecode: `WindowMixins` creates the GLFW window with `GLFW_NO_API`, redirects `glfwMakeContextCurrent` to nothing and stubs `GL.createCapabilities` to return null. Unlike VulkanMod there is also **no GL virtualization** — Radiance only intercepts Minecraft's `GlStateManager`/`RenderSystem`, not raw LWJGL calls. MTR's screens draw through Elementa → UniversalCraft, whose `URenderPass` is built on raw `glGet*`/`glDraw*` — opening any MTR screen (dashboard, ticket machine, PIDS editor…) under those builds was a guaranteed `EXCEPTION_ACCESS_VIOLATION` JVM crash. The state-fabrication mixin that fixes this under VulkanMod cannot help here: the draw calls themselves would crash even with the query fabricated.

## The fix: graceful degradation 优雅降级

A new class-presence probe — Radiance 0.1.6's GL↔Vulkan interop classes (`opengl_ui.OpenGLUiBridge`/`OpenGLUiProxy`, present in every shipped 0.1.6 jar, absent before) — tells the two generations apart. `WindowBase#onDrawScreen`, the single funnel every MTR Elementa screen renders through, checks it first: on a pre-0.1.6 Radiance the Elementa draw is replaced by a bilingual vanilla-font notice ("this screen needs Radiance 0.1.6+") drawn through `Font#drawInBatch` into the shared buffer source — the same vanilla path `SignPreviewComponent` already uses inside Elementa screens, which Radiance intercepts and renders safely. The screen stays closable, input keeps working, and a one-shot log line explains the skip.

**0.1.6+ behaviour is untouched**: the probe reads "interop present" and every renderer path is byte-identical to yanyang.16.

## Compatibility matrix 兼容矩阵

| Radiance | With MTR-Yanyang |
|---|---|
| 0.1.6-alpha, 1.21.1/1.21.4 × fabric/neoforge | ✅ fully compatible — trains, collision boxes, screens all work |
| 0.1.0–0.1.5, fabric 1.21.4 | ✅ co-loads cleanly — game/world/trains/collision boxes/riding all work; MTR screens show the update notice instead of crashing; update Radiance to restore them fully |
| 1.20.1 / forge builds | no MTR-Yanyang counterpart exists (out of scope) |

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.17+<version>-<loader>.jar`. No config changes; the Radiance guard is always on.
