package org.mtr.widget;

import gg.essential.elementa.components.ScrollComponent;
import gg.essential.elementa.components.UIContainer;
import gg.essential.elementa.constraints.*;
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.vertex.UVertexConsumer;
import org.jspecify.annotations.Nullable;
import org.mtr.core.tool.Utilities;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.tool.GuiHelper;

import java.awt.*;
import java.util.Random;
import java.util.function.DoubleConsumer;

public final class ColorInputComponent extends UIContainer {

	@Nullable
	private Color oldColor;
	private float hue;
	private float saturation;
	private float brightness;

	private boolean draggingSaturationBrightness = false;
	private boolean draggingHue = false;

	@Nullable
	private InputSource inputSource;
	private int inputSourceCooldown;

	private final SlotBackgroundComponent saturationBrightnessBoxComponent;
	private final SlotBackgroundComponent hueBoxComponent;
	private final SlotBackgroundComponent colorPreviewComponent;
	private final ButtonComponent resetButton;
	private final TextInputComponent colorInputComponent;
	private final NumberInputComponent redInputComponent;
	private final NumberInputComponent greenInputComponent;
	private final NumberInputComponent blueInputComponent;

	private static final int RIGHT_PANEL_WIDTH = 96;
	private static final int INPUT_SOURCE_COOLDOWN_LIMIT = 2;
	private static final Random RANDOM = new Random();

	public ColorInputComponent() {
		saturationBrightnessBoxComponent = (SlotBackgroundComponent) new SlotBackgroundComponent()
			.setChildOf(this)
			.setWidth(new SubtractiveConstraint(new FillConstraint(), new PixelConstraint(GuiHelper.DEFAULT_PADDING * 2)))
			.setHeight(new RelativeConstraint())
			.onMouseClickConsumer(clickEvent -> clickSaturationBrightness(clickEvent.getRelativeX(), clickEvent.getRelativeY()))
			.onMouseDragConsumer((x, y, mouseButton) -> {
				if (draggingSaturationBrightness) {
					mouseClickHSB(x, y);
				}
			})
			.onMouseReleaseRunnable(() -> draggingSaturationBrightness = false);

		saturationBrightnessBoxComponent.setBackgroundColor(Color.BLACK);

		hueBoxComponent = (SlotBackgroundComponent) new SlotBackgroundComponent()
			.setChildOf(this)
			.setX(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new PixelConstraint(GuiHelper.DEFAULT_LINE_SIZE))
			.setHeight(new RelativeConstraint())
			.onMouseClickConsumer(clickEvent -> clickHue(clickEvent.getRelativeY()))
			.onMouseDragConsumer((x, y, mouseButton) -> {
				if (draggingHue) {
					mouseClickHSB(0, y);
				}
			})
			.onMouseReleaseRunnable(() -> draggingHue = false);

		hueBoxComponent.setBackgroundColor(Color.BLACK);

		final UIContainer outerContainer = (UIContainer) new UIContainer()
			.setChildOf(this)
			.setX(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new PixelConstraint(RIGHT_PANEL_WIDTH))
			.setHeight(new RelativeConstraint());

		colorPreviewComponent = (SlotBackgroundComponent) new SlotBackgroundComponent()
			.setChildOf(outerContainer)
			.setWidth(new PixelConstraint(RIGHT_PANEL_WIDTH))
			.setHeight(new PixelConstraint(RIGHT_PANEL_WIDTH / 4F));

		final ScrollComponent scrollComponent = ((ScrollPanelComponent) new ScrollPanelComponent(true)
			.setChildOf(outerContainer)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint())
			.setHeight(new SubtractiveConstraint(new FillConstraint(), new PixelConstraint(GuiHelper.DEFAULT_PADDING)))).contentContainer;

