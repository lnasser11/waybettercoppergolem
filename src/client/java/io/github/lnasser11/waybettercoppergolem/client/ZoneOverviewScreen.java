package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Entry;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Every chest of a zone on one screen, problems first: unlabeled chests,
 * chests holding stacks that match none of their labels, chests sharing a
 * label set, then the rest. Each row can be found (sparkles), edited, or
 * given the clipboard's labels; one button pastes the clipboard onto every
 * unlabeled chest. Refreshes from the server after each action.
 */
public class ZoneOverviewScreen extends Screen {
	private static final int MIN_ROWS = 3;
	private static final int MAX_ROWS = 14;
	private int rows = MIN_ROWS;
	private static final int ACTION_WIDTH = 44;
	private static final int CONTENT_WIDTH = Ui.PANEL_WIDTH + 2 * (ACTION_WIDTH + Ui.GAP);

	private static @Nullable ZoneOverviewScreen open;

	private ZonePayloads.Overview overview;
	private int page;
	private Panel panel = new Panel(0, 0, 0, 0);
	private int listLabelY;

	private ZoneOverviewScreen(ZonePayloads.Overview overview) {
		super(Component.translatable("waybettercoppergolem.overview.title"));
		this.overview = overview;
	}

	public static void openOrUpdate(Minecraft minecraft, ZonePayloads.Overview overview) {
		ZoneOverviewScreen current = open;
		if (current != null && current.overview.anchor().equals(overview.anchor())) {
			current.overview = overview;
			current.rebuildWidgets();
			return;
		}
		minecraft.setScreenAndShow(new ZoneOverviewScreen(overview));
	}

	@Override
	public void removed() {
		super.removed();
		if (open == this) {
			open = null;
		}
	}

	@Override
	protected void init() {
		super.init();
		open = this;
		int fixed = Ui.ROW + Ui.GAP + Ui.SECTION_LABEL + Ui.GAP + Ui.ROW;
		this.rows = Ui.rowsThatFit(this.height, fixed, MIN_ROWS, MAX_ROWS);
		int contentHeight = fixed + this.rows * Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, CONTENT_WIDTH, contentHeight);
		int left = this.panel.contentX();
		int top = this.panel.contentTop();
		Optional<List<ChestLabel>> clipboard = this.minecraft != null && this.minecraft.player != null
				? Clipboard.of(this.minecraft.player).labels() : Optional.empty();
		boolean canPaste = clipboard.isPresent() && !clipboard.get().isEmpty();

