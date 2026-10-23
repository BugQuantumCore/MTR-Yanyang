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
 */
public final class VulkanCompat {

	private static volatile boolean vulkanModActive = detectVulkanMod();

	private static boolean detectVulkanMod() {
		// "net.vulkanmod.Initializer" is the Fabric entrypoint; "net.vulkanmod.vulkan.Vulkan" is
		// the core class present in every build. Checking both keeps detection stable across
		// loader variants and future refactors of either class.
		return isClassPresent("net.vulkanmod.Initializer") || isClassPresent("net.vulkanmod.vulkan.Vulkan");
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

	private VulkanCompat() {
	}
}
