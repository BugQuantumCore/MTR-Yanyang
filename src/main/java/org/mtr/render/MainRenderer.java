package org.mtr.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.MTRClient;
import org.mtr.client.DynamicTextureCache;
import org.mtr.client.MinecraftClientData;
import org.mtr.client.VehicleRidingMovement;
import org.mtr.core.data.InterchangeColorsForStationName;
import org.mtr.data.ArrivalsCacheClient;
import org.mtr.data.IGui;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import org.mtr.model.NewOptimizedModel;
import org.mtr.registry.KeyBindings;
import org.mtr.resource.RenderStage;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Top-level coordinator for every per-frame rendering operation the mod performs.
 *
 * <p>Three responsibilities live here:</p>
 * <ol>
 *   <li><b>Tick management</b> — {@link #render(PoseStack, MultiBufferSource, Vec3)}
 *       is the single entry point the loader (Fabric / NeoForge) wires into the world-render
 *       pipeline. It advances {@link #timerMillis}, ticks {@link DynamicTextureCache},
 *       riding state, and the arrivals cache, then dispatches into the per-domain
 *       renderers ({@link RenderVehicles}, {@link RenderLifts}, {@link RenderRails}).</li>
 *   <li><b>Batched draw scheduling</b> — code anywhere in the mod can call
 *       {@link #scheduleRender(ResourceLocation, boolean, QueuedRenderLayer, ScheduledRender)} or
 *       {@link #renderModel(Object2ObjectOpenHashMap, StoredMatrixTransformations, int)} to
 *       enqueue draw work for the current frame. The drains at the end of
 *       {@link #render(PoseStack, MultiBufferSource, Vec3)} sort and dispatch by
 *       {@link QueuedRenderLayer} and {@link RenderStage}.</li>
 *   <li><b>Shared frame utilities</b> — {@link #getFlashingLight()},
 *       {@link #getFlashingColor(Color, int)}, and {@link #getInterchangeRouteNames(Consumer)}
 *       hand out values derived from the current frame timer / world state, so renderers
 *       don't reinvent them.</li>
 * </ol>
 *
 * <p>The class also exposes the shared {@link #WORKER_THREAD} used for off-main-thread work
 * (occlusion culling, dynamic-texture generation, model parsing). See
 * {@link WorkerThread} for the queueing contract.</p>
 *
 * <p><b>Threading:</b> every method on this class except {@code WORKER_THREAD}'s public
 * surface must be called on the render thread. The internal {@code RENDERS} /
 * {@code CURRENT_RENDERS} / {@code MODEL_RENDERS*} maps are not thread-safe.</p>
 *
 * @see WorkerThread
 * @see QueuedRenderLayer
 * @see NewOptimizedModel
 */
public class MainRenderer {

        /**
         * Get a continuously ticking timer for rendering, suitable for animations.
         * Returns a value in milliseconds representing the time elapsed, incremented when
         * {@link MainRenderer#render(PoseStack, MultiBufferSource, Vec3)} is invoked.
         */
        @Getter
        private static long timerMillis;
        private static long lastRenderedMillis;
        /**
         * Renderer-owner state mirror for the mid-session transition detector in
         * {@link #render} — {@code rendererOwnerInitialized} stays {@code false}
         * until the first frame establishes the baseline (the first frame must
         * not trigger a rebuild). The owner, not the raw Beryl flag, decides
         * when models must be rebuilt: {@code javaRenderer} is true while Beryl
         * drives the frame OR while the native pipeline is latched off (Beryl
         * at boot / failed boot / VulkanMod absent).
         */
        private static boolean rendererOwnerInitialized;
        private static boolean javaRendererLast;
        /**
         * Grace frames left after the most recent Beryl state flip. While the
         * countdown runs, {@link #render} keeps ticking the simulation but drops
         * every queued MTR draw (vehicles, lifts, rails, passengers, models,
         * scheduled renders), keeping MTR geometry entirely off the GPU while
         * Beryl's transition settles — see {@link #BERYL_TRANSITION_GRACE_FRAMES}.
         */
        private static int berylTransitionCountdown;
        /**
         * Set when the renderer OWNER flipped (native Vulkan &harr; Java GL) during the
         * grace window; the deferred {@code CustomResourceLoader.reload()} runs on
         * the first frame after the window instead of on the transition frame
         * itself (a multi-second blocking reload inside the frame that swaps
         * Beryl's MainPass and rebuilds every terrain section is exactly the kind
         * of load that takes the driver down).
         */
        private static boolean rebuildPendingAfterBerylTransition;
        /**
         * Length of the MTR draw pause around a Beryl pipeline flip.
         *
         * <p>Beryl 0.2.0-alpha toggles its pipeline on the render thread's key
         * handling — mid-session, between two frames. The flip replaces
         * VulkanMod's MainPass object, swaps the terrain vertex format and
         * builder, and forces a full terrain rebuild
         * ({@code LevelRenderer.setBlocksDirty()}); opening any screen (for
         * example Beryl's own options screen, where the shaders checkbox lives)
         * additionally frees every Beryl GPU object
         * ({@code MemoryManager.addFrameOp(RenderingPipeline::clearResources)}).
         * MTR's per-frame load — thousands of model quads, hundreds of passenger
         * models, dynamic texture uploads — submitted into that transition
         * window has repeatedly killed the driver outright (hard process death,
         * no Minecraft crash report and no JVM hs_err). Pausing MTR's drawing
         * for a second while Beryl settles removes every MTR draw from that
         * window at the cost of a one-second invisible-trains pause.</p>
         */
        private static final int BERYL_TRANSITION_GRACE_FRAMES = 60;

        /**
         * Shared off-main-thread worker for occlusion culling and dynamic texture generation.
         * Submit work via {@link WorkerThread#scheduleVehicles(Consumer)} and friends, or by
         * posting directly to {@link WorkerThread#worker} for one-shot tasks like model parsing.
         */
        public static final WorkerThread WORKER_THREAD = new WorkerThread();

        private static final int FLASHING_INTERVAL = 1000;
        private static final int TOTAL_RENDER_STAGES = 2;
        /**
         * Cached enum-values arrays. The render loop walks these many times per frame so we hold a single
         * shared snapshot. See {@code docs/PERFORMANCE.md} §3.3.
         */
        private static final QueuedRenderLayer[] QUEUED_RENDER_LAYERS = QueuedRenderLayer.values();
        /**
         * Reusable identifier for {@link #scheduleRender(QueuedRenderLayer, ScheduledRender)} — see {@code docs/PERFORMANCE.md} §4.3.
         */
        private static final ResourceLocation EMPTY_IDENTIFIER = ResourceLocation.parse("");
        private static final ObjectArrayList<ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>>> RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);
        private static final ObjectArrayList<ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>>> CURRENT_RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);
        private static final ObjectArrayList<ScheduledTextRender> TEXT_RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);
        private static final ObjectArrayList<ScheduledTextRender> CURRENT_TEXT_RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);
        private static final Object2ObjectOpenHashMap<NewOptimizedModel, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<ObjectIntImmutablePair<StoredMatrixTransformations>>>> MODEL_RENDERS = new Object2ObjectOpenHashMap<>();
        private static final Object2ObjectOpenHashMap<NewOptimizedModel, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<ObjectIntImmutablePair<StoredMatrixTransformations>>>> MODEL_RENDERS_TRANSLUCENT = new Object2ObjectOpenHashMap<>();
        /**
         * Free-list of per-texture callback lists, refilled when the drained current-frame maps are
         * swapped out. Avoids allocating a fresh list for every texture, every render layer, every
         * frame.
         */
        private static final ArrayDeque<ObjectArrayList<ScheduledRender>> RENDER_LIST_POOL = new ArrayDeque<>();
        private static final int RENDER_LIST_POOL_LIMIT = 256;
        private static final int RECYCLABLE_LIST_SIZE = 1024;

        static {
                for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
                        final int renderStageCount = QUEUED_RENDER_LAYERS.length;
                        final ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>> rendersList = new ObjectArrayList<>(renderStageCount);
                        final ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>> currentRendersList = new ObjectArrayList<>(renderStageCount);

                        for (int j = 0; j < renderStageCount; j++) {
                                rendersList.add(j, new Object2ObjectLinkedOpenHashMap<>());
                                currentRendersList.add(j, new Object2ObjectLinkedOpenHashMap<>());
                        }

                        RENDERS.add(i, rendersList);
                        CURRENT_RENDERS.add(i, currentRendersList);
                }
        }

        /**
         * Per-frame entry point invoked by the loader's world-render hook.
         *
         * <p>Advances {@link #timerMillis} by the elapsed wall-clock time, ticks the simulation
         * mirror (vehicles, lifts, riding state, arrivals cache, dynamic texture cache), then
         * dispatches into {@link RenderVehicles}, {@link RenderLifts} and {@link RenderRails}.
         * Finally drains the {@code MODEL_RENDERS*} maps and the scheduled-render queues into
         * actual draw calls grouped by render layer.</p>
         *
         * @param matrixStack            the current world matrix stack (caller-owned, do not retain)
         * @param vertexConsumerProvider the buffer source for this frame
         * @param offset                 the camera-relative offset for the world frame
         */
        public static void render(PoseStack matrixStack, MultiBufferSource vertexConsumerProvider, Vec3 offset) {
                final Minecraft minecraftClient = Minecraft.getInstance();
                final ClientLevel clientWorld = minecraftClient.level;
                final LocalPlayer clientPlayerEntity = minecraftClient.player;

                if (clientWorld == null || clientPlayerEntity == null) {
                        return;
                }

                // Idempotent, auto-detected: brings up the native Vulkan pipeline when
                // VulkanMod is driving the frame; a no-op on the GL path. Also
                // finalises a deferred teardown on this, the only thread that
                // ever unmaps the exchange region.
                org.mtr.nativeobj.NativeRuntime.initialise();
                org.mtr.nativeobj.NativeRuntime.tickTeardown();

                // Beryl's keybind toggles its shader pipeline while the client runs,
                // and the renderer that must own the models changes with it (native
                // under plain VulkanMod, Java GL under Beryl). Models are built for
                // one path at load time, so an owner change needs a full rebuild —
                // the same reload F3+T performs. Dual-path models (native mesh + GL
                // buffer) cover the pause while the deferred rebuild runs.
                //
                // ANY Beryl state flip opens the transition grace window first (see
                // BERYL_TRANSITION_GRACE_FRAMES): the reload — when the OWNER really
                // changed, tracked through the native pipeline's live state — fires on
                // the first frame after the window, never inside the transition frame.
                // With the native pipeline latched off (Beryl ran at boot — see
                // NativeRuntime#initialise) both sides of the toggle draw through the
                // Java GL path, the already-correct models are reused and the grace
                // window only costs the one-second draw pause.
                final boolean berylActiveNow = org.mtr.client.VulkanCompat.isVulkanModActive() && org.mtr.client.VulkanCompat.isBerylActive();
                final boolean javaRendererNow = berylActiveNow || !org.mtr.nativeobj.NativeRuntime.isActive();
                if (rendererOwnerInitialized && javaRendererNow != javaRendererLast) {
                        MTR.LOGGER.info("[wrvk] Beryl shader pipeline {} — pausing MTR drawing for {} frames while the transition settles, then reloading the models for the {} renderer",
                                        berylActiveNow ? "enabled" : "disabled",
                                        BERYL_TRANSITION_GRACE_FRAMES,
                                        javaRendererNow ? "Java GL" : "native Vulkan");
                        berylTransitionCountdown = BERYL_TRANSITION_GRACE_FRAMES;
                        rebuildPendingAfterBerylTransition = true;
                }
                javaRendererLast = javaRendererNow;
                rendererOwnerInitialized = true;

                final long millisElapsed = getMillisElapsed();
                timerMillis += millisElapsed;

                MinecraftClientData.getInstance().blockedRailIds.clear();
                MinecraftClientData.getInstance().vehicles.forEach(vehicle -> vehicle.simulate(millisElapsed));
                MinecraftClientData.getInstance().lifts.forEach(lift -> {
                        lift.tick(millisElapsed);
                        if (VehicleRidingMovement.isRiding(lift.getId()) && VehicleRidingMovement.showShiftProgressBar()) {
                                clientPlayerEntity.displayClientMessage(TranslationProvider.GUI_MTR_PRESS_TO_SELECT_FLOOR.getText(KeyBindings.LIFT_MENU.getTranslatedKeyMessage().getString()), true);
                        }
                });
                lastRenderedMillis = MTRClient.getGameMillis();
                WORKER_THREAD.start();
                DynamicTextureCache.instance.tick();
                // Tick the riding cool down (dismount player if they are no longer riding a vehicle) and store the player offset cache
                VehicleRidingMovement.tick();
                ArrivalsCacheClient.INSTANCE.tick();

                // Beryl transition grace window: the simulation above keeps ticking
                // (trains stay on schedule) but every MTR draw is dropped while Beryl
                // swaps its MainPass / vertex format and the full terrain rebuild churns
                // (see BERYL_TRANSITION_GRACE_FRAMES). Queued draw state is cleared so
                // nothing buffered before the flip bursts out at the end of the window;
                // a deferred owner rebuild (native Vulkan ↔ Java GL) fires on the first
                // frame AFTER the window instead of inside the transition frame.
                if (berylTransitionCountdown > 0) {
                        berylTransitionCountdown--;
                        MODEL_RENDERS.clear();
                        MODEL_RENDERS_TRANSLUCENT.clear();
                        for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
                                final ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>> rendersList = RENDERS.get(i);
                                for (int j = 0; j < QUEUED_RENDER_LAYERS.length; j++) {
                                        final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>> pendingMap = rendersList.get(j);
                                        pendingMap.forEach((identifier, renderList) -> renderList.clear());
                                        pendingMap.clear();
                                }
                        }
                        TEXT_RENDERS.clear();
                        if (berylTransitionCountdown == 0 && rebuildPendingAfterBerylTransition) {
                                rebuildPendingAfterBerylTransition = false;
                                MTR.LOGGER.info("[wrvk] Beryl transition settled — rebuilding the MTR models for the {} renderer", javaRendererLast ? "Java GL" : "native Vulkan");
                                org.mtr.client.CustomResourceLoader.reload();
                        }
                        return;
                }

                final Vec3 cameraShakeOffset = clientPlayerEntity.position().subtract(offset);
                RenderVehicles.render(millisElapsed, cameraShakeOffset);
                RenderLifts.render(millisElapsed, cameraShakeOffset);
                RenderRails.render(matrixStack, vertexConsumerProvider, offset);
                RenderPassengers.render();

                renderModel(matrixStack, MODEL_RENDERS, offset);
                renderModel(matrixStack, MODEL_RENDERS_TRANSLUCENT, offset);
                MODEL_RENDERS.clear();
                MODEL_RENDERS_TRANSLUCENT.clear();

                for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
                        final ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>> rendersList = RENDERS.get(i);
                        final ObjectArrayList<Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>>> currentRendersList = CURRENT_RENDERS.get(i);
                        for (int j = 0; j < QUEUED_RENDER_LAYERS.length; j++) {
                                // Swap the freshly filled pending map into the current-frame slot and recycle the
                                // drained map (plus its callback lists) for the next frame — no per-frame copying.
                                final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>> drainedMap = currentRendersList.set(j, rendersList.get(j));
                                rendersList.set(j, drainedMap);
                                drainedMap.forEach((identifier, renderList) -> recycleRenderList(renderList));
                                drainedMap.clear();
                        }
                }

                CURRENT_TEXT_RENDERS.clear();
                CURRENT_TEXT_RENDERS.addAll(TEXT_RENDERS);
                TEXT_RENDERS.clear();

                for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
                        for (int j = 0; j < QUEUED_RENDER_LAYERS.length; j++) {
                                final QueuedRenderLayer queuedRenderLayer = QUEUED_RENDER_LAYERS[j];
                                CURRENT_RENDERS.get(i).get(j).forEach((key, value) -> {
                                        final RenderType renderLayer = switch (queuedRenderLayer) {
                                                case LIGHT -> MoreRenderLayers.getLight(key, false);
                                                case LIGHT_TRANSLUCENT -> MoreRenderLayers.getLight(key, true);
                                                case LIGHT_2 -> MoreRenderLayers.getLight2(key);
                                                case INTERIOR -> MoreRenderLayers.getInterior(key);
                                                case INTERIOR_TRANSLUCENT -> MoreRenderLayers.getInteriorTranslucent(key);
                                                case EXTERIOR -> MoreRenderLayers.getExterior(key);
                                                case EXTERIOR_TRANSLUCENT -> MoreRenderLayers.getExteriorTranslucent(key);
                                                case LINES -> RenderType.lines();
                                        };
                                        value.forEach(renderer -> renderer.accept(matrixStack, vertexConsumerProvider.getBuffer(renderLayer), offset));
                                });
                        }
                }

                CURRENT_TEXT_RENDERS.forEach(scheduledTextRender -> scheduledTextRender.accept(matrixStack, offset));
        }

        /**
         * Enqueue a set of pre-built {@link NewOptimizedModel} instances to be drawn this frame
         * at the given transformation and light level. Models are grouped by render stage —
         * translucent stages drain after opaque stages so transparency sorts correctly.
         *
         * @param models                      the pre-built optimised models keyed by render stage
         * @param storedMatrixTransformations the transform applied at draw time
         * @param light                       packed lightmap value (block + sky in the low bits)
         */
        public static void renderModel(Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<NewOptimizedModel>> models, StoredMatrixTransformations storedMatrixTransformations, int light) {
                models.forEach((renderStage, newOptimizedModels) -> newOptimizedModels.forEach(newOptimizedModel -> (renderStage.isTranslucent ? MODEL_RENDERS_TRANSLUCENT : MODEL_RENDERS)
                        .computeIfAbsent(newOptimizedModel, key -> new Object2ObjectOpenHashMap<>())
                        .computeIfAbsent(renderStage, key -> new ObjectArrayList<>())
                        .add(new ObjectIntImmutablePair<>(storedMatrixTransformations, light))
                ));
        }

        /**
         * Schedule a vertex-consumer-style draw to run later this frame against the given
         * {@link QueuedRenderLayer}. Use {@code priority = true} when the draw should land in
         * front of normal-priority work (e.g. UI overlays drawn over world geometry).
         *
         * @param identifier        the texture identifier driving the render layer, or {@code null} to skip
         * @param priority          {@code true} for the priority bucket, {@code false} for normal
         * @param queuedRenderLayer the render-layer family to dispatch into
         * @param scheduledRender   the draw callback invoked during the drain pass
         */
        public static void scheduleRender(@Nullable ResourceLocation identifier, boolean priority, QueuedRenderLayer queuedRenderLayer, ScheduledRender scheduledRender) {
                if (identifier != null) {
                        final Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<ScheduledRender>> renders = RENDERS.get(priority ? 1 : 0).get(queuedRenderLayer.ordinal());
                        ObjectArrayList<ScheduledRender> renderList = renders.get(identifier);
                        if (renderList == null) {
                                renderList = RENDER_LIST_POOL.isEmpty() ? new ObjectArrayList<>() : RENDER_LIST_POOL.removeFirst();
                                renders.put(identifier, renderList);
                        }
                        renderList.add(scheduledRender);
                }
        }

        /**
         * Convenience overload of
         * {@link #scheduleRender(ResourceLocation, boolean, QueuedRenderLayer, ScheduledRender)}
         * for draws that aren't keyed to a specific texture (e.g. line / debug renders).
         */
        public static void scheduleRender(QueuedRenderLayer queuedRenderLayer, ScheduledRender scheduledRender) {
                scheduleRender(EMPTY_IDENTIFIER, false, queuedRenderLayer, scheduledRender);
        }

        /**
         * Schedule a text render to run later this frame.
         *
         * @param scheduledTextRender the draw callback invoked during the drain pass
         */
        public static void scheduleTextRender(ScheduledTextRender scheduledTextRender) {
                TEXT_RENDERS.add(scheduledTextRender);
        }

        /**
         * Drop every queued draw associated with the given identifier from both the pending
         * and current frame buckets. Called when a dynamic texture is evicted so stale draws
         * don't reference a disposed {@link DynamicTexture}.
         */
        public static void cancelRender(ResourceLocation identifier) {
                RENDERS.forEach(renderForPriority -> renderForPriority.forEach(renderForPriorityAndQueuedRenderLayer -> renderForPriorityAndQueuedRenderLayer.remove(identifier)));
                CURRENT_RENDERS.forEach(renderForPriority -> renderForPriority.forEach(renderForPriorityAndQueuedRenderLayer -> renderForPriorityAndQueuedRenderLayer.remove(identifier)));
        }

        /**
         * Returns an emptied callback list to the pool for reuse by later frames, dropping it
         * instead if it grew very large or the pool is full. Callback lists used to be allocated
         * fresh for every texture, every layer, every frame.
         */
        private static void recycleRenderList(ObjectArrayList<ScheduledRender> renderList) {
                if (renderList.size() <= RECYCLABLE_LIST_SIZE && RENDER_LIST_POOL.size() < RENDER_LIST_POOL_LIMIT) {
                        renderList.clear();
                        RENDER_LIST_POOL.add(renderList);
                }
        }

        /**
         * Flatten interchange data for a station into the comma-joined display string used by
         * "Interchange:" signs and PIDS rows.
         *
         * @param getInterchanges visitor producing {@code (connectingStationName, colors)} pairs
         * @return the formatted interchange list, ready to render
         */
        public static String getInterchangeRouteNames(Consumer<BiConsumer<String, InterchangeColorsForStationName>> getInterchanges) {
                final ObjectArrayList<String> interchangeRouteNames = new ObjectArrayList<>();
                getInterchanges.accept((connectingStationName, interchangeColorsForStationName) -> interchangeColorsForStationName.forEach((color, interchangeRouteNamesForColor) -> interchangeRouteNamesForColor.forEach(interchangeRouteNames::add)));
                return IGui.mergeStationsWithCommas(interchangeRouteNames);
        }

        /**
         * @return a packed lightmap value that pulses sinusoidally between 0 and 0xF on a
         * {@link #FLASHING_INTERVAL}-millisecond period. Suitable for signal-light and
         * door-warning glows.
         */
        public static int getFlashingLight() {
                final int light = (int) Math.round(((Math.sin(Math.PI * 2 * (getTimerMillis() % FLASHING_INTERVAL) / FLASHING_INTERVAL) + 1) / 2) * 0xF);
                return LightTexture.pack(light, light);
        }

        public static Color getFlashingColor(Color color, int multiplier) {
                final double flashingProgress = ((Math.sin(Math.PI * 2 * (getTimerMillis() % FLASHING_INTERVAL) / FLASHING_INTERVAL) + 1) / 2);
                return new Color(
                        (int) (color.getRed() * Math.min(1, flashingProgress * multiplier)),
                        (int) (color.getGreen() * Math.min(1, flashingProgress * multiplier)),
                        (int) (color.getBlue() * Math.min(1, flashingProgress * multiplier))
                );
        }

        public static int getFlashingColor(int color, int multiplier) {
                return getFlashingColor(new Color(color), multiplier).getRGB();
        }

        private static void renderModel(PoseStack matrixStack, Object2ObjectOpenHashMap<NewOptimizedModel, Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<ObjectIntImmutablePair<StoredMatrixTransformations>>>> modelRenders, Vec3 offset) {
                // Native Vulkan pipeline (protocol v2): models built through the exchange
                // carry a native mesh; their draws are queued into the shared-memory frame
                // slot as RAW pose matrices + the RAW packed lightmap int, and the C++ side
                // records them inside VulkanMod's command buffer — no model-view, normal or
                // colour math happens in Java on this path. GL models in the same bucket keep
                // drawing exactly as before.
                //
                // The gate is a RUNTIME Beryl check: while Beryl's shader pipeline is active
                // the models must draw through the Java renderer so Beryl shades them through
                // its own pipelines. Native-built models carry their GL vertex buffer too
                // (dual-path build), so this fallback is exact. The GL draw goes through
                // VertexBuffer#drawWithShader (never a raw draw), which under VulkanMod binds
                // the shader's current pipeline — translated or Beryl-redirected — instead of
                // re-using whatever pipeline the previous unrelated draw left bound.
                final org.mtr.nativeobj.NativeFrameRecorder nativeRecorder;
                if (org.mtr.nativeobj.NativeRuntime.isActive() && !org.mtr.client.VulkanCompat.isBerylActive()) {
                        nativeRecorder = new org.mtr.nativeobj.NativeFrameRecorder(org.mtr.nativeobj.NativeRuntime.getInstance());
                } else {
                        nativeRecorder = null;
                }
                if (nativeRecorder != null) {
                        // re-upload dynamic textures (PIDS displays, route maps) whose pixels
                        // changed since the last drain, before this frame's items land
                        org.mtr.nativeobj.NativeTextureManager.INSTANCE.flushDirty();
                }
                modelRenders.forEach((newOptimizedModel, modelsForRenderStage) -> modelsForRenderStage.forEach((renderStage, renderDetails) -> {
                        final org.mtr.nativeobj.NativeModel nativeModel = newOptimizedModel.getNativeModel();
                        if (nativeRecorder != null && nativeModel != null) {
                                renderDetails.forEach(renderDetailsEntry -> {
                                        renderDetailsEntry.left().transform(matrixStack, offset);
                                        nativeRecorder.queue(nativeModel, matrixStack.last().pose(), renderDetailsEntry.rightInt());
                                        matrixStack.popPose();
                                });
                                return;
                        }
                        final ResourceLocation texture = newOptimizedModel.texture;
                        // The light stages must NOT go through the beacon-beam render type while
                        // VulkanMod owns the GL. Every NewOptimizedModel buffer is uploaded in
                        // DefaultVertexFormat.NEW_ENTITY (36-byte stride), but the vanilla beacon
                        // beam render type — and therefore the VulkanMod-translated
                        // rendertype_beacon_beam pipeline — is built on DefaultVertexFormat.BLOCK
                        // (32-byte stride). OpenGL never notices (the VAO carries its own stride),
                        // and the native wrvk pipeline never touches GL buffers — but under
                        // Beryl, which does not redirect the beacon shader to any of its own
                        // pipelines, the mismatch reaches the GPU: the pipeline advances the
                        // vertex binding by 32 bytes per vertex through 36-byte vertices, so every
                        // vertex after the first reads 4 bytes early — positions, colours and UVs
                        // all smear across neighbours and the lamp / light-module parts render as
                        // huge stretched black bands sweeping through the train. That is the
                        // long-standing "train model rendering error" (see the yanyang.11
                        // changelog). Both replacement layers are NEW_ENTITY — the format the
                        // buffers are actually in — and Beryl's entity pipeline handles their
                        // baked full-bright lightmap exactly like the other stages.
                        final boolean lightStagesNeedNewEntity = org.mtr.client.VulkanCompat.isVulkanModActive();
                        final RenderType renderLayer = switch (renderStage) {
                                case LIGHT -> lightStagesNeedNewEntity ? MoreRenderLayers.getExterior(texture) : MoreRenderLayers.getLight(texture, false);
                                case ALWAYS_ON_LIGHT -> lightStagesNeedNewEntity ? MoreRenderLayers.getExteriorTranslucent(texture) : MoreRenderLayers.getLight(texture, true);
                                case INTERIOR -> MoreRenderLayers.getInterior(texture);
                                case INTERIOR_TRANSLUCENT -> MoreRenderLayers.getInteriorTranslucent(texture);
                                case EXTERIOR -> MoreRenderLayers.getExterior(texture);
                        };
                        renderLayer.setupRenderState();
                        renderDetails.forEach(renderDetailsEntry -> {
                                renderDetailsEntry.left().transform(matrixStack, offset);
                                // Per-draw dimming travels through ColorModulator. While Beryl drives
                                // the frame the multiplier is pinned at 1: the packed/15 formula below
                                // degenerates to 1 in almost every lit scene anyway, but it hits
                                // exact 0 in fully dark ones (block 0, sky 0) — multiplying
                                // ColorModulator to 0 erases everything Beryl's own lighting just
                                // computed (ambient, GGX direct light, shadow occlusion) and leaves
                                // pitch-black silhouettes. With the corrected per-stage Beryl bake
                                // (VulkanCompat#staticModelBakedLight) the division of labour under Beryl
                                // is: the shader pack owns the light, the colour modulator stays neutral.
                                final float lightMultiplier = renderStage.isFullBrightness || org.mtr.client.VulkanCompat.isBerylActive() ? 1 : (float) renderDetailsEntry.rightInt() / 0xF;
                                newOptimizedModel.render(matrixStack.last().pose(), lightMultiplier, RenderSystem.getShader());
                                matrixStack.popPose();
                        });
                        renderLayer.clearRenderState();
                }));

                if (nativeRecorder != null) {
                        nativeRecorder.submitAndClear();
                }
        }

        private static long getMillisElapsed() {
                final long millisElapsed = MTRClient.getGameMillis() - lastRenderedMillis;
                final long gameMillisElapsed = (long) (MTRClient.getGameTimeDeltaTicks() * 50);
                return Math.abs(gameMillisElapsed - millisElapsed) < 50 ? gameMillisElapsed : millisElapsed;
        }

        @FunctionalInterface
        public interface ScheduledRender {
                /**
                 * Run the deferred draw against the given buffer and offset.
                 *
                 * @param matrixStack    the frame's matrix stack, already positioned for world space
                 * @param vertexConsumer the buffer for the resolved {@code RenderLayer}
                 * @param offset         camera-relative world offset
                 */
                void accept(PoseStack matrixStack, VertexConsumer vertexConsumer, Vec3 offset);
        }

        @FunctionalInterface
        public interface ScheduledTextRender {
                /**
                 * Run the deferred text draw against the given buffer and offset.
                 *
                 * @param matrixStack the frame's matrix stack, already positioned for world space
                 * @param offset      camera-relative world offset
                 */
                void accept(PoseStack matrixStack, Vec3 offset);
        }
}
