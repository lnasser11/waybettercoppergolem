package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneAccess;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
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
 * copper chest inside it or from the Golem button in its inventory. Two
 * columns so it fits a 480 × 270 GUI (and the 427 × 240 default window):
 * behavior toggles on the left, the area, the tools and the access section
 * on the right, the operator defaults in the bottom row beside Done. Anything
 * the server would refuse for this player (see the menu's flags) is shown
 * disabled with the reason as its tooltip. Widgets act
 * through vanilla menu-button clicks; values re-sync from the server
 * through the menu's data slots, so what you see is what got saved.
 */
public class ZoneSettingsScreen extends Screen implements MenuAccess<ZoneSettingsMenu> {
	private final ZoneSettingsMenu menu;
	private ZoneSettings shown = ZoneSettings.DEFAULT;
	private BlockPos shownAnchor = BlockPos.ZERO;
	private BoundingBox shownArea = new BoundingBox(BlockPos.ZERO);
	private Panel panel = new Panel(0, 0, 0, 0);
	private int leftX;
	private int rightX;
	private int behaviorLabelY;
	private int areaLabelY;
	private int accessLabelY;
	private ZoneAccess shownAccess = ZoneAccess.NONE;

	public ZoneSettingsScreen(ZoneSettingsMenu menu, Inventory inventory, Component title) {
		super(title);
		this.menu = menu;
	}

	@Override
	public ZoneSettingsMenu getMenu() {
		return this.menu;
	}

	/** An on/off toggle with short state words, so "name: state" fits one column in every language. */
	private static CycleButton.Builder<Boolean> toggle(boolean value) {
		return CycleButton.booleanBuilder(Component.translatable("waybettercoppergolem.toggle.on"),
				Component.translatable("waybettercoppergolem.toggle.off"), value);
	}

	@Override
	protected void init() {
		super.init();
		this.shown = this.menu.settings();
		this.shownAnchor = this.menu.anchorPos();
		this.shownArea = this.menu.area();
		this.shownAccess = currentAccess();
		boolean canEdit = this.menu.canEdit();
		boolean operator = this.menu.isOperator();

		int leftHeight = Ui.SECTION_LABEL + 6 * Ui.ROW;
		int rightHeight = Ui.SECTION_LABEL + 4 * Ui.ROW + Ui.GAP + Ui.SECTION_LABEL + Ui.ROW;
		int contentHeight = Math.max(leftHeight, rightHeight) + Ui.GAP + Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, contentHeight);
		this.leftX = this.panel.contentX();
		this.rightX = this.leftX + Ui.COLUMN_WIDTH + Ui.GAP;
		int top = this.panel.contentTop();
		int half = (Ui.COLUMN_WIDTH - Ui.GAP) / 2;

		// ---- left: behavior
		int y = top;
		this.behaviorLabelY = y;
		y += Ui.SECTION_LABEL;
		editable(this.addRenderableWidget(toggle(this.shown.reorganize())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.reorganize"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_REORGANIZE))),
				"waybettercoppergolem.settings.reorganize.tooltip", canEdit);
		y += Ui.ROW;
		editable(this.addRenderableWidget(toggle(this.shown.tidyInside())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.tidy"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_TIDY))),
				"waybettercoppergolem.settings.tidy.tooltip", canEdit);
		y += Ui.ROW;
		editable(this.addRenderableWidget(toggle(this.shown.dryRun())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.dry_run"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN))),
				"waybettercoppergolem.settings.dry_run.tooltip", canEdit);
		y += Ui.ROW;
		editable(this.addRenderableWidget(toggle(this.shown.stayInside())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.stay_inside"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_STAY_INSIDE))),
				"waybettercoppergolem.settings.stay_inside.tooltip", canEdit);
		y += Ui.ROW;
		editable(this.addRenderableWidget(toggle(this.shown.hangFrames())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.hang_frames"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_HANG_FRAMES))),
				"waybettercoppergolem.settings.hang_frames.tooltip", canEdit);
		y += Ui.ROW;
		editable(this.addRenderableWidget(toggle(this.shown.perchIdle())
				.create(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.perch_idle"),
						(button, value) -> click(ZoneSettingsMenu.BUTTON_TOGGLE_PERCH_IDLE))),
				"waybettercoppergolem.settings.perch_idle.tooltip", canEdit);

		// ---- right: area and tools
		y = top;
		this.areaLabelY = y;
		y += Ui.SECTION_LABEL;
		editable(this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.set_area"), button -> {
							// Corners are clicked in the world, so leave the screen.
							click(ZoneSettingsMenu.BUTTON_SET_AREA);
							this.onClose();
						})
				.bounds(this.rightX, y, half, Ui.BUTTON_HEIGHT).build()),
				"waybettercoppergolem.settings.set_area.tooltip", canEdit);
		editable(this.addRenderableWidget(new ConfirmButton(this.rightX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.reset_area"),
						() -> click(ZoneSettingsMenu.BUTTON_RESET_AREA))),
				"waybettercoppergolem.settings.reset_area.tooltip", canEdit);
		y += Ui.ROW;
		// ---- right: vertical reach stepper
		Button down = Button.builder(Component.translatable("waybettercoppergolem.settings.reach_down"),
						button -> click(ZoneSettingsMenu.BUTTON_REACH_DOWN))
				.bounds(this.rightX, y, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build();
		down.active = canEdit && this.shown.verticalReach() > ZoneSettings.MIN_VERTICAL_REACH;
		this.addRenderableWidget(down);
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.reach", this.shown.verticalReach()), button -> {})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.reach.tooltip")))
				.bounds(this.rightX + Ui.BUTTON_HEIGHT + Ui.GAP, y, Ui.COLUMN_WIDTH - 2 * (Ui.BUTTON_HEIGHT + Ui.GAP), Ui.BUTTON_HEIGHT)
				.build()); // a readout: looks live, does nothing on click, carries the tooltip
		Button up = Button.builder(Component.translatable("waybettercoppergolem.settings.reach_up"),
						button -> click(ZoneSettingsMenu.BUTTON_REACH_UP))
				.bounds(this.rightX + Ui.COLUMN_WIDTH - Ui.BUTTON_HEIGHT, y, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build();
		up.active = canEdit && this.shown.verticalReach() < ZoneSettings.MAX_VERTICAL_REACH;
		this.addRenderableWidget(up);
		y += Ui.ROW;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.overview"), button -> {
							// The overview arrives as its own screen.
							click(ZoneSettingsMenu.BUTTON_OVERVIEW);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.overview.tooltip")))
				.bounds(this.rightX, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.simulate"), button -> {
							click(ZoneSettingsMenu.BUTTON_SIMULATE);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.simulate.tooltip")))
				.bounds(this.rightX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		Button learn = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.learn"), button -> {
							// The proposal arrives in chat, so close the screen to read it.
							click(ZoneSettingsMenu.BUTTON_LEARN);
							this.onClose();
						})
				.bounds(this.rightX, y, half, Ui.BUTTON_HEIGHT).build());
		if (this.menu.canLearn()) {
			learn.setTooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.learn.tooltip")));
		} else {
			// Editors who are not operators are refused by learn_requires_op; everyone else by the zone's access rule.
			learn.active = false;
			learn.setTooltip(Tooltip.create(Component.translatable(canEdit
					? "waybettercoppergolem.learn.not_allowed" : "waybettercoppergolem.access.read_only")));
		}
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.show_area"), button -> {
							click(ZoneSettingsMenu.BUTTON_SHOW_AREA);
							this.onClose();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.show_area.tooltip")))
				.bounds(this.rightX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW + Ui.GAP;

		// ---- right: access
		this.accessLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.access"), button -> {
							if (this.minecraft != null) {
								this.minecraft.setScreenAndShow(new ZoneAccessScreen(this, this.shownAnchor, canEdit, operator));
							}
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.settings.access.tooltip")))
				.bounds(this.rightX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());

		// ---- bottom row: the operator defaults and Done
		int bottomY = this.panel.bottom() - Ui.PADDING - Ui.BUTTON_HEIGHT;
		int third = (Ui.PANEL_WIDTH - 2 * Ui.GAP) / 3;
		editable(this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.settings.save_defaults"),
						button -> click(ZoneSettingsMenu.BUTTON_SAVE_DEFAULTS))
				.bounds(this.leftX, bottomY, third, Ui.BUTTON_HEIGHT).build()),
				"waybettercoppergolem.settings.save_defaults.tooltip", operator);
		editable(this.addRenderableWidget(new ConfirmButton(this.leftX + third + Ui.GAP, bottomY, third, Ui.BUTTON_HEIGHT,
						Component.translatable("waybettercoppergolem.settings.apply_all"),
						() -> click(ZoneSettingsMenu.BUTTON_APPLY_ALL))),
				"waybettercoppergolem.settings.apply_all.tooltip", operator);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.leftX + 2 * (third + Ui.GAP), bottomY, Ui.PANEL_WIDTH - 2 * (third + Ui.GAP), Ui.BUTTON_HEIGHT)
				.build());
	}

	/** Gives the widget its tooltip, and disables it with the reason when the server said this player may not edit. */
	private static void editable(AbstractWidget widget, String tooltipKey, boolean allowed) {
		if (allowed) {
			widget.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
		} else {
			widget.active = false;
			widget.setTooltip(Tooltip.create(Component.translatable("waybettercoppergolem.access.read_only")));
		}
	}

	/** The zone's owner and trusted list, read from the synced zone registry. */
	private ZoneAccess currentAccess() {
		if (this.minecraft == null || this.minecraft.level == null) {
			return ZoneAccess.NONE;
		}
		Zone zone = Zones.all(this.minecraft.level).get(this.shownAnchor);
		return zone == null ? ZoneAccess.NONE : zone.access();
	}

	/** A menu button pressed from the access screen, which keeps this screen (and its menu) open underneath. */
	void clickFromChild(int buttonId) {
		click(buttonId);
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
				|| !this.menu.area().equals(this.shownArea) || !currentAccess().equals(this.shownAccess)) {
			// Server confirmed a change through the data slots; rebuild so
			// every widget shows the authoritative values.
			this.rebuildWidgets();
		}
	}

	/** The panel goes under the widgets, so it is drawn with the background. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.panel.header(graphics, this.font, this.title,
				Component.translatable("waybettercoppergolem.settings.subtitle",
						this.shownAnchor.getX() + ", " + this.shownAnchor.getY() + ", " + this.shownAnchor.getZ(),
						Zones.describeArea(this.shownArea)));
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.behavior"), this.leftX, this.behaviorLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.area_tools"), this.rightX, this.areaLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.settings.section.access",
				Zones.describeAccess(this.shownAccess)), this.rightX, this.accessLabelY, Ui.COLUMN_WIDTH);
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
