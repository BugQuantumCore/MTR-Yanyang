MTR-Yanyang 4.1.0-beta-2.yanyang.11 — drop-in upgrade for 4.1.0-beta-2.yanyang.10 (1.21.1 & 1.21.4, Fabric & NeoForge).

## The train model rendering error, finally root-caused 列车模型渲染错误

The huge pure-black stretched bands sweeping through train interiors (anchored at the walls and ceiling where lamps and light modules sit) came from a **vertex-format mismatch on the LIGHT render stages**. Every MTR static mesh (train bodies, interiors, lamps, PSD casings, signs) is uploaded in `NEW_ENTITY` format — 36-byte stride — but the `LIGHT` / `ALWAYS_ON_LIGHT` stages drew through `RenderType.beaconBeam`, whose Vulkan pipeline is built on `BLOCK` format — **32-byte stride**. OpenGL never cares (the VAO carries its own stride) and plain VulkanMod never touches these GL buffers (native wrvk pipeline), but under **Beryl** the draw lands on the VulkanMod-translated beacon pipeline: the GPU walks 36-byte vertices with a 32-byte binding, every vertex after the first reads 4 bytes early, and the light-module parts render as enormous stretched black bands.

While VulkanMod owns the renderer the light stages now draw through the NEW_ENTITY layers (`entityCutout` / `itemEntityTranslucentCull`). Beryl's entity pipeline lights them from the baked full-bright lightmap — lamps and light modules glow warm at night and read lit at day, the per-stage emissive behaviour yanyang.9 designed — with **correct geometry**. Plain OpenGL keeps the original beacon look, byte for byte.

## Brush-held rail type colours still glowing — the sun, not the lamp 手持刷子时轨道类型颜色异常发光

yanyang.10 capped the **block** component of the overlay light, which closed Beryl's warm-emission term — but reading Beryl 0.2.0-alpha's `entity.vsh` shows the packed light feeds **three** terms: sky-ambient, a **sun gate** `max((sky² − 0.5)/0.5, 0)` that multiplies the full GGX sun term (opens above sky 11.3), and the block emission. With sky still 15, the up-facing overlay quads took **full sun plus full ambient** in daylight; pure yellow / pure cyan type colours crossed Beryl's bloom threshold (`r+g+b ≥ 2.0`) and bloomed exactly as before.

`VulkanCompat.overlayLightForBeryl` now clamps **both** components — block ≤ 8, sky ≤ 11, so the sun gate is mathematically closed. Day: the type colours render lit and saturated but below the bloom threshold. Night: a soft warm emission keeps them legible like a lamp, never a beacon. The white node-marker cubes left the no-lightmap beacon layer for the entity layer under VulkanMod (full-white 3.0 was blooming day and night), the invalid-node markers now actually flash through their light for the first time, and cable-car guide lines dim 75% under Beryl for the same threshold reason.

## The two remaining boot-log lines

- `psd_door_bottom_right_1 72×36 limits mip level from 4 to 2`: a full reachability audit (blockstates → models → textures) confirms **the mod references nothing mip-limiting any more**. The sprite is reintroduced by an external resource pack overriding `assets/mtr/models/block/psd_door_1.json` with a pre-beta-2 model — remove or update that pack (e.g. the loaded `file/MTR4.1.0-beta-2 Chinese translation`) and the atlas keeps full mip-4 quality.
- `[mtr:textures/block/transparent.png] Invalid model type for model resource ""`: the improved yanyang.10 logging identified the entry — a custom-resource model slot with an **empty model path** and a placeholder texture from pack / creator data, not a broken mod file. Legitimate empty slots now log at debug (a non-empty path with a wrong extension still logs at error).

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.11+<version>-<loader>.jar`. Beryl and plain-VulkanMod sessions pick up the new render paths automatically; no config changes.
