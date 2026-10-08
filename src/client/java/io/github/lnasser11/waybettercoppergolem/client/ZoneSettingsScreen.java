package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Settings panel for a sorting zone, opened by sneak-right-clicking any
 * copper chest inside it. Widgets act through vanilla menu-button clicks;
 * values re-sync from the server through the menu's data slots, so what
 * you see is what got saved.
 */
public class ZoneSettingsScreen extends Screen implements MenuAccess<ZoneSettingsMenu> {
	private static final int WIDGET_WIDTH = Ui.PANEL_WIDTH;
	private static final int WIDGET_HEIGHT = Ui.BUTTON_HEIGHT;
	private static final int GAP = Ui.GAP;
	private static final int ROWS = 7;
	private static final int HEADER_HEIGHT = 36;

	private final ZoneSettingsMenu menu;
	private ZoneSettings shown;
	private BlockPos shownAnchor;
	private BoundingBox shownArea;

	public ZoneSettingsScreen(ZoneSettingsMenu menu, Inventory inventory, Component title) {
		super(title);
		this.menu = menu;
	}

	@Override
	public ZoneSettingsMenu getMenu() {
		return this.menu;
	}

	private int top() {
		return this.height / 2 - (ROWS * (WIDGET_HEIGHT + GAP) + HEADER_HEIGHT) / 2;
	}

	@Override
	protected void init() {
		super.init();
		this.shown = this.menu.settings();
		this.shownAnchor = this.menu.anchorPos();
		this.shownArea = this.menu.area();
		int x = this.width / 2 - WIDGET_WIDTH / 2;
		int y = top() + HEADER_HEIGHT;
		int half = (WIDGET_WIDTH - GAP) / 2;

		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.reorganize())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.reorganize"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_REORGANIZE)));
		y += WIDGET_HEIGHT + GAP;
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.tidyInside())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.tidy"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_TIDY)));
		y += WIDGET_HEIGHT + GAP;
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.dryRun())
				.create(x, y, WIDGET_WIDTH, WIDGET_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.dry_run"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN)));
		y += WIDGET_HEIGHT + 2 * GAP;

		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.set_area"), button -> {
							// Corners are clicked in the world, so leave the screen.
							click(ZoneSettingsMenu.BUTTON_SET_AREA);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.set_area.tooltip")))
				.bounds(x, y, half, WIDGET_HEIGHT).build());
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.reset_area"),
						button -> click(ZoneSettingsMenu.BUTTON_RESET_AREA))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.reset_area.tooltip")))
				.bounds(x + half + GAP, y, half, WIDGET_HEIGHT).build());
		y += WIDGET_HEIGHT + GAP;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.show_area"), button -> {
							click(ZoneSettingsMenu.BUTTON_SHOW_AREA);
							this.onClose();
						})
				.bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += WIDGET_HEIGHT + GAP;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.learn"), button -> {
							// The proposal arrives in chat, so close the screen to read it.
							click(ZoneSettingsMenu.BUTTON_LEARN);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.learn.tooltip")))
				.bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
		y += WIDGET_HEIGHT + 2 * GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(x, y, WIDGET_WIDTH, WIDGET_HEIGHT).build());
	}

	private void click(int buttonId) {
		if (this.minecraft != null && this.minecraft.gameMode != null && this.minecraft.player != null) {
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, buttonId);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!this.menu.settings().equals(this.shown) || !this.menu.anchorPos().equals(this.shownAnchor)
				|| !this.menu.area().equals(this.shownArea)) {
			// Server confirmed a change through the data slots; rebuild so
			// every widget shows the authoritative values.
			this.rebuildWidgets();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int x = this.width / 2;
		int top = top();
		graphics.centeredText(this.font, this.title, x, top - 16, Ui.TEXT);
		graphics.centeredText(this.font,
				Component.translatable("waybettercoppergolem.settings.anchor",
						this.shownAnchor.getX(), this.shownAnchor.getY(), this.shownAnchor.getZ()),
				x, top + 4, Ui.TEXT_MUTED);
		graphics.centeredText(this.font,
				Component.translatable("waybettercoppergolem.settings.area", Zones.describeArea(this.shownArea)),
				x, top + 18, Ui.TEXT_MUTED);
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
