package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Move;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What the golems would do with the zone's copper chests right now, one
 * row per (item, source, destination): "12× Iron Ingot → Ingots › Iron
 * [x y z]". Rows with no destination come first. Clicking a row sparkles
 * its destination (or its source when there is none).
 */
public class SimulationScreen extends Screen {
	private static final int MIN_ROWS = 3;
	private static final int MAX_ROWS = 16;
	private int rows = MIN_ROWS;
	private static final int CONTENT_WIDTH = Ui.PANEL_WIDTH + 60;

	private static @Nullable SimulationScreen open;

	private ZonePayloads.Simulation simulation;
	private int page;
	private Panel panel = new Panel(0, 0, 0, 0);
	private int listLabelY;

	private SimulationScreen(ZonePayloads.Simulation simulation) {
		super(Component.translatable("waybettercoppergolem.simulation.title"));
		this.simulation = simulation;
	}

	public static void openOrUpdate(Minecraft minecraft, ZonePayloads.Simulation simulation) {
		SimulationScreen current = open;
		if (current != null && current.simulation.anchor().equals(simulation.anchor())) {
			current.simulation = simulation;
			current.rebuildWidgets();
			return;
		}
		minecraft.setScreenAndShow(new SimulationScreen(simulation));
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
		int fixed = Ui.SECTION_LABEL + Ui.GAP + Ui.ROW;
		this.rows = Ui.rowsThatFit(this.height, fixed, MIN_ROWS, MAX_ROWS);
		int contentHeight = fixed + this.rows * Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, CONTENT_WIDTH, contentHeight);
		int left = this.panel.contentX();
		this.listLabelY = this.panel.contentTop();
		List<Move> moves = this.simulation.moves();
		int pages = Math.max(1, (moves.size() + this.rows - 1) / this.rows);
		this.page = Math.clamp(this.page, 0, pages - 1);
		int y = this.listLabelY + Ui.SECTION_LABEL;
		for (int i = this.page * this.rows; i < Math.min(moves.size(), (this.page + 1) * this.rows); i++) {
			this.addRenderableWidget(row(left, y, moves.get(i)));
			y += Ui.ROW;
		}
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
		int refreshX = left + Ui.BUTTON_HEIGHT + Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.refresh"),
						button -> ClientPlayNetworking.send(new ZonePayloads.RunSimulation(this.simulation.anchor())))
				.bounds(refreshX, bottom, 80, Ui.BUTTON_HEIGHT).build());
		int doneX = refreshX + 80 + Ui.GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(doneX, bottom, this.panel.contentRight() - Ui.BUTTON_HEIGHT - Ui.GAP - doneX, Ui.BUTTON_HEIGHT).build());
	}

	private ListRow row(int x, int y, Move move) {
		Item item = BuiltInRegistries.ITEM.getOptional(move.item()).orElse(Items.BARRIER);
		MutableComponent primary = move.sort()
				? Component.translatable("waybettercoppergolem.simulation.sort_row", move.count())
				: move.tidy()
				? Component.translatable("waybettercoppergolem.simulation.tidy_row", move.count(), item.getName(item.getDefaultInstance()))
				: move.reorganize()
				? Component.translatable("waybettercoppergolem.simulation.reorganize_row", move.count(), item.getName(item.getDefaultInstance()))
				: Component.literal(move.count() + "× ").append(item.getName(item.getDefaultInstance()));
		BlockPos target = move.to().orElse(move.from());
		ListRow row = new ListRow(x, y, CONTENT_WIDTH, primary, () -> highlight(target))
				.icon(new ItemStack(item));
		if (move.to().isEmpty()) {
			row.secondary(Component.translatable("waybettercoppergolem.simulation.nowhere"), Ui.PROBLEM).accent(Ui.PROBLEM);
		} else {
			BlockPos to = move.to().get();
			MutableComponent detail = Component.literal("→ ");
			detail.append(move.toLabels().isEmpty()
					? Component.translatable("waybettercoppergolem.simulation.unlabeled_target")
					: LabelResolver.listNames(move.toLabels()));
			detail.append(" [" + to.getX() + " " + to.getY() + " " + to.getZ() + "]");
			row.secondary(detail, move.toLabels().isEmpty() ? Ui.TEXT_MUTED : Ui.AUTO);
		}
		BlockPos from = move.from();
		return row.tooltip(Component.translatable(move.sort()
						? "waybettercoppergolem.simulation.sort_row.tooltip"
						: move.tidy()
						? "waybettercoppergolem.simulation.tidy_row.tooltip"
						: move.reorganize() ? "waybettercoppergolem.simulation.reorganize_row.tooltip"
						: "waybettercoppergolem.simulation.row.tooltip",
				from.getX() + " " + from.getY() + " " + from.getZ()));
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
		long stuck = this.simulation.moves().stream().filter(move -> move.to().isEmpty()).count();
		long tidy = this.simulation.moves().stream().filter(move -> move.tidy() || move.sort()).count();
		long reorganize = this.simulation.moves().stream().filter(Move::reorganize).count();
		this.panel.header(graphics, this.font, this.title, Component.translatable("waybettercoppergolem.simulation.summary",
				Ui.count(this.simulation.sourceChests(), "copper"), Ui.count((int) (this.simulation.moves().size() - tidy - reorganize), "moves"),
				Ui.count((int) stuck, "stuck"), Ui.count((int) reorganize, "reorganize"), Ui.count((int) tidy, "tidy")));
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.simulation.caveat"),
				this.panel.contentX(), this.listLabelY, this.panel.contentWidth());
		if (this.simulation.moves().isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.simulation.none"),
					this.panel.centerX(), this.listLabelY + Ui.SECTION_LABEL + 8, Ui.TEXT_HINT);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
