package org.mtr.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.mtr.MTR;
import org.mtr.config.Config;
import org.mtr.config.LanguageDisplay;
import org.mtr.core.tool.Utilities;
import org.mtr.data.IGui;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2LongArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.render.MainRenderer;
import org.mtr.render.MoreRenderLayers;
import org.mtr.resource.ResourceManagerHelper;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.text.AttributedString;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Runtime texture generation and caching system.
 *
 * <p>Most "text on a sign" textures (station names, PIDS rows, exit letters, direction
 * arrows, …) are not shipped in the resource pack — they are drawn at runtime by
 * {@link RouteMapGenerator} from live simulation data, then uploaded to GL as
 * {@link DynamicTexture}s. This class owns that pipeline.</p>
 *
 * <p>Caller flow:</p>
 * <ol>
 *   <li>A renderer calls one of the {@code getXxx(...)} accessors per frame.</li>
 *   <li>If the texture is already cached and fresh, its {@link DynamicResource} is
 *       returned immediately with a refreshed expiry. The {@code identifier} field can be
 *       bound straight away.</li>
 *   <li>If not, a generation task is posted to
 *       {@link org.mtr.render.WorkerThread#scheduleDynamicTextures(Runnable)} and a
 *       placeholder ({@link DefaultRenderingColor}) is returned for this frame. The
 *       texture lands in the cache within a few frames.</li>
 * </ol>
 *
 * <p>{@link #tick()} runs once per render frame and evicts entries whose expiry has
 * passed; entries are then queued for GL texture deletion after a {@value #COOLDOWN_TIME}
 * ms grace period to outlast any draws still referring to them.</p>
 *
 * <p>Performance hotspots are documented in {@code docs/PERFORMANCE.md} §2.</p>
 */
public class DynamicTextureCache implements IGui {

        @Nullable
        private volatile Font font;
        @Nullable
        private volatile Font fontCjk;

        private final Object2ObjectLinkedOpenHashMap<String, DynamicResource> dynamicResources = new Object2ObjectLinkedOpenHashMap<>();
        private final ObjectOpenHashSet<String> generatingResources = new ObjectOpenHashSet<>();
        private final ConcurrentLinkedQueue<Runnable> resourceRegistryQueue = new ConcurrentLinkedQueue<>();
        private volatile long resourceEpoch;
        private volatile boolean closed;
        private long nextEvictionNanos = System.nanoTime();
        private final Object2LongArrayMap<ResourceLocation> deletedResources = new Object2LongArrayMap<>();

        public static DynamicTextureCache instance = new DynamicTextureCache();

        public static final float LINE_HEIGHT_MULTIPLIER = 1.25F;
        private static final int COOLDOWN_TIME = 10000; // Images not requested within the last 10 seconds will be unregistered
        private static final ResourceLocation DEFAULT_BLACK_RESOURCE = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "textures/block/black.png");
        private static final ResourceLocation DEFAULT_WHITE_RESOURCE = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "textures/block/white.png");
        private static final ResourceLocation DEFAULT_TRANSPARENT_RESOURCE = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "textures/block/transparent.png");
        private static final int MAX_IMAGE_SIZE = 2048;
        private static final int MAX_PENDING_TEXTURES = 32;
        private static final int MAX_UPLOADS_PER_FRAME = 4;
        private static final long EVICTION_INTERVAL_NANOS = 250_000_000L;
        // Bounded font-loading retries before the logical-font fallback (see loadBundledFont).
        private static final int FONT_LOAD_ATTEMPTS = 3;
        private static final String FALLBACK_LOGICAL_FONT = "Dialog";

        /**
         * Clear all asset-related cache and mark all dynamic textures to be drawn.
         */
        public synchronized void reload() {
                font = null;
                fontCjk = null;
                refresh();
        }

        /**
         * Mark all dynamic textures to be redrawn.
         */
        public synchronized void refresh() {
                resourceEpoch++;
                MTR.LOGGER.debug("Refreshing dynamic resources; {} textures in memory; {} textures queued to be destroyed", dynamicResources.size(), deletedResources.size());
                dynamicResources.values().forEach(dynamicResource -> dynamicResource.needsRefresh = true);
                generatingResources.clear();
        }

        /**
         * Once-per-render-frame eviction pass. Removes entries whose expiry has passed (their
         * GL textures are queued for deletion after a {@value #COOLDOWN_TIME} ms grace period)
         * and finalises the deletion of any cooled-off textures from the previous tick.
         *
         * <p>Uploads are bounded per frame; expiry scans run at most four times per second.</p>
         */
        public void tick() {
                for (int i = 0; i < MAX_UPLOADS_PER_FRAME; i++) {
                        final Runnable registration = resourceRegistryQueue.poll();
                        if (registration == null) {
                                break;
                        }
                        registration.run();
                }
                final long nowNanos = System.nanoTime();
                if (nowNanos - nextEvictionNanos < 0) {
                        return;
                }
                nextEvictionNanos = nowNanos + EVICTION_INTERVAL_NANOS;
                final ObjectArrayList<String> keysToRemove = new ObjectArrayList<>();
                final long currentTimeMillis = System.currentTimeMillis();
                dynamicResources.forEach((checkKey, checkDynamicResource) -> {
                        if (checkDynamicResource.expiryTime < currentTimeMillis) {
                                checkDynamicResource.remove();
                                deletedResources.put(checkDynamicResource.identifier, currentTimeMillis + COOLDOWN_TIME);
                                keysToRemove.add(checkKey);
                        }
                });
                keysToRemove.forEach(dynamicResources::remove);

                final ObjectArrayList<ResourceLocation> deletedResourcesToRemove = new ObjectArrayList<>();
                deletedResources.forEach((identifier, expiryTime) -> {
                        if (expiryTime < currentTimeMillis) {
                                Minecraft.getInstance().getTextureManager().release(identifier);
                                deletedResourcesToRemove.add(identifier);
                        }
                });
                deletedResourcesToRemove.forEach(deletedResources::removeLong);
        }

        public DynamicResource getPixelatedText(String text, int textColor, int maxWidth, double cjkSizeRatio, boolean fullPixel) {
                return getResource(String.format("pixelated_text_%s_%s_%s_%s_%s", text, textColor, maxWidth, cjkSizeRatio, fullPixel), () -> RouteMapGenerator.generatePixelatedText(text, textColor, maxWidth, cjkSizeRatio, fullPixel), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getColorStrip(long platformId) {
                return getResource(String.format("color_%s", platformId), () -> RouteMapGenerator.generateColorStrip(platformId), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getStationName(String stationName, float aspectRatio) {
                return getResource(String.format("station_name_%s_%s", stationName, aspectRatio), () -> RouteMapGenerator.generateStationName(stationName, aspectRatio), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getTallStationName(int textColor, String stationName, int stationColor, float aspectRatio) {
                return getResource(String.format("tall_station_name_%s_%s_%s_%s", textColor, stationName, stationColor, aspectRatio), () -> RouteMapGenerator.generateTallStationName(textColor, stationName, stationColor, aspectRatio), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getStationNameEntrance(int textColor, String stationName, float aspectRatio) {
                return getResource(String.format("station_name_entrance_%s_%s_%s", textColor, stationName, aspectRatio), () -> RouteMapGenerator.generateStationNameEntrance(textColor, stationName, aspectRatio), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getSingleRowStationName(long platformId, float aspectRatio) {
                return getResource(String.format("single_row_station_name_%s_%s", platformId, aspectRatio), () -> RouteMapGenerator.generateSingleRowStationName(platformId, aspectRatio), DefaultRenderingColor.WHITE);
        }

        public DynamicResource getSignText(String text, HorizontalAlignment horizontalAlignment, float paddingScale, int backgroundColor, int textColor) {
                return getResource(String.format("sign_text_%s_%s_%s_%s_%s", text, horizontalAlignment, paddingScale, backgroundColor, textColor), () -> RouteMapGenerator.generateSignText(text, horizontalAlignment, paddingScale, backgroundColor, textColor), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getLiftPanelDisplay(String originalText, int textColor) {
                return getResource(String.format("lift_panel_display_%s_%s", originalText, textColor), () -> RouteMapGenerator.generateLiftPanel(originalText, textColor), DefaultRenderingColor.BLACK);
        }

        public DynamicResource getExitSignLetter(String exitLetter, String exitNumber, int backgroundColor) {
                return getResource(String.format("exit_sign_letter_%s_%s_%s", exitLetter, exitNumber, backgroundColor), () -> RouteMapGenerator.generateExitSignLetter(exitLetter, exitNumber, backgroundColor), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getRouteSquare(int color, String routeName, HorizontalAlignment horizontalAlignment) {
                return getResource(String.format("route_square_%s_%s_%s", color, routeName, horizontalAlignment), () -> RouteMapGenerator.generateRouteSquare(color, routeName, horizontalAlignment), DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getDirectionArrow(long platformId, boolean hasLeft, boolean hasRight, HorizontalAlignment horizontalAlignment, boolean showToString, float paddingScale, float aspectRatio, int backgroundColor, int textColor, int transparentColor) {
                return getResource(String.format("direction_arrow_%s_%s_%s_%s_%s_%s_%s_%s_%s_%s", platformId, hasLeft, hasRight, horizontalAlignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor), () -> RouteMapGenerator.generateDirectionArrow(platformId, hasLeft, hasRight, horizontalAlignment, showToString, paddingScale, aspectRatio, backgroundColor, textColor, transparentColor), transparentColor == 0 && backgroundColor == ARGB_WHITE ? DefaultRenderingColor.WHITE : DefaultRenderingColor.TRANSPARENT);
        }

        public DynamicResource getRouteMap(long platformId, boolean vertical, boolean flip, float aspectRatio, boolean transparentWhite) {
                return getResource(String.format("route_map_%s_%s_%s_%s_%s", platformId, vertical, flip, aspectRatio, transparentWhite), () -> RouteMapGenerator.generateRouteMap(platformId, vertical, flip, aspectRatio, transparentWhite), transparentWhite ? DefaultRenderingColor.TRANSPARENT : DefaultRenderingColor.WHITE);
        }

        public byte[] getTextPixels(String text, int[] dimensions, int fontSizeCjk, int fontSize) {
                return getTextPixels(text, dimensions, Integer.MAX_VALUE, (int) (Math.max(fontSizeCjk, fontSize) * LINE_HEIGHT_MULTIPLIER), fontSizeCjk, fontSize, 0, null);
        }

        /**
         * Rasterise {@code text} into a packed grayscale pixel byte array, honouring the
         * current language-display setting, bundled-font glyph coverage, and AWT system-font
         * fallback when neither bundled font can render a character.
         *
         * @param text                raw text, with {@code |} treated as a part separator
         * @param dimensions          out-parameter: {@code [width, height]} of the rendered pixels
         * @param maxWidth            cap on the rendered width before clipping; {@link Integer#MAX_VALUE} for unbounded
         * @param maxHeight           cap on the rendered height
         * @param fontSizeCjk         font size to use when the text part contains CJK glyphs
         * @param fontSize            font size to use otherwise
         * @param padding             pixels of padding around each row (multi-row only)
         * @param horizontalAlignment {@code null} to lay out as a single horizontal row;
         *                            non-null to wrap parts vertically with the given alignment
         * @return tightly-packed {@code BYTE_GRAY} pixels suitable for compositing into a
         * {@link NativeImage}; empty if the fonts have not yet loaded
         */
        public byte[] getTextPixels(String text, int[] dimensions, int maxWidth, int maxHeight, int fontSizeCjk, int fontSize, int padding, @Nullable HorizontalAlignment horizontalAlignment) {
                final Font textFont = font;
                final Font textFontCjk = fontCjk;
                if (maxWidth <= 0 || maxHeight <= 0 || textFont == null || textFontCjk == null) {
                        dimensions[0] = 0;
                        dimensions[1] = 0;
                        return new byte[0];
                }

                final boolean oneRow = horizontalAlignment == null;
                final String[] defaultTextSplit = IGui.textOrUntitled(text).split("\\|");
                final String[] textSplit;
                if (Config.getClient().getLanguageDisplay() == LanguageDisplay.NORMAL) {
                        textSplit = defaultTextSplit;
                } else {
                        final String[] tempTextSplit = Arrays.stream(IGui.textOrUntitled(text).split("\\|")).filter(textPart -> IGui.isCjk(textPart) == (Config.getClient().getLanguageDisplay() == LanguageDisplay.CJK_ONLY)).toArray(String[]::new);
                        textSplit = tempTextSplit.length == 0 ? defaultTextSplit : tempTextSplit;
                }
                final AttributedString[] attributedStrings = new AttributedString[textSplit.length];
                final int[] textWidths = new int[textSplit.length];
                final int[] fontSizes = new int[textSplit.length];
                final FontRenderContext context = new FontRenderContext(new AffineTransform(), false, false);
                int width = 0;
                int height = 0;

                for (int index = 0; index < textSplit.length; index++) {
                        final int newFontSize = IGui.isCjk(textSplit[index]) || textFont.canDisplayUpTo(textSplit[index]) >= 0 ? fontSizeCjk : fontSize;
                        attributedStrings[index] = new AttributedString(textSplit[index]);
                        fontSizes[index] = newFontSize;

                        final Font fontSized = textFont.deriveFont(Font.PLAIN, newFontSize);
                        final Font fontCjkSized = textFontCjk.deriveFont(Font.PLAIN, newFontSize);

                        for (int characterIndex = 0; characterIndex < textSplit[index].length();) {
                                final int character = textSplit[index].codePointAt(characterIndex);
                                final int characterEnd = characterIndex + Character.charCount(character);
                                final Font newFont;
                                if (fontSized.canDisplay(character)) {
                                        newFont = fontSized;
                                } else if (fontCjkSized.canDisplay(character)) {
                                        newFont = fontCjkSized;
                                } else {
                                        newFont = SystemFontFallback.find(character).deriveFont(Font.PLAIN, newFontSize);
                                }
                                textWidths[index] += newFont.getStringBounds(textSplit[index].substring(characterIndex, characterEnd), context).getBounds().width;
                                attributedStrings[index].addAttribute(TextAttribute.FONT, newFont, characterIndex, characterEnd);
                                characterIndex = characterEnd;
                        }

                        if (oneRow) {
                                if (index > 0) {
                                        width += padding;
                                }
                                width += textWidths[index];
                                height = Math.max(height, (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER));
                        } else {
                                width = Utilities.clampSafe(textWidths[index], width, maxWidth);
                                height += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
                        }
                }

                int textOffset = 0;
                final int imageHeight = Math.min(height, maxHeight);
                if (width <= 0 || imageHeight <= 0) {
                        dimensions[0] = 0;
                        dimensions[1] = 0;
                        return new byte[0];
                }
                final BufferedImage image = new BufferedImage(width + (oneRow ? 0 : padding * 2), imageHeight + (oneRow ? 0 : padding * 2), BufferedImage.TYPE_BYTE_GRAY);
                final Graphics2D graphics2D = image.createGraphics();
                graphics2D.setColor(Color.WHITE);
                graphics2D.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                for (int index = 0; index < textSplit.length; index++) {
                        if (oneRow) {
                                graphics2D.drawString(attributedStrings[index].getIterator(), textOffset, height / LINE_HEIGHT_MULTIPLIER);
                                textOffset += textWidths[index] + padding;
                        } else {
                                final float scaleY = (float) imageHeight / height;
                                final float textWidth = Math.min(maxWidth, textWidths[index] * scaleY);
                                final float scaleX = textWidth / textWidths[index];
                                final AffineTransform stretch = new AffineTransform();
                                stretch.concatenate(AffineTransform.getScaleInstance(scaleX, scaleY));
                                graphics2D.setTransform(stretch);
                                graphics2D.drawString(attributedStrings[index].getIterator(), horizontalAlignment.getOffset(0, textWidth - width) / scaleY + padding / scaleX, textOffset + fontSizes[index] + padding / scaleY);
                                textOffset += (int) (fontSizes[index] * LINE_HEIGHT_MULTIPLIER);
                        }
                }

                dimensions[0] = width + (oneRow ? 0 : padding * 2);
                dimensions[1] = imageHeight + (oneRow ? 0 : padding * 2);
                final byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                graphics2D.dispose();
                image.flush();
                return pixels;
        }

        /**
         * Load one of the bundled TrueType fonts used for dynamic text rendering, retrying a
         * bounded number of times before falling back to a logical (system) font.
         *
         * <p>The previous {@code while (font == null)} spin retried forever when the resource
         * was missing ({@code ResourceManagerHelper.readResource} then never invokes its
         * consumer) or corrupt ({@code Font.createFont} throwing on every iteration), wedging
         * the single {@link org.mtr.render.WorkerThread} task loop: dynamic texture generation
         * and occlusion culling stalled permanently behind a 100% CPU error-log flood.
         * Bounded retries keep the worker alive, and the logical fallback keeps texture
         * generation working — glyphs the fallback cannot shape render as missing glyphs
         * rather than hanging the game.</p>
         *
         * @param fontPath path of the font under the {@code mtr} namespace, e.g. {@code font/noto-sans-semibold.ttf}
         * @param fontName human-readable font name used in log messages
         * @return the loaded font, or a logical font once every attempt has failed
         */
        private Font loadBundledFont(String fontPath, String fontName) {
                for (int attempt = 1; attempt <= FONT_LOAD_ATTEMPTS; attempt++) {
                        final Font[] loadedFont = {null};
                        final int attemptNumber = attempt;
                        ResourceManagerHelper.readResource(ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, fontPath), inputStream -> {
                                try {
                                        loadedFont[0] = Font.createFont(Font.TRUETYPE_FONT, inputStream);
                                } catch (Exception e) {
                                        MTR.LOGGER.error("Failed to load the {} font for dynamic text rendering (attempt {}/{})", fontName, attemptNumber, FONT_LOAD_ATTEMPTS, e);
                                }
                        });
                        if (loadedFont[0] != null) {
                                return loadedFont[0];
                        }
                }
                // Exactly one warning per fallback, not per retry.
                MTR.LOGGER.warn("Failed to load the {} font for dynamic text rendering after {} attempts; falling back to the {} logical font until the next resource reload", fontName, FONT_LOAD_ATTEMPTS, FALLBACK_LOGICAL_FONT);
                return Font.decode(FALLBACK_LOGICAL_FONT);
        }

        private DynamicResource getResource(String key, Supplier<@Nullable NativeImage> supplier, DefaultRenderingColor defaultRenderingColor) {
                final DynamicResource dynamicResource = dynamicResources.get(key);
                if (dynamicResource != null) {
                        dynamicResource.expiryTime = System.currentTimeMillis() + COOLDOWN_TIME;
                }
                if (closed || dynamicResource != null && !dynamicResource.needsRefresh || generatingResources.contains(key) || generatingResources.size() >= MAX_PENDING_TEXTURES) {
                        return dynamicResource == null ? defaultRenderingColor.dynamicResource : dynamicResource;
                }

                final long requestEpoch = resourceEpoch;
                final int maximumImageSize = MAX_IMAGE_SIZE * (int) Math.pow(2, Config.getClient().getDynamicTextureResolution());
                final boolean accepted = MainRenderer.WORKER_THREAD.scheduleDynamicTextures(() -> {
                        if (closed || requestEpoch != resourceEpoch) {
                                return;
                        }
                        try {
                                if (!loadFonts(requestEpoch)) {
                                        return;
                                }
                                RouteMapGenerator.setConstants();
                                // Pixel copying belongs on the generation worker, before GPU registration.
                                final NativeImage nativeImage = cropImage(supplier.get(), maximumImageSize);
                                synchronized (this) {
                                        if (closed || requestEpoch != resourceEpoch) {
                                                if (nativeImage != null) {
                                                        nativeImage.close();
                                                }
                                        } else {
                                                resourceRegistryQueue.offer(() -> registerResource(key, requestEpoch, nativeImage));
                                        }
                                }
                        } catch (Exception e) {
                                MTR.LOGGER.error("Failed to generate dynamic texture {}", key, e);
                                resourceRegistryQueue.offer(() -> {
                                        if (requestEpoch == resourceEpoch) {
                                                generatingResources.remove(key);
                                        }
                                });
                        }
                });
                if (accepted) {
                        generatingResources.add(key);
                }
                return dynamicResource == null ? defaultRenderingColor.dynamicResource : dynamicResource;
        }

        private boolean loadFonts(long requestEpoch) {
                final Font currentFont = font;
                final Font currentFontCjk = fontCjk;
                final Font loadedFont = currentFont == null ? loadBundledFont("font/noto-sans-semibold.ttf", "Noto Sans Semibold") : currentFont;
                final Font loadedFontCjk = currentFontCjk == null ? loadBundledFont("font/noto-serif-tc-semibold.ttf", "Noto Serif TC Semibold") : currentFontCjk;
                synchronized (this) {
                        if (closed || requestEpoch != resourceEpoch) {
                                return false;
                        }
                        font = loadedFont;
                        fontCjk = loadedFontCjk;
                        return true;
                }
        }

        @Nullable
        private static NativeImage cropImage(@Nullable NativeImage image, int maximumSize) {
                if (image == null || image.getWidth() <= maximumSize && image.getHeight() <= maximumSize) {
                        return image;
                }
                NativeImage cropped = null;
                try {
                        cropped = new NativeImage(NativeImage.Format.RGBA, Math.min(maximumSize, image.getWidth()), Math.min(maximumSize, image.getHeight()), false);
                        for (int y = 0; y < cropped.getHeight(); y++) {
                                for (int x = 0; x < cropped.getWidth(); x++) {
                                        RouteMapGenerator.setPixel(cropped, x, y, RouteMapGenerator.getPixel(image, x, y));
                                }
                        }
                        return cropped;
                } catch (RuntimeException | Error e) {
                        if (cropped != null) {
                                cropped.close();
                        }
                        throw e;
                } finally {
                        image.close();
                }
        }

        private void registerResource(String key, long requestEpoch, @Nullable NativeImage image) {
                if (closed || requestEpoch != resourceEpoch) {
                        if (image != null) {
                                image.close();
                        }
                        return;
                }
                DynamicTexture texture = null;
                boolean registered = false;
                try {
                        if (image == null) {
                                return;
                        }
                        texture = new DynamicTexture(image);
                        final ResourceLocation identifier = ResourceLocation.fromNamespaceAndPath(MTR.MOD_ID, "id_" + MTR.randomString());
                        Minecraft.getInstance().getTextureManager().register(identifier, texture);
                        registered = true;
                        org.mtr.nativeobj.NativeTextureManager.INSTANCE.markDirty(identifier);
                        final DynamicResource oldResource = dynamicResources.put(key, new DynamicResource(identifier, texture));
                        if (oldResource != null) {
                                oldResource.remove();
                                deletedResources.put(oldResource.identifier, System.currentTimeMillis() + COOLDOWN_TIME);
                        }
                } catch (Exception e) {
                        MTR.LOGGER.error("Failed to register dynamic texture {}", key, e);
                } finally {
                        if (!registered && image != null) {
                                if (texture == null) {
                                        image.close();
                                } else {
                                        texture.close();
                                }
                        }
                        generatingResources.remove(key);
                }
        }

        /** Release textures and stale worker results when leaving a client session. */
        public synchronized void close() {
                closed = true;
                resourceEpoch++;
                Runnable registration;
                while ((registration = resourceRegistryQueue.poll()) != null) {
                        registration.run();
                }
                dynamicResources.values().forEach(resource -> {
                        resource.remove();
                        Minecraft.getInstance().getTextureManager().release(resource.identifier);
                });
                deletedResources.keySet().forEach(identifier -> Minecraft.getInstance().getTextureManager().release(identifier));
                dynamicResources.clear();
                deletedResources.clear();
                generatingResources.clear();
        }

        /** Lazily initialised on the generation worker; includes negative glyph lookups. */
        private static final class SystemFontFallback {
                private static final Font[] FONTS = GraphicsEnvironment.getLocalGraphicsEnvironment().getAllFonts();
                private static final Font DEFAULT_FONT = Font.decode(FALLBACK_LOGICAL_FONT);
                private static final Map<Integer, Font> CACHE = new ConcurrentHashMap<>();
                private static final int MAX_CACHED_GLYPHS = 4096;

                private static Font find(int codePoint) {
                        final Font cached = CACHE.get(codePoint);
                        if (cached != null) {
                                return cached;
                        }
                        Font result = DEFAULT_FONT;
                        for (final Font candidate : FONTS) {
                                if (candidate.canDisplay(codePoint)) {
                                        result = candidate;
                                        break;
                                }
                        }
                        if (CACHE.size() < MAX_CACHED_GLYPHS) {
                                CACHE.put(codePoint, result);
                        }
                        return result;
                }
        }

        /**
         * Generated-texture handle returned by every {@code getXxx(...)} accessor.
         *
         * <p>Holds the GL {@link ResourceLocation} to bind plus the rendered pixel dimensions.
         * Instances are short-lived: a fresh one is allocated each time a texture is
         * regenerated, and {@link #remove()} cancels any in-flight scheduled renders and
         * evicts cached render-layer entries.</p>
         */
        public static class DynamicResource {

                private long expiryTime;
                private boolean needsRefresh;
                public final int width;
                public final int height;
                public final ResourceLocation identifier;

                private DynamicResource(ResourceLocation identifier, @Nullable DynamicTexture nativeImageBackedTexture) {
                        this.identifier = identifier;
                        expiryTime = System.currentTimeMillis() + COOLDOWN_TIME;
                        if (nativeImageBackedTexture != null) {
                                final NativeImage nativeImage = nativeImageBackedTexture.getPixels();
                                if (nativeImage != null) {
                                        width = nativeImage.getWidth();
                                        height = nativeImage.getHeight();
                                } else {
                                        width = 16;
                                        height = 16;
                                }
                        } else {
                                width = 16;
                                height = 16;
                        }
                }

                private void remove() {
                        MainRenderer.cancelRender(identifier);
                        MoreRenderLayers.removeFromCache(identifier);
                }
        }

        private enum DefaultRenderingColor {
                BLACK(DEFAULT_BLACK_RESOURCE),
                WHITE(DEFAULT_WHITE_RESOURCE),
                TRANSPARENT(DEFAULT_TRANSPARENT_RESOURCE);

                private final DynamicResource dynamicResource;

                DefaultRenderingColor(ResourceLocation identifier) {
                        dynamicResource = new DynamicResource(identifier, null);
                }
        }
}
