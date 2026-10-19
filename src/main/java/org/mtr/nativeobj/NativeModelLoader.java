package org.mtr.nativeobj;

import org.mtr.MTR;

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

			final ByteBuffer reply = awaitResult(runtime, requestId, 60_000);
			if (reply == null) {
				return new NativeBuild(0, List.of());
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
	 */
	public NativeModel place(NativeModel source, double translateX, double translateY, double translateZ, boolean flip) {
		final NativeRuntime runtime = NativeRuntime.getInstance();
		if (runtime == null) {
			return null;
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

			final ByteBuffer reply = awaitResult(runtime, requestId, 60_000);
			if (reply == null) {
				return null;
			}
			final int instanceModelId = reply.getInt();
			final int meshCount = reply.getInt();
			if (meshCount == 0 || instanceModelId == 0) {
				return null;
			}
			final int instanceMeshId = reply.getInt();
			final float[] aabb = new float[6];
			for (int i = 0; i < 6; i++) {
				aabb[i] = reply.getFloat();
			}
			// same texture/stage/colour as the source; the mesh id is the instance's
			return new NativeModel(instanceModelId, instanceMeshId, source.texture, source.renderStage, source.colorPacked, aabb);
		} catch (Throwable throwable) {
			MTR.LOGGER.error("[wrvk] native INSTANCE failed", throwable);
			return null;
		}
	}

	/** Poll the results queue until the reply with the matching request id lands. */
	private ByteBuffer awaitResult(NativeRuntime runtime, int requestId, long timeoutMillis) {
		final long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			for (final WrxRegion.ResultMessage message : runtime.regionForTests().consumeResults()) {
				if (message.type() == WrxRegion.RES_MODELS || message.type() == WrxRegion.RES_INSTANCE || message.type() == WrxRegion.RES_ERROR) {
					final ByteBuffer payload = ByteBuffer.wrap(message.payload()).order(ByteOrder.LITTLE_ENDIAN);
					if (payload.remaining() >= 4 && payload.getInt(0) == requestId) {
						if (message.type() == WrxRegion.RES_ERROR) {
							MTR.LOGGER.error("[wrvk] native build reported error for request {}", requestId);
							return null;
						}
						// skip the echoed request id; the caller reads the payload body
						payload.position(4);
						return payload;
					}
				}
			}
			NativeFrameRecorder.spin(250);
		}
		MTR.LOGGER.error("[wrvk] native build reply for request {} timed out", requestId);
		return null;
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
