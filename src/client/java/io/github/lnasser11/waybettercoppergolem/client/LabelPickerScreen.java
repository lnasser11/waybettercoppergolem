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
 * header shows the chest's current labels; a row of chips removes single
 * labels and a Replace/Add toggle decides how a choice combines with
 * them; the search column starts out with labels suggested from the
 * chest's contents, each with how many stacks it covers. The screen stays
 * open and refreshes after every change.
 *
 * <p>Left column: preset categories and the special labels. Right column:
 * item search; picking an item lists its stops, exact first and then its
 * tags narrow to broad, with member counts. Everything is computed from
 * the client's own registries and tags; the server only receives the
 * final choice and validates it.
 */
public class LabelPickerScreen extends Screen {
	private static final int MAX_RESULTS = 8;
	/** Back + exact + up to 7 tags when an item is selected. */
	private static final int RESULT_ROWS = MAX_RESULTS + 1;

	/** The editor currently on screen in chest mode, if any (tracked here; see {@link #removed()}). */
	private static @Nullable LabelPickerScreen openEditor;

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private @Nullable EditorContext context;
	private boolean addMode;
	private String query = "";
	private @Nullable Item selected;
	private Panel panel = new Panel(0, 0, 0, 0);
	private int leftX;
	private int rightX;
	private int categoriesLabelY;
	private int specialLabelY;
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

