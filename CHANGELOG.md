# Changelog

Concise, newest first. Tag scheme changed at `4.1.0-beta-2.yanyang.4` (previously `YYMTR-4.1.0-beta.N`); the jars are drop-in upgrades of one another.

## 4.1.0-beta-2.yanyang.10 (2026-10-30)

**The easement curves silently fell back for every rail — the angle semantics bug behind "两个新增的轨道路径算法未对实际路径产生任何影响"**
- TSC stores rail angles in a specific facing convention: after the `Rail.getAngles` normalisation applied when a rail is created, `angle1` faces **from node 1 toward node 2**, but `angle2` faces **back from node 2 toward node 1**. The classic segment solver corrects for this internally (its `getOpposite()` dance before solving). `EasedRailPath.build` did not: it read the stored `angle2` as the *arrival* heading, so nearly every real rail computed a heading change near 180° and was rejected by the presolve guard — **silently falling back to the classic solver**, which is bit-identical to what the rail already looked like. Worse, the geometries that slipped past the guard (90° and 45° corners) solved a *hairpin twice as long as the real track*. Verified by compiling the real `RailMath`/`EasedRailPath`/`Angle` sources in a harness against stored-semantics geometry: straight/S-bend/22.5° rails all rejected; 90° corners solved a 14.9-block monster instead of 8.2.
- The fix is one line of semantics: the eased solver now uses `angle2.getOpposite()` as the node-2 travel heading (and feeds the same corrected angle to the end-heading sample). Post-fix harness on stored-semantics geometry: straight rails take the straight table, S-bends solve at their true length, 22.5°–135° bends all solve with lengths 1–6 % over the classic chord (the easement bulge), the same physical track laid in either node order produces byte-identical geometry, and only genuine >135° hairpins still fall back — by design.

**Brush-held rail type colours glowing under Beryl — 轨道在手持刷子时的类型颜色异常发光**
- The rail overlays that appear while holding the brush / rail connectors (the **coloured/flashing rail type tint**, one-way arrows, signal dotted lines, node markers, rail statistics text) all drew with `DEFAULT_LIGHT = pack(15, 15)`. Under Beryl the block half of that feeds the squared warm-emission term at full strength ≈ 1.76× — the type colours read as glowing. All seven draw sites now pass through `VulkanCompat.capBlockLightForBeryl`, the same clamp the passenger fix has used since yanyang.7: sky 15 keeps the overlays clearly visible day and night, while the block component is capped so Beryl renders them lit, not emissive. Non-Beryl renderers are untouched.

**Boot log hygiene — the five reported log-error classes**
- **60× Log4j `ClassCastException` per boot** (15 lookups × 4 contexts): the TSC jar shipped a fully relocated log4j (`org.mtr.libraries.org.apache.logging.log4j`) whose `Log4j2Plugins` cache keeps *original* class names; inside Minecraft the shaded `Interpolator` resolved those against the game's own unrelocated log4j classes and `asSubclass` cross-failed on every `LoggerContext` init. The TSC shadow jar no longer ships log4j at all (`log4j-api`, `log4j-core`, `log4j-slf4j2-impl` excluded, references left unrelocated): TSC's own `LogManager`/`Logger` calls bind to the Minecraft host logger — its messages now land in `latest.log` properly tagged instead of spraying stack traces.
- **`mtr:textures/block/transparent.png` — `[…] Invalid model!`**: that error is MTR's own model-type check (`VehicleModel.getModelLoaderBase`) firing on a **custom resource entry whose model path is neither `.bbmodel` nor `.obj`** — a resource pack the game loads, not the mod jar. The error now also logs the offending model resource path so the broken pack entry can be identified at a glance.
- **3× `File mtr:sounds/minecart/{loop,motor3,motor4}.ogg does not exist`**: `sounds.json` still declared the three events although the ogg files were removed from the jar long ago. The dead entries are gone (the remaining minecart events — `doorcls`, `dooropn`, `point`, `run` — all have their files).
- **`psd_door_bottom_right_1` 72×36 → whole blocks atlas mip 4→2**: the only mip-limiting sprites reached the atlas through placeholder particle models (`psd_door_1`, `apg_door` — particle-only, no elements; the doors themselves are rendered dynamically). Their particles now reference the 16×16 `transparent` texture, and a full audit confirms **no blockstate-referenced model references any texture below the mip-4 requirement any more** — the atlas keeps full mipmap quality, which sharpens every block in the distance, not just MTR's.
- **1× passenger skin `HttpTimeoutException`**: the Mojang profile fetch now retries once inside the request-throttle window before giving up (transient connection drops used to go straight to the warn + stale-cache fallback).

