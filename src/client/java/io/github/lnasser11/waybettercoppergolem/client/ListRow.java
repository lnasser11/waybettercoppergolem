package io.github.lnasser11.waybettercoppergolem.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A flat, clickable list row: optional item icon, primary text, optional
 * secondary text right-aligned in its own color, optional 2px accent bar
 * on the left, hover highlight. Used wherever a screen shows a list
 * (search results, stops, suggestions, members, chests, moves) instead of
 * a stack of vanilla buttons, which are kept for actions.
 */
public class ListRow extends AbstractButton {
	private final Runnable action;
	private @Nullable ItemStack icon;
	private @Nullable Component secondary;
	private int secondaryColor = Ui.TEXT_MUTED;
	private int primaryColor = Ui.TEXT;
	private int accent;
	private boolean iconOnly;

	public ListRow(int x, int y, int width, Component primary, Runnable action) {
		super(x, y, width, Ui.BUTTON_HEIGHT, primary);
		this.action = action;
	}

	public ListRow icon(@Nullable ItemStack icon) {
		this.icon = icon;
		return this;
	}

	public ListRow secondary(@Nullable Component text, int color) {
		this.secondary = text;
		this.secondaryColor = color;
		return this;
	}

	public ListRow primaryColor(int color) {
		this.primaryColor = color;
		return this;
	}

	/** A 2px bar on the left edge, for state at a glance. 0 = none. */
	public ListRow accent(int color) {
		this.accent = color;
		return this;
	}

	public ListRow tooltip(@Nullable Component text) {
		this.setTooltip(text == null ? null : Tooltip.create(text));
		return this;
	}

	/** Draw only the icon; the message stays for the tooltip and narration (a compact chip). */
	public ListRow iconOnly() {
		this.iconOnly = true;
		return this;
	}

	public ListRow enabled(boolean enabled) {
		this.active = enabled;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		this.action.run();
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x = this.getX();
		int y = this.getY();
		int right = x + this.getWidth();
		int background = !this.active ? Ui.ROW_DISABLED : this.isHoveredOrFocused() ? Ui.ROW_HOVER : Ui.ROW_BACKGROUND;
		graphics.fill(x, y, right, y + this.getHeight(), background);
		if (this.accent != 0) {
			graphics.fill(x, y, x + 2, y + this.getHeight(), this.accent);
		}
		int textX = x + 6;
		if (this.icon != null) {
			graphics.item(this.icon, this.iconOnly ? x + (this.getWidth() - 16) / 2 : x + 2, y + 2);
			textX = x + Ui.ICON_SLOT + 2;
		}
		if (this.iconOnly) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int textY = y + (this.getHeight() - 8) / 2;
		int textRight = right - 6;
		if (this.secondary != null) {
			int secondaryWidth = font.width(this.secondary);
			graphics.text(font, this.secondary, textRight - secondaryWidth, textY,
					this.active ? this.secondaryColor : Ui.TEXT_DISABLED);
			textRight -= secondaryWidth + 8;
		}
		int available = Math.max(10, textRight - textX);
		Component primary = this.getMessage();
		int color = this.active ? this.primaryColor : Ui.TEXT_DISABLED;
		if (font.width(primary) <= available) {
			graphics.text(font, primary, textX, textY, color);
		} else {
			List<FormattedCharSequence> lines = font.split(primary, available - font.width("…"));
			FormattedCharSequence first = lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.getFirst();
			graphics.text(font, first, textX, textY, color);
			graphics.text(font, "…", textX + font.width(first), textY, color);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
