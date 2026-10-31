MTR-Yanyang 4.1.0-beta-2.yanyang.12 — drop-in upgrade for 4.1.0-beta-2.yanyang.11 (1.21.1 & 1.21.4, Fabric & NeoForge).

Three fixes from yanyang.11 field reports: the gangway connections glowing abnormally inside and out, all waiting passengers apparently shrunk to half a player, and the phone-holding pose looking wrong. All three are Beryl/entity-pipeline lighting and placement issues — plain GL and plain VulkanMod render paths are byte-for-byte unchanged.

## Gangway connections glowing inside and out 车厢连接处内外异常发光

The connection quads draw through the entity pipeline, so under Beryl their packed light feeds the same three shader terms as all entity geometry: squared-sky ambient, the sun gate (full GGX direct sun, opens above sky 11.3), and the squared warm block emission. Two different bugs, one per side:

- The **outer bellows** used the raw world light — MTR platforms keep block light 15, so `~1.76x` warm emission lit them like a lamp box.
- The **inner walkway** drew flat `pack(15,15)` on lit cars — full sky opened the sun gate AND the full emission blew light gangway textures past Beryl's `r+g+b >= 2.0` bloom threshold into the overexposed milky doorway; in daylight both sides additionally took the full sun term.

Fix: the outer side now goes through `capBlockLightForBeryl` (block <= 4, sky truthful — the same lamp-lit look as the rest of the exterior); the lit inner takes `overlayLightForBeryl(DEFAULT_LIGHT)` (block 8, sky 11 — clearly lit, sun gate mathematically closed, comfortably below bloom); unlit interiors keep the capped world light.

## "Half-size" passengers were half-buried in the track bed 乘客等比缩小一半的真相

Waiting passengers were placed exactly on the rail path, in the middle of the track. The rail Y is the *track block layer*, not the platform floor — wherever the platform surface sits above the track plane (the standard MTR station layout) the entire waiting crowd rendered buried to chest depth, and a crowd of head-and-shoulders sticking out of the floor reads, at any normal viewing distance, exactly like "everyone shrunk to half size".

Waiters now stand beside the track on the actual standable surface: a deterministic along-track sample gives the rail heading, both sides are probed (shared ground-scan cache, offsets 1.5/2.75, window railY..railY+2.5, highest valid surface wins = the platform side), and the passenger faces the track while waiting. Degenerate geometry falls back to the old position. On-board riders were never affected. The AI child scale range also rises 0.55–0.65 to **0.68–0.78** — children at almost exactly half player height had mixed into the "everyone is half size" report.

## Phone pose: the gaze now lands on the screen 手机姿势修复

The old pose's gaze missed the phone by ~37 degrees (arms -50, phone near-vertical -12 lean at belly height, head bowed 40 — watchers appeared to stare straight through the phone at the ground beyond). Geometry recomputed numerically against the ModelPart transform chain: arms **-60** with mirrored ±18 inward yaw so the hands (±2.3, 7.0, -8.2) wrap the phone's sides, phone pivot (0, 6.8, -7.0) at upper-chest height with a -28 lean turning the screen up to the face, head bowed 52, putting the gaze axis on the screen. The cuboid keeps its 3x5x0.5 proportions and hands-convergence placement.

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.12+<version>-<loader>.jar`. Beryl and plain-VulkanMod sessions pick up the new render paths automatically; no config changes.
