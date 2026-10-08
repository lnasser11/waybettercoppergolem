package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads.EditorContext;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads.Suggestion;
import io.github.lnasser11.waybettercoppergolem.net.SetClipboardPayload;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.Optional;
import java.util.Set;

/**
 * The label picker, in two modes.
 *
 * <p><b>Clipboard mode</b> (sneak-right-click the air with the tool):
 * whatever you choose goes onto the clipboard, ready to paste.
 *
 * <p><b>Chest mode</b> (the golem button in a chest's inventory): choices
 * apply to that chest right away (and land on the clipboard too). The
 * header shows the chest's current labels; the third column has a
 * Replace/Add toggle and one removable row per current label; the search
 * column starts out with labels suggested from the chest's contents, each
 * with how many stacks it covers. The screen stays open and refreshes
 * after every change.
 *
 * <p>Three columns on a 480-wide GUI (categories · any item · special).
 * On narrower GUIs (the default 854×480 window is 427×240) the categories
 * become a strip of icon chips under the search box and the special
 * column moves to the left, so everything still fits in 240 px.
 * Sized to fit a 480 × 270 GUI; the result list grows on taller screens.
 * Closes with Escape (or the inventory key) like an inventory.
 */
public class LabelPickerScreen extends Screen {
	private static final int MIN_RESULT_ROWS = 4;
	private static final int MIN_RESULT_ROWS_NARROW = 3;
	private static final int CHIPS_PER_ROW = 6;
	private static final int MAX_RESULT_ROWS = 12;
	private static final int MAX_CHIPS = 2;

	/** The editor currently on screen in chest mode, if any (tracked here; see {@link #removed()}). */
	private static @Nullable LabelPickerScreen openEditor;

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private @Nullable EditorContext context;
	private boolean addMode;
	private String query = "";
	private @Nullable Item selected;
	private Panel panel = new Panel(0, 0, 0, 0);
	private int resultRows = MIN_RESULT_ROWS;
	private int categoriesX;
	private int searchX;
	private int specialX;
	private int categoriesLabelY;
	private int specialLabelY;
	private int chestLabelY = -1;
	private int searchLabelY;
	private int resultsLabelY;
	private int resultsTop;

	private LabelPickerScreen(@Nullable EditorContext context) {
		super(Component.translatable(context == null
				? "waybettercoppergolem.picker.title" : "waybettercoppergolem.editor.title"));
		this.context = context;
	}

	public static LabelPickerScreen forClipboard() {
		return new LabelPickerScreen(null);
	}

	/** Opens the editor for the chest, or refreshes it if it is already open for that chest. */
	public static void openOrUpdate(Minecraft minecraft, EditorContext context) {
		LabelPickerScreen open = openEditor;
		if (open != null && open.context != null && open.context.pos().equals(context.pos())) {
			open.context = context;
			open.rebuildWidgets();
			return;
		}
		minecraft.setScreenAndShow(new LabelPickerScreen(context));
	}

	@Override
	public void removed() {
		super.removed();
		if (openEditor == this) {
			openEditor = null;
		}
	}

	private boolean chestMode() {
		return this.context != null;
	}

