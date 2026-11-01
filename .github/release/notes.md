MTR-Yanyang 4.1.0-beta-2.yanyang.15 — drop-in upgrade for 4.1.0-beta-2.yanyang.14 (1.21.1 & 1.21.4, Fabric & NeoForge).

The collision boxes that "did nothing" — found dead in the bytecode, and revived. Plus the phone pose, rebuilt.

## Why the boxes weren't there for you 碰撞箱为什么没有生效

The yanyang.13/14 collision boxes hook the one vanilla funnel that makes boats and shulkers solid (`EntityGetter#getEntityCollisions`) — and that hook is correct **in vanilla** (bytecode-verified: the movement call dispatches through `CommonLevelAccessor`'s delegating default into the patched body).

But your pack runs **Lithium** — and Lithium's `entity.collisions.movement` module rewrites `Entity#collide` from the inside. Disassembling it shows exactly what happens to the funnel: the inner `getEntityCollisions` call site gets wrapped by a "postponed entity collection" that returns an empty list on the spot, and the sweep is resolved in Lithium's own static implementation instead. The patched body is **never entered** — no crash, no log line, nothing in the crash report to hint at it. The boxes simply do not exist on a lithium install. That is precisely what you saw: F3+B on, walking straight through the whole crowd and a standing train.

## The fix: a path Lithium cannot bypass 撞不穿的兜底

This build adds a second, independent mechanism — `EntityMoveMixin`, injected at the tail of `Entity#move`. That public method is the one thing the movement pipeline never skips: Lithium rewires the *internals* of `collide`, but `move` itself runs untouched on every installation. After the movement has been applied, the new `FakeEntityCollisions.depenetrate` checks whether the mover (a client-side player) ended up inside any registered passenger or train-car box — and pushes them back out along the shallowest axis:

- **On lithium packs** the funnel contributes nothing and this fallback does all the work — passengers and trains are finally solid, and a moving train still knocks you flying (the knockback impulse never depended on the funnel).
- **On vanilla packs** the funnel already blocks the movement before any overlap can form, so the fallback simply sees nothing — both mechanisms compose without conflict.
- The eviction is bounded (capped per tick, up to three passes for crowd corners) and never shoves the player *down* into the ground — a track-level player caught by a train's hull is pushed out sideways, never buried.
- Dedicated servers remain a strict no-op: the registries are client-side, the fast path returns before any work happens, and server-side movement is untouched.

## Phone pose rebuilt 手机姿势重做

The yanyang.12 pose leaned the phone back 28° to turn the screen up toward the bowed head. In-game — viewed from a platform looking down at track-side crowds — a 28° slab shows mostly its screen face and silhouettes as a **flat horizontal tray**: the "手机的角度依然不正确" report. The pose is rebuilt and re-verified by software-rendering it through the real 1.21.1 model classes:

- The slab now leans back only **14°** — legibly vertical from every angle, screen still facing the reader.
- It rides higher, at **chin height** (`(0, 5.8, −7.6)`), shortening the gaze line from the bowed head.
- Arms at **−62° with ±19° inward yaw** converge on the raised slab's sides — a relaxed underhand grip instead of the wide tray-carrying spread.

## Install

Replace the mod jar with `mtr-4.1.0-beta-2.yanyang.15+<version>-<loader>.jar`. No config changes; both features are always on. If you had blacklisted `mtr.mixins.json` or touched Lithium's `entity.collisions.movement` rule while debugging, no changes are needed — the fallback works with Lithium at its defaults.
