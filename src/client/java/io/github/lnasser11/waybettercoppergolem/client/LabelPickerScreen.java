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

import net.minecraft.ChatFormatting;
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
 * header shows the chest's current labels as removable chips and a
 * Replace/Add toggle; the search column starts out with labels suggested
 * from the chest's contents, each with how many stacks it covers. The
 * screen stays open and refreshes after every change.
 *
 * <p>Left column: preset categories, catch-all, off-limits, remove
 * labels, empty clipboard. Right column: item search; picking an item
 * lists its stops, exact first and then its tags narrow to broad, with
 * member counts. Everything is computed from the client's own registries
 * and tags; the server only receives the final choice and validates it.
 */
public class LabelPickerScreen extends Screen {
	private static final int MAX_RESULTS = 8;

	private record IconAt(ItemStack stack, int x, int y) {
	}

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private final List<IconAt> icons = new ArrayList<>();
	private @Nullable EditorContext context;
	private boolean addMode;
	private @Nullable EditBox search;
	private String query = "";
	private @Nullable Item selected;
	private int rightX;
	private int resultsTop;

	private LabelPickerScreen(@Nullable EditorContext context) {
		super(Component.translatable(context == null
				? "waybettercoppergolem.picker.title" : "waybettercoppergolem.editor.title"));
		this.context = context;
	}

	public static LabelPickerScreen forClipboard() {
		return new LabelPickerScreen(null);
	}

	/** The editor currently on screen in chest mode, if any (tracked here; see {@link #removed()}). */
	private static @Nullable LabelPickerScreen openEditor;

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

	private int headerHeight() {
		return chestMode() ? 44 + Ui.ROW : 36;
	}

	private int top() {
		return Math.max(headerHeight(), this.height / 2 - 118);
	}

