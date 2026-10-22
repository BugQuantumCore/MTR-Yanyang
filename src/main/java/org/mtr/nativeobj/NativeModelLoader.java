package org.mtr.nativeobj;

import org.mtr.MTR;
import org.mtr.resource.RenderStage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The build-side protocol of the exchange: LOAD_OBJ / INSTANCE / FREE and
 * the result decoding that turns native mesh records into
 * {@link NativeModel}s. The heavy geometry never crosses back into Java —
 * only ids, AABBs, colours and material names do.
 */
public final class NativeModelLoader {

        public static final NativeModelLoader INSTANCE = new NativeModelLoader();

        private final AtomicInteger requestIds = new AtomicInteger();

        private NativeModelLoader() {
        }

        /**
         * Publish MSG_LOAD_OBJ and wait for the reply. Called from the model
         * loader's worker thread; bounded wait, and every failure returns an empty
         * list so the loader logs and falls back gracefully.
         */
        public NativeBuild loadObj(String objString, List<String[]> mtlFiles, boolean splitModel, boolean flipTextureV) {
                final NativeRuntime runtime = NativeRuntime.getInstance();
                if (runtime == null) {
                        return new NativeBuild(List.of());
                }
                try {
                        final int requestId = requestIds.incrementAndGet();
                        final int objLength = objString.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                        final ByteBuffer body = ByteBuffer.allocate(4 + objLength + 4096 + mtlFiles.stream().mapToInt(parts -> parts.length * 16 + parts[0].length() + parts[1].length()).sum())
                                        .order(ByteOrder.LITTLE_ENDIAN);
                        body.putInt(requestId);
                        body.putInt((splitModel ? 1 : 0) | (flipTextureV ? 2 : 0));
                        putString(body, objString);
                        body.putInt(mtlFiles.size());
                        for (final String[] mtl : mtlFiles) {
                                putString(body, mtl[0]);
                                putString(body, mtl[1]);
                        }
                        final byte[] bodyBytes = new byte[body.position()];
                        body.flip();
                        body.get(bodyBytes);
                        runtime.publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                        .putInt(WrxRegion.MSG_LOAD_OBJ).put(bodyBytes).array());

                        final ByteBuffer reply = runtime.awaitResult(requestId, 60_000);
                        if (reply == null) {
                                return new NativeBuild(List.of());
                        }
                        // reply layout: requestId (skipped by awaitResult), groupCount,
                        // per group {name, modelId, meshCount, meshes...}
                        final int groupCount = reply.getInt();
                        final List<NativeBuild.Group> groups = new ArrayList<>(groupCount);
                        for (int g = 0; g < groupCount; g++) {
                                final int groupNameLength = reply.getInt();
                                final byte[] groupNameBytes = new byte[groupNameLength];
                                reply.get(groupNameBytes);
                                final String groupName = new String(groupNameBytes, java.nio.charset.StandardCharsets.UTF_8);
                                final int modelId = reply.getInt();
                                final int meshCount = reply.getInt();
                                final List<NativeBuild.Mesh> meshes = new ArrayList<>(meshCount);
                                for (int i = 0; i < meshCount; i++) {
                                        final int meshId = reply.getInt();
                                        final int vertexCount = reply.getInt();
                                        final int indexCount = reply.getInt();
                                        final float[] aabb = new float[6];
                                        for (int j = 0; j < 6; j++) {
                                                aabb[j] = reply.getFloat();
                                        }
                                        final int kdR = reply.getInt();
                                        final int kdG = reply.getInt();
                                        final int kdB = reply.getInt();
                                        final int kdA = reply.getInt();
                                        final int nameLength = reply.getInt();
                                        final byte[] nameBytes = new byte[nameLength];
                                        reply.get(nameBytes);
                                        meshes.add(new NativeBuild.Mesh(meshId, vertexCount, indexCount, aabb,
                                                        kdR << 24 | kdG << 16 | kdB << 8 | kdA, new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)));
                                }
                                runtime.trackModel(modelId);
                                groups.add(new NativeBuild.Group(groupName, modelId, meshes));
                        }
                        return new NativeBuild(groups);
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native LOAD_OBJ failed", throwable);
                        return new NativeBuild(List.of());
                }
        }

        /**
         * Publish MSG_INSTANCE for a placement merge and await the reply. Mirrors
         * {@code NewOptimizedModelGroup.merge}: translate + optional flip; identity
         * placements alias the source GPU data (no copy).
         *
         * <p>The reply carries one mesh per source material — a placed
         * multi-material model owns several mesh ids, and every one of them is
         * returned (the first revision kept only mesh zero and silently dropped
         * every other material).</p>
         *
         * @param resolvedStage the stage the placement resolves to (the merge
         *                      default when the source entry has none)
         */
        public List<NativeModel> place(NativeModel source, RenderStage resolvedStage, double translateX, double translateY, double translateZ, boolean flip) {
                final NativeRuntime runtime = NativeRuntime.getInstance();
                if (runtime == null) {
                        return List.of();
                }
                try {
                        final int requestId = requestIds.incrementAndGet();
                        final ByteBuffer body = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                        body.putInt(requestId);
                        body.putInt(source.modelId);
                        body.putInt(flip ? 1 : 0);
                        body.putFloat((float) translateX);
                        body.putFloat((float) translateY);
                        body.putFloat((float) translateZ);
                        final byte[] bodyBytes = new byte[body.position()];
                        body.flip();
                        body.get(bodyBytes);
                        runtime.publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                        .putInt(WrxRegion.MSG_INSTANCE).put(bodyBytes).array());

                        final ByteBuffer reply = runtime.awaitResult(requestId, 60_000);
                        if (reply == null) {
                                return List.of();
                        }
                        final int instanceModelId = reply.getInt();
                        final int meshCount = reply.getInt();
                        if (meshCount == 0 || instanceModelId == 0) {
                                return List.of();
                        }
                        // Every placed model gets a fresh native model id (even
                        // identity aliases) — track it so resource reloads free it
                        runtime.trackModel(instanceModelId);
                        final List<NativeModel> placed = new ArrayList<>(meshCount);
                        for (int i = 0; i < meshCount; i++) {
                                final int instanceMeshId = reply.getInt();
                                final float[] aabb = new float[6];
                                for (int j = 0; j < 6; j++) {
                                        aabb[j] = reply.getFloat();
                                }
                                // same texture/stage/colour as the source; the mesh id is the instance's
                                placed.add(new NativeModel(instanceModelId, instanceMeshId, source.texture, resolvedStage, source.colorPacked, aabb));
                        }
                        return placed;
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native INSTANCE failed", throwable);
                        return List.of();
                }
        }

        private static void putString(ByteBuffer buffer, String value) {
                final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buffer.putInt(bytes.length);
                buffer.put(bytes);
        }

        public record NativeBuild(List<Group> groups) {

                public record Group(String name, int modelId, List<Mesh> meshes) {
                }

                public record Mesh(int meshId, int vertexCount, int indexCount, float[] aabb, int colorPacked, String name) {
                }
        }
}