		// ---- header actions
		long unlabeled = this.overview.entries().stream().filter(e -> e.labels().isEmpty()).count();
		Button pasteAll = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.overview.paste_all", unlabeled), button -> {
							for (Entry entry : this.overview.entries()) {
								if (entry.labels().isEmpty()) {
									ClientPlayNetworking.send(new EditorPayloads.SetChestLabels(entry.pos(), clipboard.orElseThrow()));
								}
							}
							refresh();
						})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.overview.paste_all.tooltip")))
				.bounds(left, top, Ui.COLUMN_WIDTH + 60, Ui.BUTTON_HEIGHT).build());
		pasteAll.active = canPaste && unlabeled > 0;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.refresh"),
						button -> refresh())
				.bounds(this.panel.contentRight() - 80, top, 80, Ui.BUTTON_HEIGHT).build());

		// ---- rows
		List<Entry> entries = this.overview.entries();
		int pages = Math.max(1, (entries.size() + this.rows - 1) / this.rows);
		this.page = Math.clamp(this.page, 0, pages - 1);
		this.listLabelY = top + Ui.ROW + Ui.GAP;
		int y = this.listLabelY + Ui.SECTION_LABEL;
		int rowWidth = CONTENT_WIDTH - 3 * (ACTION_WIDTH + Ui.GAP);
		for (int i = this.page * this.rows; i < Math.min(entries.size(), (this.page + 1) * this.rows); i++) {
			Entry entry = entries.get(i);
			BlockPos pos = entry.pos();
			this.addRenderableWidget(row(left, y, rowWidth, entry));
			int x = left + rowWidth + Ui.GAP;
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.find"),
							button -> highlight(pos))
					.bounds(x, y, ACTION_WIDTH, Ui.BUTTON_HEIGHT).build());
			x += ACTION_WIDTH + Ui.GAP;
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.edit"),
							button -> ClientPlayNetworking.send(new EditorPayloads.OpenEditor(pos)))
					.bounds(x, y, ACTION_WIDTH, Ui.BUTTON_HEIGHT).build());
			x += ACTION_WIDTH + Ui.GAP;
			Button paste = this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.paste"),
							button -> {
								ClientPlayNetworking.send(new EditorPayloads.SetChestLabels(pos, clipboard.orElseThrow()));
								refresh();
							})
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.overview.paste.tooltip")))
					.bounds(x, y, ACTION_WIDTH, Ui.BUTTON_HEIGHT).build());
			paste.active = canPaste;
			y += Ui.ROW;
		}

		// ---- pager + done
		int bottom = this.listLabelY + Ui.SECTION_LABEL + this.rows * Ui.ROW + Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			this.page--;
			this.rebuildWidgets();
		}).bounds(left, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build()).active = this.page > 0;
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			this.page++;
			this.rebuildWidgets();
		}).bounds(this.panel.contentRight() - Ui.BUTTON_HEIGHT, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build())
				.active = this.page < pages - 1;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panel.centerX() - 100, bottom, 200, Ui.BUTTON_HEIGHT).build());
	}

	/** "[x y z] labels" with the state in color, problems as the detail text. */
	private ListRow row(int x, int y, int width, Entry entry) {
		BlockPos pos = entry.pos();
		MutableComponent primary = Component.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "] ");
		int color;
		int accent = 0;
		if (entry.labels().isEmpty()) {
			primary.append(Component.translatable("waybettercoppergolem.overview.unlabeled"));
			color = Ui.ATTENTION;
			accent = Ui.ATTENTION;
		} else {
			primary.append(LabelResolver.listNames(entry.labels().labels()));
			color = entry.labels().explicit() ? Ui.EXPLICIT : Ui.AUTO;
		}
		Component detail = null;
		int detailColor = Ui.TEXT_MUTED;
		for (String problem : entry.problems()) {
			if (problem.equals("unlabeled")) {
				continue;
			}
			detail = Component.translatable("waybettercoppergolem.overview.problem." + problem);
			if (!problem.equals("empty")) {
				detailColor = Ui.ATTENTION;
				if (accent == 0) {
					accent = Ui.ATTENTION;
				}
			}
			break; // the most important problem is listed first by the server
		}
		return new ListRow(x, y, width, primary, () -> highlight(pos))
				.primaryColor(color)
				.secondary(detail, detailColor)
				.accent(accent)
				.tooltip(Component.translatable("waybettercoppergolem.overview.row.tooltip", entry.stacks()));
	}

	private void refresh() {
		ClientPlayNetworking.send(new ZonePayloads.OpenOverview(this.overview.anchor()));
	}

	private void highlight(BlockPos pos) {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand("wbcg highlight " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
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
		BlockPos anchor = this.overview.anchor();
		List<Entry> entries = this.overview.entries();
		long unlabeled = entries.stream().filter(e -> e.labels().isEmpty()).count();
		long misplaced = entries.stream().filter(e -> e.problems().contains("misplaced")).count();
		long duplicate = entries.stream().filter(e -> e.problems().contains("duplicate")).count();
		this.panel.header(graphics, this.font,
				Component.translatable("waybettercoppergolem.overview.heading",
						anchor.getX() + " " + anchor.getY() + " " + anchor.getZ(), Zones.describeArea(this.overview.area())),
				Component.translatable("waybettercoppergolem.overview.summary",
						entries.size(), unlabeled, misplaced, duplicate, this.overview.copperChests()));
		int pages = Math.max(1, (entries.size() + this.rows - 1) / this.rows);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.overview.section",
				this.page + 1, pages), this.panel.contentX(), this.listLabelY);
		if (entries.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.overview.none"),
					this.panel.centerX(), this.listLabelY + Ui.SECTION_LABEL + 8, Ui.TEXT_HINT);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
