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
 * copper chest inside it or from the Golem button in its inventory. Three
 * sections: behavior toggles, the area, and the tools (overview,
 * simulate, learn). Widgets act through vanilla menu-button clicks; values
 * re-sync from the server through the menu's data slots, so what you see
 * is what got saved.
 */
public class ZoneSettingsScreen extends Screen implements MenuAccess<ZoneSettingsMenu> {
	private final ZoneSettingsMenu menu;
	private ZoneSettings shown = ZoneSettings.DEFAULT;
	private BlockPos shownAnchor = BlockPos.ZERO;
	private BoundingBox shownArea = new BoundingBox(BlockPos.ZERO);
	private Panel panel = new Panel(0, 0, 0, 0);
	private int behaviorLabelY;
	private int areaLabelY;
	private int toolsLabelY;
	private int defaultsLabelY;

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
		this.shownAnchor = this.menu.anchorPos();
		this.shownArea = this.menu.area();

		int contentHeight = 4 * Ui.SECTION_LABEL + 8 * Ui.ROW + 3 * Ui.GAP + Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, contentHeight);
		int x = this.panel.contentX();
		int width = Ui.PANEL_WIDTH;
		int half = (width - Ui.GAP) / 2;
		int y = this.panel.contentTop();

		// ---- behavior
		this.behaviorLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.reorganize())
				.create(x, y, width, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.reorganize"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_REORGANIZE)))
				.setTooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.reorganize.tooltip")));
		y += Ui.ROW;
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.tidyInside())
				.create(x, y, width, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.tidy"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_TIDY)))
				.setTooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.tidy.tooltip")));
		y += Ui.ROW;
		this.addRenderableWidget(CycleButton.onOffBuilder(this.shown.dryRun())
				.create(x, y, width, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.dry_run"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN)))
				.setTooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.dry_run.tooltip")));
		y += Ui.ROW + Ui.GAP;

		// ---- area
		this.areaLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.set_area"), button -> {
							// Corners are clicked in the world, so leave the screen.
							click(ZoneSettingsMenu.BUTTON_SET_AREA);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.set_area.tooltip")))
				.bounds(x, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.reset_area"),
						button -> click(ZoneSettingsMenu.BUTTON_RESET_AREA))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.reset_area.tooltip")))
				.bounds(x + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.show_area"), button -> {
							click(ZoneSettingsMenu.BUTTON_SHOW_AREA);
							this.onClose();
						})
				.bounds(x, y, width, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW + Ui.GAP;

		// ---- tools
		this.toolsLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.overview"), button -> {
							// The overview arrives as its own screen.
							click(ZoneSettingsMenu.BUTTON_OVERVIEW);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.overview.tooltip")))
				.bounds(x, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.simulate"), button -> {
							click(ZoneSettingsMenu.BUTTON_SIMULATE);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.simulate.tooltip")))
				.bounds(x + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.learn"), button -> {
							// The proposal arrives in chat, so close the screen to read it.
							click(ZoneSettingsMenu.BUTTON_LEARN);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.learn.tooltip")))
				.bounds(x, y, width, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW + Ui.GAP;

		// ---- defaults (operators)
		this.defaultsLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.save_defaults"),
						button -> click(ZoneSettingsMenu.BUTTON_SAVE_DEFAULTS))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.save_defaults.tooltip")))
				.bounds(x, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.apply_all"),
						button -> click(ZoneSettingsMenu.BUTTON_APPLY_ALL))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.apply_all.tooltip")))
				.bounds(x + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panel.centerX() - 100, y, 200, Ui.BUTTON_HEIGHT).build());
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
		this.panel.draw(graphics);
		this.panel.header(graphics, this.font, this.title,
				Component.translatable("waybettercoppergolem.settings.subtitle",
						this.shownAnchor.getX() + ", " + this.shownAnchor.getY() + ", " + this.shownAnchor.getZ(),
						Zones.describeArea(this.shownArea)));
		int x = this.panel.contentX();
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.behavior"), x, this.behaviorLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.area"), x, this.areaLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.tools"), x, this.toolsLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.defaults"), x, this.defaultsLabelY);
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
