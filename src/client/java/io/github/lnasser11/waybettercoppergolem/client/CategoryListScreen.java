package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.List;

/** The preset categories, each opening its tuning screen. Reached from the picker's "Tune categories…". */
public class CategoryListScreen extends Screen {
	public CategoryListScreen() {
		super(Component.translatable("waybettercoppergolem.categories.title"));
	}

	@Override
	protected void init() {
		super.init();
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int rows = (presets.size() + 1) / 2;
		int top = Math.max(30, this.height / 2 - (rows * Ui.ROW + 2 * Ui.ROW) / 2);
		int leftX = this.width / 2 - Ui.PANEL_WIDTH / 2;
		int y = top;
		for (int i = 0; i < presets.size(); i++) {
			TagKey<Item> preset = presets.get(i);
			int x = leftX + (i % 2) * (Ui.COLUMN_WIDTH + Ui.GAP);
			this.addRenderableWidget(Button.builder(
							Component.translatable("waybettercoppergolem.categories.row",
									LabelResolver.tagName(preset.location()), LabelResolver.tagSize(preset)),
							button -> ClientPlayNetworking.send(new TuningPayloads.OpenTuning(preset.location())))
					.tooltip(Tooltip.create(Component.literal(preset.location().toString())))
					.bounds(x, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			if (i % 2 == 1) {
				y += Ui.ROW;
			}
		}
		if (presets.size() % 2 == 1) {
			y += Ui.ROW;
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, y + Ui.GAP, 200, Ui.BUTTON_HEIGHT).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int rows = (presets.size() + 1) / 2;
		int top = Math.max(30, this.height / 2 - (rows * Ui.ROW + 2 * Ui.ROW) / 2);
		graphics.centeredText(this.font, this.title, this.width / 2, top - 22, Ui.TEXT);
		graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.categories.hint"),
				this.width / 2, top - 10, Ui.TEXT_MUTED);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