		resetButton = (ButtonComponent) (new ButtonComponent(true)
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint()));

		resetButton.setDisabled(true);
		resetButton.setText(TranslationProvider.GUI_MTR_RESET.getString());
		resetButton.onClick(() -> {
			if (oldColor != null) {
				setSelectedColor(oldColor, InputSource.EXTERNAL);
			}
		});

		final ButtonComponent randomButton = (ButtonComponent) (new ButtonComponent(true)
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint())
			.setWidth(new RelativeConstraint()));

		randomButton.setText(TranslationProvider.GUI_MTR_COLOR_RANDOM.getString());
		randomButton.onClick(this::setRandomColor);

		GuiHelper.createSpacing(scrollComponent);
		GuiHelper.createLabel(scrollComponent, TranslationProvider.GUI_MTR_COLOR_HEX.getString());

		colorInputComponent = (TextInputComponent) new TextInputComponent()
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint())
			.setWidth(new RelativeConstraint())
			.setHeight(new PixelConstraint(20));

		colorInputComponent.setForceUpperCase(true);
		colorInputComponent.setFilter("[^\\dA-F]");
		colorInputComponent.setMaxLength(6);
		colorInputComponent.onChange(() -> colorInputChanged(false));
		colorInputComponent.onFocusLost(() -> colorInputChanged(true));

		GuiHelper.createSpacing(scrollComponent);
		GuiHelper.createLabel(scrollComponent, TranslationProvider.GUI_MTR_COLOR_RGB.getString());

		final UIContainer rgbContainer = (UIContainer) new UIContainer()
			.setChildOf(scrollComponent)
			.setY(new SiblingConstraint())
			.setWidth(new RelativeConstraint())
			.setHeight(new PixelConstraint(NumberInputComponent.HEIGHT));

		redInputComponent = addColorInputComponents(rgbContainer, value -> setSelectedColor((int) value, null, null));
		greenInputComponent = addColorInputComponents(rgbContainer, value -> setSelectedColor(null, (int) value, null));
		blueInputComponent = addColorInputComponents(rgbContainer, value -> setSelectedColor(null, null, (int) value));
	}

	@Override
	public void draw(UMatrixStack matrixStack) {
		super.draw(matrixStack);
		ImageComponentBase.drawRectangle(vertexConsumer -> {
			final ColorPickerGeometry.QuadConsumer quads = (left, top, right, bottom, topLeft, bottomLeft, bottomRight, topRight) ->
				drawQuad(matrixStack, vertexConsumer, left, top, right, bottom, topLeft, bottomLeft, bottomRight, topRight);
			ColorPickerGeometry.hue(hueBoxComponent.getLeft() + 1, hueBoxComponent.getTop() + 1, hueBoxComponent.getRight() - 1, hueBoxComponent.getBottom() - 1, quads);
			ColorPickerGeometry.saturationBrightness(hue, saturationBrightnessBoxComponent.getLeft() + 1, saturationBrightnessBoxComponent.getTop() + 1,
				saturationBrightnessBoxComponent.getRight() - 1, saturationBrightnessBoxComponent.getBottom() - 1, false, quads);

			final float previewLeft = colorPreviewComponent.getLeft() + 1;
			final float previewRight = colorPreviewComponent.getRight() - 1;
			final float previewMiddle = (previewLeft + previewRight) / 2;
			final float previewTop = colorPreviewComponent.getTop() + 1;
			final float previewBottom = colorPreviewComponent.getBottom() - 1;
			final int currentColor = Color.HSBtoRGB(hue, saturation, brightness);
			final int previousColor = oldColor == null ? Color.BLACK.getRGB() : oldColor.getRGB();
			quads.accept(previewLeft, previewTop, previewMiddle, previewBottom, currentColor, currentColor, currentColor, currentColor);
			quads.accept(previewMiddle, previewTop, previewRight, previewBottom, previousColor, previousColor, previousColor, previousColor);
			drawSelection(matrixStack, vertexConsumer, hueBoxComponent, 0, hue, true);
			drawSelection(matrixStack, vertexConsumer, saturationBrightnessBoxComponent, brightness, saturation, false);
		}, true);

		if (inputSourceCooldown <= INPUT_SOURCE_COOLDOWN_LIMIT) {
			inputSourceCooldown++;
		}
	}

	public Color getSelectedColor() {
		return new Color(Color.HSBtoRGB(hue, saturation, brightness));
	}

	public void setSelectedColor(Color color) {
		setSelectedColor(color, InputSource.EXTERNAL);
	}

	public void setRandomColor() {
		setSelectedColor(new Color(RANDOM.nextInt(0xFFFFFF)), InputSource.EXTERNAL);
	}

	private void setSelectedColor(@Nullable Integer red, @Nullable Integer green, @Nullable Integer blue) {
		final Color color = getSelectedColor();
		setSelectedColor(new Color(red == null ? color.getRed() : red, green == null ? color.getGreen() : green, blue == null ? color.getBlue() : blue), InputSource.RGB);
	}

	private void setSelectedColor(Color color, InputSource inputSource) {
		if (inputSourceCooldown > INPUT_SOURCE_COOLDOWN_LIMIT) {
			this.inputSource = inputSource;
		}

		if (this.inputSource != InputSource.HSB) {
			final float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
			hue = hsb[0];
			saturation = hsb[1];
			brightness = hsb[2];
		}

		if (this.inputSource != InputSource.COLOR) {
			final String colorHexString = Utilities.numberToPaddedHexString(color.getRGB() & 0xFFFFFF, 6);
			if (!colorInputComponent.getText().equals(colorHexString)) {
				colorInputComponent.setText(colorHexString);
			}
		}

		if (this.inputSource != InputSource.RGB) {
			if (redInputComponent.getValue() != color.getRed()) {
				redInputComponent.setValue(color.getRed());
			}

			if (greenInputComponent.getValue() != color.getGreen()) {
				greenInputComponent.setValue(color.getGreen());
			}

			if (blueInputComponent.getValue() != color.getBlue()) {
				blueInputComponent.setValue(color.getBlue());
			}
		}

		if (oldColor == null) {
			oldColor = color;
		}

		resetButton.setDisabled(oldColor.getRGB() == color.getRGB());
		inputSourceCooldown = 0;
	}

	private void colorInputChanged(boolean updateColorInput) {
		try {
			setSelectedColor(new Color(Integer.parseInt(colorInputComponent.getText(), 16)), updateColorInput ? InputSource.EXTERNAL : InputSource.COLOR);
		} catch (Exception ignored) {
		}
	}

	private void clickHue(float y) {
		if (Utilities.isBetween(y, 0, hueBoxComponent.getHeight())) {
			draggingHue = true;
			mouseClickHSB(0, y);
		}
	}

	private void clickSaturationBrightness(float x, float y) {
		if (Utilities.isBetween(x, 0, saturationBrightnessBoxComponent.getWidth()) && Utilities.isBetween(y, 0, saturationBrightnessBoxComponent.getHeight())) {
			draggingSaturationBrightness = true;
			mouseClickHSB(x, y);
		}
	}

	private void mouseClickHSB(float x, float y) {
		if (draggingHue) {
			hue = getHue(y);
			setSelectedColor(getSelectedColor(), InputSource.HSB);
		}

		if (draggingSaturationBrightness) {
			saturation = getSaturation(y);
			brightness = getBrightness(x);
			setSelectedColor(getSelectedColor(), InputSource.HSB);
		}
	}

	private float getHue(float y) {
		return Utilities.clampSafe((y - 1.5F) / (hueBoxComponent.getHeight() - 3), 0, 1);
	}

	private float getSaturation(float y) {
		return Utilities.clampSafe((y - 1.5F) / (saturationBrightnessBoxComponent.getHeight() - 3), 0, 1);
	}

	private float getBrightness(float x) {
		return Utilities.clampSafe((x - 1.5F) / (saturationBrightnessBoxComponent.getWidth() - 3), 0, 1);
	}

	private static NumberInputComponent addColorInputComponents(UIContainer container, DoubleConsumer callback) {
		return (NumberInputComponent) new NumberInputComponent(0, 0xFF, 1, false, callback)
			.setChildOf(container)
			.setX(new SiblingConstraint())
			.setWidth(new ScaleConstraint(new RelativeConstraint(), 1F / 3));
	}

	private static void drawSelection(UMatrixStack matrixStack, UVertexConsumer vertexConsumer, SlotBackgroundComponent component, float selectionX, float selectionY, boolean fullWidth) {
		final float left = component.getLeft() + 1;
		final float top = component.getTop() + 1;
		final float width = component.getWidth() - 2;
		final float height = component.getHeight() - 2;
		final float x1 = left + selectionX * (width - 1);
		final float x2 = x1 + (fullWidth ? width : 1);
		final float y1 = top + selectionY * (height - 1);
		final float y2 = y1 + 1;
		drawQuad(matrixStack, vertexConsumer, Utilities.clampSafe(x1 - 1, left, left + width), Utilities.clampSafe(y1 - 1, top, top + height),
			Utilities.clampSafe(x2 + 1, left, left + width), Utilities.clampSafe(y2 + 1, top, top + height), 0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000);
		drawQuad(matrixStack, vertexConsumer, x1, y1, x2, y2, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF);
	}

	private static void drawQuad(UMatrixStack matrixStack, UVertexConsumer vertexConsumer, float left, float top, float right, float bottom, int topLeft, int bottomLeft, int bottomRight, int topRight) {
		vertexConsumer.pos(matrixStack, left, top, 0).color(topLeft >>> 16 & 0xFF, topLeft >>> 8 & 0xFF, topLeft & 0xFF, topLeft >>> 24).endVertex();
		vertexConsumer.pos(matrixStack, left, bottom, 0).color(bottomLeft >>> 16 & 0xFF, bottomLeft >>> 8 & 0xFF, bottomLeft & 0xFF, bottomLeft >>> 24).endVertex();
		vertexConsumer.pos(matrixStack, right, bottom, 0).color(bottomRight >>> 16 & 0xFF, bottomRight >>> 8 & 0xFF, bottomRight & 0xFF, bottomRight >>> 24).endVertex();
		vertexConsumer.pos(matrixStack, right, top, 0).color(topRight >>> 16 & 0xFF, topRight >>> 8 & 0xFF, topRight & 0xFF, topRight >>> 24).endVertex();
	}

	private enum InputSource {EXTERNAL, HSB, COLOR, RGB}
}
