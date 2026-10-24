package org.mtr.model;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.nativeobj.NativeModel;
import org.mtr.nativeobj.NativeModelLoader;
import org.mtr.resource.RenderStage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class NewOptimizedModelGroup {

        public final ObjectArrayList<AABB> boxes = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<@Nullable RenderStage, Object2ObjectOpenHashMap<ResourceLocation, ObjectArrayList<StoredVertexData>>> storedVertexConsumersForRenderStageAndTexture = new Object2ObjectOpenHashMap<>();
        /**
         * Native channel: entries whose geometry lives on the C++ side (built by
         * the native OBJ loader) and never becomes Java vertex data. Kept parallel
         * to the vertex channel so {@link #merge} and {@link #build} treat both.
         */
        private final List<NativeEntry> nativeEntries = new ArrayList<>();
        /**
         * Protocol-v2 upload cache: the native mesh for each (render stage,
         * texture) bucket of the vertex channel, uploaded once on the first
         * {@link #build} and re-wrapped by every later build. Without the cache,
         * repeated builds on one shared group (per-position door models via
         * {@code VehicleResource.mapDoors}, per-(carNumber, totalCars) body
         * rebuilds) would upload duplicate GPU meshes and a large pack would
         * exhaust the native mesh budget. The cache lives exactly as long as the
         * group, and the group lives exactly as long as its native models (both
         * are rebuilt together on every resource reload, when the native side
         * frees every model), so a cached {@link NativeModel} can never outlive
         * its mesh.
         */
        private final Object2ObjectOpenHashMap<RenderStage, Object2ObjectOpenHashMap<ResourceLocation, NativeModel>> nativeUploadCache = new Object2ObjectOpenHashMap<>();

        public record NativeEntry(@Nullable RenderStage renderStage, ResourceLocation texture, NativeModel model, boolean colorSet) {
        }

        public void add(@Nullable RenderStage renderStage, ResourceLocation texture, Consumer<ObjectArrayList<StoredVertexData>> consumer, @Nullable ObjectArrayList<AABB> boxes) {
                final ObjectArrayList<StoredVertexData> storedVertexDataList = new ObjectArrayList<>();
                consumer.accept(storedVertexDataList);
                storedVertexConsumersForRenderStageAndTexture.computeIfAbsent(renderStage, key -> new Object2ObjectOpenHashMap<>()).computeIfAbsent(texture, key -> new ObjectArrayList<>()).addAll(storedVertexDataList);

                if (boxes == null) {
                        final double[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                        storedVertexDataList.forEach(storedVertexData -> {
                                bounds[0] = Math.min(bounds[0], storedVertexData.x());
                                bounds[1] = Math.min(bounds[1], storedVertexData.y());
                                bounds[2] = Math.min(bounds[2], storedVertexData.z());
                                bounds[3] = Math.max(bounds[3], storedVertexData.x());
                                bounds[4] = Math.max(bounds[4], storedVertexData.y());
                                bounds[5] = Math.max(bounds[5], storedVertexData.z());
                        });
                        this.boxes.add(new AABB(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
                } else {
                        this.boxes.addAll(boxes);
                }
        }

        public Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<NewOptimizedModel>> build(VertexFormat.Mode drawMode) {
                final Object2ObjectOpenHashMap<RenderStage, ObjectArrayList<NewOptimizedModel>> newOptimizedModels = new Object2ObjectOpenHashMap<>(RenderStage.values().length);
                storedVertexConsumersForRenderStageAndTexture.forEach((renderStage, storedVertexConsumerForTexture) -> {
                        if (renderStage != null) {
                                storedVertexConsumerForTexture.forEach((texture, storedVertexDataList) -> {
                                        // Dual-path build: the GL vertex buffer is ALWAYS created —
                                        // the render drain decides per frame whether the native mesh
                                        // (protocol v2) or the GL path draws this model, because
                                        // Beryl's pipeline can be toggled while the client runs.
                                        //
                                        // Native upload: when the runtime is active and the vertex
                                        // list is a whole number of primitives, the geometry is
                                        // uploaded ONCE per (stage, texture) via MSG_UPLOAD_MESH and
                                        // cached — build() runs repeatedly on shared groups
                                        // (per-position door models, per-car body rebuilds), and a
                                        // re-upload would duplicate the GPU mesh and blow the
                                        // native mesh budget on large packs. The cached model is
                                        // wrapped directly, exactly like the nativeEntries loop
                                        // below (a null upload falls back to the GL path; the cache
                                        // lives exactly as long as the group, and groups are
                                        // rebuilt together with their native models on every
                                        // resource reload).
                                        final Consumer<VertexConsumer> vertexCallback = storedVertexDataList.isEmpty() ? null : vertexConsumer -> StoredVertexData.apply(storedVertexDataList, vertexConsumer);
                                        final int stride = drawMode == VertexFormat.Mode.QUADS ? 4 : drawMode == VertexFormat.Mode.TRIANGLES ? 3 : 0;
                                        final NativeModel cached = nativeUploadCache
                                                        .computeIfAbsent(renderStage, key -> new Object2ObjectOpenHashMap<>())
                                                        .get(texture);
                                        if (cached != null) {
                                                newOptimizedModels.computeIfAbsent(renderStage, key -> new ObjectArrayList<>())
                                                                .add(new NewOptimizedModel(texture, drawMode, vertexCallback, cached));
                                                return;
                                        }
                                        if (org.mtr.nativeobj.NativeRuntime.isActive() && stride != 0 && !storedVertexDataList.isEmpty() && storedVertexDataList.size() % stride == 0) {
                                                final int textureKey = org.mtr.nativeobj.NativeTextureManager.INSTANCE.register(texture);
                                                final NativeModel nativeModel = NativeModelLoader.INSTANCE.uploadMesh(texture, textureKey, renderStage, stride == 4, storedVertexDataList);
                                                if (nativeModel != null) {
                                                        nativeUploadCache.computeIfAbsent(renderStage, key -> new Object2ObjectOpenHashMap<>()).put(texture, nativeModel);
                                                        newOptimizedModels.computeIfAbsent(renderStage, key -> new ObjectArrayList<>())
                                                                        .add(new NewOptimizedModel(texture, drawMode, vertexCallback, nativeModel));
                                                        return;
                                                }
                                        }
                                        newOptimizedModels
                                                        .computeIfAbsent(renderStage, key -> new ObjectArrayList<>())
                                                        .add(new NewOptimizedModel(texture, drawMode, vertexCallback));
                                });
                        }
                });
                for (final NativeEntry entry : nativeEntries) {
                        if (entry.renderStage() != null) {
                                newOptimizedModels.computeIfAbsent(entry.renderStage(), key -> new ObjectArrayList<>())
                                        .add(new NewOptimizedModel(entry.texture(), drawMode, null, entry.model()));
                        }
                }
                return newOptimizedModels;
        }

        /**
         * Register a natively-built mesh with this group. The AABB comes from the
         * native build reply, so {@link #boxes} stays exactly as informative as
         * the Java path's.
         */
        public void addNative(@Nullable RenderStage renderStage, ResourceLocation texture, NativeModel model) {
                final float[] aabb = model.aabb;
                boxes.add(new AABB(aabb[0], aabb[1], aabb[2], aabb[3], aabb[4], aabb[5]));
                nativeEntries.add(new NativeEntry(renderStage, texture, model, true));
        }

        /** Native entries of this group, for the placement merge. */
        public List<NativeEntry> nativeEntries() {
                return nativeEntries;
        }

        public void merge(NewOptimizedModelGroup newOptimizedModelGroup, RenderStage defaultRenderStage, double translateX, double translateY, double translateZ, boolean flip) {
                newOptimizedModelGroup.storedVertexConsumersForRenderStageAndTexture.forEach((renderStage, storedVertexConsumerForTexture) -> {
                        final RenderStage newRenderStage = renderStage == null ? defaultRenderStage : renderStage;
                        storedVertexConsumerForTexture.forEach((texture, storedVertexDataList) -> storedVertexDataList
                                .forEach(storedVertexData -> storedVertexConsumersForRenderStageAndTexture
                                        .computeIfAbsent(newRenderStage, key -> new Object2ObjectOpenHashMap<>())
                                        .computeIfAbsent(texture, key -> new ObjectArrayList<>())
                                        .add(storedVertexData.modify(translateX, translateY, translateZ, flip))
                                )
                        );
                });
                for (final NativeEntry entry : newOptimizedModelGroup.nativeEntries) {
                        final RenderStage newRenderStage = entry.renderStage() == null ? defaultRenderStage : entry.renderStage();
                        // One placed model owns one mesh per source material — keep
                        // them all; keeping only mesh zero dropped every material
                        // except the first (invisible vehicle parts).
                        final List<NativeModel> placedModels = NativeModelLoader.INSTANCE.place(entry.model(), newRenderStage, translateX, translateY, translateZ, flip);
                        for (final NativeModel placed : placedModels) {
                                boxes.add(new AABB(placed.aabb[0], placed.aabb[1], placed.aabb[2], placed.aabb[3], placed.aabb[4], placed.aabb[5]));
                                nativeEntries.add(new NativeEntry(newRenderStage, entry.texture(), placed, entry.colorSet()));
                        }
                }
        }
}
