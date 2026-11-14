# Supplemental gameplay source review — completed

## Scope and method

Repository: `MTR-Yanyang`.

Read the complete actual source of **155 Java files** in four directories: **block 108, item 19, data 16, sound 12**. This includes their four package-info files. The initial corpus was 12,195 physical lines. Reads were bounded full-file batches with line numbers, not keyword-only scans; a truncated VehicleExtension import/constructor span was explicitly reread. Targeted call-site reads in MTRClient, packet handlers, VehicleResource, registries and TSC Angle were used to confirm findings. Previously reviewed paths/caches within `data` were rechecked only as part of this complete directory coverage.

The initial assignment was read-only. The parent then authorized the localized fixes below. No other repository files were edited by this reviewer for this supplemental task. **No local builds, tests, benchmarks, game runs or project-code execution occurred.** Only source edits, readback and `git diff --check` were used. Parent owns publication and GitHub CI.

## Confirmed defects fixed in this batch

1. **Malformed announcer sound IDs could repeatedly fail client ticks.** `block/BlockTrainAnnouncer.java` previously called `ResourceLocation.parse(soundId)` inside a queued task. GUI/packet input was unvalidated, and the queue removed an entry only after executing it. Invalid IDs are now ignored with `tryParse`; due entries are removed before execution and each task has RuntimeException isolation. Tasks sharing a due millisecond are combined rather than overwriting one another. Delayed tasks capture message/ID values rather than retaining the block entity. A public `clearQueue()` is supplied for the parent's MTRClient disconnect/world lifecycle hook.
2. **Announcer/schedule sensor filters did not survive NBT save/load.** Both subclasses overrode read/write without calling the base implementation. `BlockTrainAnnouncer` and `BlockTrainScheduleSensor` now call `super` for both methods; `BlockTrainSensorBase.readNbt` clears old filter IDs before loading the replacement snapshot.
3. **Relative delays were supplied as absolute game ticks.** `BlockTicketBarrier` (40 ticks), `BlockTicketProcessor` (20), `BlockTrainPoweredSensorBase` (10, two sites), and `BlockDriverKeyDispenser` (4) created `ScheduledTick` with the delay in `triggerTick`. Replaced these with `world.scheduleTick(pos, this, delay)` while preserving triggering and duplicate-scheduling guards. This prevents mature worlds treating each intended delayed event as already overdue.
4. **Signal placement discarded the cardinal quadrant.** `BlockSignalBase` passed `quadrant / 4F` (0..3.75 degrees) to `Direction.fromYRot`, effectively always selecting SOUTH. It now supplies `(quadrant / 4) * 90F`, keeping the existing 22.5/45 degree remainder flags. Integer division is deliberate; using the full continuous angle would round the cardinal component and double-count the remainder.
5. **BVE brake transitions were compared against never-updated state.** `sound/BveVehicleSound.java` now updates `oldSpeedChange` and `oldOnRoute` after sound processing. This avoids repeated per-frame brake application sounds/pressure deductions and restores release/return-to-depot transition detection.
6. **BVE compressor pressure never recovered at normal frame rates.** The pressure field is float, but the per-frame increment was cast to int, discarding sub-1kPa increments. Removed that cast so elapsed time contributes continuously.
7. **Short railway sign arrays could partially modify state then throw.** `BlockRailwaySign.RailwaySignBlockEntity.setData` now validates both arrays are non-null, both lengths exactly match the block's slot count, and every selected-ID set is non-null before any mutation. Invalid updates return unchanged; nullable individual sign IDs remain valid empty slots.

Changed files (10):
- `src/main/java/org/mtr/block/BlockDriverKeyDispenser.java`
- `src/main/java/org/mtr/block/BlockRailwaySign.java`
- `src/main/java/org/mtr/block/BlockSignalBase.java`
- `src/main/java/org/mtr/block/BlockTicketBarrier.java`
- `src/main/java/org/mtr/block/BlockTicketProcessor.java`
- `src/main/java/org/mtr/block/BlockTrainAnnouncer.java`
- `src/main/java/org/mtr/block/BlockTrainPoweredSensorBase.java`
- `src/main/java/org/mtr/block/BlockTrainScheduleSensor.java`
- `src/main/java/org/mtr/block/BlockTrainSensorBase.java`
- `src/main/java/org/mtr/sound/BveVehicleSound.java`

All are frozen for parent publication. `git diff --check` passed and all changed spans were read back. No new regression test was added for these Minecraft-world-dependent block/sound paths; CI compilation alone will not validate their in-game behavior.

## Remaining confirmed follow-up issues (not fixed in this batch)

