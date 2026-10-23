package org.mtr.client;

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
 * <p>Beryl (the shader pipeline layered on top of VulkanMod) is detected the same way. Its
 * presence matters for the render strategy: Beryl swaps every entity-family vanilla shader
 * instance to its own G-buffer pipelines, whose uniforms read VulkanMod's global
 * {@code VRenderSystem} state instead of the vanilla uniform objects — see
 * {@code org.mtr.render.VulkanFrameState} for the per-draw bridge this necessitates.</p>
 */
public final class VulkanCompat {

	private static volatile boolean vulkanModActive = detectVulkanMod();
	private static volatile boolean berylActive = detectBeryl();

	private static boolean detectVulkanMod() {
		// "net.vulkanmod.Initializer" is the Fabric entrypoint; "net.vulkanmod.vulkan.Vulkan" is
		// the core class present in every build. Checking both keeps detection stable across
		// loader variants and future refactors of either class.
		return isClassPresent("net.vulkanmod.Initializer") || isClassPresent("net.vulkanmod.vulkan.Vulkan");
	}

	private static boolean detectBeryl() {
		// "net.beryl.BerylMod" is Beryl's mod initialiser; "net.beryl.render.RenderingPipeline"
		// is the class that owns the shader-pipeline state and exists in every Beryl build.
		return isClassPresent("net.beryl.BerylMod") || isClassPresent("net.beryl.render.RenderingPipeline");
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
	 * @return {@code true} when the Beryl shader pipeline has been detected on top of
	 *         VulkanMod. When this is the case MTR keeps its models on the Java renderer
	 * (the native Vulkan recorder is bypassed) so Beryl can shade MTR geometry through
	 *         its own pipelines — see {@code NativeRuntime#initialise()}.
	 */
	public static boolean isBerylActive() {
		return berylActive;
	}

	private VulkanCompat() {
	}
}
