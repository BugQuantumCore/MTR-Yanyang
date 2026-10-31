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
         * <p>In the packed lightmap format the block component lives in bits 20-23
         * ({@code blockLight << 20}) and the sky component in bits 4-7 ({@code skyLight << 4}):
         * {@code LightTexture.pack} builds exactly that, and {@code setLight} splits the int
         * back into the two lightmap texture coordinates ({@code u2} = block &times; 16 in the
         * high half — the lightmap texture's horizontal axis is block light — and
         * {@code v2} = sky &times; 16 in the low half; {@code LightTexture.FULL_BRIGHT} =
         * {@code 0xF000F0} = {@code pack(15, 15)} is the canonical witness). The yanyang.7/8
         * version read {@code >> 4 & 0xF} — the <em>sky</em> nibble — and rebuilt the light
         * as {@code cap << 4 | light & 0xF}, dropping the real block bits entirely and
         * rewriting the sky nibble: with sky above the cap every rider was force-fed
         * {@code 0x40} (block 0, sky 4 — glow-free but dim, and the real sky light was
         * lost), while with sky at or below the cap the light passed through untouched and
         * lit-platform riders kept the full uncapped block light. The surgery now clamps
         * the actual block nibble and preserves the sky nibble exactly.</p>
         *
         * @param packedLight {@code LightTexture.pack(blockLight, skyLight)}
         * @return the same packed value with the block component clamped to
         *         {@link #BERYL_HUMANOID_BLOCK_LIGHT_CAP} when Beryl is active, unchanged otherwise
         */
        public static int capBlockLightForBeryl(int packedLight) {
                if (!isBerylActive()) {
                        return packedLight;
                }
                final int blockLight = packedLight >> 20 & 0xF;
                if (blockLight <= BERYL_HUMANOID_BLOCK_LIGHT_CAP) {
                        return packedLight;
                }
                return BERYL_HUMANOID_BLOCK_LIGHT_CAP << 20 | packedLight & 0xFF0FFFFF;
        }

        /**
         * Block-light component for MTR's brush-overlay family (rail type tints, one-way arrows,
         * signal dotted lines, node markers, rail statistics text) while Beryl drives the frame.
         *
         * <p>See {@link #capBlockLightForBeryl} for the packed layout. The block component feeds
         * Beryl's additive warm emission {@code 2·(block/16)²·(1−ambient)·(1, 0.7, 0.5)}, so the
         * humanoid cap of 4 leaves only a faint warmth — the yanyang.10 choice. The yanyang.11
         * cap is 8: at {@code (8/16)² × 2 = 0.5} the emission keeps the overlays clearly legible
         * in dark scenes (night, tunnels) where the ambient floor contributes almost nothing,
         * without any bloom risk (see below).</p>
         */
        public static final int BERYL_OVERLAY_BLOCK_LIGHT = 8;

        /**
         * Sky-light ceiling for the brush-overlay family while Beryl drives the frame.
         *
         * <p>Beryl's entity vertex shader ({@code entity.vsh}, verified against 0.2.0-alpha)
         * derives <em>three</em> terms from the packed light, not one:</p>
         * <ol>
         *   <li>{@code ambient = (sky/16)²·AmbientLightFactor − NightFactor} (floored at
         *       {@code MinAmbientLight}), contributing {@code albedo·0.3·ambient};</li>
         *   <li>{@code sunGate = max(((sky/16)² − 0.5)/0.5, 0)} multiplying the full GGX sun
         *       term — the gate opens as soon as {@code sky > 11.31};</li>
         *   <li>the warm emission above, from the block component.</li>
         * </ol>
         * <p>yanyang.10 kept {@code sky = 15} and capped only the block nibble. That closed the
         * emission term but left the gate wide open: the overlay quads face straight up
         * ({@code normal (0, 1, 0)}), so in daylight they took the <em>full sun GGX term plus the
         * full sky ambient</em> — saturated type colours (pure yellow, pure cyan) climbed past
         * r+g+b ≈ 2.0, Beryl's bloom filter threshold ({@code bloom/filter.fsh} discards fragments
         * below {@code r+g+b < 2.0}), and the overlays bloomed exactly as before. The cap the
         * user saw was the sun, not the lamp.</p>
         *
         * <p>{@code sky = 11} closes the gate exactly: {@code (11/16)² = 0.4727 < 0.5}, zero sun
         * term, while the ambient floor keeps the overlays readable. MTR's own
         * {@code IGui.MAX_LIGHT_INTERIOR = 0xF000B0} = {@code pack(15, 11)} — sky 11 — is the
         * precedent for "fully lit but never sun-gated".</p>
         */
        public static final int BERYL_OVERLAY_SKY_LIGHT = 11;

        /**
         * Clamps both lightmap components of a brush-overlay light value for Beryl: block to
         * {@link #BERYL_OVERLAY_BLOCK_LIGHT}, sky to {@link #BERYL_OVERLAY_SKY_LIGHT}. A
         * passthrough everywhere else.
         *
         * <p>Result: day — {@code ambient ≈ 0.14·albedo + emission ≈ 0.27·texColor·warm}, total
         * well under the bloom threshold; night — {@code emission ≈ 0.5·(1−ambient)} keeps the
         * overlays glowing softly like a lamp, never like a beacon. Flashing overlays pulse
         * through their colour multiplier, which is unaffected by this clamp.</p>
         *
         * @param packedLight {@code LightTexture.pack(blockLight, skyLight)}
         * @return the same packed value with block clamped to 8 and sky clamped to 11 while
         *         Beryl is active, unchanged otherwise
         */
        public static int overlayLightForBeryl(int packedLight) {
                if (!isBerylActive()) {
                        return packedLight;
                }
                final int blockLight = Math.min(packedLight >> 20 & 0xF, BERYL_OVERLAY_BLOCK_LIGHT);
                final int skyLight = Math.min(packedLight >> 4 & 0xF, BERYL_OVERLAY_SKY_LIGHT);
                // Rewrite exactly the two nibbles; any other bits pass through untouched, the
                // same discipline as capBlockLightForBeryl.
                return blockLight << 20 | skyLight << 4 | packedLight & 0xFF0FFF0F;
        }

        /**
         * Block-light component baked into EXTERIOR static models while Beryl owns the GL
         * models — the static-mesh companion of {@link #BERYL_HUMANOID_BLOCK_LIGHT_CAP}.
         *
         * <p>yanyang.8 baked the raw value {@code 0xF00000} believing it to be
         * {@code LightTexture.pack(0, 15)}. In the packed lightmap format the high 16 bits
         * are {@code u2} — the <em>block</em> axis of the lightmap texture — so
         * {@code 0xF00000} is actually {@code pack(15, 0)} ({@code 0xF000F0} =
         * {@code pack(15, 15)} = {@code LightTexture.FULL_BRIGHT} is the canonical witness).
         * Every static model therefore shipped with full block emission and zero sky
         * light: Beryl's entity shader fed the 240/256 block coordinate straight into its
         * squared warm-emission term while the collapsed sky component closed the ambient
         * and GGX direct-light gates — entire trains rendered as flat orange-glowing
         * silhouettes with no shading, in every scene, day or night. That is the yanyang.8
         * "model rendering much worse" regression.</p>
         *
         * <p>The corrected EXTERIOR bake is {@code pack(4, 15)}: block 4 — the same ceiling
         * the humanoid cap settled on after block 8 still read as glowing — leaves a faint
         * lamp-lit warmth instead of dead-black silhouettes, while sky 15 keeps Beryl's
         * ambient, GGX direct lighting, shadow-map occlusion and fog fully working (Beryl's
         * {@code light.z} direct-light gate needs {@code lightY > 0.707}, which a zeroed
         * sky coordinate never opens).</p>
         */
        public static final int BERYL_STATIC_EXTERIOR_BLOCK_LIGHT = 4;

        /**
         * The lightmap coordinate baked into a static {@code NewOptimizedModel} vertex
         * buffer at build time, keyed to the renderer that will own the finished model and
         * to the render stage the mesh belongs to.
         *
         * <p>Every MTR static model — train bodies, doors, rails, signs, PIDS casings, lift
         * shells — is built once into a GPU vertex buffer whose {@code UV2} (lightmap)
         * attribute is frozen at upload. The vanilla-GL path deliberately bakes
         * {@code DEFAULT_LIGHT} = {@code LightTexture.pack(15, 15)} and dims per draw
         * through the shader colour — the long-standing MTR cartoon look. Beryl's entity
         * pipeline, however, computes lighting from that baked coordinate (verified against
         * its {@code entity.vsh}/{@code entity.fsh} 0.2.0-alpha): the sky part drives
         * ambient and the direct-light gate, and the block part is added straight to the
         * fragment as a warm emission — {@code texColor * lightX * vec3(1, 0.7, 0.5)}.</p>
         *
         * <p>While Beryl owns the GL models the bake is therefore per render stage:</p>
         * <ul>
         * <li><b>Full-brightness stages</b> ({@code LIGHT}, {@code ALWAYS_ON_LIGHT},
         * {@code INTERIOR}, {@code INTERIOR_TRANSLUCENT}) keep the full-bright
         * {@code pack(15, 15)}. These stages are self-lit by design under every other
         * renderer, and Beryl's block-light emission is exactly what makes headlight and
         * light-module parts glow — yanyang.8's uniform zero-block bake silenced the train
         * model light modules and left interiors dark, the "module group emissive is
         * wrong" report.</li>
         * <li><b>{@code EXTERIOR}</b> — the only stage that follows the scene light — bakes
         * {@code pack(4, 15)}: a faint lamp-lit warmth, never a beacon.</li>
         * </ul>
         *
         * <p>Called from the model build ({@code NewOptimizedModelGroup#build}), which only
         * ever runs inside {@code CustomResourceLoader#reload()} — the same rebuild that
         * fires after every Beryl owner flip (the transition grace window defers it), so
         * the bake variant always matches the renderer that will actually draw the
         * buffer.</p>
         *
         * @param isFullBrightness whether the mesh belongs to a full-brightness render stage
         * @return the lightmap coordinate to bake: the vanilla MTR full-bright constant
         *         ({@code IGui.DEFAULT_LIGHT} = {@code 0xF000F0}) for non-Beryl renderers
         *         and for full-brightness stages, {@code pack(4, 15)} for EXTERIOR under Beryl
         */
        public static int staticModelBakedLight(boolean isFullBrightness) {
                if (!isBerylActive() || isFullBrightness) {
                        return 0xF000F0;
                }
                return BERYL_STATIC_EXTERIOR_BLOCK_LIGHT << 20 | 0xF0;
        }

        private VulkanCompat() {
        }
}
