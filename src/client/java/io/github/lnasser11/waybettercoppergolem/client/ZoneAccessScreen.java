package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneAccess;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

import java.util.List;

/**
 * Who may change a zone: the owner, the trusted players (each with a
 * remove button for editors), a name box to trust someone, and "Take
 * over" for operators. Reads the owner and the trusted list from the
 * synced zone registry and refreshes when the server changes it; trust
 * changes go through {@link ZonePayloads.Trust}, the takeover through the
 * zone menu's button, so this screen keeps the zone menu open underneath.
 */
public class ZoneAccessScreen extends Screen {
	private static final int MAX_ROWS = 6;

	private final ZoneSettingsScreen parent;
	private final BlockPos anchor;
	private final boolean canEdit;
	private final boolean operator;
	private ZoneAccess shown = ZoneAccess.NONE;
	private String name = "";
	private Panel panel = new Panel(0, 0, 0, 0);
	private int trustedLabelY;

	public ZoneAccessScreen(ZoneSettingsScreen parent, BlockPos anchor, boolean canEdit, boolean operator) {
		super(Component.translatable("waybettercoppergolem.access.title"));
		this.parent = parent;
		this.anchor = anchor;
		this.canEdit = canEdit;
		this.operator = operator;
	}

	private ZoneAccess currentAccess() {
		if (this.minecraft == null || this.minecraft.level == null) {
			return ZoneAccess.NONE;
		}
		Zone zone = Zones.all(this.minecraft.level).get(this.anchor);
		return zone == null ? ZoneAccess.NONE : zone.access();
	}

	@Override
	protected void init() {
		super.init();
		this.shown = currentAccess();
		int rows = Math.clamp(this.shown.trusted().size(), 1, MAX_ROWS);
		int contentHeight = Ui.ROW + Ui.GAP + Ui.SECTION_LABEL + rows * Ui.ROW + Ui.GAP + Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, contentHeight);
		int left = this.panel.contentX();
		int y = this.panel.contentTop();

		// ---- trust a player by name
		EditBox box = new EditBox(this.font, left, y, Ui.COLUMN_WIDTH + 40, Ui.BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.access.name"));
		box.setHint(Component.translatable("waybettercoppergolem.access.name_hint"));
		box.setMaxLength(16);
		box.setValue(this.name);
		box.setResponder(text -> this.name = text);
		box.setEditable(this.canEdit);
		this.addRenderableWidget(box);
		Button trust = Button.builder(Component.translatable("waybettercoppergolem.access.trust"), button -> {
			if (!this.name.isBlank()) {
				ClientPlayNetworking.send(new ZonePayloads.Trust(this.anchor, this.name.trim(), true));
				this.name = "";
				box.setValue("");
			}
		}).tooltip(Tooltip.create(Component.translatable(this.canEdit
				? "waybettercoppergolem.access.trust.tooltip" : "waybettercoppergolem.access.read_only")))
				.bounds(left + Ui.COLUMN_WIDTH + 40 + Ui.GAP, y, Ui.PANEL_WIDTH - Ui.COLUMN_WIDTH - 40 - Ui.GAP, Ui.BUTTON_HEIGHT).build();
		trust.active = this.canEdit;
		this.addRenderableWidget(trust);
		y += Ui.ROW + Ui.GAP;

		// ---- the trusted list
		this.trustedLabelY = y;
		y += Ui.SECTION_LABEL;
		List<NameAndId> trusted = this.shown.trusted();
		for (int i = 0; i < Math.min(trusted.size(), MAX_ROWS); i++) {
			NameAndId entry = trusted.get(i);
			this.addRenderableWidget(new ListRow(left, y, Ui.PANEL_WIDTH - Ui.BUTTON_HEIGHT - Ui.GAP,
					Component.literal(entry.name()), () -> {}));
			Button remove = Button.builder(Component.literal("✕"),
							button -> ClientPlayNetworking.send(new ZonePayloads.Trust(this.anchor, entry.name(), false)))
					.tooltip(Tooltip.create(Component.translatable(this.canEdit
							? "waybettercoppergolem.access.untrust.tooltip" : "waybettercoppergolem.access.read_only")))
					.bounds(left + Ui.PANEL_WIDTH - Ui.BUTTON_HEIGHT, y, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build();
			remove.active = this.canEdit;
			this.addRenderableWidget(remove);
			y += Ui.ROW;
		}

		// ---- bottom: take over (operators) and back
		int bottomY = this.panel.bottom() - Ui.PADDING - Ui.BUTTON_HEIGHT;
		int half = (Ui.PANEL_WIDTH - Ui.GAP) / 2;
		Button takeOver = new ConfirmButton(left, bottomY, half, Ui.BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.access.take_over"),
				() -> this.parent.clickFromChild(ZoneSettingsMenu.BUTTON_TAKE_OVER))
				.withTooltip(Component.translatable(this.operator
						? "waybettercoppergolem.access.take_over.tooltip" : "waybettercoppergolem.access.operators_only"));
		takeOver.active = this.operator;
		this.addRenderableWidget(takeOver);
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
				.bounds(left + half + Ui.GAP, bottomY, half, Ui.BUTTON_HEIGHT).build());
	}

	@Override
	public void tick() {
		super.tick();
		if (!currentAccess().equals(this.shown)) {
			this.rebuildWidgets();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.panel.header(graphics, this.font, this.title, Zones.describeAccess(this.shown));
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.access.trusted_header",
				this.shown.trusted().size()), this.panel.contentX(), this.trustedLabelY, this.panel.contentWidth());
		if (this.shown.trusted().isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.access.nobody_trusted"),
					this.panel.centerX(), this.trustedLabelY + Ui.SECTION_LABEL + 6, Ui.TEXT_HINT);
		}
	}

	/** Back to the zone screen, which is still open underneath. */
	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
