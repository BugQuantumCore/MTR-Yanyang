package org.mtr.nativeobj;

import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.model.StoredVertexData;
import org.mtr.resource.RenderStage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The build-side protocol of the exchange: LOAD_OBJ / INSTANCE /
 * UPLOAD_MESH / FREE and the result decoding that turns native mesh records
 * into {@link NativeModel}s. The heavy geometry never crosses back into
 * Java — only ids, AABBs, colours and material names do.
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
                        // Byte-exact UTF-8 sizing: putString writes byte counts, so
                        // reserving String.length() (chars) under-reserves non-ASCII
                        // MTL content and overflows the body buffer — the model then
                        // silently falls back to the javagl path. Every string is
                        // encoded ONCE and the same bytes feed both the size
                        // reservation and the write.
                        final byte[] objBytes = objString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        final List<byte[]> mtlBytes = new ArrayList<>(mtlFiles.size() * 2);
                        int mtlLength = 0;
                        for (final String[] mtl : mtlFiles) {
                                final byte[] nameBytes = mtl[0].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                final byte[] contentBytes = mtl[1].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                mtlBytes.add(nameBytes);
                                mtlBytes.add(contentBytes);
                                mtlLength += nameBytes.length + contentBytes.length;
                        }
                        final ByteBuffer body = ByteBuffer.allocate(4 + objBytes.length + 4096 + 16 * mtlBytes.size() + mtlLength)
                                        .order(ByteOrder.LITTLE_ENDIAN);
                        body.putInt(requestId);
                        body.putInt((splitModel ? 1 : 0) | (flipTextureV ? 2 : 0));
                        putString(body, objBytes);
                        body.putInt(mtlFiles.size());
                        for (int i = 0; i < mtlFiles.size(); i++) {
                                putString(body, mtlBytes.get(i * 2));
                                putString(body, mtlBytes.get(i * 2 + 1));
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
                                // same texture/stage/colour/pool key as the source; the mesh id is the instance's
                                placed.add(new NativeModel(instanceModelId, instanceMeshId, source.texture, resolvedStage, source.colorPacked, aabb, source.textureKey));
                        }
                        return placed;
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native INSTANCE failed", throwable);
                        return List.of();
                }
        }

        /**
         * Publish MSG_UPLOAD_MESH and await the RES_INSTANCE-shaped reply.
         * This is the v2 entry point for meshes that originate on the Java
         * side — Blockbench quads and the javagl fallback — so EVERY
         * optimised-model draw under Vulkan can be recorded natively. The
         * vertex encoding mirrors the GPU 24-byte layout (f32 x,y,z,u,v +
         * snorm8 normal bytes packed as Java's {@code (byte)(n * 127.0F)},
         * truncation toward zero); the Kd is white because Blockbench colour
         * travels in the vertices.
         *
         * @return the uploaded mesh as a {@link NativeModel}, or
         * {@code null} when the runtime is inactive, the counts do not match
         * the draw mode, or the native build failed (the caller falls back
         * to the GL path)
         */
        @Nullable
        public NativeModel uploadMesh(ResourceLocation texture, int textureKey, RenderStage stage, boolean quads, List<StoredVertexData> vertices) {
                final NativeRuntime runtime = NativeRuntime.getInstance();
                if (runtime == null || stage == null || vertices == null || vertices.isEmpty()) {
                        return null;
                }
                final int vertexCount = vertices.size();
                if (vertexCount % (quads ? 4 : 3) != 0) {
                        return null;
                }
                try {
                        final int requestId = requestIds.incrementAndGet();
                        final ByteBuffer body = ByteBuffer.allocate(24 + vertexCount * WrxRegion.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                        body.putInt(requestId);
                        body.putInt(textureKey);
                        body.putInt(stage.ordinal());
                        body.putInt(quads ? 1 : 0);
                        body.putInt(vertexCount);
                        body.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF); // kd = white
                        for (final StoredVertexData vertex : vertices) {
                                body.putFloat(vertex.x());
                                body.putFloat(vertex.y());
                                body.putFloat(vertex.z());
                                body.putFloat(vertex.u());
                                body.putFloat(vertex.v());
                                body.put((byte) (vertex.normalX() * 127.0F));
                                body.put((byte) (vertex.normalY() * 127.0F));
                                body.put((byte) (vertex.normalZ() * 127.0F));
                                body.put((byte) 0); // pad
                        }
                        final byte[] bodyBytes = new byte[body.position()];
                        body.flip();
                        body.get(bodyBytes);
                        runtime.publish(ByteBuffer.allocate(4 + bodyBytes.length).order(ByteOrder.LITTLE_ENDIAN)
                                        .putInt(WrxRegion.MSG_UPLOAD_MESH).put(bodyBytes).array());

                        final ByteBuffer reply = runtime.awaitResult(requestId, 60_000);
                        if (reply == null) {
                                return null;
                        }
                        final int instanceModelId = reply.getInt();
                        final int meshCount = reply.getInt();
                        if (meshCount == 0 || instanceModelId == 0) {
                                return null; // rejected (bad counts / empty mesh) — caller falls back to GL
                        }
                        runtime.trackModel(instanceModelId);
                        // one mesh per upload — the whole vertex list is a single material
                        final int meshId = reply.getInt();
                        final float[] aabb = new float[6];
                        for (int j = 0; j < 6; j++) {
                                aabb[j] = reply.getFloat();
                        }
                        return new NativeModel(instanceModelId, meshId, texture, stage, 0xFFFFFFFF, aabb, textureKey);
                } catch (Throwable throwable) {
                        MTR.LOGGER.error("[wrvk] native UPLOAD_MESH failed", throwable);
                        return null;
                }
        }

        /** Length-prefixed UTF-8 string body: u32 byte count, then the bytes. */
        private static void putString(ByteBuffer buffer, byte[] utf8Bytes) {
                buffer.putInt(utf8Bytes.length);
                buffer.put(utf8Bytes);
        }

        public record NativeBuild(List<Group> groups) {

                public record Group(String name, int modelId, List<Mesh> meshes) {
                }

                public record Mesh(int meshId, int vertexCount, int indexCount, float[] aabb, int colorPacked, String name) {
                }
        }
}
