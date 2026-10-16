package org.mtr.mod.resource.nativeobj;

import org.junit.jupiter.api.Test;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.vertex.Vertex;

import de.javagl.obj.FloatTuple;
import de.javagl.obj.Mtl;
import de.javagl.obj.MtlReader;
import de.javagl.obj.Obj;
import de.javagl.obj.ObjFace;
import de.javagl.obj.ObjReader;
import de.javagl.obj.ObjSplitting;
import de.javagl.obj.ObjUtils;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Byte-exact equivalence between the native OBJ pipeline and the Java one.
 *
 * <h2>Why this test lives in the Gradle test source set</h2>
 *
 * <p>The golden generator in {@code native/tools} cannot run standalone: the published mappings
 * jar is compiled against an intermediate namespace and only becomes {@code net.minecraft.*}
 * after Loom remaps it, so {@code MaterialProperties} cannot even be class-loaded outside a
 * development environment. This source set has the remapped jars and the full Minecraft
 * classpath, which makes it the only place the real pipeline can be executed. The pure-native
 * tests in {@code native/tests} cover the same ground without a JVM, so the equivalence claim is
 * checkable in both environments.</p>
 *
 * <h2>What is compared</h2>
 *
 * <p>For each case in {@code native/tests/golden/manifest.txt} the test does two things:</p>
 *
 * <ol>
 *   <li>runs the reference pipeline - the real {@code ObjReader}, {@code ObjSplitting},
 *       {@code ObjUtils.convertToRenderable}, {@code RawMesh} and {@code RawModel} - and packs
 *       the result exactly as {@code RawMesh.upload} does;</li>
 *   <li>runs the same case through {@link NativeObjModel} and packs the result the same way.</li>
 * </ol>
 *
 * <p>The two byte arrays, the index arrays and the per-bucket records must match exactly. A
 * mismatch in vertex order shows up as a byte difference even when the rendered image would be
 * identical, which is the point: the GPU buffer has to be the same buffer.</p>
 *
 * <h2>When native is unavailable</h2>
 *
 * <p>The test is skipped rather than failed, so a checkout without a native build still has a
 * green suite. Produce the library with {@code native/tools/build-native.ps1} to run it.</p>
 */
public class NativeObjEquivalenceTest {

	/** Must match {@code OptimizedModel.DEFAULT_MAPPING} and the native ABI. */
	private static final int VERTEX_STRIDE = 24;

	private static final Path GOLDEN_DIRECTORY = Paths.get("..", "native", "tests", "golden");

	// -----------------------------------------------------------------------
	// The test
	// -----------------------------------------------------------------------

	@Test
	public void nativePipelineReproducesTheJavaBuffers() throws Exception {
		assumeTrue(NativeObjPipeline.isAvailable(),
				"the native wrobj library is not available: " + NativeObjPipeline.getStatusMessage());

		final Path manifest = GOLDEN_DIRECTORY.resolve("manifest.txt");
		assumeTrue(Files.isRegularFile(manifest), "the golden manifest is missing at " + manifest);

		final List<ReferenceCase> cases = readManifest(manifest);
		assertTrue(cases.size() >= 7, "the manifest should cover every decision point, found " + cases.size());

		for (final ReferenceCase testCase : cases) {
			compareCase(manifest.getParent(), testCase);
		}
	}