- **P2 — Unbounded recursive infrastructure walks.** `item/ItemLiftRefresher.java:98-138` recurses once per track with no depth/node/time bound; visited positions prevent cycles, not stack exhaustion. A sufficiently long horizontal/diagonal chain can overflow the server stack. `block/IBlock.java:37-45` likewise propagates recursively along an unlimited line; `maxBlocksAway` limits each gap, not total traversal. `block/BlockEscalatorStep.java:148-160` recursively explores three forward neighbors without a visited set, allowing redundant revisits where branches merge. Use iterative, bounded traversals with preserved ordering in a separate change.
- **P2 — Multi-rail build queue broadcasts grow quadratically.** `item/ItemNodeModifierSelectableBlockBase.java:139-146` obtains one rail at a time; each `data/RailActionModule.java:31-43` mark method immediately broadcasts the full growing queue (`:51-53`) to all players. N rails cause O(N²) list serialization/network work. Batch additions and broadcast once; pending action count also needs a considered backpressure policy.
- **P2 — Build time budget has coarse granularity and unbounded visited storage.** `data/RailAction.java:136-161` checks 2ms only between complete radius/height slices. Block updates/chunk access may overrun that budget; all changed BlockPos values remain in `blacklistedPositions` for the whole action. A resumable x/y cursor, monotonic deadline and loaded-chunk/maximum-region policy would bound cost more reliably.
- **P2 — Block-entity edits are not visibly broadcast to other clients.** `BlockEntityPacketHandler.send` applies client prediction only to the sender and `runServer` calls `setData`; the block setters generally only call `setChanged`. `ItemLiftButtonsLinkModifier` also directly calls setters without an update packet. No block update broadcast is present along those reviewed paths. Multiplayer observers may retain old sign/filter/lift-link data until chunk reload. Confirm with a second client before redesigning update synchronization.
- **P2 — BVE parser robustness remains incomplete.** `sound/BveConfigFile.java:106` constructs a resource ID before distinguishing numeric `[mtr]` fields from sound fields, so a valid scientific numeric value containing `+` can be rejected as an ID. Integer/float/token parsing in BveConfigFile and BveMotorData4/5 is not isolated per row and lacks finite-value validation; malformed third-party files can abort that sound/vehicle load rather than yielding a controlled fallback. Keep this separate from the confirmed sound runtime fixes.

## Runtime and design limits

- Existing sensor logic remains client-driven: VehicleExtension scans nearby client blocks and sends activation packets; BlockTrainScheduleSensor installs its ticker only client-side, and BlockSignalBase checks client-side redstone and sends updates. Unobserved/headless operation, multiple clients and spoofed driving/sensor inputs need separate server-authoritative design work.
- Platform/lift door collision is intentionally empty server-side and based on client door values. Exact physical behavior, culling/cache interactions and compatibility with collision optimization mods require in-game verification; this source review does not certify them.
- No measured FPS/CPU/GPU or sound-channel gain is claimed. No claim is made that the whole repository, generated dependencies or every loader/version compatibility combination was runtime validated.
- GitHub CI must compile every target after the parent's combined commit. Game verification still needs: old-world tick delays and gate passage without repeated fares; all 16 signal headings; valid/invalid delayed announcements, coincident tasks and disconnect/world transitions; sensor filter save/load/reconfiguration; BVE brake/release/depot transitions and compressor recovery at multiple frame rates; invalid short/null sign arrays leaving all state unchanged; multiplayer observers.

## Complete per-file reading coverage

Each file below was read in full. Line counts reflect the working tree at report generation (after approved fixes).

### block

