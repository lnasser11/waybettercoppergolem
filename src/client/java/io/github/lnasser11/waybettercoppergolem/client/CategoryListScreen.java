package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** The preset categories, each opening its tuning screen. Reached from the picker's "Tune categories…". */
public class CategoryListScreen extends Screen {
	private Panel panel = new Panel(0, 0, 0, 0);

	public CategoryListScreen() {
		super(Component.translatable("waybettercoppergolem.categories.title"));
	}

	@Override
	protected void init() {
		super.init();
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int rows = (presets.size() + 1) / 2;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, rows * Ui.ROW + Ui.ROW);
		int x = this.panel.contentX();
		int y = this.panel.contentTop();
		for (int i = 0; i < presets.size(); i++) {
			TagKey<Item> preset = presets.get(i);
			int rowX = x + (i % 2) * (Ui.COLUMN_WIDTH + Ui.GAP);
			this.addRenderableWidget(new ListRow(rowX, y, Ui.COLUMN_WIDTH, LabelResolver.tagName(preset.location()),
					() -> ClientPlayNetworking.send(new TuningPayloads.OpenTuning(preset.location())))
					.icon(sampleStack(preset))
					.secondary(Component.translatable("waybettercoppergolem.categories.count", LabelResolver.tagSize(preset)), Ui.TEXT_MUTED)
					.tooltip(Component.literal(preset.location().toString())));
			if (i % 2 == 1) {
				y += Ui.ROW;
			}
		}
		if (presets.size() % 2 == 1) {
			y += Ui.ROW;
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panel.centerX() - 100, y + Ui.GAP, 200, Ui.BUTTON_HEIGHT).build());
	}

	private static ItemStack sampleStack(TagKey<Item> tag) {
		return BuiltInRegistries.ITEM.get(tag)
				.flatMap(named -> named.stream().findFirst())
				.map(holder -> new ItemStack(holder.value()))
				.orElse(new ItemStack(Items.CHEST));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
		this.panel.header(graphics, this.font, this.title, Component.translatable("waybettercoppergolem.categories.hint"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