	private void compareCase(Path directory, ReferenceCase testCase) throws IOException {
		final Path objPath = directory.resolve(testCase.objPath);
		final Path mtlPath = testCase.mtlPath == null ? null : directory.resolve(testCase.mtlPath);

		final String objText = new String(Files.readAllBytes(objPath), StandardCharsets.UTF_8);
		final String mtlText = mtlPath == null ? null
				: new String(Files.readAllBytes(mtlPath), StandardCharsets.UTF_8);

		final ModelBuffers reference = buildReference(testCase, objText, mtlText,
				objPath.getFileName().toString());
		final ModelBuffers nativeBuffers = buildNative(testCase, objText, mtlText,
				objPath.getFileName().toString());

		final String label = testCase.name;

		assertEquals(reference.keys.size(), nativeBuffers.keys.size(),
				label + ": material key count");
		for (int i = 0; i < Math.min(reference.keys.size(), nativeBuffers.keys.size()); i++) {
			final KeyRecord expected = reference.keys.get(i);
			final KeyRecord actual = nativeBuffers.keys.get(i);
			assertEquals(expected.shaderType, actual.shaderType,
					label + ": key " + i + " shader type");
			assertEquals(expected.color, actual.color, label + ": key " + i + " colour");
			// The texture string is compared before resolution, so a normalisation difference
			// fails here with a readable message instead of surfacing as a bucket mismatch.
			assertEquals(expected.texture, actual.texture,
					label + ": key " + i + " resolved texture string");
		}

		assertEquals(reference.draws.size(), nativeBuffers.draws.size(),
				label + ": draw bucket count");
		for (int i = 0; i < Math.min(reference.draws.size(), nativeBuffers.draws.size()); i++) {
			assertEquals(reference.draws.get(i).toString(), nativeBuffers.draws.get(i).toString(),
					label + ": bucket " + i);
		}

		assertEquals(reference.vertexData.length, nativeBuffers.vertexData.length,
				label + ": vertex buffer size");
		assertEquals(reference.indexData.length, nativeBuffers.indexData.length,
				label + ": index buffer size");

		for (int i = 0; i < reference.vertexData.length; i++) {
			if (reference.vertexData[i] != nativeBuffers.vertexData[i]) {
				final int vertexIndex = i / VERTEX_STRIDE;
				assertEquals(describeVertex(reference.vertexData, vertexIndex),
						describeVertex(nativeBuffers.vertexData, vertexIndex),
						label + ": first vertex difference at vertex " + vertexIndex
								+ ", byte " + (i % VERTEX_STRIDE));
				// describeVertex matched, so the difference was in the padding byte only.
				assertEquals(reference.vertexData[i], nativeBuffers.vertexData[i],
						label + ": padding byte at vertex " + vertexIndex);
			}
		}

		for (int i = 0; i < reference.indexData.length; i += 4) {
			assertEquals(readInt(reference.indexData, i), readInt(nativeBuffers.indexData, i),
					label + ": index " + (i / 4));
		}
	}

	// -----------------------------------------------------------------------
	// Reference pipeline
	// -----------------------------------------------------------------------

