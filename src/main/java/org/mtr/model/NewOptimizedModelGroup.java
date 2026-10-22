package org.mtr.model;

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
                                storedVertexConsumerForTexture.forEach((texture, storedVertexDataList) -> newOptimizedModels
                                        .computeIfAbsent(renderStage, key -> new ObjectArrayList<>())
                                        .add(new NewOptimizedModel(texture, drawMode, storedVertexDataList.isEmpty() ? null : vertexConsumer -> StoredVertexData.apply(storedVertexDataList, vertexConsumer)))
                                );
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
