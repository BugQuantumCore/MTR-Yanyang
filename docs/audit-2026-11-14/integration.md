# Integration, packet and lifecycle review

Review date: 2026-11-14. No local builds, test execution, benchmarks or game
launches were performed. This ledger supplements the deeper native, rendering,
collision/passenger and resource reviews; it does not turn targeted reads into
a claim of complete third-party or generated-code coverage.

## Changes and integration checked

- `RegistryServer` binds C2S assembly to the authenticated player object;
  `RegistryClient` uses a separate client assembly scope. Server/client lifecycle
  hooks clear the relevant packet buffers.
- `PacketBufferSender/Receiver` preserve primitive boundaries, legacy padded
  chunks and embedded NUL characters while bounding packet assembly and decoded
  collection counts. Netty buffers are released on completion/error/eviction.
- Request forwarding is constrained to a relative path on the loopback
  authority. The riding update checks the authenticated UUID, a single rider,
  finite coordinates and consistent dismount state before forwarding or
  changing Minecraft-side riding state. Ticket credit is granted only after
  option and emerald validation.
- Generic request, depot, update/delete, block-entity configuration, screen,
  route-announcement, sign and sensor packet handlers were read through to their
  visible effect boundaries. The permission limits below remain substantive.
- The entity movement/collision mixins, path epoch/ID mixins and UniversalCraft
  state-compatibility mixins were inspected together with their call targets.
  This does not execute Mixin transformation under every other installed mod.
- `MTRClient` closes old map providers on replacement, disconnect and dimension
  change; clears announcement and signal lookup state; and configures multipart
  request/file bounds. `MapCachePath` rejects untrusted path segments before
  creating the cache directory, preserving existing safe IDs and dimension paths.
- Supplemental changes were cross-read by a reviewer who did not implement
  them: path helpers, provider shutdown/eviction, Blockbench baked-vertex bounds,
  vehicle fallback and capacity schema/call-site alignment.
- Native Java comments and configuration documentation now distinguish transport
  v3 from the retained FRM2 layout and describe the synchronous JNI frame path.

## Confirmed open boundaries

1. **Server authorization remains incomplete.** `BlockEntityPacketHandler`
   forwards edits directly to the sender's world; loaded-chunk checks in concrete
   setters are not proximity, item, ownership or permission checks. Generic
   `PacketRequestResponseBase` operations default to accepting data except where
   explicitly overridden. Remote update/delete/depot operations, driving rights,
   sensor activation and HTTP endpoint allowlists require a coordinated policy
   rather than an arbitrary permission-level gate that could break intended
   server workflows. UUID validation alone does not authorize a driver.
2. **JSON/resource semantic budgets remain separate from packet byte bounds.**
   A bounded byte payload can still encode expensive semantic operations or
   nonfinite/mis-sized resource fields. Do not interpret the 64 MiB assembly cap
   as a CPU or per-peer rate limit. One abusive peer can still pressure the global
   pending budget; fairness/rate policies are not implemented by this patch.
3. **Callback maps lack comprehensive deadlines.**
   `PacketForwardClientRequest.CALLBACKS` and
   `PacketCheckRouteIdHasDisabledAnnouncements.CALLBACKS` remove entries only on
   a matching reply. Missing responses can retain callbacks/screens. A bounded
   request registry with timeout/disconnect completion and caller-visible failure
   semantics is preferable to silently dropping accepted callbacks.
4. **Cache cleanup is not complete repository-wide.** Map, collision, texture
   and signal paths were repaired, but the supplemental resource/UI reports list
   remaining lift/sign/preview ownership issues. A source audit cannot infer
   driver-side reclamation from Java reachability.

## Test coverage precision

`BlockbenchElementBoundsTest` uses actual Minecraft baked vertices as the
numerical oracle, including a directly supplied parent-transform chain. It
does **not** exercise the existing `BlockbenchModelLoader.iterateChildren`
parent-chain propagation issue. `VehicleResourceDataTest` verifies fallback
geometry and area distribution by constructing a cache from the helper's
outputs; the production initializer call was verified by source inspection,
not by that particular test. Empty-resource and capacity JSON round trips are
separate cases. No test here claims actual GPU visual parity.

`CachedFileProviderTest` uses controlled entries and a negative lifespan with
enough margin for the existing randomized offset; it does not depend on sleeps.
Repeated close does not release already-removed resources again. The visible
map read/update/close entry points are on the client owner thread; no new
background world query was introduced.

## CI and artifact provenance

The checked-out source is the triggering PR revision. The TSC revision is pinned
and its root plus path-computation tests run before consumption. Shader downloads
use the exact artifact name, and packaged native/shader hashes are compared with
the files injected by the same native workflow run. Regression XML artifacts are
retained even on failing jobs. Cross targets are machine/platform-checked, not
executed as Android/iOS games; iPadOS is the same iOS binary under a separate
packaging directory.

The macOS jobs in run `43474395663` failed before obtaining a runner, not inside
the compiler or tests. The next matrix uses macOS 26 ARM with deployment target
15.0 and macOS 15 Intel for iOS cross compilation. Final PR check runs, head SHA
and their downloadable reports are the execution evidence; neither this ledger
nor an earlier green commit substitutes for those results.