	/**
	 * Runs the Java pipeline and packs its output the way {@code RawMesh.upload} does.
	 *
	 * <p>The only piece re-implemented rather than called is the shell around
	 * {@code OptimizedModel.ObjModel}: its constructor takes an {@code Identifier} and its
	 * bookkeeping is trivial, while every geometric decision still runs in the real
	 * {@code RawMesh} code.</p>
	 */
	private ModelBuffers buildReference(ReferenceCase testCase, String objText, String mtlText,
			String modelResource) throws IOException {
		final Obj sourceObj = ObjReader.read(new ByteArrayInputStream(objText.getBytes(StandardCharsets.UTF_8)));

		final Map<String, Mtl> materials = new HashMap<>();
		if (mtlText != null && !mtlText.isEmpty()) {
			MtlReader.read(new ByteArrayInputStream(mtlText.getBytes(StandardCharsets.UTF_8)))
					.forEach(mtl -> materials.put(mtl.getName(), mtl));
		}

		final ModelBuffers buffers = new ModelBuffers();
		final Map<MaterialKey, org.mtr.mapping.render.model.RawMesh> accumulated = new LinkedHashMap<>();

		ObjSplitting.splitByGroups(sourceObj).forEach((groupName, groupObj) -> {
			final Map<String, Mtl> effective = testCase.materialGroupTextures ? materials : new HashMap<>();

			ObjSplitting.splitByMaterialGroups(groupObj).forEach((groupKey, materialObj) -> {
				if (materialObj.getNumFaces() == 0) {
					return;
				}

				final Map<String, String> options = splitMaterialOptions(groupKey);
				final String materialGroupName = options.get("");
				final OptimizedModel.ShaderType shaderType = legacyMapping(options.getOrDefault("#", ""));
				final boolean groupFlipV = "1".equals(options.getOrDefault("flipv", "0"));

				final String texture;
				final Integer color;
				if (!effective.isEmpty()) {
					final Mtl objMaterial = effective.get(groupKey);
					if (objMaterial == null) {
						texture = "";
						color = null;
					} else {
						texture = objMaterial.getMapKd() == null || objMaterial.getMapKd().isEmpty()
								? "" : objMaterial.getMapKd();
						final FloatTuple kd = objMaterial.getKd();
						color = kd == null ? mergeColor(255, 255, 255, 255)
								: mergeColor((int) (kd.getX() * 255.0F), (int) (kd.getY() * 255.0F),
										(int) (kd.getZ() * 255.0F),
										(int) (objMaterial.getD() * 255.0F));
					}
				} else {
					texture = materialGroupName.equals("_") ? "" : materialGroupName;
					color = mergeColor(255, 255, 255, 255);
				}

				final String resolved = resolveTexture(texture, modelResource);
				final Obj renderable = ObjUtils.convertToRenderable(materialObj);

				final org.mtr.mapping.render.model.RawMesh mesh =
						new org.mtr.mapping.render.model.RawMesh(
								new org.mtr.mapping.render.batch.MaterialProperties(shaderType, null, color));

				for (int i = 0; i < renderable.getNumVertices(); i++) {
					final Vertex vertex = new Vertex();
					final FloatTuple position = renderable.getVertex(i);
					vertex.position = new org.mtr.mapping.holder.Vector3f(
							position.getX(), position.getY(), position.getZ());
					if (i < renderable.getNumNormals()) {
						final FloatTuple normal = renderable.getNormal(i);
						vertex.normal = new org.mtr.mapping.holder.Vector3f(
								normal.getX(), normal.getY(), normal.getZ());
					} else {
						vertex.normal = new org.mtr.mapping.holder.Vector3f(0.0F, 0.0F, 0.0F);
					}
					if (i < renderable.getNumTexCoords()) {
						final FloatTuple uv = renderable.getTexCoord(i);
						vertex.u = uv.getX();
						vertex.v = (groupFlipV || testCase.flipTextureV) ? 1.0F - uv.getY() : uv.getY();
					} else {
						vertex.u = 0.0F;
						vertex.v = 0.0F;
					}
					mesh.vertices.add(vertex);
				}

				for (int i = 0; i < renderable.getNumFaces(); i++) {
					final ObjFace face = renderable.getFace(i);
					mesh.faces.add(new Face(new int[]{
							face.getVertexIndex(0), face.getVertexIndex(1), face.getVertexIndex(2)}));
				}

				mesh.validateVertexIndex();

				// ObjModel.loadModel rotates every source mesh by 180 degrees about X.
				mesh.applyRotation(new org.mtr.mapping.holder.Vector3f(1.0F, 0.0F, 0.0F), 180.0F);

				// Model-level transform, i.e. ObjectResource.apply*, then the vehicle-level one
				// from StoredModelResourceBase.transform. The order is what the load path does:
				// the model resource's own transform runs first, with rotation X then Y then Z,
				// and each ObjectResource applyScale recomputes the normals.
				applyTransform(mesh, testCase.modelTranslation, testCase.modelRotation,
						testCase.modelScale, testCase.modelMirror);
				applyTransform(mesh, testCase.schemaTranslation, testCase.schemaRotation,
						testCase.schemaScale, testCase.schemaMirror);

				final MaterialKey key = new MaterialKey(shaderType, resolved, color);
				buffers.recordKey(key);
				accumulated.computeIfAbsent(key, k -> new org.mtr.mapping.render.model.RawMesh(
						new org.mtr.mapping.render.batch.MaterialProperties(shaderType, null, color)))
						.append(mesh);
			});
		});

		packBuffers(buffers, accumulated, testCase.transforms);
		return buffers;
	}

