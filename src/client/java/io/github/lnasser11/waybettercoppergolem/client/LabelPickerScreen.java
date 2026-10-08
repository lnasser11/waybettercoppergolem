package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.SetClipboardPayload;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The label picker: opened by sneak-right-clicking the air with the label
 * tool. Whatever you choose goes onto the clipboard, ready to paste.
 *
 * <p>Left column: the preset categories, catch-all, off-limits, the
 * "remove labels" marker and "empty clipboard". Right column: an item
 * search (pre-filled with what you carry); picking an item lists its
 * stops, exact first and then its tags narrow to broad, with member
 * counts. Everything is computed from the client's own registries and
 * tags; the server only receives the final choice and validates it.
 */
public class LabelPickerScreen extends Screen {
	private static final int COLUMN_WIDTH = 150;
	private static final int GAP = 4;
	private static final int BUTTON_HEIGHT = 20;
	private static final int ROW = BUTTON_HEIGHT + GAP;
	private static final int MAX_RESULTS = 8;

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private @Nullable EditBox search;
	private String query = "";
	private @Nullable Item selected;
	private int rightX;
	private int resultsTop;

	public LabelPickerScreen() {
		super(Component.translatable("waybettercoppergolem.picker.title"));
	}

	private int top() {
		return Math.max(36, this.height / 2 - 118);
	}

	@Override
	protected void init() {
		super.init();
		int totalWidth = 2 * COLUMN_WIDTH + GAP;
		int leftX = this.width / 2 - totalWidth / 2;
		this.rightX = leftX + COLUMN_WIDTH + GAP;
		int top = top();

		// ---- left: categories, then the special labels
		int half = (COLUMN_WIDTH - GAP) / 2;
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int y = top;
		for (int i = 0; i < presets.size(); i++) {
			TagKey<Item> preset = presets.get(i);
			int x = leftX + (i % 2) * (half + GAP);
			Identifier tagId = preset.location();
			this.addRenderableWidget(Button.builder(LabelResolver.tagName(tagId),
							button -> choose(ChestLabel.tag(sampleFor(tagId), tagId)))
					.tooltip(Tooltip.create(stopTooltip(preset)))
					.bounds(x, y, half, BUTTON_HEIGHT).build());
			if (i % 2 == 1) {
				y += ROW;
			}
		}
		if (presets.size() % 2 == 1) {
			y += ROW;
		}
		y += GAP;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.catch_all"),
						button -> choose(ChestLabel.catchAll()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.catch_all.tooltip")))
				.bounds(leftX, y, half, BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.off_limits"),
						button -> choose(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(Items.COBWEB))))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.off_limits.tooltip")))
				.bounds(leftX + half + GAP, y, half, BUTTON_HEIGHT).build());
		y += ROW;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.remove_labels"),
						button -> send(SetClipboardPayload.removeLabelsMarker()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.remove_labels.tooltip")))
				.bounds(leftX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
		y += ROW;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.empty_clipboard"),
						button -> send(SetClipboardPayload.empty()))
				.bounds(leftX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
		int leftBottom = y + ROW;

		// ---- right: search + results
		this.search = new EditBox(this.font, this.rightX, top, COLUMN_WIDTH, BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.picker.search"));
		this.search.setHint(Component.translatable("waybettercoppergolem.picker.search_hint"));
		this.search.setMaxLength(40);
		this.search.setValue(this.query);
		this.search.setResponder(text -> {
			this.query = text;
			this.selected = null;
			rebuildResults();
		});
		this.addRenderableWidget(this.search);
		this.resultsTop = top + ROW + 10;
		rebuildResults();

		int rightBottom = this.resultsTop + (MAX_RESULTS + 1) * ROW;
		int doneY = Math.max(leftBottom, rightBottom) + GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, Math.min(doneY, this.height - BUTTON_HEIGHT - 6), 200, BUTTON_HEIGHT).build());
	}

