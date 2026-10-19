package org.mtr.config;

import org.mtr.Keys;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.generated.config.ClientSchema;

public final class Client extends ClientSchema {

        public static final int DYNAMIC_RESOLUTION_COUNT = 8;
        public static final int TRAIN_OSCILLATION_COUNT = 150;

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
                return languageDisplay == null ? LanguageDisplay.NORMAL : languageDisplay;
        }

        /**
         * The transport the native Vulkan OBJ pipeline uses; never null. A null in
         * the config file (or an old file) resolves to the documented default.
         */
        public NativeCommMethod getNativeCommMethod() {
                return nativeCommMethod == null ? NativeCommMethod.SHARED_MEMORY : nativeCommMethod;
        }

        public void cycleNativeCommMethod() {
                final NativeCommMethod[] values = NativeCommMethod.values();
                nativeCommMethod = values[(getNativeCommMethod().ordinal() + 1) % values.length];
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

        public boolean getDisableShadowsForShaders() {
                return disableShadowsForShaders;
        }

        public boolean matchesPreloadResourcePattern(String id) {
                return id.replaceFirst(preloadResourcePattern, "").isEmpty();
        }

        public boolean showBetaWarningScreen() {
                return !Keys.MOD_VERSION.equals(betaWarningVersion);
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
                this.dynamicTextureResolution = Math.clamp(dynamicTextureResolution, 0, DYNAMIC_RESOLUTION_COUNT);
        }

        public void setVehicleOscillationMultiplier(double trainOscillationMultiplier) {
                this.vehicleOscillationMultiplier = Math.clamp(trainOscillationMultiplier, 0, (TRAIN_OSCILLATION_COUNT / 100.0));
        }

        public void toggleDefaultRail3D() {
                defaultRail3D = !defaultRail3D;
        }

        public void toggleUseMTRFont() {
                useMTRFont = !useMTRFont;
        }

        public void toggleDisableShadowsForShaders() {
                disableShadowsForShaders = !disableShadowsForShaders;
        }

        public void hideBetaWarningScreen() {
                betaWarningVersion = Keys.MOD_VERSION;
        }
}
