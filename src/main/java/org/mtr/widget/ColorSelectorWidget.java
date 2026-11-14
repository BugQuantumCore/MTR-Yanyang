package org.mtr.widget;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.mtr.core.tool.Utilities;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.screen.TextCase;
import org.mtr.tool.Drawing;
import org.mtr.tool.GuiHelper;

import java.awt.*;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class ColorSelectorWidget extends PopupWidgetBase {

	private float hue;
	private float saturation;
	private float brightness;
	private MouseZone hoverMouseZone = MouseZone.NONE;
	private MouseZone draggingMouseZone = MouseZone.NONE;

	private Color oldColor = Color.BLACK;
	@Nullable
	private IntConsumer colorCallback;

	private final Runnable onDismiss;
	private final BetterTextFieldWidget colorTextField;
	private final BetterTextFieldWidget redTextField;
	private final BetterTextFieldWidget greenTextField;
	private final BetterTextFieldWidget blueTextField;

	private static final int CONTROLS_SIZE = 60;

	public ColorSelectorWidget(int minWidth, Runnable onDismiss, Runnable applyBlur) {
		super(Math.max(minWidth, GuiHelper.DEFAULT_PADDING * 3 + GuiHelper.DEFAULT_LINE_SIZE + CONTROLS_SIZE), applyBlur, Component.translatable("selectWorld.edit.save").getString(), TranslationProvider.GUI_MTR_COLOR_RANDOM.getString(), TranslationProvider.GUI_MTR_RESET.getString(), TranslationProvider.GUI_MTR_CLOSE.getString());
		this.onDismiss = onDismiss;
		colorTextField = new BetterTextFieldWidget(6, TextCase.UPPER, "[^\\dA-F]", "", CONTROLS_SIZE, text -> textCallback(text, 16, (existingColor, component) -> setColor(new Color(component))));
		redTextField = new BetterTextFieldWidget(3, TextCase.DEFAULT, "\\D", "", CONTROLS_SIZE, text -> textCallback(text, 10, (existingColor, component) -> setColor(component, existingColor.getGreen(), existingColor.getBlue())));
		greenTextField = new BetterTextFieldWidget(3, TextCase.DEFAULT, "\\D", "", CONTROLS_SIZE, text -> textCallback(text, 10, (existingColor, component) -> setColor(existingColor.getRed(), component, existingColor.getBlue())));
		blueTextField = new BetterTextFieldWidget(3, TextCase.DEFAULT, "\\D", "", CONTROLS_SIZE, text -> textCallback(text, 10, (existingColor, component) -> setColor(existingColor.getRed(), existingColor.getGreen(), component)));
	}

	@Override
	public void init(Consumer<ClickableWidgetBase> addRenderableWidget) {
		super.init(addRenderableWidget);
		addRenderableWidget.accept(colorTextField);
		addRenderableWidget.accept(redTextField);
		addRenderableWidget.accept(greenTextField);
		addRenderableWidget.accept(blueTextField);
	}

	@Override
	protected void render(GuiGraphics context, int mouseX, int mouseY) {
		visible = colorCallback != null;

		final int controlsX = getX() + width - GuiHelper.DEFAULT_PADDING - CONTROLS_SIZE;
		colorTextField.setPosition(controlsX, getY() + GuiHelper.DEFAULT_PADDING);
		redTextField.setPosition(controlsX, getY() + GuiHelper.DEFAULT_PADDING * 2 + GuiHelper.DEFAULT_LINE_SIZE);
		greenTextField.setPosition(controlsX, getY() + GuiHelper.DEFAULT_PADDING * 3 + GuiHelper.DEFAULT_LINE_SIZE * 2);
		blueTextField.setPosition(controlsX, getY() + GuiHelper.DEFAULT_PADDING * 4 + GuiHelper.DEFAULT_LINE_SIZE * 3);

		final Drawing drawing = new Drawing(context.pose(), RenderType.gui());
		final int mainHeight = height - GuiHelper.DEFAULT_PADDING * 2 - GuiHelper.DEFAULT_LINE_SIZE;
		final int mainWidth = width - GuiHelper.DEFAULT_PADDING * 4 - GuiHelper.DEFAULT_LINE_SIZE - CONTROLS_SIZE;
		hoverMouseZone = MouseZone.NONE;

		// Draw the selected colour
		drawing.setVerticesWH(controlsX, getY() + GuiHelper.DEFAULT_PADDING * 5 + GuiHelper.DEFAULT_LINE_SIZE * 4, CONTROLS_SIZE, CONTROLS_SIZE).setColor(Color.HSBtoRGB(hue, saturation, brightness) | 0xFF000000).draw();

		// Draw hue bar
		final int hueBarX = controlsX - GuiHelper.DEFAULT_PADDING - GuiHelper.DEFAULT_LINE_SIZE;
		final int startY = getY() + GuiHelper.DEFAULT_PADDING;
		final int startX = getX() + GuiHelper.DEFAULT_PADDING;
		final ColorPickerGeometry.QuadConsumer quads = (left, top, right, bottom, topLeft, bottomLeft, bottomRight, topRight) ->
			drawing.setVertices(left, top, right, bottom).setColor(topLeft, bottomLeft, bottomRight, topRight).draw();
		if (mainHeight > 0 && (draggingMouseZone == MouseZone.HUE || Utilities.isBetween(mouseX, hueBarX, hueBarX + GuiHelper.DEFAULT_LINE_SIZE - 1) && Utilities.isBetween(mouseY, startY, startY + mainHeight - 1))) {
			if (draggingMouseZone == MouseZone.HUE) {
				hue = mainHeight == 1 ? 0 : (float) Math.clamp(mouseY - startY, 0, mainHeight - 1) / (mainHeight - 1);
				updateTextFields();
			}
			hoverMouseZone = MouseZone.HUE;
		}
		ColorPickerGeometry.hue(hueBarX, startY, hueBarX + GuiHelper.DEFAULT_LINE_SIZE, startY + mainHeight, quads);

		// Draw saturation and brightness rectangle
		if (mainWidth > 0 && mainHeight > 0 && (draggingMouseZone == MouseZone.SATURATION_BRIGHTNESS || Utilities.isBetween(mouseX, startX, startX + mainWidth - 1) && Utilities.isBetween(mouseY, startY, startY + mainHeight - 1))) {
			if (draggingMouseZone == MouseZone.SATURATION_BRIGHTNESS) {
				saturation = mainWidth == 1 ? 0 : (float) Math.clamp(mouseX - startX, 0, mainWidth - 1) / (mainWidth - 1);
				brightness = mainHeight == 1 ? 1 : 1 - (float) Math.clamp(mouseY - startY, 0, mainHeight - 1) / (mainHeight - 1);
				updateTextFields();
			}
			hoverMouseZone = MouseZone.SATURATION_BRIGHTNESS;
		}
		ColorPickerGeometry.saturationBrightness(hue, startX, startY, startX + mainWidth, startY + mainHeight, true, quads);

		final double selectedHuePosition = getY() + GuiHelper.DEFAULT_PADDING + hue * (mainHeight - 1);
		final double selectedSaturationPosition = getX() + GuiHelper.DEFAULT_PADDING + saturation * (mainWidth - 1);
		final double selectedBrightnessPosition = getY() + height - GuiHelper.DEFAULT_LINE_SIZE - GuiHelper.DEFAULT_PADDING - brightness * (mainHeight - 1) - 1;

		// Draw hue selector
		drawing.setVerticesWH(hueBarX, selectedHuePosition - 1, GuiHelper.DEFAULT_LINE_SIZE, 3).setColor(GuiHelper.BACKGROUND_COLOR).draw();
		drawing.setVerticesWH(hueBarX, selectedHuePosition, GuiHelper.DEFAULT_LINE_SIZE, 1).setColor(GuiHelper.WHITE_COLOR).draw();

		// Draw saturation and brightness selector
		drawing.setVerticesWH(selectedSaturationPosition - 1, selectedBrightnessPosition, 3, 1).setColor(GuiHelper.BACKGROUND_COLOR).draw();
		drawing.setVerticesWH(selectedSaturationPosition, selectedBrightnessPosition - 1, 1, 3).setColor(GuiHelper.BACKGROUND_COLOR).draw();
		drawing.setVerticesWH(selectedSaturationPosition, selectedBrightnessPosition, 1, 1).setColor(GuiHelper.WHITE_COLOR).draw();

		// Render text fields
		colorTextField.renderWidget(context, mouseX, mouseY, 0);
		redTextField.renderWidget(context, mouseX, mouseY, 0);
		greenTextField.renderWidget(context, mouseX, mouseY, 0);
		blueTextField.renderWidget(context, mouseX, mouseY, 0);
	}

	@Override
	public void onClick(double mouseX, double mouseY) {
		draggingMouseZone = hoverMouseZone;
	}

	@Override
	public void onRelease(double mouseX, double mouseY) {
		draggingMouseZone = MouseZone.NONE;
	}

	@Override
	protected void onClickAction(int index) {
		switch (index) {
			case 0 -> {
				if (colorCallback != null) {
					colorCallback.accept(new Color(Color.HSBtoRGB(hue, saturation, brightness)).getRGB() & 0x00FFFFFF);
				}
				setColorCallbackInternal(null);
			}
			case 1 -> setColor(new Random().nextInt(0xFF), new Random().nextInt(0xFF), new Random().nextInt(0xFF));
			case 2 -> setColor(oldColor);
			case 3 -> setColorCallbackInternal(null);
		}
	}

	@Override
	protected void setWidgetHeight() {
		setHeight(GuiHelper.DEFAULT_PADDING * 6 + GuiHelper.DEFAULT_LINE_SIZE * 5 + CONTROLS_SIZE);
	}

	public void setColorCallback(IntConsumer colorCallback, int oldColorRGB) {
		setColorCallbackInternal(colorCallback);
		oldColor = new Color(oldColorRGB & 0x00FFFFFF, true);
		setColor(oldColor);
	}

	private void setColorCallbackInternal(@Nullable IntConsumer colorCallback) {
		if (colorCallback == null) {
			onDismiss.run();
			this.colorCallback = null;
		} else {
			this.colorCallback = colorCallback;
		}
		visible = this.colorCallback != null;
	}

	private void setColor(Color color) {
		setColor(color.getRed(), color.getGreen(), color.getBlue());
	}

	private void setColor(int red, int green, int blue) {
		final float[] hsb = Color.RGBtoHSB(red, green, blue, null);
		hue = hsb[0];
		saturation = hsb[1];
		brightness = hsb[2];
		updateTextFields();
	}

	private void updateTextFields() {
		final Color color = new Color(Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF, true);
		setTextField(colorTextField, color.getRGB(), 16);
		setTextField(redTextField, color.getRed(), 10);
		setTextField(greenTextField, color.getGreen(), 10);
		setTextField(blueTextField, color.getBlue(), 10);
		buttonGroup.buttons[2].active = color.getRGB() != oldColor.getRGB();
	}

	private void textCallback(String text, int base, ColorCallback colorCallback) {
		try {
			final int value = Integer.parseInt(text, base);
			colorCallback.accept(new Color(Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF), base == 10 ? Math.clamp(value, 0, 0xFF) : value);
		} catch (Exception ignored) {
		}
	}

	private static void setTextField(BetterTextFieldWidget textField, int value, int base) {
		try {
			if (Integer.parseInt(textField.getText(), base) == value) {
				return;
			}
		} catch (Exception ignored) {
		}
		textField.setText(base == 10 ? String.valueOf(value) : StringUtils.leftPad(Integer.toHexString(value).toUpperCase(Locale.ENGLISH), 6, "0"));
	}

	private enum MouseZone {
		NONE, SATURATION_BRIGHTNESS, HUE
	}

	@FunctionalInterface
	private interface ColorCallback {
		void accept(Color existingColor, int component);
	}
}
