package org.mtr.config;

import org.mtr.generated.lang.TranslationProvider;

/**
 * Transport used between the Java side and the native Vulkan OBJ pipeline
 * (see {@code docs/NATIVE_VULKAN.md}).
 *
 * <ul>
 *   <li>{@link #SHARED_MEMORY} — the default. All data travels through the
 *       memory-mapped exchange region and a native poller thread drains it;
 *       on JDK releases below 22 the region is accessed through
 *       {@code sun.misc.Unsafe}-level primitives, which is exactly where the
 *       small stability risk comes from. Works on every supported JDK.</li>
 *   <li>{@link #PANAMA_FFM} — Java calls the native entry points directly
 *       through the Foreign Function &amp; Memory API and drives the same
 *       region synchronously. Deterministic, no poller thread, but the FFM
 *       API is only final from JDK 22, so the runtime falls back to shared
 *       memory on older JVMs.</li>
 * </ul>
 */
public enum NativeCommMethod {
	SHARED_MEMORY(TranslationProvider.OPTIONS_MTR_JAVA_CPP_COMMUNICATION_METHOD_SHARED_MEMORY, TranslationProvider.OPTIONS_MTR_JAVA_CPP_COMMUNICATION_METHOD_SHARED_MEMORY_DESCRIPTION),
	PANAMA_FFM(TranslationProvider.OPTIONS_MTR_JAVA_CPP_COMMUNICATION_METHOD_PANAMA_FFM, TranslationProvider.OPTIONS_MTR_JAVA_CPP_COMMUNICATION_METHOD_PANAMA_FFM_DESCRIPTION);

	public final TranslationProvider.TranslationHolder translationKey;
	public final TranslationProvider.TranslationHolder descriptionKey;

	NativeCommMethod(TranslationProvider.TranslationHolder translationKey, TranslationProvider.TranslationHolder descriptionKey) {
		this.translationKey = translationKey;
		this.descriptionKey = descriptionKey;
	}
}