## 4.1.0-beta-2.yanyang.9 (2026-10-30)

**The yanyang.8 bake was byte-swapped — the root cause of "after the last update rendering got much worse and the light modules' emissive broke"**
- yanyang.8 documented its static-model lightmap bake as `LightTexture.pack(0, 15)` (block 0, sky 15) but shipped the raw value `0xF00000`. In the packed lightmap format the two 16-bit halves are the two lightmap texture coordinates: the high half `u2` is the lightmap's **block** axis and the low half `v2` the **sky** axis (`LightTexture.FULL_BRIGHT = 0xF000F0 = pack(15, 15)` is the canonical witness; the MTR constant `MAX_LIGHT_INTERIOR = 0xF000B0 = pack(15, 11)` agrees). `0xF00000` is therefore really **`pack(15, 0)`** — the exact inverse of the intent. Under Beryl every static model (train bodies, doors, rails, signs, PIDS casings, lift shells) shipped with **full block emission and zero sky light**: the 240/256 block coordinate fed Beryl's squared warm-emission term at full strength in every scene, while the collapsed sky component closed the ambient and GGX direct-light gates. Entire trains rendered as flat orange-glowing silhouettes with no shading, day or night — precisely the reported regression, and strictly worse than yanyang.7 (which at least kept ambient and direct light).
- The bake is now **byte-correct and per render stage** (`VulkanCompat.staticModelBakedLight(isFullBrightness)`):
  - **Full-brightness stages** — `LIGHT`, `ALWAYS_ON_LIGHT`, `INTERIOR`, `INTERIOR_TRANSLUCENT`, self-lit by design under every other renderer — keep the full-bright `pack(15, 15)`. Beryl's block-light emission is exactly what makes headlight and light-module parts glow, so **the module-group emissive works again** and train interiors read as lit at night (seen through the windows). Under every non-Beryl renderer nothing changes at all — the same constant the vanilla-GL path always used.
  - **`EXTERIOR`** — the only stage that follows the scene light — bakes `pack(4, 15)`: block 4 (the ceiling the humanoid cap settled on after 8 still read as glowing — `≈ 0.118×` warm, "standing under a lamp", never a beacon) plus the finally-correct sky 15, so Beryl's ambient, GGX direct lighting, shadow-map occlusion and fog all work on train bodies again. No more orange silhouettes, and no more pitch-black trains either.
- The humanoid `capBlockLightForBeryl` surgery had the same axis mix-up: `>> 4 & 0xF` reads the **sky** nibble, not the block component (block lives in bits 20-23). It "worked" by accident — force-feeding every well-lit rider `0x40` (block 0, sky 4): glow-free but dim, and riders in low-sky scenes (night, indoor platforms) passed through the check untouched and kept the full uncapped block light. The surgery now clamps the actual block nibble and preserves the true sky nibble — consistent rider brightness across day/night and indoor/outdoor.

**Two classic railway easement shapes: 三次抛物线 / 半波正弦缓和曲线**
- The rail modifier's Shape control is now always available and cycles **Quadratic → Two Radii → Cubic Parabola → Half-Wave Sine** (the vertical-radius input stays gated to sloped rails). Both new shapes are full-rail curvature-continuous easements — curvature starts and ends at zero, so they meet the adjoining rails' ends without a curvature step:
  - **Cubic parabola** — piecewise-linear curvature, the classic straight–easement–circle–easement–straight mainline sequence;
  - **Half-wave sine** — a single half-wave of sine as the curvature diagram, the smoothest classic profile, with no corner in the curvature anywhere.