	/** Applies the placements, then generates normals, de-duplicates and packs. */
	private void packBuffers(ModelBuffers buffers,
			Map<MaterialKey, org.mtr.mapping.render.model.RawMesh> accumulated,
			List<double[]> transforms) {
		final Map<MaterialKey, org.mtr.mapping.render.model.RawMesh> placed = new LinkedHashMap<>();

		for (final double[] transform : transforms) {
			final OptimizedModel.ShaderType shaderType =
					OptimizedModel.ShaderType.values()[(int) transform[0]];
			for (final Map.Entry<MaterialKey, org.mtr.mapping.render.model.RawMesh> entry : accumulated.entrySet()) {
				final MaterialKey key = entry.getKey();
				final MaterialKey placedKey = key.shaderType == OptimizedModel.ShaderType.CUTOUT
						? new MaterialKey(shaderType, key.texture, key.color) : key;
				final org.mtr.mapping.render.model.RawMesh copy =
						new org.mtr.mapping.render.model.RawMesh(entry.getValue().materialProperties);
				entry.getValue().vertices.forEach(vertex -> copy.vertices.add(new Vertex(vertex)));
				entry.getValue().faces.forEach(face -> copy.faces.add(new Face(face)));
				copy.applyTranslation((float) transform[1], (float) transform[2], (float) transform[3]);
				if (transform[4] != 0.0) {
					copy.applyRotation(new org.mtr.mapping.holder.Vector3f(0.0F, 1.0F, 0.0F), 180.0F);
				}
				placed.computeIfAbsent(placedKey, k -> new org.mtr.mapping.render.model.RawMesh(
						new org.mtr.mapping.render.batch.MaterialProperties(k.shaderType, null, k.color)))
						.append(copy);
			}
		}

		final ByteArrayOutputStream vertexBytes = new ByteArrayOutputStream();
		final ByteArrayOutputStream indexBytes = new ByteArrayOutputStream();
		int vertexOffset = 0;
		int indexOffset = 0;

		for (final Map.Entry<MaterialKey, org.mtr.mapping.render.model.RawMesh> entry : placed.entrySet()) {
			final org.mtr.mapping.render.model.RawMesh mesh = entry.getValue();
			mesh.generateNormals();
			mesh.distinct();
			if (mesh.faces.isEmpty() || mesh.vertices.isEmpty()) {
				continue;
			}

			buffers.draws.add(new DrawRecord(
					entry.getKey().shaderType.ordinal(),
					entry.getKey().color == null ? 0xFFFFFFFF : entry.getKey().color,
					buffers.keys.indexOf(entry.getKey()),
					vertexOffset, mesh.vertices.size(),
					indexOffset, mesh.faces.size() * 3));

			for (final Vertex vertex : mesh.vertices) {
				writeFloat(vertexBytes, vertex.position.getX());
				writeFloat(vertexBytes, vertex.position.getY());
				writeFloat(vertexBytes, vertex.position.getZ());
				writeFloat(vertexBytes, vertex.u);
				writeFloat(vertexBytes, vertex.v);
				final Vector3f normal = new Vector3f(vertex.normal.getX(), vertex.normal.getY(),
						vertex.normal.getZ());
				normal.normalize();
				vertexBytes.write((byte) (normal.x * 127.0F));
				vertexBytes.write((byte) (normal.y * 127.0F));
				vertexBytes.write((byte) (normal.z * 127.0F));
				vertexBytes.write(0);
			}

			for (final Face face : mesh.faces) {
				for (final int index : face.vertices) {
					writeInt(indexBytes, index + vertexOffset);
				}
			}

			vertexOffset += mesh.vertices.size();
			indexOffset += mesh.faces.size() * 3;
		}

		buffers.vertexData = vertexBytes.toByteArray();
		buffers.indexData = indexBytes.toByteArray();
	}

	// -----------------------------------------------------------------------
	// Native pipeline
	// -----------------------------------------------------------------------

