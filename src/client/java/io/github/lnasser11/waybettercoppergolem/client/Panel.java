package io.github.lnasser11.waybettercoppergolem.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

/**
 * The dark panel every mod screen sits on: background, border, a header
 * with title and subtitle, and helpers for section labels and separators.
 * Pure drawing; screens keep laying out vanilla widgets inside
 * {@link #contentX()} / {@link #contentTop()}.
 */
public record Panel(int x, int y, int width, int height) {
	/** A panel of the given content size, centered on the screen (content excludes padding and header). */
	public static Panel centered(int screenWidth, int screenHeight, int contentWidth, int contentHeight) {
		int width = contentWidth + 2 * Ui.PADDING;
		int height = contentHeight + 2 * Ui.PADDING + Ui.HEADER_HEIGHT;
		int x = screenWidth / 2 - width / 2;
		int y = Math.max(4, screenHeight / 2 - height / 2);
		return new Panel(x, y, width, height);
	}

	public int contentX() {
		return this.x + Ui.PADDING;
	}

	public int contentTop() {
		return this.y + Ui.HEADER_HEIGHT + Ui.PADDING;
	}

	public int contentWidth() {
		return this.width - 2 * Ui.PADDING;
	}

	public int contentRight() {
		return this.x + this.width - Ui.PADDING;
	}

	public int bottom() {
		return this.y + this.height;
	}

	public int centerX() {
		return this.x + this.width / 2;
	}

	/** Background, border and the header separator. Call first in extractRenderState. */
	public void draw(GuiGraphicsExtractor graphics) {
		graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, Ui.PANEL_BACKGROUND);
		outline(graphics, this.x, this.y, this.width, this.height, Ui.PANEL_BORDER);
		graphics.fill(this.x + 1, this.y + 1, this.x + this.width - 1, this.y + 2, Ui.PANEL_INNER_LINE);
		separator(graphics, this.y + Ui.HEADER_HEIGHT - 1);
	}

	/** Title centered in the header, with an optional muted subtitle under it. */
	public void header(GuiGraphicsExtractor graphics, Font font, Component title, @Nullable Component subtitle) {
		graphics.centeredText(font, title, centerX(), this.y + 8, Ui.TEXT);
		if (subtitle != null) {
			graphics.centeredText(font, subtitle, centerX(), this.y + 20, Ui.TEXT_MUTED);
		}
	}

	/** A full-width thin line at {@code y}. */
	public void separator(GuiGraphicsExtractor graphics, int lineY) {
		graphics.fill(this.x + 1, lineY, this.x + this.width - 1, lineY + 1, Ui.SEPARATOR);
	}

	/** A muted label above a group of controls; returns the y where the controls start. */
	public static int sectionLabel(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y) {
		graphics.text(font, text, x, y, Ui.TEXT_HINT);
		return y + Ui.SECTION_LABEL;
	}

	public static void outline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
		graphics.fill(x, y, x + width, y + 1, color);
		graphics.fill(x, y + height - 1, x + width, y + height, color);
		graphics.fill(x, y, x + 1, y + height, color);
		graphics.fill(x + width - 1, y, x + width, y + height, color);
	}
}