		int leftHeight = Ui.SECTION_LABEL + 6 * Ui.ROW + Ui.GAP + Ui.SECTION_LABEL + 2 * Ui.ROW
				+ (chestMode() ? 0 : Ui.ROW) + Ui.GAP + Ui.ROW;
		int rightHeight = Ui.SECTION_LABEL + Ui.ROW + Ui.SECTION_LABEL + RESULT_ROWS * Ui.ROW;
		int chipsHeight = chestMode() ? Ui.ROW + Ui.GAP : 0;
		int contentHeight = chipsHeight + Math.max(leftHeight, rightHeight) + Ui.GAP + Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, contentHeight);
		this.leftX = this.panel.contentX();
		this.rightX = this.leftX + Ui.COLUMN_WIDTH + Ui.GAP;
		int top = this.panel.contentTop();

		if (chestMode()) {
			addHeaderChips(this.leftX, top);
			top += Ui.ROW + Ui.GAP;
		}

		// ---- left: categories, then the special labels
		int half = (Ui.COLUMN_WIDTH - Ui.GAP) / 2;
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		this.categoriesLabelY = top;
		int y = top + Ui.SECTION_LABEL;
		for (int i = 0; i < presets.size(); i++) {
			TagKey<Item> preset = presets.get(i);
			int x = this.leftX + (i % 2) * (half + Ui.GAP);
			Identifier tagId = preset.location();
			this.addRenderableWidget(Button.builder(LabelResolver.tagName(tagId),
							button -> choose(ChestLabel.tag(sampleFor(tagId), tagId)))
					.tooltip(Tooltip.create(stopTooltip(preset)))
					.bounds(x, y, half, Ui.BUTTON_HEIGHT).build());
			if (i % 2 == 1) {
				y += Ui.ROW;
			}
		}
		if (presets.size() % 2 == 1) {
			y += Ui.ROW;
		}
		y += Ui.GAP;
		this.specialLabelY = y;
		y += Ui.SECTION_LABEL;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.catch_all"),
						button -> choose(ChestLabel.catchAll()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.catch_all.tooltip")))
				.bounds(this.leftX, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.off_limits"),
						button -> choose(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(Items.COBWEB))))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.off_limits.tooltip")))
				.bounds(this.leftX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.remove_labels"),
						button -> sendLabels(List.of()))
				.tooltip(Tooltip.create(Component.translatable(chestMode()
						? "waybettercoppergolem.editor.remove_labels.tooltip"
						: "waybettercoppergolem.picker.remove_labels.tooltip")))
				.bounds(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		if (!chestMode()) {
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.empty_clipboard"),
							button -> send(SetClipboardPayload.empty()))
					.bounds(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
		}
		y += Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.tune_categories"),
						button -> this.minecraft.setScreenAndShow(new CategoryListScreen()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_categories.tooltip")))
				.bounds(this.leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());

		// ---- right: search + results
		this.searchLabelY = top;
		EditBox search = new EditBox(this.font, this.rightX, top + Ui.SECTION_LABEL, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
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
		this.resultsLabelY = top + Ui.SECTION_LABEL + Ui.ROW;
		this.resultsTop = this.resultsLabelY + Ui.SECTION_LABEL;
		rebuildResults();

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panel.centerX() - 100, this.panel.bottom() - Ui.PADDING - Ui.BUTTON_HEIGHT, 200, Ui.BUTTON_HEIGHT)
				.build());
	}

	/** Chest mode: the Replace/Add toggle and one removable chip per current label. */
	private void addHeaderChips(int leftX, int y) {
		EditorContext ctx = this.context;
		if (ctx == null) {
			return;
		}
		int x = leftX;
		this.addRenderableWidget(Button.builder(Component.translatable(this.addMode
						? "waybettercoppergolem.editor.mode_add" : "waybettercoppergolem.editor.mode_replace"), button -> {
					this.addMode = !this.addMode;
					this.rebuildWidgets();
				})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.editor.mode.tooltip")))
				.bounds(x, y, 90, Ui.BUTTON_HEIGHT).build());
		x += 90 + Ui.GAP;
		int rightEdge = leftX + Ui.PANEL_WIDTH;
		for (ChestLabel label : ctx.current().labels()) {
			Component text = Component.literal("✕ ").append(LabelResolver.shortName(label));
			int width = Math.min(this.font.width(text) + 12, Ui.COLUMN_WIDTH);
			if (x + width > rightEdge) {
				break; // more chips than fit; the header line still lists them all
			}
			List<ChestLabel> remaining = new ArrayList<>(ctx.current().labels());
			remaining.remove(label);
			this.addRenderableWidget(Button.builder(text, button -> sendLabels(remaining))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.editor.remove_one.tooltip")))
					.bounds(x, y, width, Ui.BUTTON_HEIGHT).build());
			x += width + Ui.GAP;
		}
	}

	/** Replaces the right-column list: suggestions / carried items, item matches, or the selected item's stops. */
	private void rebuildResults() {
		for (AbstractWidget widget : this.dynamic) {
			this.removeWidget(widget);
		}
		this.dynamic.clear();
		int y = this.resultsTop;
		if (this.selected != null) {
			Item item = this.selected;
			Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
			add(new ListRow(this.rightX, y, Ui.COLUMN_WIDTH, Component.translatable("waybettercoppergolem.picker.back"), () -> {
				this.selected = null;
				rebuildResults();
			}).primaryColor(Ui.TEXT_MUTED));
			y += Ui.ROW;
			add(row(y, Component.translatable("waybettercoppergolem.picker.exact_row"), () -> choose(ChestLabel.exact(itemId)), null)
					.icon(new ItemStack(item))
					.secondary(Component.translatable("waybettercoppergolem.picker.exact_detail"), Ui.TEXT_MUTED)
					.primaryColor(Ui.EXPLICIT));
			y += Ui.ROW;
			int shown = 0;
			for (TagKey<Item> tag : LabelResolver.orderedTags(item)) {
				if (shown++ >= RESULT_ROWS - 2) {
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
				if (y >= this.resultsTop + RESULT_ROWS * Ui.ROW) {
					break;
				}
				ChestLabel label = suggestion.label();
				boolean full = suggestion.coveredStacks() == suggestion.totalStacks();
				add(row(y, LabelResolver.shortName(label), () -> choose(label), label.tagId().orElse(null))
						.icon(iconFor(label))
						.secondary(Component.translatable("waybettercoppergolem.editor.coverage",
								suggestion.coveredStacks(), suggestion.totalStacks()), full ? Ui.OK : Ui.TEXT_MUTED)
						.accent(full ? Ui.OK : 0)
						.tooltip(label.tagId().map(id -> stopTooltip(LabelResolver.itemTag(id))).orElse(null)));
				y += Ui.ROW;
			}
			return;
		}
		for (Item item : matches()) {
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
		ListRow row = new ListRow(this.rightX, y, width, text, action);
		if (tuneTag != null) {
			add(Button.builder(Component.literal("…"),
							button -> ClientPlayNetworking.send(new TuningPayloads.OpenTuning(tuneTag)))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_row.tooltip")))
					.bounds(this.rightX + Ui.COLUMN_WIDTH - Ui.ICON_SLOT, y, Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build());
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
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
				this.leftX, this.categoriesLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.picker.section.special"),
				this.leftX, this.specialLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.picker.section.search"),
				this.rightX, this.searchLabelY);
		Panel.sectionLabel(graphics, this.font, resultsLabel(), this.rightX, this.resultsLabelY);
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
