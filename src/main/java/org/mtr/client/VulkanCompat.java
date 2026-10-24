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

        private VulkanCompat() {
        }
}