	@Override
	protected void init() {
		super.init();
		if (chestMode()) {
			openEditor = this;
		}
		this.dynamic.clear();
		boolean wide = Ui.wide(this.width);
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int chipRows = (presets.size() + CHIPS_PER_ROW - 1) / CHIPS_PER_ROW;

		int categoriesHeight = wide ? Ui.SECTION_LABEL + ((presets.size() + 1) / 2) * Ui.ROW : 0;
		int specialHeight = chestMode()
				? Ui.SECTION_LABEL + (2 + MAX_CHIPS) * Ui.ROW + Ui.GAP + Ui.SECTION_LABEL + 2 * Ui.ROW
				: Ui.SECTION_LABEL + 4 * Ui.ROW;
		int chipsHeight = wide ? 0 : Ui.SECTION_LABEL + chipRows * Ui.ROW;
		int searchFixed = Ui.SECTION_LABEL + Ui.ROW + chipsHeight + Ui.SECTION_LABEL;
		this.resultRows = Ui.rowsThatFit(this.height, searchFixed,
				wide ? MIN_RESULT_ROWS : MIN_RESULT_ROWS_NARROW, MAX_RESULT_ROWS);
		int searchHeight = searchFixed + this.resultRows * Ui.ROW;
		int contentHeight = Math.max(categoriesHeight, Math.max(searchHeight, specialHeight));
		this.panel = Panel.centered(this.width, this.height, wide ? Ui.WIDE_PANEL_WIDTH : Ui.PANEL_WIDTH, contentHeight);
		int top = this.panel.contentTop();
		if (wide) {
			this.categoriesX = this.panel.contentX();
			this.searchX = this.categoriesX + Ui.COLUMN_WIDTH + Ui.GAP;
			this.specialX = this.searchX + Ui.COLUMN_WIDTH + Ui.GAP;
		} else {
			this.specialX = this.panel.contentX();
			this.searchX = this.specialX + Ui.COLUMN_WIDTH + Ui.GAP;
			this.categoriesX = this.searchX;
		}
		int specialTop = top;

		// ---- categories: a grid of named buttons in their own column, or icon chips under the search box
		int half = (Ui.COLUMN_WIDTH - Ui.GAP) / 2;
		int y;
		if (wide) {
			this.categoriesLabelY = top;
			y = top + Ui.SECTION_LABEL;
			for (int i = 0; i < presets.size(); i++) {
				TagKey<Item> preset = presets.get(i);
				int x = this.categoriesX + (i % 2) * (half + Ui.GAP);
				Identifier tagId = preset.location();
				this.addRenderableWidget(Button.builder(LabelResolver.tagName(tagId),
								button -> choose(ChestLabel.tag(sampleFor(tagId), tagId)))
						.tooltip(Tooltip.create(stopTooltip(preset)))
						.bounds(x, y, half, Ui.BUTTON_HEIGHT).build());
				if (i % 2 == 1) {
					y += Ui.ROW;
				}
			}
		} else {
			this.categoriesLabelY = top + Ui.SECTION_LABEL + Ui.ROW;
			y = this.categoriesLabelY + Ui.SECTION_LABEL;
			int chipWidth = (Ui.COLUMN_WIDTH - (CHIPS_PER_ROW - 1) * Ui.GAP) / CHIPS_PER_ROW;
			for (int i = 0; i < presets.size(); i++) {
				TagKey<Item> preset = presets.get(i);
				Identifier tagId = preset.location();
				int x = this.searchX + (i % CHIPS_PER_ROW) * (chipWidth + Ui.GAP);
				MutableComponent tooltip = LabelResolver.tagName(tagId).copy().append("\n").append(stopTooltip(preset));
				this.addRenderableWidget(new ListRow(x, y, chipWidth, LabelResolver.tagName(tagId),
						() -> choose(ChestLabel.tag(sampleFor(tagId), tagId)))
						.icon(new ItemStack(BuiltInRegistries.ITEM.getOptional(sampleFor(tagId)).orElse(Items.CHEST)))
						.iconOnly()
						.tooltip(tooltip));
				if (i % CHIPS_PER_ROW == CHIPS_PER_ROW - 1) {
					y += Ui.ROW;
				}
			}
		}

		// ---- special (and, in chest mode, this chest's labels)
		y = specialTop;
		this.chestLabelY = -1;
		if (chestMode()) {
			this.chestLabelY = y;
			y += Ui.SECTION_LABEL;
			this.addRenderableWidget(Button.builder(Component.translatable(this.addMode
							? "waybettercoppergolem.editor.mode_add" : "waybettercoppergolem.editor.mode_replace"), button -> {
						this.addMode = !this.addMode;
						this.rebuildWidgets();
					})
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.editor.mode.tooltip")))
					.bounds(this.specialX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
			List<ChestLabel> current = this.context.current().labels();
			for (int i = 0; i < MAX_CHIPS; i++) {
				if (i < current.size()) {
					ChestLabel label = current.get(i);
					List<ChestLabel> remaining = new ArrayList<>(current);
					remaining.remove(label);
					this.addRenderableWidget(new ListRow(this.specialX, y, Ui.COLUMN_WIDTH,
							Component.literal("✕ ").append(LabelResolver.shortName(label)), () -> sendLabels(remaining))
							.primaryColor(this.context.current().explicit() ? Ui.EXPLICIT : Ui.AUTO)
							.tooltip(Component.translatable("waybettercoppergolem.editor.remove_one.tooltip")));
				}
				y += Ui.ROW;
			}
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.remove_labels"),
							button -> sendLabels(List.of()))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.editor.remove_labels.tooltip")))
					.bounds(this.specialX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW + Ui.GAP;
		}
		this.specialLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.catch_all"),
						button -> choose(ChestLabel.catchAll()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.catch_all.tooltip")))
				.bounds(this.specialX, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.off_limits"),
						button -> choose(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(Items.COBWEB))))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.off_limits.tooltip")))
				.bounds(this.specialX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		if (!chestMode()) {
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.remove_labels"),
							button -> sendLabels(List.of()))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.remove_labels.tooltip")))
					.bounds(this.specialX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.empty_clipboard"),
							button -> send(SetClipboardPayload.empty()))
					.bounds(this.specialX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
		}
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.tune_categories"),
						button -> this.minecraft.setScreenAndShow(new CategoryListScreen()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_categories.tooltip")))
				.bounds(this.specialX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());

		// ---- any item: search + results
		this.searchLabelY = top;
		EditBox search = new EditBox(this.font, this.searchX, top + Ui.SECTION_LABEL, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.picker.search"));
		search.setHint(Component.translatable("waybettercoppergolem.picker.search_hint"));
		search.setMaxLength(40);
		search.setValue(this.query);
		search.setResponder(text -> {
			this.query = text;
			this.selected = null;
			rebuildResults();
		});
		this.addRenderableWidget(search);
		this.resultsLabelY = top + Ui.SECTION_LABEL + Ui.ROW + chipsHeight;
		this.resultsTop = this.resultsLabelY + Ui.SECTION_LABEL;
		rebuildResults();
	}

	/** Replaces the results list: suggestions / carried items, item matches, or the selected item's stops. */
	private void rebuildResults() {
		for (AbstractWidget widget : this.dynamic) {
			this.removeWidget(widget);
		}
		this.dynamic.clear();
		int y = this.resultsTop;
		int limit = this.resultsTop + this.resultRows * Ui.ROW;
		if (this.selected != null) {
			Item item = this.selected;
			Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
			add(new ListRow(this.searchX, y, Ui.COLUMN_WIDTH, Component.translatable("waybettercoppergolem.picker.back"), () -> {
				this.selected = null;
				rebuildResults();
			}).primaryColor(Ui.TEXT_MUTED));
			y += Ui.ROW;
			add(row(y, Component.translatable("waybettercoppergolem.picker.exact_row"), () -> choose(ChestLabel.exact(itemId)), null)
					.icon(new ItemStack(item))
					.secondary(Component.translatable("waybettercoppergolem.picker.exact_detail"), Ui.TEXT_MUTED)
					.primaryColor(Ui.EXPLICIT));
			y += Ui.ROW;
			for (TagKey<Item> tag : LabelResolver.orderedTags(item)) {
				if (y >= limit) {
					break;
				}
				Identifier tagId = tag.location();
				add(row(y, LabelResolver.tagName(tagId), () -> choose(ChestLabel.tag(itemId, tagId)), tagId)
						.icon(new ItemStack(item))
						.secondary(Component.translatable("waybettercoppergolem.picker.count", LabelResolver.tagSize(tag)), Ui.TEXT_MUTED)
						.tooltip(stopTooltip(tag)));
				y += Ui.ROW;
			}
			return;
		}
		if (chestMode() && this.query.isBlank()) {
			for (Suggestion suggestion : this.context.suggestions()) {
				if (y >= limit) {
					break;
				}
				ChestLabel label = suggestion.label();
				boolean full = suggestion.coveredStacks() == suggestion.totalStacks();
				MutableComponent tooltip = Component.translatable("waybettercoppergolem.editor.coverage.tooltip",
						suggestion.coveredStacks(), suggestion.totalStacks());
				label.tagId().ifPresent(id -> tooltip.append("\n").append(stopTooltip(LabelResolver.itemTag(id))));
				add(row(y, LabelResolver.shortName(label), () -> choose(label), label.tagId().orElse(null))
						.icon(iconFor(label))
						.secondary(Component.translatable("waybettercoppergolem.editor.coverage",
								suggestion.coveredStacks(), suggestion.totalStacks()), full ? Ui.OK : Ui.TEXT_MUTED)
						.accent(full ? Ui.OK : 0)
						.tooltip(tooltip));
				y += Ui.ROW;
			}
			return;
		}
		for (Item item : matches()) {
			if (y >= limit) {
				break;
			}
			add(row(y, item.getName(item.getDefaultInstance()), () -> {
				this.selected = item;
				rebuildResults();
			}, null).icon(new ItemStack(item)));
			y += Ui.ROW;
		}
	}

	/** A result row, with a "…" tuning button on the right when it stands for a tag. */
	private ListRow row(int y, Component text, Runnable action, @Nullable Identifier tuneTag) {
		int width = Ui.COLUMN_WIDTH - (tuneTag != null ? Ui.ICON_SLOT + Ui.GAP : 0);
		ListRow row = new ListRow(this.searchX, y, width, text, action);
		if (tuneTag != null) {
			add(Button.builder(Component.literal("…"),
							button -> ClientPlayNetworking.send(new TuningPayloads.OpenTuning(tuneTag)))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_row.tooltip")))
					.bounds(this.searchX + Ui.COLUMN_WIDTH - Ui.ICON_SLOT, y, Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build());
		}
		return row;
	}

	private <T extends AbstractWidget> T add(T widget) {
		this.dynamic.add(widget);
		this.addRenderableWidget(widget);
		return widget;
	}

	/** With an empty query: what the player carries. Otherwise: registry search by name or id. */
	private List<Item> matches() {
		String needle = this.query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			Set<Item> carried = new LinkedHashSet<>();
			if (this.minecraft != null && this.minecraft.player != null) {
				Inventory inventory = this.minecraft.player.getInventory();
				for (int slot = 0; slot < inventory.getContainerSize() && carried.size() < this.resultRows; slot++) {
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
		return results.size() > this.resultRows ? results.subList(0, this.resultRows) : results;
	}

	/** A representative member item for a category chosen without a sample. */
	private static Identifier sampleFor(Identifier tagId) {
		return BuiltInRegistries.ITEM.get(LabelResolver.itemTag(tagId))
				.flatMap(named -> named.stream().findFirst())
				.map(holder -> BuiltInRegistries.ITEM.getKey(holder.value()))
				.orElse(BuiltInRegistries.ITEM.getKey(Items.CHEST));
	}

	private static ItemStack iconFor(ChestLabel label) {
		return label.itemId()
				.flatMap(BuiltInRegistries.ITEM::getOptional)
				.map(ItemStack::new)
				.orElse(new ItemStack(Items.CHEST));
	}

	private static Component stopTooltip(TagKey<Item> tag) {
		return Component.translatable("waybettercoppergolem.picker.tag_tooltip",
				tag.location().toString(), LabelResolver.tagSize(tag));
	}

	// ---------------------------------------------------------------- sending

	private void choose(ChestLabel label) {
		if (!chestMode()) {
			send(SetClipboardPayload.of(label));
			return;
		}
		List<ChestLabel> labels = new ArrayList<>();
		if (this.addMode) {
			labels.addAll(this.context.current().labels());
		}
		if (!labels.contains(label)) {
			labels.add(label);
		}
		sendLabels(labels);
	}

	/** Chest mode: apply to the chest (the screen refreshes when the server answers). Clipboard mode: set the clipboard. */
	private void sendLabels(List<ChestLabel> labels) {
		if (chestMode()) {
			ClientPlayNetworking.send(new EditorPayloads.SetChestLabels(this.context.pos(), labels));
			return;
		}
		send(labels.isEmpty() ? SetClipboardPayload.removeLabelsMarker()
				: new SetClipboardPayload(Optional.of(labels)));
	}

	private void send(SetClipboardPayload payload) {
		ClientPlayNetworking.send(payload);
		this.onClose();
	}

	// ---------------------------------------------------------------- rendering

	/** The panel goes under the widgets, so it is drawn with the background. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		Component title;
		Component subtitle;
		if (chestMode()) {
			BlockPos pos = this.context.pos();
			title = Component.translatable("waybettercoppergolem.editor.title", pos.getX() + " " + pos.getY() + " " + pos.getZ());
			subtitle = currentLine();
		} else {
			title = this.title;
			subtitle = this.minecraft != null && this.minecraft.player != null
					? LabelTool.describeClipboard(Clipboard.of(this.minecraft.player)) : null;
		}
		this.panel.header(graphics, this.font, title, subtitle);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.picker.section.categories"),
				this.categoriesX, this.categoriesLabelY);
		if (this.chestLabelY >= 0) {
			Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.editor.section.this_chest"),
					this.specialX, this.chestLabelY);
		}
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.picker.section.special"),
				this.specialX, this.specialLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.picker.section.search"),
				this.searchX, this.searchLabelY);
		Panel.sectionLabel(graphics, this.font, resultsLabel(), this.searchX, this.resultsLabelY);
	}

	private Component resultsLabel() {
		if (this.selected != null) {
			return Component.translatable("waybettercoppergolem.picker.section.stops",
					this.selected.getName(this.selected.getDefaultInstance()));
		}
		if (chestMode() && this.query.isBlank()) {
			return Component.translatable(this.context.suggestions().isEmpty()
					? "waybettercoppergolem.editor.no_suggestions" : "waybettercoppergolem.editor.suggested");
		}
		if (this.query.isBlank()) {
			return Component.translatable("waybettercoppergolem.picker.from_inventory");
		}
		return Component.translatable(this.dynamic.isEmpty()
				? "waybettercoppergolem.picker.no_results" : "waybettercoppergolem.picker.section.matches");
	}

	private Component currentLine() {
		EditorContext ctx = this.context;
		if (ctx == null || ctx.current().isEmpty()) {
			return Component.translatable("waybettercoppergolem.editor.none");
		}
		String key = ctx.current().explicit()
				? "waybettercoppergolem.editor.current" : "waybettercoppergolem.editor.current_auto";
		return Component.translatable(key, LabelResolver.listNames(ctx.current().labels()));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