	@Override
	protected void init() {
		super.init();
		if (chestMode()) {
			openEditor = this;
		}
		this.dynamic.clear();
		this.icons.clear();
		int leftX = this.width / 2 - Ui.PANEL_WIDTH / 2;
		this.rightX = leftX + Ui.COLUMN_WIDTH + Ui.GAP;
		int top = top();

		if (chestMode()) {
			addHeaderChips(leftX, top - Ui.ROW);
		}

		// ---- left: categories, then the special labels
		int half = (Ui.COLUMN_WIDTH - Ui.GAP) / 2;
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		int y = top;
		for (int i = 0; i < presets.size(); i++) {
			TagKey<Item> preset = presets.get(i);
			int x = leftX + (i % 2) * (half + Ui.GAP);
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
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.catch_all"),
						button -> choose(ChestLabel.catchAll()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.catch_all.tooltip")))
				.bounds(leftX, y, half, Ui.BUTTON_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.off_limits"),
						button -> choose(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(Items.COBWEB))))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.off_limits.tooltip")))
				.bounds(leftX + half + Ui.GAP, y, half, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.remove_labels"),
						button -> sendLabels(List.of()))
				.tooltip(Tooltip.create(Component.translatable(chestMode()
						? "waybettercoppergolem.editor.remove_labels.tooltip"
						: "waybettercoppergolem.picker.remove_labels.tooltip")))
				.bounds(leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		if (!chestMode()) {
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.empty_clipboard"),
							button -> send(SetClipboardPayload.empty()))
					.bounds(leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
		}
		y += Ui.GAP;
		this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.picker.tune_categories"),
						button -> this.minecraft.setScreenAndShow(new CategoryListScreen()))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_categories.tooltip")))
				.bounds(leftX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
		y += Ui.ROW;
		int leftBottom = y;

		// ---- right: search + results
		this.search = new EditBox(this.font, this.rightX, top, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
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
		this.resultsTop = top + Ui.ROW + 10;
		rebuildResults();

		int rightBottom = this.resultsTop + (MAX_RESULTS + 1) * Ui.ROW;
		int doneY = Math.max(leftBottom, rightBottom) + Ui.GAP;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, Math.min(doneY, this.height - Ui.BUTTON_HEIGHT - 6), 200, Ui.BUTTON_HEIGHT)
				.build());
	}

	/** Chest mode header row: the Replace/Add toggle and one removable chip per current label. */
	private void addHeaderChips(int leftX, int y) {
		EditorContext ctx = this.context;
		if (ctx == null) {
			return;
		}
		int x = leftX;
		Button mode = Button.builder(Component.translatable(this.addMode
						? "waybettercoppergolem.editor.mode_add" : "waybettercoppergolem.editor.mode_replace"), button -> {
					this.addMode = !this.addMode;
					this.rebuildWidgets();
				})
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.editor.mode.tooltip")))
				.bounds(x, y, 90, Ui.BUTTON_HEIGHT).build();
		this.addRenderableWidget(mode);
		x += 90 + Ui.GAP;
		int rightEdge = leftX + Ui.PANEL_WIDTH;
		for (ChestLabel label : ctx.current().labels()) {
			Component text = Component.literal("✕ ").append(LabelResolver.shortName(label));
			int width = Math.min(this.font.width(text) + 12, Ui.COLUMN_WIDTH);
			if (x + width > rightEdge) {
				break; // more chips than fit; the current line above still lists them all
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
		this.icons.clear();
		int y = this.resultsTop;
		if (this.selected != null) {
			Item item = this.selected;
			Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
			add(Button.builder(Component.translatable("waybettercoppergolem.picker.back"), button -> {
				this.selected = null;
				rebuildResults();
			}).bounds(this.rightX, y, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT).build());
			y += Ui.ROW;
			addIconRow(y, new ItemStack(item),
					Component.translatable("waybettercoppergolem.picker.exact", item.getName(item.getDefaultInstance())),
					null, () -> choose(ChestLabel.exact(itemId)));
			y += Ui.ROW;
			int shown = 0;
			for (TagKey<Item> tag : LabelResolver.orderedTags(item)) {
				if (shown++ >= MAX_RESULTS - 1) {
					break;
				}
				Identifier tagId = tag.location();
				addIconRow(y, new ItemStack(item),
						Component.translatable("waybettercoppergolem.picker.stop", LabelResolver.tagName(tagId),
								LabelResolver.tagSize(tag)),
						stopTooltip(tag), () -> choose(ChestLabel.tag(itemId, tagId)), tagId);
				y += Ui.ROW;
			}
			return;
		}
		if (chestMode() && this.query.isBlank()) {
			for (Suggestion suggestion : this.context.suggestions()) {
				if (y >= this.resultsTop + MAX_RESULTS * Ui.ROW) {
					break;
				}
				ChestLabel label = suggestion.label();
				Component text = Component.translatable("waybettercoppergolem.editor.suggestion",
						LabelResolver.shortName(label), suggestion.coveredStacks(), suggestion.totalStacks());
				Component tooltip = label.tagId().map(id -> stopTooltip(LabelResolver.itemTag(id))).orElse(null);
				addIconRow(y, iconFor(label), text, tooltip, () -> choose(label), label.tagId().orElse(null));
				y += Ui.ROW;
			}
			return;
		}
		for (Item item : matches()) {
			addIconRow(y, new ItemStack(item), item.getName(item.getDefaultInstance()), null, () -> {
				this.selected = item;
				rebuildResults();
			});
			y += Ui.ROW;
		}
	}

	private void addIconRow(int y, ItemStack icon, Component text, @Nullable Component tooltip, Runnable action) {
		addIconRow(y, icon, text, tooltip, action, null);
	}

	/** A row with an icon slot on the left and, for tag labels, a "…" button on the right that opens tuning. */
	private void addIconRow(int y, ItemStack icon, Component text, @Nullable Component tooltip, Runnable action,
			@Nullable Identifier tuneTag) {
		int width = Ui.COLUMN_WIDTH - Ui.ICON_SLOT - (tuneTag != null ? Ui.ICON_SLOT + Ui.GAP : 0);
		Button.Builder builder = Button.builder(text, button -> action.run())
				.bounds(this.rightX + Ui.ICON_SLOT, y, width, Ui.BUTTON_HEIGHT);
		if (tooltip != null) {
			builder.tooltip(Tooltip.create(tooltip));
		}
		add(builder.build());
		if (tuneTag != null) {
			add(Button.builder(Component.literal("…"),
							button -> ClientPlayNetworking.send(new TuningPayloads.OpenTuning(tuneTag)))
					.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.picker.tune_row.tooltip")))
					.bounds(this.rightX + Ui.COLUMN_WIDTH - Ui.ICON_SLOT, y, Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build());
		}
		this.icons.add(new IconAt(icon, this.rightX + 2, y + 2));
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

	private static ItemStack iconFor(ChestLabel label) {
		return label.itemId()
				.flatMap(BuiltInRegistries.ITEM::getOptional)
				.map(ItemStack::new)
				.orElse(new ItemStack(Items.CHEST));
	}

	private static Component stopTooltip(TagKey<Item> tag) {
		return Component.translatable("waybettercoppergolem.picker.tag_tooltip",
				tag.location().toString(), LabelResolver.tagSize(tag)).withStyle(ChatFormatting.GRAY);
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
		int top = top();
		int centerX = this.width / 2;
		if (chestMode()) {
			BlockPos pos = this.context.pos();
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.editor.title",
					pos.getX() + " " + pos.getY() + " " + pos.getZ()), centerX, top - headerHeight() + 4, Ui.TEXT);
			graphics.centeredText(this.font, currentLine(), centerX, top - headerHeight() + 18, Ui.TEXT_MUTED);
		} else {
			graphics.centeredText(this.font, this.title, centerX, top - 28, Ui.TEXT);
			if (this.minecraft != null && this.minecraft.player != null) {
				graphics.centeredText(this.font, LabelTool.describeClipboard(Clipboard.of(this.minecraft.player)),
						centerX, top - 14, Ui.TEXT_MUTED);
			}
		}
		if (this.selected == null && this.query.isBlank()) {
			String key = chestMode() && !this.context.suggestions().isEmpty()
					? "waybettercoppergolem.editor.suggested" : "waybettercoppergolem.picker.from_inventory";
			graphics.text(this.font, Component.translatable(key), this.rightX, this.resultsTop - 10, Ui.TEXT_HINT);
		} else if (this.selected == null && this.dynamic.isEmpty()) {
			graphics.text(this.font, Component.translatable("waybettercoppergolem.picker.no_results"),
					this.rightX, this.resultsTop - 10, Ui.TEXT_HINT);
		}
		for (IconAt icon : this.icons) {
			graphics.item(icon.stack(), icon.x(), icon.y());
		}
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