- Implementation at the block scale: the path between the two nodes is solved once at construction time (a damped 2×2 Newton iteration pinning arc length and a heading-neutral flex amplitude against the endpoint coordinates, endpoint-exact with sub-millimetre residuals smoothed to zero) and integrated into **one shared sample table** — rendering, vehicle placement, bounds and radius queries all interpolate that same table, so **trains follow the displayed track by construction**. No per-frame cost: all solve work is construction-time and the render path replays the same segment stream as before (`RailGeometryCache` keeps caching it). Geometry outside what the families can represent (extreme hairpins, rails doubling back behind their start node, solver failure) automatically falls back to the classic segment solver — eased shapes are a strictly-optional refinement. zh_cn / zh_tw translations included.

**TSC integration restructured — the fork is now a branch of this repository**
- Per maintainer request, the Transport-Simulation-Core patches (previously a committed patch file applied by CI, and now also carrying the easement-curve work: `Rail.Shape`, `RailMath`, `EasedRailPath`) live on the **`tsc-1.21.x` branch of MTR-Yanyang** as real commits — based on the **Yanyang Technology Group optimized TSC** (`Yanyang-Technology-Group/Transport-Simulation-Core` @ `8d72d57`, the exact base every previous yanyang build shipped, so MTR 4.1.x compatibility is unchanged). Both CI workflows check the branch out directly, the patch file is gone, and TSC changes are now reviewable per-commit instead of as one opaque blob. The TSC-side commits also carry the previously-patched robustness fixes: the `Simulator` catch-up loop is bounded by 30 s of wall time (a non-converging tick loop can no longer hang a dimension forever), the directions `Graph` clears stale walking connections between refresh cycles, `VehicleExtraData.copy` skips re-parsing the vehicle path it is about to re-attach, and the TSC build no longer double-ships `log4j-slf4j2-impl`.

## 4.1.0-beta-2.yanyang.8 (2026-10-29)

Diagnosed against the freshly decompiled Beryl 0.2.0-alpha `entity.vsh`/`entity.fsh`/`particle.vsh` plus the vanilla 1.21.1 `LightTexture.pack` bytecode and the VulkanMod 0.6.7 `VBO.drawWithShader` translation — this round found the piece the yanyang.7 passenger clamp could not cover.

**Vehicles and every static MTR model glowing warm under Beryl — the real remaining "glow"**
- yanyang.7 clamped the block-light fed to the **per-drawn** humanoids (passengers, riding players), but the glow persisted — because the biggest emitter was never the passengers. **Every** `NewOptimizedModel` (train bodies, doors, rails, signs, PIDS casings, lift shells) bakes its `UV2` lightmap attribute into a static GPU buffer once at build time, and that bake was the vanilla-GL full-bright constant `DEFAULT_LIGHT = pack(15, 15)`. Beryl's entity shader reads that frozen coordinate as a squared warm emission — `texColor × (240/256)² × 2 × (1 − lightY) × vec3(1, 0.7, 0.5)` ≈ **1.55× warm glow** in exactly the scenes where `lightY` collapses (night, roofed stations), i.e. **entire trains glowing at night and inside stations**.
- While Beryl owns the GL models, the baked coordinate is now `pack(0, 15)`: block 0 zeroes the emission term; sky 15 keeps Beryl's own ambient, GGX direct lighting, shadow-map occlusion and fog fully working (a zeroed sky too would close Beryl's direct-light gate `light.z` and leave unshaded silhouettes). Plain GL, plain VulkanMod and the native pipeline keep the full-bright bake — the MTR cartoon look is unchanged outside Beryl. The bake variant is chosen at build time, and models are only ever built inside the post-transition rebuild, so it always matches the renderer that draws them.
- `renderModel` pins the per-draw `ColorModulator` at **1** while Beryl drives the frame: the old `packed/0xF` formula hits exact **0** in fully dark scenes (block 0, sky 0) and multiplied every pixel by zero — erasing what Beryl's own lighting just computed and leaving pitch-black silhouettes. Under Beryl the division of labour is now clean: the shader pack owns the light, the colour modulator stays neutral. (The formula stays untouched on the GL/VulkanMod paths — it degenerates to 1 in almost every scene there anyway.)
- The humanoid block-light cap drops from 8 to **4**: yanyang.7's cap of 8 still fed the emission a `(128/256)² × 2 = 0.5×` warm bloom that read as glowing on real scenes; 4 leaves `≈ 0.118×` — a faint "standing under a lamp" warmth instead of a beacon.

