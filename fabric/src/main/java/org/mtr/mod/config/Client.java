package org.mtr.mod.config;

import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Utilities;
import org.mtr.mod.Keys;
import org.mtr.mod.generated.config.ClientSchema;

public final class Client extends ClientSchema {

	public static final int DYNAMIC_RESOLUTION_COUNT = 8;
	public static final int TRAIN_OSCILLATION_COUNT = 15;

	public Client(ReaderBase readerBase) {
		super(readerBase);
		updateData(readerBase);
	}

	public boolean getChatAnnouncements() {
		return chatAnnouncements;
	}

	public boolean getTextToSpeechAnnouncements() {
		return textToSpeechAnnouncements;
	}

	public boolean getHideTranslucentParts() {
		return hideTranslucentParts;
	}

	public LanguageDisplay getLanguageDisplay() {
		return languageDisplay;
	}

	public int getDynamicTextureResolution() {
		return (int) dynamicTextureResolution;
	}

	public double getVehicleOscillationMultiplier() {
		return vehicleOscillationMultiplier;
	}

	public boolean getDefaultRail3D() {
		return defaultRail3D;
	}

	public boolean getUseMTRFont() {
		return useMTRFont;
	}

	/**
	 * Whether OBJ models may be built and drawn through the native C++ pipeline.
	 *
	 * <p>When false, OBJ models load through the original Java loader and draw through the
	 * original {@code OptimizedRenderer}, which is the reference behaviour of upstream MTR. The
	 * setting exists so a player who suspects the native path on their machine can turn it off
	 * without removing the mod: it is read live by both the model loader and the draw wrapper,
	 * so switching it and reloading resources (F3+T, or rejoining the world) is enough.</p>
	 */
	public boolean getUseNativeObjRendering() {
		return useNativeObjRendering;
	}

	public boolean getDisableShadowsForShaders() {
		return disableShadowsForShaders;
	}

	public boolean matchesPreloadResourcePattern(String id) {
		return id.replaceFirst(preloadResourcePattern, "").isEmpty();
	}

	public boolean showBetaWarningScreen() {
		return false && !Keys.MOD_VERSION.equals(betaWarningVersion);
	}

	public void toggleChatAnnouncements() {
		chatAnnouncements = !chatAnnouncements;
	}

	public void toggleTextToSpeechAnnouncements() {
		textToSpeechAnnouncements = !textToSpeechAnnouncements;
	}

	public void toggleHideTranslucentParts() {
		hideTranslucentParts = !hideTranslucentParts;
	}

	public void cycleLanguageDisplay() {
		languageDisplay = LanguageDisplay.values()[(languageDisplay.ordinal() + 1) % LanguageDisplay.values().length];
	}

	public void setDynamicTextureResolution(int dynamicTextureResolution) {
		this.dynamicTextureResolution = Utilities.clamp(dynamicTextureResolution, 0, DYNAMIC_RESOLUTION_COUNT);
	}

	public void setVehicleOscillationMultiplier(double trainOscillationMultiplier) {
		this.vehicleOscillationMultiplier = Utilities.clamp(trainOscillationMultiplier, 0, (TRAIN_OSCILLATION_COUNT / 10.0));
	}

	public void toggleDefaultRail3D() {
		defaultRail3D = !defaultRail3D;
	}

	public void toggleUseMTRFont() {
		useMTRFont = !useMTRFont;
	}

	public void toggleUseNativeObjRendering() {
		useNativeObjRendering = !useNativeObjRendering;
	}

	public void toggleDisableShadowsForShaders() {
		disableShadowsForShaders = !disableShadowsForShaders;
	}

	public void hideBetaWarningScreen() {
		betaWarningVersion = Keys.MOD_VERSION;
	}
}
