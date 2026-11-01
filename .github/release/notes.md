MTR-Yanyang 4.1.0-beta-2.yanyang.14 — drop-in upgrade for 4.1.0-beta-2.yanyang.13 (1.21.1 & 1.21.4, Fabric & NeoForge).

The half-size passenger mystery, finally solved for real — and trains that can hit you.

## AI passengers: the real half-size root cause 乘客大小的真正原因

Three releases tried to fix "所有AI乘客等比缩小到玩家的一半" — child-scale ranges raised, then removed entirely, then waiters repositioned — and the report kept coming back, because none of those were the cause. This release root-caused it by disassembling the actual 1.21.1 client bytecode:

**Every freshly baked `PlayerModel` starts with `EntityModel.young = true`.** While that flag is set, `AgeableListModel` (the player model's own superclass) renders in **vanilla baby proportions** — the body at `1 / babyBodyScale = 0.5×`, the head at `0.75×` — a figure whose head top lands at about **1.06 blocks instead of the adult 1.875**: exactly "uniformly shrunk to half a player". Real players never show this because `LivingEntityRenderer.render` resets the flag (`model.young = entity.isBaby()`, always false for players) on every single render — but the yanyang passenger renderer drives the model *directly*, so the constructor default survived every previous fix (the removed age variation was multiplying on top of the hidden 0.5 baby scale, which is why removing it changed nothing).

The baked wide/slim models now set `young = false` once at bake time. Every AI passenger — platform waiters, walkers, on-board standees — now renders at **exactly the vanilla player scale**, identical to `minecraft:player`.

## Full train collision boxes — trains can now knock you flying 为列车添加完整的碰撞箱，使其可以撞飞前方的玩家

Trains used to be completely intangible: you could stand on the track and an express would pass straight through you. Now every car publishes real, world-space collision boxes into the same vanilla collision funnel that makes boats and shulkers solid — the train body is a physical object you cannot stand inside.

- **The hit**: a car sweeping into you at ≥ 1.5 blocks/second applies a knockback impulse **along the train's travel direction** plus an upward component — the train visibly clips you and sends you flying. The impulse scales with speed (capped), with an 800 ms cooldown per train, so it reads as one solid hit rather than a machine-gun shove.
- **Platform safety**: the knockback only fires for players standing **below the car's floor level** — i.e. actually on the track. Standing normally on the platform (level with the train floor) never triggers it.
- **Boarding still works**: the vehicle you are riding registers no boxes at all (you walk freely inside your own train, exactly as before), and cars stopped at a platform with open doors carve the open doorway corridors out of their boxes, so stepping through an open door to board is never blocked. The knockback check is skipped entirely while you ride.
- **No server impact**: the boxes live in the same client-side registry family as the passenger boxes — dedicated servers, mob pathfinding and cross-dimension queries see an empty set.

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.14+<version>-<loader>.jar`. No config changes; both features are always on.
