MTR-Yanyang 4.1.0-beta-2.yanyang.13 — drop-in upgrade for 4.1.0-beta-2.yanyang.12 (1.21.1 & 1.21.4, Fabric & NeoForge).

The AI-passenger upgrade: solid collision boxes, ground-following walk animation, and exact `minecraft:player` size for every passenger.

> **Re-release note**: the first yanyang.13 jars crashed every game launch (`@Mixin target type mismatch: net.minecraft.class_1924 is an interface`) — the collision-box mixin was a class mixin targeting an interface. This build re-releases the same tag with the mixin correctly declared as an interface. If your `4.1.0-beta-2.yanyang.13` crashes on startup, re-download; the changelog has the details.

## Collision boxes — you can no longer walk through the crowd 为AI乘客添加碰撞箱

AI passengers are purely rendered (analytical positions, no entities behind them), so until now the whole crowd was walk-through ghosts. Every on-foot passenger now carries a solid, player-sized collision box (`0.6 x 1.8`) that tracks their rendered position every frame: `EntityGetter#getEntityCollisions` — the same vanilla funnel that makes boats and shulkers impassable — is extended with the boxes a client-side registry publishes.

- Solid, shulker-style: you physically cannot walk through a waiting or walking passenger.
- The registry filters to the local player's own movement in the matching dimension — mob pathfinding, dedicated servers and cross-dimension queries never see the boxes (empty-set fast path), so there is no server cost and no mob weirdness.
- On-board riders deliberately register nothing: their boxes would sweep through platforms at train speed.

## Walk animation polish 行走动画优化

- **Ground-following walking legs**: walking legs used to lerp Y in a straight 3D line between the two platform points — walking straight through hills, fences and station walls. They now sample the actual standable surface at the current position every frame (through the shared ground-scan cache, in a window around the two platform heights, clamped to a 2-block band so a dip or bridge doesn't drop or float the walker).
- **Smoothed steps**: residual half-block ground steps ease per passenger (slopes, not stair pops), and position discontinuities (re-planned legs, geometry fallbacks) blend over 250 ms instead of blinking; genuine teleports still snap instead of superman-sliding.
- **Gait life**: a tiny shoulder counter-sway (~2.6 degrees) and arm z-sway (~1.7 degrees) on top of the vanilla walk formulas, driven by the limb-swing phase and fully faded out when standing — the standing pose stays byte-identical to vanilla.

## Exact player size 确保大小正常

The age variation is gone: after two rounds of "passengers are half a player" reports (yanyang.11, and again after yanyang.12's ground fix), the variation itself — 15% of every crowd scaled to 0.55-0.78 plus a ±3% adult height jitter — read as a size bug. Every AI passenger, platform waiter and on-board standee alike, now renders at **exactly the vanilla player scale**: same model, same 1.8-block height, same proportions as `minecraft:player`. If any passenger still looks the wrong size next to you, that would now be a genuine new bug worth a screenshot.

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.13+<version>-<loader>.jar`. No config changes; collision boxes are always on for on-foot AI passengers.