	/**
	 * Runs the same case through {@link NativeObjModel}, i.e. through the path the mod uses.
	 *
	 * <p>Going through {@code NativeObjModel} rather than the raw JNI entry points means this
	 * also covers the Java-side integration: the texture table, the handle resolver, the bounds
	 * bookkeeping and the copy semantics.</p>
	 */
	private ModelBuffers buildNative(ReferenceCase testCase, String objText, String mtlText,
			String modelResource) {
		final byte[] objBytes = objText.getBytes(StandardCharsets.UTF_8);
		final byte[] mtlBytes = mtlText == null ? null : mtlText.getBytes(StandardCharsets.UTF_8);
		final boolean materialGroupTextures = mtlText != null && !mtlText.isEmpty();

		// A null result is a legitimate outcome, not a failure: the native key list is null
		// when the document has no material groups at all, which is what javagl does too.
		// ObjSplitting.splitByMaterialGroups iterates obj.getNumMaterialGroups(), and
		// ObjReader creates no material group when the document carried no usemtl statement,
		// so such a document has no buckets and ObjModelLoader.loadModel returns an empty
		// mesh list. Treating null as an error here would hide the difference rather than
		// check it, and the reference side is compared against below either way.
		final NativeObjKey[] keys =
				NativeObjPipeline.loadKeys(objBytes, mtlBytes, testCase.flipTextureV);

		final ModelBuffers buffers = new ModelBuffers();
		final Identifier[] textures = new Identifier[keys == null ? 0 : keys.length];
		final String[] lookupKeys = new String[keys == null ? 0 : keys.length];
		for (int i = 0; i < textures.length; i++) {
			textures[i] = new Identifier("test", "texture/" + i + ".png");
			lookupKeys[i] = keys[i].textureString + '\u0000' + keys[i].shaderType;
			// The native key's textureString is already normalised by the native parser, and it
			// is what the reference side compares against.
			buffers.keys.add(new KeyRecord(keys[i].shaderType, keys[i].color, keys[i].textureString));
		}

		// The native handle for a key is its index, so the resolver returns the index and the
		// recorded texture is the resolved string. That makes texture identity comparable to the
		// reference without needing real texture files.
		final NativeTextureResolver resolver = (textureString, shaderType) -> {
			final String wanted = (textureString == null ? "" : textureString) + '\u0000' + shaderType;
			for (int i = 0; i < lookupKeys.length; i++) {
				if (lookupKeys[i].equals(wanted)) {
					return i;
				}
			}
			return NativeTextureResolver.NO_TEXTURE;
		};

		final long handle = NativeObjPipeline.createBuilder(objBytes, mtlBytes, materialGroupTextures,
				testCase.flipTextureV, resolver);
		if (handle == 0L) {
			// No material groups means nothing to build, which happens exactly when the
			// document carried no usemtl. The native side reports that by declining to create a
			// builder rather than by producing an empty one, so the buffers stay empty here and
			// the reference side produces an empty model for the same input. Both sides are
			// still compared below, which is the point: zero buckets on one side and one on the
			// other is the divergence this test exists to catch, and it caught exactly that.
			return buffers;
		}

		try {
			NativeObjPipeline.applyTransform(handle, 0,
					testCase.schemaTranslation[0], testCase.schemaTranslation[1], testCase.schemaTranslation[2]);
			NativeObjPipeline.applyTransform(handle, 1,
					testCase.schemaRotation[0], testCase.schemaRotation[1], testCase.schemaRotation[2]);
			NativeObjPipeline.applyTransform(handle, 2,
					clampScale(testCase.schemaScale[0]), clampScale(testCase.schemaScale[1]),
					clampScale(testCase.schemaScale[2]));
			NativeObjPipeline.applyTransform(handle, 3,
					testCase.schemaMirror[0] ? 1 : 0, testCase.schemaMirror[1] ? 1 : 0,
					testCase.schemaMirror[2] ? 1 : 0);

			for (final double[] transform : testCase.transforms) {
				NativeObjPipeline.nativeAddTransform(handle, (int) transform[0],
						transform[1], transform[2], transform[3], transform[4] != 0.0);
			}

			final NativeObjResult result = NativeObjPipeline.nativeBuild(handle);
			assertNotNull(result, "the native build should produce geometry");
			try {
				for (final NativeObjDraw draw : result.draws) {
					buffers.draws.add(new DrawRecord(draw.shaderType, draw.color, draw.keyIndex,
							draw.vertexOffset, draw.vertexCount, draw.indexOffset, draw.indexCount));
				}
				buffers.vertexData = toByteArray(result.vertexBuffer);
				buffers.indexData = toByteArray(result.indexBuffer);
			} finally {
				result.release();
			}
		} finally {
			NativeObjPipeline.freeNativeBuilder(handle);
		}

		return buffers;
	}

