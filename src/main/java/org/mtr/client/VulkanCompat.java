package org.mtr.client;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Detects whether <a href="https://github.com/xCollateral/VulkanMod">VulkanMod</a> is driving the renderer.
 *
 * <p>VulkanMod replaces Minecraft's OpenGL context with a Vulkan backend. Any code that still
 * issues raw LWJGL OpenGL calls against the (now missing) context crashes the JVM with a native
 * {@code EXCEPTION_ACCESS_VIOLATION}. This class is the single switch used by the Vulkan
 * compatibility paths (for example the UniversalCraft mixins in {@code org.mtr.mixin}) to decide
 * whether the Vulkan-safe code paths should be used. When VulkanMod is absent, every caller keeps
 * the original OpenGL behaviour.</p>
 *
 * <p>Detection is done through class presence rather than loader-specific mod-list APIs so that it
 * works identically on Fabric and NeoForge, and it never initialises any VulkanMod class
 * ({@code Class.forName} is called with {@code initialize = false}).</p>
 *
 * <p>Beryl (the shader pipeline layered on top of VulkanMod) is detected in two tiers.
 * <b>Mod presence</b> — the Beryl jar is in the mods folder — is detected through class presence,
 * exactly like VulkanMod. <b>Pipeline activity</b> — Beryl's shader pipeline is actually driving
 * the current frame — is read from Beryl's own runtime flag
 * ({@code net.beryl.render.RenderingPipeline.useShaderPipeline}), falling back to the persisted
 * {@code net.beryl.BerylMod.CONFIG.shadersOn} setting (both verified against Beryl 0.2.0-alpha).
 * The distinction matters: with the mod installed but shaders <em>disabled</em>, Beryl's
 * {@code RenderingPipeline.beginRender} returns before touching anything, the frame is driven by
 * plain VulkanMod, and MTR's native Vulkan pipeline must run — a class-presence-only check used to
 * disable it permanently for anyone with the Beryl jar installed, no matter whether shaders were
 * on (see {@code NativeRuntime#initialise()}). When neither runtime hook resolves, the
 * class-presence result stands (conservative: keep the Java renderer).</p>
 *
 * <p>The activity flag is a cached-{@link Field} reflection read on every call — it must NOT be
 * cached as a value, because Beryl's keybind and options screen toggle
 * {@code useShaderPipeline} while the client runs.</p>
 */
public final class VulkanCompat {

        private static volatile boolean vulkanModActive = detectVulkanMod();
        private static volatile boolean berylInstalled = detectBerylInstalled();
        /**
         * Beryl's runtime "the shader pipeline is driving this frame" flag — the
         * package-private static boolean
         * {@code net.beryl.render.RenderingPipeline.useShaderPipeline}, resolved
         * once. {@code null} when the field cannot be resolved.
         */
        @Nullable
        private static final Field BERYL_USE_SHADER_PIPELINE = resolveBerylUseShaderPipeline();
        /**
         * Beryl's persisted {@code shadersOn} setting holder — the public static
         * {@code net.beryl.BerylMod.CONFIG} object, resolved once. {@code null}
         * when unavailable.
         */
        @Nullable
        private static final Object BERYL_CONFIG = resolveBerylConfig();
        /**
         * The public boolean {@code shadersOn} field on Beryl's config object;
         * {@code null} when unavailable.
         */
        @Nullable
        private static final Field BERYL_CONFIG_SHADERS_ON = resolveBerylConfigShadersOn();

        private static boolean detectVulkanMod() {
                // "net.vulkanmod.Initializer" is the Fabric entrypoint; "net.vulkanmod.vulkan.Vulkan" is
                // the core class present in every build. Checking both keeps detection stable across
                // loader variants and future refactors of either class.
                return isClassPresent("net.vulkanmod.Initializer") || isClassPresent("net.vulkanmod.vulkan.Vulkan");
        }

        private static boolean detectBerylInstalled() {
                // "net.beryl.BerylMod" is Beryl's mod initialiser; "net.beryl.render.RenderingPipeline"
                // is the class that owns the shader-pipeline state and exists in every Beryl build.
                return isClassPresent("net.beryl.BerylMod") || isClassPresent("net.beryl.render.RenderingPipeline");
        }

        @Nullable
        private static Field resolveBerylUseShaderPipeline() {
                if (!berylInstalled) {
                        return null;
                }
                try {
                        final Class<?> pipeline = Class.forName("net.beryl.render.RenderingPipeline", false, VulkanCompat.class.getClassLoader());
                        final Field field = pipeline.getDeclaredField("useShaderPipeline");
                        field.setAccessible(true);
                        return field;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return null;
                }
        }

        @Nullable
        private static Object resolveBerylConfig() {
                if (!berylInstalled) {
                        return null;
                }
                try {
                        final Class<?> mod = Class.forName("net.beryl.BerylMod", false, VulkanCompat.class.getClassLoader());
                        return mod.getField("CONFIG").get(null);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return null;
                }
        }

        @Nullable
        private static Field resolveBerylConfigShadersOn() {
                if (BERYL_CONFIG == null) {
                        return null;
                }
                try {
                        return BERYL_CONFIG.getClass().getField("shadersOn");
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                        return null;
                }
        }

        private static boolean isClassPresent(String className) {
                try {
                        Class.forName(className, false, VulkanCompat.class.getClassLoader());
                        return true;
                } catch (ClassNotFoundException | LinkageError ignored) {
                        return false;
                }
        }

        /**
         * @return {@code true} when VulkanMod has been detected in the mod instance
         */
        public static boolean isVulkanModActive() {
                return vulkanModActive;
        }

        /**
         * @return {@code true} when the Beryl <b>mod</b> is installed — independent of
         *         whether its shader pipeline is currently enabled
         */
        public static boolean isBerylInstalled() {
                return berylInstalled;
        }

        /**
         * @return {@code true} when Beryl's shader pipeline is actually driving the current
         *         frame (the mod is installed <b>and</b> its shaders are on). This is the
         *         condition under which MTR must keep its models on the Java renderer — see
         *         {@code NativeRuntime#initialise()} and the drain in
         *         {@code MainRenderer#renderModel}. Re-evaluated on every call: Beryl's
         *         keybind toggles the flag at runtime.
         */
        public static boolean isBerylActive() {
                if (!berylInstalled) {
                        return false;
                }
                if (BERYL_USE_SHADER_PIPELINE != null) {
                        try {
                                return BERYL_USE_SHADER_PIPELINE.getBoolean(null);
                        } catch (IllegalAccessException | RuntimeException ignored) {
                                // fall through to the config fallback
                        }
                }
                if (BERYL_CONFIG != null && BERYL_CONFIG_SHADERS_ON != null) {
                        try {
                                return BERYL_CONFIG_SHADERS_ON.getBoolean(BERYL_CONFIG);
                        } catch (IllegalAccessException | RuntimeException ignored) {
                                // fall through to the conservative default
                        }
                }
                // runtime hooks unavailable (an unknown Beryl build) — assume active so
                // the Java renderer keeps handling it, exactly like the old behaviour
                return true;
        }

        /**
         * Ceiling for the block-light component fed to MTR-rendered humanoids (AI passengers
         * and riding players) while Beryl's shader pipeline drives the frame.
         *
         * <p>Beryl's entity vertex shader ({@code entity.vsh}, verified against
         * 0.2.0-alpha) turns the block-light lightmap coordinate into an <b>additive warm
         * emission</b> — {@code texColor * ((uv/256)^2 * 2) * vec3(1.0, 0.7, 0.5)} — instead of
         * the vanilla lightmap texture sample. At block light 15 that is a {@code 1.76&times;}
         * warm bloom on every entity; MTR stations keep light level 15 across whole platforms
         * (mob-spawn-proofing), so every passenger rendered with a truthful packed light
         * still glows after the yanyang.6 light-sampling fix — the real light simply IS 15.
         * The yanyang.7 cap of 8 still left a {@code (128/256)^2 &times; 2 = 0.5&times;} warm
         * bloom that read as glowing on the user's scenes, so the cap is now 4 —
         * {@code (64/256)^2 &times; 2 &asymp; 0.118&times;}, a faint "standing under a lamp"
         * warmth instead of a beacon. Plain VulkanMod and OpenGL use the vanilla lightmap
         * curve and keep the truthful value.</p>
         */
        public static final int BERYL_HUMANOID_BLOCK_LIGHT_CAP = 4;

        /**
         * Caps the block-light component of a packed lightmap value for an MTR-rendered
         * humanoid while Beryl is driving the frame; a passthrough everywhere else.
         *
         * @param packedLight {@code LightTexture.pack(blockLight, skyLight)}
         * @return the same packed value with the block component clamped to
         *         {@link #BERYL_HUMANOID_BLOCK_LIGHT_CAP} when Beryl is active, unchanged otherwise
         */
        public static int capBlockLightForBeryl(int packedLight) {
                if (!isBerylActive()) {
                        return packedLight;
                }
                final int blockLight = packedLight >> 4 & 0xF;
                if (blockLight <= BERYL_HUMANOID_BLOCK_LIGHT_CAP) {
                        return packedLight;
                }
                return BERYL_HUMANOID_BLOCK_LIGHT_CAP << 4 | packedLight & 0xF;
        }

        /**
         * The lightmap coordinate baked into every static {@code NewOptimizedModel} vertex
         * buffer at build time, keyed to the renderer that will own the finished model.
         *
         * <p>Every MTR static model — train bodies, doors, rails, signs, PIDS casings, lift
         * shells — is built once into a GPU vertex buffer whose {@code UV2} (lightmap)
         * attribute is frozen at upload. The vanilla-GL path deliberately bakes
         * {@code DEFAULT_LIGHT} = {@code LightTexture.pack(15, 15)} (block 15, sky 15) and
         * dims per draw through the shader colour — the long-standing MTR cartoon look.
         * Beryl's entity pipeline, however, computes lighting from that baked coordinate
         * (verified against its {@code entity.vsh}/{@code entity.fsh} 0.2.0-alpha):
         * the sky part {@code lightY = (uv/256)^2} drives ambient, and the <b>block</b>
         * part {@code lightX = (uv/256)^2 * (1 - lightY) * 2} is added straight to the
         * fragment as a <b>warm emission</b> — {@code texColor * lightX * vec3(1, 0.7, 0.5)}.
         * With the baked block component at 15 the emission is
         * {@code (240/256)^2 &times; 2 &asymp; 1.55&times;} in every dark or roofed scene
         * (where {@code lightY} collapses and its emission-suppressing {@code (1 - lightY)}
         * factor vanishes): <b>entire trains glowing warm at night and inside stations</b> —
         * the user-visible "vehicles still glow under Beryl" after yanyang.7 clamped only
         * the per-drawn humanoids.</p>
         *
         * <p>While Beryl owns the GL models the bake is therefore {@code pack(0, 15)}:
         * block 0 zeroes the emission term everywhere, while sky 15 keeps Beryl's own
         * ambient light, GGX direct lighting, shadow-map occlusion and fog fully working
         * (Beryl's {@code light.z} direct-light gate needs {@code lightY > 0.707}, which a
         * zeroed sky coordinate would never open, leaving the model lit only by
         * {@code MinAmbientLight} — an unshaded silhouette). Per-draw brightness continues
         * through {@code ColorModulator}, which Beryl's fragment shader applies after the
         * emission fix (see {@code MainRenderer#renderModel}). Plain GL, plain VulkanMod and
         * the native pipeline keep {@code IGui.DEFAULT_LIGHT} = {@code 0xF000F0}, so nothing
         * changes outside Beryl.</p>
         *
         * <p>Called from the model build ({@code StoredVertexData#apply}), which only ever
         * runs inside {@code CustomResourceLoader#reload()} — the same rebuild that fires
         * after every Beryl owner flip (the transition grace window defers it), so the bake
         * variant always matches the renderer that will actually draw the buffer.</p>
         */
        public static final int BERYL_STATIC_MODEL_LIGHT = 0xF00000; // LightTexture.pack(0, 15)

        /**
         * @return the lightmap coordinate to bake into a static {@code NewOptimizedModel}
         *         vertex buffer: {@link #BERYL_STATIC_MODEL_LIGHT} while Beryl's shader
         *         pipeline will draw the model, the vanilla MTR full-bright constant
         *         ({@code IGui.DEFAULT_LIGHT} = {@code 0xF000F0}) everywhere else
         */
        public static int staticModelBakedLight() {
                return isBerylActive() ? BERYL_STATIC_MODEL_LIGHT : 0xF000F0;
        }

        private VulkanCompat() {
        }
}
