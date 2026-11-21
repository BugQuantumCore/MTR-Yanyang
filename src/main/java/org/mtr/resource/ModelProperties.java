package org.mtr.resource;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.generated.resource.ModelPropertiesSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class ModelProperties extends ModelPropertiesSchema {

        @Nullable
        public final ResourceLocation gangwayInnerSideTexture;
        @Nullable
        public final ResourceLocation gangwayInnerTopTexture;
        @Nullable
        public final ResourceLocation gangwayInnerBottomTexture;
        @Nullable
        public final ResourceLocation gangwayOuterSideTexture;
        @Nullable
        public final ResourceLocation gangwayOuterTopTexture;
        @Nullable
        public final ResourceLocation gangwayOuterBottomTexture;
        @Nullable
        public final ResourceLocation barrierInnerSideTexture;
        @Nullable
        public final ResourceLocation barrierInnerTopTexture;
        @Nullable
        public final ResourceLocation barrierInnerBottomTexture;
        @Nullable
        public final ResourceLocation barrierOuterSideTexture;
        @Nullable
        public final ResourceLocation barrierOuterTopTexture;
        @Nullable
        public final ResourceLocation barrierOuterBottomTexture;

        public ModelProperties(ReaderBase readerBase) {
                super(readerBase);
                updateData(readerBase);
                gangwayInnerSideTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayInnerSideResource, "png"), "gangway inner side");
                gangwayInnerTopTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayInnerTopResource, "png"), "gangway inner top");
                gangwayInnerBottomTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayInnerBottomResource, "png"), "gangway inner bottom");
                gangwayOuterSideTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayOuterSideResource, "png"), "gangway outer side");
                gangwayOuterTopTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayOuterTopResource, "png"), "gangway outer top");
                gangwayOuterBottomTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(gangwayOuterBottomResource, "png"), "gangway outer bottom");
                barrierInnerSideTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierInnerSideResource, "png"), "barrier inner side");
                barrierInnerTopTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierInnerTopResource, "png"), "barrier inner top");
                barrierInnerBottomTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierInnerBottomResource, "png"), "barrier inner bottom");
                barrierOuterSideTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierOuterSideResource, "png"), "barrier outer side");
                barrierOuterTopTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierOuterTopResource, "png"), "barrier outer top");
                barrierOuterBottomTexture = validatePanelTexture(CustomResourceTools.formatIdentifier(barrierOuterBottomResource, "png"), "barrier outer bottom");
        }

        ModelProperties(double modelYOffset) {
                super(modelYOffset, "", "", "", "", "", "", 0, 0, 0, 0, "", "", "", "", "", "", 0, 0, 0, 0);
                gangwayInnerSideTexture = null;
                gangwayInnerTopTexture = null;
                gangwayInnerBottomTexture = null;
                gangwayOuterSideTexture = null;
                gangwayOuterTopTexture = null;
                gangwayOuterBottomTexture = null;
                barrierInnerSideTexture = null;
                barrierInnerTopTexture = null;
                barrierInnerBottomTexture = null;
                barrierOuterSideTexture = null;
                barrierOuterTopTexture = null;
                barrierOuterBottomTexture = null;
        }

        ModelProperties(
                ObjectArrayList<ModelPropertiesPart> parts,
                double modelYOffset,
                String gangwayInnerSideResource,
                String gangwayInnerTopResource,
                String gangwayInnerBottomResource,
                String gangwayOuterSideResource,
                String gangwayOuterTopResource,
                String gangwayOuterBottomResource,
                double gangwayWidth,
                double gangwayHeight,
                double gangwayYOffset,
                double gangwayZOffset,
                String barrierInnerSideResource,
                String barrierInnerTopResource,
                String barrierInnerBottomResource,
                String barrierOuterSideResource,
                String barrierOuterTopResource,
                String barrierOuterBottomResource,
                double barrierWidth,
                double barrierHeight,
                double barrierYOffset,
                double barrierZOffset
        ) {
                super(
                        modelYOffset,
                        gangwayInnerSideResource,
                        gangwayInnerTopResource,
                        gangwayInnerBottomResource,
                        gangwayOuterSideResource,
                        gangwayOuterTopResource,
                        gangwayOuterBottomResource,
                        gangwayWidth,
                        gangwayHeight,
                        gangwayYOffset,
                        gangwayZOffset,
                        barrierInnerSideResource,
                        barrierInnerTopResource,
                        barrierInnerBottomResource,
                        barrierOuterSideResource,
                        barrierOuterTopResource,
                        barrierOuterBottomResource,
                        barrierWidth,
                        barrierHeight,
                        barrierYOffset,
                        barrierZOffset
                );
                this.parts.addAll(parts);
                gangwayInnerSideTexture = CustomResourceTools.formatIdentifier(gangwayInnerSideResource, "png");
                gangwayInnerTopTexture = CustomResourceTools.formatIdentifier(gangwayInnerTopResource, "png");
                gangwayInnerBottomTexture = CustomResourceTools.formatIdentifier(gangwayInnerBottomResource, "png");
                gangwayOuterSideTexture = CustomResourceTools.formatIdentifier(gangwayOuterSideResource, "png");
                gangwayOuterTopTexture = CustomResourceTools.formatIdentifier(gangwayOuterTopResource, "png");
                gangwayOuterBottomTexture = CustomResourceTools.formatIdentifier(gangwayOuterBottomResource, "png");
                barrierInnerSideTexture = CustomResourceTools.formatIdentifier(barrierInnerSideResource, "png");
                barrierInnerTopTexture = CustomResourceTools.formatIdentifier(barrierInnerTopResource, "png");
                barrierInnerBottomTexture = CustomResourceTools.formatIdentifier(barrierInnerBottomResource, "png");
                barrierOuterSideTexture = CustomResourceTools.formatIdentifier(barrierOuterSideResource, "png");
                barrierOuterTopTexture = CustomResourceTools.formatIdentifier(barrierOuterTopResource, "png");
                barrierOuterBottomTexture = CustomResourceTools.formatIdentifier(barrierOuterBottomResource, "png");
        }

        public void iterateParts(Consumer<ModelPropertiesPart> consumer) {
                parts.forEach(consumer);
        }

        /**
         * Gangway / barrier panel textures referenced by the model-properties JSON that no
         * resource pack actually provides must not reach the renderer: on plain OpenGL
         * they degrade to the magenta-black checkerboard between every car
         * ("Failed to load texture: mtr:<id>/<id>_connector_*.png"), and under VulkanMod
         * the missing-texture fallback carries a {@code null VkGlTexture} whose bind
         * throws straight out of {@code RenderLayer#startDrawing} and crashes the game
         * (MainRenderer#logScheduledRenderFailure is the backstop). Dropping the
         * reference renders exactly like the optional field being unset — the panel is
         * simply not drawn — and says so once per texture. Runtime-registered dynamic
         * textures ({@code dynamic/...} identifiers, e.g. script-created displays) have
         * no pack file by design and are exempt.
         */
        @Nullable
        private static ResourceLocation validatePanelTexture(@Nullable ResourceLocation texture, String panel) {
                if (texture == null || texture.getPath().startsWith("dynamic/")) {
                        return texture;
                }
                boolean missing;
                try {
                        missing = Minecraft.getInstance().getResourceManager().getResource(texture).isEmpty();
                } catch (Exception ignored) {
                        // ResourceManager unavailable mid-reload — keep the reference; the
                        // scheduled-drain guard still contains any later failure.
                        missing = false;
                }
                if (missing) {
                        if (REPORTED_MISSING_PANEL_TEXTURES.add(texture.toString())) {
                                MTR.LOGGER.warn("Gangway/barrier {} texture {} does not exist in any resource pack; the panel will not be rendered (add the file or remove the model-properties reference)", panel, texture);
                        }
                        return null;
                }
                return texture;
        }

        private static final Set<String> REPORTED_MISSING_PANEL_TEXTURES = java.util.Collections.synchronizedSet(new HashSet<>());

        public double getModelYOffset() {
                return modelYOffset;
        }

        public double getGangwayWidth() {
                return gangwayWidth;
        }

        public double getGangwayHeight() {
                return gangwayHeight;
        }

        public double getGangwayYOffset() {
                return gangwayYOffset;
        }

        public double getGangwayZOffset() {
                return gangwayZOffset;
        }

        public double getBarrierWidth() {
                return barrierWidth;
        }

        public double getBarrierHeight() {
                return barrierHeight;
        }

        public double getBarrierYOffset() {
                return barrierYOffset;
        }

        public double getBarrierZOffset() {
                return barrierZOffset;
        }

        VehicleModelWrapper toVehicleModelWrapper(String modelResource, String textureResource, String modelPropertiesResource, String positionDefinitionsResource, boolean flipTextureV, ObjectArrayList<ModelPropertiesPartWrapper> parts) {
                return new VehicleModelWrapper(
                        modelResource,
                        textureResource,
                        modelPropertiesResource,
                        positionDefinitionsResource,
                        flipTextureV,
                        parts,
                        modelYOffset,
                        gangwayInnerSideResource,
                        gangwayInnerTopResource,
                        gangwayInnerBottomResource,
                        gangwayOuterSideResource,
                        gangwayOuterTopResource,
                        gangwayOuterBottomResource,
                        gangwayWidth,
                        gangwayHeight,
                        gangwayYOffset,
                        gangwayZOffset,
                        barrierInnerSideResource,
                        barrierInnerTopResource,
                        barrierInnerBottomResource,
                        barrierOuterSideResource,
                        barrierOuterTopResource,
                        barrierOuterBottomResource,
                        barrierWidth,
                        barrierHeight,
                        barrierYOffset,
                        barrierZOffset
                );
        }

        public void addPartsIfEmpty(ObjectSet<String> partNames) {
                if (parts.isEmpty()) {
                        parts.add(new ModelPropertiesPart(partNames));
                }
        }
}