	// -----------------------------------------------------------------------
	// Support
	// -----------------------------------------------------------------------

	private static byte[] toByteArray(ByteBuffer buffer) {
		if (buffer == null) {
			return new byte[0];
		}
		final ByteBuffer copy = buffer.duplicate();
		copy.order(ByteOrder.LITTLE_ENDIAN);
		final byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}

	private static int readInt(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF)
				| ((bytes[offset + 1] & 0xFF) << 8)
				| ((bytes[offset + 2] & 0xFF) << 16)
				| ((bytes[offset + 3] & 0xFF) << 24);
	}

	private static void writeFloat(ByteArrayOutputStream stream, float value) {
		writeInt(stream, Float.floatToRawIntBits(value));
	}

	private static void writeInt(ByteArrayOutputStream stream, int value) {
		stream.write(value & 0xFF);
		stream.write((value >>> 8) & 0xFF);
		stream.write((value >>> 16) & 0xFF);
		stream.write((value >>> 24) & 0xFF);
	}

	private static String describeVertex(byte[] data, int vertexIndex) {
		final int offset = vertexIndex * VERTEX_STRIDE;
		if (offset + VERTEX_STRIDE > data.length) {
			return "<out of range>";
		}
		return String.format(Locale.ROOT,
				"pos=(%s %s %s) uv=(%s %s) normal=(%d %d %d)",
				readInt(data, offset), readInt(data, offset + 4), readInt(data, offset + 8),
				readInt(data, offset + 12), readInt(data, offset + 16),
				data[offset + 20], data[offset + 21], data[offset + 22]);
	}

	private static double clampScale(double value) {
		return value <= 0 ? 1 : value;
	}

	/**
	 * Applies one translation, rotation, scale and mirror group, in that order.
	 *
	 * <p>Mirrors {@code ObjectResource.transform}: the translation runs first, then X, Y and Z
	 * rotations each in their own pass, then the scale (whose non-uniform branch corrects the
	 * normals), then the mirror (whose odd-parity branch reverses the winding).</p>
	 */
	private static void applyTransform(org.mtr.mapping.render.model.RawMesh mesh,
			double[] translation, double[] rotation, double[] scale, boolean[] mirror) {
		mesh.applyTranslation((float) translation[0], (float) translation[1], (float) translation[2]);
		mesh.applyRotation(new org.mtr.mapping.holder.Vector3f(1.0F, 0.0F, 0.0F), (float) rotation[0]);
		mesh.applyRotation(new org.mtr.mapping.holder.Vector3f(0.0F, 1.0F, 0.0F), (float) rotation[1]);
		mesh.applyRotation(new org.mtr.mapping.holder.Vector3f(0.0F, 0.0F, 1.0F), (float) rotation[2]);
		mesh.applyScale((float) clampScale(scale[0]), (float) clampScale(scale[1]),
				(float) clampScale(scale[2]));
		mesh.applyMirror(mirror[0], mirror[1], mirror[2], mirror[0], mirror[1], mirror[2]);
	}

	private static int mergeColor(int r, int g, int b, int a) {
		return r << 24 | g << 16 | b << 8 | a;
	}

	/**
	 * Verbatim copy of the expression ObjModelLoader applies to a texture string.
	 *
	 * <p>The doubled-backslash pass matters: it collapses the two-character sequence to a
	 * single slash, and the result feeds the resolved path, which is part of the material
	 * identity that decides which meshes merge. {@code native/tools/TexturePathReference.java}
	 * prints the JVM's own results for the same reason the native tests pin them.</p>
	 */
	private static String normaliseLikeMtr(String texture) {
		return texture.replace("\\\\", "/").replace("\\", "/");
	}

	/**
	 * The material identity for the reference run.
	 *
	 * <p>Only the texture string participates, lower-cased the way
	 * {@code CustomResourceTools.formatIdentifierString} would. The native run records its own
	 * normalised string, so comparing the two checks the normalisation directly rather than
	 * through a resolution proxy.</p>
	 */
	private static String resolveTexture(String textureString, String modelResource) {
		return normaliseLikeMtr(textureString).toLowerCase(Locale.ROOT);
	}

	private static OptimizedModel.ShaderType legacyMapping(String type) {
		switch (type.toLowerCase(Locale.ENGLISH)) {
			case "exteriortranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT;
			case "light":
				return OptimizedModel.ShaderType.CUTOUT_GLOWING;
			case "always_on_light":
			case "lighttranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT_GLOWING;
			case "interior":
				return OptimizedModel.ShaderType.CUTOUT_BRIGHT;
			case "interior_translucent":
			case "interiortranslucent":
				return OptimizedModel.ShaderType.TRANSLUCENT_BRIGHT;
			default:
				return OptimizedModel.ShaderType.CUTOUT;
		}
	}

	private static Map<String, String> splitMaterialOptions(String source) {
		final Map<String, String> result = new HashMap<>();
		final String[] majorParts = source.split("#", 2);
		result.put("", majorParts[0]);
		if (majorParts.length > 1) {
			for (final String minorPart : majorParts[1].split(",")) {
				final String[] tokens = minorPart.split("=", 2);
				if (tokens.length > 1) {
					result.put(tokens[0], tokens[1]);
				} else if (!result.containsKey("#")) {
					result.put("#", tokens[0]);
				} else {
					result.put(tokens[0].toLowerCase(Locale.ROOT), "1");
				}
			}
		}
		return result;
	}

	// -----------------------------------------------------------------------
	// Case model
	// -----------------------------------------------------------------------

	private static List<ReferenceCase> readManifest(Path manifest) throws IOException {
		final List<ReferenceCase> cases = new ArrayList<>();
		ReferenceCase current = null;

		for (final String rawLine : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
			final String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			final int space = line.indexOf(' ');
			final String keyword = space < 0 ? line : line.substring(0, space);
			final String value = space < 0 ? "" : line.substring(space + 1).trim();

			if ("case".equals(keyword)) {
				current = new ReferenceCase();
				current.name = value;
				cases.add(current);
				continue;
			}
			if ("end".equals(keyword) || current == null) {
				continue;
			}

			switch (keyword) {
				case "obj":
					current.objPath = value;
					break;
				case "mtl":
					current.mtlPath = value.isEmpty() ? null : value;
					break;
				case "flipTextureV":
					current.flipTextureV = "1".equals(value);
					break;
				case "materialGroupTextures":
					current.materialGroupTextures = !"0".equals(value);
					break;
				case "modelTranslation":
					current.modelTranslation = parseDoubles(value);
					break;
				case "modelRotation":
					current.modelRotation = parseDoubles(value);
					break;
				case "modelScale":
					current.modelScale = parseDoubles(value);
					break;
				case "modelMirror": {
					final double[] values = parseDoubles(value);
					current.modelMirror = new boolean[]{
							values[0] != 0.0, values[1] != 0.0, values[2] != 0.0};
					break;
				}
				case "schemaTranslation":
					current.schemaTranslation = parseDoubles(value);
					break;
				case "schemaRotation":
					current.schemaRotation = parseDoubles(value);
					break;
				case "schemaScale":
					current.schemaScale = parseDoubles(value);
					break;
				case "schemaMirror": {
					final double[] values = parseDoubles(value);
					current.schemaMirror = new boolean[]{
							values[0] != 0.0, values[1] != 0.0, values[2] != 0.0};
					break;
				}
				case "transform": {
					final String[] parts = value.split(",");
					current.transforms.add(new double[]{
							Integer.parseInt(parts[0].trim()),
							Double.parseDouble(parts[1].trim()),
							Double.parseDouble(parts[2].trim()),
							Double.parseDouble(parts[3].trim()),
							"1".equals(parts[4].trim()) ? 1.0 : 0.0});
					break;
				}
				default:
					break;
			}
		}

		return cases;
	}

	private static double[] parseDoubles(String value) {
		final String[] parts = value.split(",");
		final double[] result = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			result[i] = Double.parseDouble(parts[i].trim());
		}
		return result;
	}

	private static final class ReferenceCase {

		String name = "";
		String objPath;
		String mtlPath;
		boolean flipTextureV;
		boolean materialGroupTextures = true;
		/** Model-resource level, applied first. */
		double[] modelTranslation = {0.0, 0.0, 0.0};
		double[] modelRotation = {0.0, 0.0, 0.0};
		double[] modelScale = {1.0, 1.0, 1.0};
		boolean[] modelMirror = {false, false, false};
		/** Vehicle level, applied second. */
		double[] schemaTranslation = {0.0, 0.0, 0.0};
		double[] schemaRotation = {0.0, 0.0, 0.0};
		double[] schemaScale = {1.0, 1.0, 1.0};
		boolean[] schemaMirror = {false, false, false};
		final List<double[]> transforms = new ArrayList<>();
	}

	private static final class KeyRecord {

		final int shaderType;
		final int color;
		final String texture;

		KeyRecord(int shaderType, int color, String texture) {
			this.shaderType = shaderType;
			this.color = color;
			this.texture = texture;
		}
	}

	private static final class DrawRecord {

		final int shaderType;
		final int color;
		final int keyIndex;
		final int vertexOffset;
		final int vertexCount;
		final int indexOffset;
		final int indexCount;

		DrawRecord(int shaderType, int color, int keyIndex, int vertexOffset, int vertexCount,
				int indexOffset, int indexCount) {
			this.shaderType = shaderType;
			this.color = color;
			this.keyIndex = keyIndex;
			this.vertexOffset = vertexOffset;
			this.vertexCount = vertexCount;
			this.indexOffset = indexOffset;
			this.indexCount = indexCount;
		}

		@Override
		public String toString() {
			return "DrawRecord[shader=" + shaderType + " color=0x" + Integer.toHexString(color)
					+ " key=" + keyIndex + " vertices=" + vertexCount + "@" + vertexOffset
					+ " indices=" + indexCount + "@" + indexOffset + "]";
		}
	}

	/** Identity of a merge bucket: the three things MaterialProperties compares on this path. */
	private static final class MaterialKey {

		final OptimizedModel.ShaderType shaderType;
		final String texture;
		final Integer color;

		MaterialKey(OptimizedModel.ShaderType shaderType, String texture, Integer color) {
			this.shaderType = shaderType;
			this.texture = texture;
			this.color = color;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) {
				return true;
			}
			if (!(object instanceof MaterialKey)) {
				return false;
			}
			final MaterialKey other = (MaterialKey) object;
			return shaderType == other.shaderType && Objects.equals(texture, other.texture)
					&& Objects.equals(color, other.color);
		}

		@Override
		public int hashCode() {
			return Objects.hash(shaderType, texture, color);
		}
	}

	private static final class ModelBuffers {

		final List<KeyRecord> keys = new ArrayList<>();
		final List<DrawRecord> draws = new ArrayList<>();
		byte[] vertexData = new byte[0];
		byte[] indexData = new byte[0];

		void recordKey(MaterialKey key) {
			if (keys.stream().anyMatch(record -> record.texture.equals(key.texture)
					&& record.shaderType == key.shaderType.ordinal())) {
				return;
			}
			keys.add(new KeyRecord(key.shaderType.ordinal(),
					key.color == null ? 0xFFFFFFFF : key.color, key.texture));
		}
	}
}