	/** Replaces the right-column list: item matches, or the selected item's stops. */
	private void rebuildResults() {
		for (AbstractWidget widget : this.dynamic) {
			this.removeWidget(widget);
		}
		this.dynamic.clear();
		int y = this.resultsTop;
		if (this.selected != null) {
			Item item = this.selected;
			Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
			add(Button.builder(Component.translatable("waybettercoppergolem.picker.back"), button -> {
				this.selected = null;
				rebuildResults();
			}).bounds(this.rightX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
			y += ROW;
			add(Button.builder(Component.translatable("waybettercoppergolem.picker.exact", item.getName(item.getDefaultInstance())),
							button -> choose(ChestLabel.exact(itemId)))
					.bounds(this.rightX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
			y += ROW;
			int shown = 0;
			for (TagKey<Item> tag : LabelResolver.orderedTags(item)) {
				if (shown++ >= MAX_RESULTS - 1) {
					break;
				}
				Identifier tagId = tag.location();
				add(Button.builder(Component.translatable("waybettercoppergolem.picker.stop",
										LabelResolver.tagName(tagId), LabelResolver.tagSize(tag)),
								button -> choose(ChestLabel.tag(itemId, tagId)))
						.tooltip(Tooltip.create(stopTooltip(tag)))
						.bounds(this.rightX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
				y += ROW;
			}
			return;
		}
		List<Item> matches = matches();
		for (Item item : matches) {
			add(Button.builder(item.getName(item.getDefaultInstance()), button -> {
				this.selected = item;
				rebuildResults();
			}).bounds(this.rightX, y, COLUMN_WIDTH, BUTTON_HEIGHT).build());
			y += ROW;
		}
	}

	private void add(AbstractWidget widget) {
		this.dynamic.add(widget);
		this.addRenderableWidget(widget);
	}

	/** With an empty query: what the player carries. Otherwise: registry search by name or id. */
	private List<Item> matches() {
		String needle = this.query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			Set<Item> carried = new LinkedHashSet<>();
			if (this.minecraft != null && this.minecraft.player != null) {
				Inventory inventory = this.minecraft.player.getInventory();
				for (int slot = 0; slot < inventory.getContainerSize() && carried.size() < MAX_RESULTS; slot++) {
					ItemStack stack = inventory.getItem(slot);
					if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
						carried.add(stack.getItem());
					}
				}
			}
			return new ArrayList<>(carried);
		}
		List<Item> results = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR) {
				continue;
			}
			String name = item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT);
			String path = BuiltInRegistries.ITEM.getKey(item).getPath();
			if (name.contains(needle) || path.contains(needle)) {
				results.add(item);
			}
		}
		results.sort(Comparator
				.comparing((Item item) -> !item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT).startsWith(needle))
				.thenComparing(item -> item.getName(item.getDefaultInstance()).getString()));
		return results.size() > MAX_RESULTS ? results.subList(0, MAX_RESULTS) : results;
	}

	/** A representative member item for a category chosen without a sample. */
	private static Identifier sampleFor(Identifier tagId) {
		return BuiltInRegistries.ITEM.get(LabelResolver.itemTag(tagId))
				.flatMap(named -> named.stream().findFirst())
				.map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()))
				.orElse(BuiltInRegistries.ITEM.getKey(Items.CHEST));
	}

	private static Component stopTooltip(TagKey<Item> tag) {
		return Component.translatable("waybettercoppergolem.picker.tag_tooltip",
				tag.location().toString(), LabelResolver.tagSize(tag)).withStyle(ChatFormatting.GRAY);
	}

	private void choose(ChestLabel label) {
		send(SetClipboardPayload.of(label));
	}

	private void send(SetClipboardPayload payload) {
		ClientPlayNetworking.send(payload);
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 28, 0xFFFFFFFF);
		if (this.minecraft != null && this.minecraft.player != null) {
			graphics.centeredText(this.font, LabelTool.describeClipboard(Clipboard.of(this.minecraft.player)),
					this.width / 2, top - 14, 0xFFAAAAAA);
		}
		if (this.selected == null && this.query.isBlank()) {
			graphics.text(this.font, Component.translatable("waybettercoppergolem.picker.from_inventory"),
					this.rightX, this.resultsTop - 10, 0xFF888888);
		} else if (this.selected == null && this.dynamic.isEmpty()) {
			graphics.text(this.font, Component.translatable("waybettercoppergolem.picker.no_results"),
					this.rightX, this.resultsTop - 10, 0xFF888888);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