**Confirmed Beryl-side, not fixable from MTR**
- Particles glowing under Beryl is Beryl's own doing: its `particle.vsh` carries the **same squared-emission formula** (`lightX = (UV2.x/256)² × (1 − lightY)`), and vanilla fills particle lightmap coordinates with the real block light. Any particle near a light source of 10+ will bloom warm — that is the shader pack's design, outside MTR's reach.

**AI passenger size / phone pose — verified, no defect found**
- The passenger transform chain was re-derived against the freshly decompiled vanilla 1.21.1 `LivingEntityRenderer.render` bytecode: the MTR chain (`ageScale → flip(-1,-1,1) → 0.9375 → translate(0,-1.501,0) → YP180`) is mathematically identical to vanilla's (`scale(getAgeScale()) → setupRotations YP(180−yaw) → flip → scale() → translate(0,-1.501,0)`) — `Player.getAgeScale()` is where the `0.9375` comes from, and every element of the chain (diagonal scales, the Y-180 rotation and the Y-translation) commutes, so the children's feet stay on the ground exactly like vanilla babies. The phone pivot `(0, 8.2, −7.6)` sits within 0.05 blocks of the computed hand-convergence point of the −50° arm pose. If size/phone issues still reproduce **with shaders off** (the native Vulkan path), that is a different pipeline — send that log and it will be fixed there.

## 4.1.0-beta-2.yanyang.7 (2026-10-27)

Both yanyang.6 follow-ups below were diagnosed against the actual Beryl 0.2.0-alpha bytecode + shaders (decompiled `RenderingPipeline`, its keybind/options mixins and `entity.vsh`/`terrain.fsh`), not inferred from log lines.

**Beryl toggle hard crash — second, deeper fix**
- yanyang.6 latched the native recorder off, but the crash persisted because the *toggle itself* is far heavier than the attach it replaced: Beryl flips its pipeline on the render thread mid-session, swaps VulkanMod's `MainPass` object, reverts the terrain vertex format/builder, forces a **full terrain rebuild** (`LevelRenderer.setBlocksDirty()`), and — when toggled from its options screen — frees **every Beryl GPU object** (`clearResources`). MTR kept submitting its full per-frame load (thousands of model quads, hundreds of passenger models) straight into that transition window, and on an owner flip even ran the **multi-second blocking `CustomResourceLoader.reload()` inside the transition frame itself**. Any of that can take the driver down with no crash report and no hs_err.
- MTR now opens a **60-frame transition grace window** on any Beryl state flip: the simulation keeps ticking (trains stay on schedule) but every MTR draw — vehicles, lifts, rails, passengers, models, scheduled renders, dynamic-texture uploads — is dropped while Beryl settles, then resumes automatically. The owner rebuild (native Vulkan ↔ Java GL) now fires on the **first frame after** the window instead of inside the transition frame. The one-second invisible-trains pause replaces a hard crash.

**Passenger glow under Beryl — real root cause**
- yanyang.6 sampled the truthful packed light, but the glow persisted at lit stations — because Beryl's entity vertex shader (`entity.vsh`, shared with `terrain.fsh`) turns the **block-light coordinate itself** into a squared warm emission: `texColor × ((uv/256)² × 2) × vec3(1.0, 0.7, 0.5)`. At block light 15 that is a **1.76×** warm bloom on every entity — and MTR platforms deliberately sit at light level 15 (mob-spawn-proofing), so the *truthful* light feeds the emission directly. Tunnels and daylight were already fixed by the light sampling; lit platforms were not.
- While Beryl drives the frame, the block-light component fed to **every MTR-rendered humanoid** (AI passengers, platform and on-board, plus real riding players, phone cuboid included) is now clamped at **8** — a gentle 0.5× lamp-lit warm tint instead of a beacon. Plain VulkanMod and OpenGL keep the truthful value (vanilla lightmap curve, no emission), so nothing changes outside Beryl. The cap lives in `VulkanCompat.capBlockLightForBeryl` with the shader math documented; raise `BERYL_HUMANOID_BLOCK_LIGHT_CAP` if you prefer brighter passengers.

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
