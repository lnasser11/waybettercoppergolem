package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Settings panel opened by sneak-right-clicking a copper chest. Widgets act
 * through vanilla menu-button clicks; values re-sync from the server through
 * the menu's data slots, so what you see is what got saved.
 */
public class ZoneSettingsScreen extends Screen implements MenuAccess<ZoneSettingsMenu> {
	private static final int WIDGET_WIDTH = 220;
	private static final int WIDGET_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int ROWS = 9;
	private static final int RADIUS_STEP = 4;
	private static final int CARRY_STEP = 16;

	private static final int LABEL_COLOR = 0xFFFFFFFF;
	private static final int LABEL_COLOR_INACTIVE = 0xFF707070;

	private final ZoneSettingsMenu menu;
	/** Widgets whose setting vanilla mode overrides; greyed out while it is on. */
	private final java.util.List<net.minecraft.client.gui.components.AbstractWidget> overridden =
			new java.util.ArrayList<>();
	private ZoneSettings shown;
	private int radiusLabelY;
	private int reachLabelY;
	private int carryLabelY;

	public ZoneSettingsScreen(ZoneSettingsMenu menu, Inventory inventory, Component title) {
		super(title);
		this.menu = menu;
	}

	@Override
	public ZoneSettingsMenu getMenu() {
		return this.menu;
	}

	@Override
	protected void init() {
		super.init();
		this.shown = this.menu.settings();
		int x = this.width / 2 - WIDGET_WIDTH / 2;
		int y = Math.max(30, this.height / 2 - (ROWS * (WIDGET_HEIGHT + GAP)) / 2);

		this.overridden.clear();
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.vanillaMode())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.vanilla"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_VANILLA)));
		y += WIDGET_HEIGHT + GAP;
		this.overridden.add(this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.reorganize())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.reorganize"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_REORGANIZE))));
		y += WIDGET_HEIGHT + GAP;
		this.overridden.add(this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.tidyInside())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.tidy"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_TIDY))));
		y += WIDGET_HEIGHT + GAP;
		this.overridden.add(this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.dryRun())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.dry_run"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN))));
		y += WIDGET_HEIGHT + GAP;
		this.radiusLabelY = y + 6;
		addStepperRow(x, y, () -> this.shown.searchRadius(), RADIUS_STEP, 4, ZoneSettings.MAX_SEARCH_RADIUS,
				ZoneSettingsMenu.BUTTON_RADIUS_BASE);
		y += WIDGET_HEIGHT + GAP;
		this.reachLabelY = y + 6;
		addStepperRow(x, y, () -> this.shown.verticalReach(), 1, 1, ZoneSettings.MAX_VERTICAL_REACH,
				ZoneSettingsMenu.BUTTON_REACH_BASE);
		y += WIDGET_HEIGHT + GAP;
		this.carryLabelY = y + 6;
		addStepperRow(x, y, () -> this.shown.carryAmount(), CARRY_STEP, CARRY_STEP, ZoneSettings.MAX_CARRY_AMOUNT,
				ZoneSettingsMenu.BUTTON_CARRY_BASE);
		y += WIDGET_HEIGHT + 2 * GAP;

		// Vanilla mode overrides every other behavior setting, so show them as
		// inactive rather than letting them look like they still apply.
		for (net.minecraft.client.gui.components.AbstractWidget widget : this.overridden) {
			widget.active = !this.shown.vanillaMode();
		}
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.categories"),
						button -> openCategoryEditor())
				.bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += WIDGET_HEIGHT + GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	private void addStepperRow(int x, int y, java.util.function.IntSupplier value,
			int step, int min, int max, int buttonBase) {
		this.overridden.add(this.addRenderableWidget(Button.builder(Component.literal("-"),
						button -> click(buttonBase + Math.max(min, value.getAsInt() - step)))
				.bounds(x, y, WIDGET_HEIGHT, WIDGET_HEIGHT).build()));
		this.overridden.add(this.addRenderableWidget(Button.builder(Component.literal("+"),
						button -> click(buttonBase + Math.min(max, value.getAsInt() + step)))
				.bounds(x + WIDGET_WIDTH - WIDGET_HEIGHT, y, WIDGET_HEIGHT, WIDGET_HEIGHT).build()));
	}

	private void click(int buttonId) {
		if (this.minecraft != null && this.minecraft.gameMode != null && this.minecraft.player != null) {
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
		}
	}

	private void openCategoryEditor() {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.closeContainer();
			this.minecraft.gui.setScreen(new CategoryEditorScreen());
		}
	}

	@Override
	public void tick() {
		super.tick();
		ZoneSettings current = this.menu.settings();
		if (!current.equals(this.shown)) {
			// Server confirmed a change through the data slots; rebuild so
			// every widget shows the authoritative values.
			this.rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int x = this.width / 2;
		int top = Math.max(30, this.height / 2 - (ROWS * (WIDGET_HEIGHT + GAP)) / 2);
		graphics.centeredText(this.font, this.title, x, top - WIDGET_HEIGHT, LABEL_COLOR);
		int valueColor = this.shown.vanillaMode() ? LABEL_COLOR_INACTIVE : LABEL_COLOR;
		graphics.centeredText(this.font,
				Component.translatable("waybettercoppergolem.settings.radius", this.shown.searchRadius()),
				x, this.radiusLabelY, valueColor);
		graphics.centeredText(this.font,
				Component.translatable("waybettercoppergolem.settings.reach", this.shown.verticalReach()),
				x, this.reachLabelY, valueColor);
		graphics.centeredText(this.font,
				Component.translatable("waybettercoppergolem.settings.carry", this.shown.carryAmount()),
				x, this.carryLabelY, valueColor);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.closeContainer();
		}
		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
