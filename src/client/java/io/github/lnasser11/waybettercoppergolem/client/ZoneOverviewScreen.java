package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Entry;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
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
	private static final int ROWS = 8;
	private static final int ACTION_WIDTH = 44;
	private static final int ROW_WIDTH = Ui.PANEL_WIDTH + 2 * (ACTION_WIDTH + Ui.GAP);

	private static @Nullable ZoneOverviewScreen open;

	private ZonePayloads.Overview overview;
	private int page;

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

	private int top() {
		return Math.max(48, this.height / 2 - (ROWS + 3) * Ui.ROW / 2);
	}

	@Override
	protected void init() {
		super.init();
		open = this;
		int left = this.width / 2 - ROW_WIDTH / 2;
		int top = top();
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
				.bounds(left, top, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
		pasteAll.active = canPaste && unlabeled > 0;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.refresh"),
						button -> refresh())
				.bounds(left + Ui.COLUMN_WIDTH + Ui.GAP, top, 80, Ui.BUTTON_HEIGHT).build());

		// ---- rows
		List<Entry> entries = this.overview.entries();
		int pages = Math.max(1, (entries.size() + ROWS - 1) / ROWS);
		this.page = Math.clamp(this.page, 0, pages - 1);
		int y = top + Ui.ROW + 12;
		int labelWidth = ROW_WIDTH - 3 * (ACTION_WIDTH + Ui.GAP);
		for (int i = this.page * ROWS; i < Math.min(entries.size(), (this.page + 1) * ROWS); i++) {
			Entry entry = entries.get(i);
			BlockPos pos = entry.pos();
			this.addRenderableWidget(Button.builder(rowText(entry), button -> highlight(pos))
					.tooltip(Tooltip.create(rowTooltip(entry)))
					.bounds(left, y, labelWidth, Ui.BUTTON_HEIGHT).build());
			int x = left + labelWidth + Ui.GAP;
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
		int bottom = top + Ui.ROW + 12 + ROWS * Ui.ROW + Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			this.page--;
			this.rebuildWidgets();
		}).bounds(left, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build()).active = this.page > 0;
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			this.page++;
			this.rebuildWidgets();
		}).bounds(left + ROW_WIDTH - Ui.BUTTON_HEIGHT, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build())
				.active = this.page < pages - 1;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, bottom, 200, Ui.BUTTON_HEIGHT).build());
	}

	private void refresh() {
		ClientPlayNetworking.send(new ZonePayloads.OpenOverview(this.overview.anchor()));
	}

	private void highlight(BlockPos pos) {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand("wbcg highlight " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
		}
	}

	private static Component rowText(Entry entry) {
		BlockPos pos = entry.pos();
		MutableComponent text = Component.literal("[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "] ")
				.withStyle(ChatFormatting.GRAY);
		if (entry.labels().isEmpty()) {
			text.append(Component.translatable("waybettercoppergolem.overview.unlabeled").withStyle(ChatFormatting.YELLOW));
		} else {
			text.append(LabelResolver.listNames(entry.labels().labels()));
			if (!entry.labels().explicit()) {
				text.append(Component.literal(" (auto)").withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		for (String problem : entry.problems()) {
			if (!problem.equals("unlabeled")) {
				text.append(" ").append(Component.translatable("waybettercoppergolem.overview.problem." + problem)
						.withStyle(problem.equals("empty") ? ChatFormatting.DARK_GRAY : ChatFormatting.GOLD));
			}
		}
		return text;
	}

	private static Component rowTooltip(Entry entry) {
		return Component.translatable("waybettercoppergolem.overview.row.tooltip", entry.stacks());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = top();
		BlockPos anchor = this.overview.anchor();
		graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.overview.heading",
				anchor.getX() + " " + anchor.getY() + " " + anchor.getZ(), Zones.describeArea(this.overview.area())),
				this.width / 2, top - 34, Ui.TEXT);
		List<Entry> entries = this.overview.entries();
		long unlabeled = entries.stream().filter(e -> e.labels().isEmpty()).count();
		long misplaced = entries.stream().filter(e -> e.problems().contains("misplaced")).count();
		long duplicate = entries.stream().filter(e -> e.problems().contains("duplicate")).count();
		graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.overview.summary",
				entries.size(), unlabeled, misplaced, duplicate, this.overview.copperChests()),
				this.width / 2, top - 20, Ui.TEXT_MUTED);
		int pages = Math.max(1, (entries.size() + ROWS - 1) / ROWS);
		graphics.text(this.font, Component.translatable("waybettercoppergolem.overview.page", this.page + 1, pages),
				this.width / 2 - ROW_WIDTH / 2, top + Ui.ROW + 2, Ui.TEXT_HINT);
		if (entries.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.overview.none"),
					this.width / 2, top + Ui.ROW + 20, Ui.TEXT_HINT);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
