package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Move;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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

import java.util.ArrayList;
import java.util.List;

/**
 * What the golems would do with the zone's copper chests right now, one
 * row per (item, source, destination): "12× Iron Ingot → Ingots › Iron
 * [x y z]". Rows with no destination come first. Clicking a row sparkles
 * its destination (or its source when there is none).
 */
public class SimulationScreen extends Screen {
	private static final int ROWS = 10;
	private static final int ROW_WIDTH = Ui.PANEL_WIDTH + 60;

	private record IconAt(ItemStack stack, int x, int y) {
	}

	private static @Nullable SimulationScreen open;

	private ZonePayloads.Simulation simulation;
	private final List<IconAt> icons = new ArrayList<>();
	private int page;

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

	private int top() {
		return Math.max(44, this.height / 2 - (ROWS + 2) * Ui.ROW / 2);
	}

	@Override
	protected void init() {
		super.init();
		open = this;
		this.icons.clear();
		int left = this.width / 2 - ROW_WIDTH / 2;
		int top = top();
		List<Move> moves = this.simulation.moves();
		int pages = Math.max(1, (moves.size() + ROWS - 1) / ROWS);
		this.page = Math.clamp(this.page, 0, pages - 1);
		int y = top;
		for (int i = this.page * ROWS; i < Math.min(moves.size(), (this.page + 1) * ROWS); i++) {
			Move move = moves.get(i);
			BlockPos target = move.to().orElse(move.from());
			this.addRenderableWidget(Button.builder(rowText(move), button -> highlight(target))
					.tooltip(Tooltip.create(rowTooltip(move)))
					.bounds(left + Ui.ICON_SLOT, y, ROW_WIDTH - Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build());
			Item item = BuiltInRegistries.ITEM.getOptional(move.item()).orElse(Items.BARRIER);
			this.icons.add(new IconAt(new ItemStack(item), left + 2, y + 2));
			y += Ui.ROW;
		}
		int bottom = top + ROWS * Ui.ROW + Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			this.page--;
			this.rebuildWidgets();
		}).bounds(left, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build()).active = this.page > 0;
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			this.page++;
			this.rebuildWidgets();
		}).bounds(left + ROW_WIDTH - Ui.BUTTON_HEIGHT, bottom, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build())
				.active = this.page < pages - 1;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.overview.refresh"),
						button -> ClientPlayNetworking.send(new ZonePayloads.RunSimulation(this.simulation.anchor())))
				.bounds(this.width / 2 - 100 - 84, bottom, 80, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100 + 2, bottom, 180, Ui.BUTTON_HEIGHT).build());
	}

	private void highlight(BlockPos pos) {
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand("wbcg highlight " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
		}
	}

	private static Component rowText(Move move) {
		Item item = BuiltInRegistries.ITEM.getOptional(move.item()).orElse(Items.BARRIER);
		MutableComponent text = Component.literal(move.count() + "× ").append(item.getName(item.getDefaultInstance()))
				.append(" → ");
		if (move.to().isEmpty()) {
			return text.append(Component.translatable("waybettercoppergolem.simulation.nowhere").withStyle(ChatFormatting.RED));
		}
		BlockPos to = move.to().get();
		if (move.toLabels().isEmpty()) {
			text.append(Component.translatable("waybettercoppergolem.simulation.unlabeled_target").withStyle(ChatFormatting.GRAY));
		} else {
			text.append(LabelResolver.listNames(move.toLabels()).copy().withStyle(ChatFormatting.AQUA));
		}
		return text.append(Component.literal(" [" + to.getX() + " " + to.getY() + " " + to.getZ() + "]")
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component rowTooltip(Move move) {
		BlockPos from = move.from();
		return Component.translatable("waybettercoppergolem.simulation.row.tooltip",
				from.getX() + " " + from.getY() + " " + from.getZ());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 32, Ui.TEXT);
		long stuck = this.simulation.moves().stream().filter(move -> move.to().isEmpty()).count();
		graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.simulation.summary",
				this.simulation.sourceChests(), this.simulation.moves().size(), stuck), this.width / 2, top - 20, Ui.TEXT_MUTED);
		graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.simulation.caveat"),
				this.width / 2, top - 9, Ui.TEXT_HINT);
		if (this.simulation.moves().isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.simulation.none"),
					this.width / 2, top + 20, Ui.TEXT_HINT);
		}
		for (IconAt icon : this.icons) {
			graphics.item(icon.stack(), icon.x(), icon.y());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
