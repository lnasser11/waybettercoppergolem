package io.github.lnasser11.waybettercoppergolem.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * A button for actions that are easy to regret: the first click arms it
 * and its text turns into "Confirm", the second click within five seconds
 * runs the action. Left alone, it disarms on its own.
 */
public class ConfirmButton extends Button.Plain {
	public static final long CONFIRM_WINDOW_MILLIS = 5000;

	private final Component label;
	private final Runnable action;
	private long armedAt;

	public ConfirmButton(int x, int y, int width, int height, Component label, Runnable action) {
		super(x, y, width, height, label, button -> {}, DEFAULT_NARRATION);
		this.label = label;
		this.action = action;
	}

	public ConfirmButton withTooltip(Component tooltip) {
		this.setTooltip(Tooltip.create(tooltip));
		return this;
	}

	public boolean isArmed() {
		return this.armedAt != 0 && System.currentTimeMillis() - this.armedAt <= CONFIRM_WINDOW_MILLIS;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		if (isArmed()) {
			this.armedAt = 0;
			this.action.run();
		} else {
			this.armedAt = System.currentTimeMillis();
		}
	}

	@Override
	public Component getMessage() {
		return isArmed() ? Component.translatable("waybettercoppergolem.confirm") : this.label;
	}
}