- `src/main/java/org/mtr/block/BlockAPGDoor.java` — 34 lines; complete source read.
- `src/main/java/org/mtr/block/BlockAPGGlass.java` — 66 lines; complete source read.
- `src/main/java/org/mtr/block/BlockAPGGlassEnd.java` — 17 lines; complete source read.
- `src/main/java/org/mtr/block/BlockArrivalProjector1Large.java` — 28 lines; complete source read.
- `src/main/java/org/mtr/block/BlockArrivalProjector1Medium.java` — 28 lines; complete source read.
- `src/main/java/org/mtr/block/BlockArrivalProjector1Small.java` — 28 lines; complete source read.
- `src/main/java/org/mtr/block/BlockArrivalProjectorBase.java` — 78 lines; complete source read.
- `src/main/java/org/mtr/block/BlockCeiling.java` — 38 lines; complete source read.
- `src/main/java/org/mtr/block/BlockCeilingAuto.java` — 71 lines; complete source read.
- `src/main/java/org/mtr/block/BlockClock.java` — 55 lines; complete source read.
- `src/main/java/org/mtr/block/BlockClockPole.java` — 21 lines; complete source read.
- `src/main/java/org/mtr/block/BlockDirectionalDoubleBlockBase.java` — 60 lines; complete source read.
- `src/main/java/org/mtr/block/BlockDriverKeyDispenser.java` — 199 lines; complete source read.
- `src/main/java/org/mtr/block/BlockEntityExtension.java` — 46 lines; complete source read.
- `src/main/java/org/mtr/block/BlockEscalatorBase.java` — 135 lines; complete source read.
- `src/main/java/org/mtr/block/BlockEscalatorSide.java` — 98 lines; complete source read.
- `src/main/java/org/mtr/block/BlockEscalatorStep.java` — 166 lines; complete source read.
- `src/main/java/org/mtr/block/BlockEyeCandy.java` — 149 lines; complete source read.
- `src/main/java/org/mtr/block/BlockGlassFence.java` — 78 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftButtons.java` — 185 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftDoor.java` — 33 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftDoorOdd.java` — 73 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftPanelBase.java` — 173 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftPanelEven1.java` — 35 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftPanelEven2.java` — 35 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftPanelOdd1.java` — 36 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftPanelOdd2.java` — 36 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftTrackBase.java` — 49 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftTrackDiagonal.java` — 81 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftTrackFloor.java` — 116 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftTrackHorizontal.java` — 42 lines; complete source read.
- `src/main/java/org/mtr/block/BlockLiftTrackVertical.java` — 41 lines; complete source read.
- `src/main/java/org/mtr/block/BlockNode.java` — 165 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSBase.java` — 212 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSHorizontal1.java` — 45 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSHorizontal2.java` — 45 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSHorizontal3.java` — 50 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSHorizontalBase.java` — 113 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSPole.java` — 40 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSVertical1.java` — 37 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSVerticalBase.java` — 103 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPIDSVerticalSingleArrival1.java` — 55 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDAPGBase.java` — 44 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDAPGDoorBase.java` — 260 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDAPGGlassBase.java` — 94 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDAPGGlassEndBase.java` — 115 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDDoor.java` — 36 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDGlass.java` — 20 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDGlassEnd.java` — 20 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPSDTop.java` — 226 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPlatform.java` — 64 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPlatformSlab.java` — 50 lines; complete source read.
- `src/main/java/org/mtr/block/BlockPoleCheckBase.java` — 50 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRailwaySign.java` — 281 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRailwaySignPole.java` — 65 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRouteSignBase.java` — 81 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRouteSignStandingLight.java` — 47 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRouteSignStandingMetal.java` — 42 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRouteSignWallLight.java` — 45 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRouteSignWallMetal.java` — 36 lines; complete source read.
- `src/main/java/org/mtr/block/BlockRubbishBin.java` — 71 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalBase.java` — 221 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight2Aspect1.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight2Aspect2.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight2Aspect3.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight2Aspect4.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight3Aspect1.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight3Aspect2.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight4Aspect1.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLight4Aspect2.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalLightBase.java` — 32 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalSemaphore1.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalSemaphore2.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockSignalSemaphoreBase.java` — 39 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationColor.java` — 24 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationColorGlass.java` — 36 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationColorGlassSlab.java` — 52 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationColorPole.java` — 43 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationColorSlab.java` — 24 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameBase.java` — 49 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameEntrance.java` — 104 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameTallBase.java` — 151 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameTallBlock.java` — 47 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameTallBlockDoubleSided.java` — 47 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameTallStanding.java` — 59 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameTallWall.java` — 48 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameWallBase.java` — 57 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameWallBlack.java` — 31 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameWallGray.java` — 31 lines; complete source read.
- `src/main/java/org/mtr/block/BlockStationNameWallWhite.java` — 31 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTactileMap.java` — 91 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTicketBarrier.java` — 95 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTicketMachine.java` — 48 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTicketProcessor.java` — 96 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTicketProcessorEnquiry.java` — 30 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainAnnouncer.java` — 120 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainCargoLoader.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainCargoUnloader.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainPoweredSensorBase.java` — 59 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainRedstoneSensor.java` — 26 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainScheduleSensor.java` — 84 lines; complete source read.
- `src/main/java/org/mtr/block/BlockTrainSensorBase.java` — 98 lines; complete source read.
- `src/main/java/org/mtr/block/BlockWaterloggable.java` — 68 lines; complete source read.
- `src/main/java/org/mtr/block/DoubleVerticalBlock.java` — 48 lines; complete source read.
- `src/main/java/org/mtr/block/IBlock.java` — 178 lines; complete source read.
- `src/main/java/org/mtr/block/PlatformHelper.java` — 75 lines; complete source read.
- `src/main/java/org/mtr/block/TripleHorizontalBlock.java` — 68 lines; complete source read.
- `src/main/java/org/mtr/block/package-info.java` — 8 lines; complete source read.

### item

- `src/main/java/org/mtr/item/ItemBlockClickingBase.java` — 58 lines; complete source read.
- `src/main/java/org/mtr/item/ItemBlockEnchanted.java` — 18 lines; complete source read.
- `src/main/java/org/mtr/item/ItemBridgeCreator.java` — 21 lines; complete source read.
- `src/main/java/org/mtr/item/ItemBrush.java` — 39 lines; complete source read.
- `src/main/java/org/mtr/item/ItemCreativeDriverKey.java` — 17 lines; complete source read.
- `src/main/java/org/mtr/item/ItemDashboard.java` — 99 lines; complete source read.
- `src/main/java/org/mtr/item/ItemDepotDriverKey.java` — 55 lines; complete source read.
- `src/main/java/org/mtr/item/ItemDriverKey.java` — 35 lines; complete source read.
- `src/main/java/org/mtr/item/ItemEscalator.java` — 53 lines; complete source read.
- `src/main/java/org/mtr/item/ItemLiftButtonsLinkModifier.java` — 52 lines; complete source read.
- `src/main/java/org/mtr/item/ItemLiftRefresher.java` — 155 lines; complete source read.
- `src/main/java/org/mtr/item/ItemNodeModifierBase.java` — 116 lines; complete source read.
- `src/main/java/org/mtr/item/ItemNodeModifierSelectableBlockBase.java` — 196 lines; complete source read.
- `src/main/java/org/mtr/item/ItemPSDAPGBase.java` — 165 lines; complete source read.
- `src/main/java/org/mtr/item/ItemRailModifier.java` — 146 lines; complete source read.
- `src/main/java/org/mtr/item/ItemSignalModifier.java` — 59 lines; complete source read.
- `src/main/java/org/mtr/item/ItemTunnelCreator.java` — 19 lines; complete source read.
- `src/main/java/org/mtr/item/ItemTunnelWallCreator.java` — 21 lines; complete source read.
- `src/main/java/org/mtr/item/package-info.java` — 8 lines; complete source read.

### data

- `src/main/java/org/mtr/data/ArrivalsCache.java` — 134 lines; complete source read.
- `src/main/java/org/mtr/data/ArrivalsCacheClient.java` — 35 lines; complete source read.
- `src/main/java/org/mtr/data/ArrivalsCacheServer.java` — 60 lines; complete source read.
- `src/main/java/org/mtr/data/IGui.java` — 307 lines; complete source read.
- `src/main/java/org/mtr/data/PersistentStateData.java` — 61 lines; complete source read.
- `src/main/java/org/mtr/data/PersistentVehicleData.java` — 160 lines; complete source read.
- `src/main/java/org/mtr/data/RailAction.java` — 186 lines; complete source read.
- `src/main/java/org/mtr/data/RailActionModule.java` — 54 lines; complete source read.
- `src/main/java/org/mtr/data/RailActionType.java` — 19 lines; complete source read.
- `src/main/java/org/mtr/data/RailPathSnapshot.java` — 128 lines; complete source read.
- `src/main/java/org/mtr/data/RailType.java` — 59 lines; complete source read.
- `src/main/java/org/mtr/data/ServerRailPaths.java` — 285 lines; complete source read.
- `src/main/java/org/mtr/data/TicketSystem.java` — 202 lines; complete source read.
- `src/main/java/org/mtr/data/TrainType.java` — 182 lines; complete source read.
- `src/main/java/org/mtr/data/VehicleExtension.java` — 375 lines; complete source read.
- `src/main/java/org/mtr/data/package-info.java` — 8 lines; complete source read.

### sound

- `src/main/java/org/mtr/sound/BveConfigFile.java` — 241 lines; complete source read.
- `src/main/java/org/mtr/sound/BveMotorData4.java` — 118 lines; complete source read.
- `src/main/java/org/mtr/sound/BveMotorData5.java` — 103 lines; complete source read.
- `src/main/java/org/mtr/sound/BveMotorDataBase.java` — 10 lines; complete source read.
- `src/main/java/org/mtr/sound/BveVehicleSound.java` — 288 lines; complete source read.
- `src/main/java/org/mtr/sound/BveVehicleSoundConfig.java` — 42 lines; complete source read.
- `src/main/java/org/mtr/sound/LegacyVehicleSound.java` — 73 lines; complete source read.
- `src/main/java/org/mtr/sound/LoopingSoundInstance.java` — 65 lines; complete source read.
- `src/main/java/org/mtr/sound/ScheduledSound.java` — 63 lines; complete source read.
- `src/main/java/org/mtr/sound/VehicleLoopingSoundInstance.java` — 76 lines; complete source read.
- `src/main/java/org/mtr/sound/VehicleSoundBase.java` — 69 lines; complete source read.
- `src/main/java/org/mtr/sound/package-info.java` — 8 lines; complete source read.
