package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads.TuningContext;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edit what a category (any tag label) contains in this world. Left: the
 * current members, paged; click one to exclude it; excluded base items
 * are listed last and click to restore. Right: search any item to add
 * it, or add the item in your hand. Changes go to the server one at a
 * time and the screen refreshes from its answer. Read-only without
 * operator permission.
 */
public class CategoryTuningScreen extends Screen {
	private static final int ROWS = 8;

	private record IconAt(ItemStack stack, int x, int y) {
	}

	/** The tuning screen currently open, if any, so the server's refresh can update it in place. */
	private static @Nullable CategoryTuningScreen open;

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private final List<IconAt> icons = new ArrayList<>();
	private TuningContext context;
	private int page;
	private String query = "";
	private int leftX;
	private int rightX;
	private int listTop;

	private CategoryTuningScreen(TuningContext context) {
		super(Component.translatable("waybettercoppergolem.tuning.title", LabelResolver.tagName(context.tagId())));
		this.context = context;
	}

	public static void openOrUpdate(Minecraft minecraft, TuningContext context) {
		CategoryTuningScreen current = open;
		if (current != null && current.context.tagId().equals(context.tagId())) {
			current.context = context;
			current.rebuildWidgets();
			return;
		}
		minecraft.setScreenAndShow(new CategoryTuningScreen(context));
	}

	@Override
	public void removed() {
		super.removed();
		if (open == this) {
			open = null;
		}
	}

	private int top() {
		return Math.max(40, this.height / 2 - 110);
	}

	@Override
	protected void init() {
		super.init();
		open = this;
		this.dynamic.clear();
		this.icons.clear();
		this.leftX = this.width / 2 - Ui.PANEL_WIDTH / 2;
		this.rightX = this.leftX + Ui.COLUMN_WIDTH + Ui.GAP;
		int top = top();
		this.listTop = top + 12;

		// ---- right: search, add held, reset
		EditBox search = new EditBox(this.font, this.rightX, top, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.picker.search"));
		search.setHint(Component.translatable("waybettercoppergolem.tuning.search_hint"));
		search.setMaxLength(40);
		search.setValue(this.query);
		search.setResponder(text -> {
			this.query = text;
			rebuildLists();
		});
		this.addRenderableWidget(search);

		int bottom = this.listTop + Ui.ROW + ROWS * Ui.ROW + Ui.GAP;
		Button addHeld = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.tuning.add_held"), button -> {
							if (this.minecraft != null && this.minecraft.player != null) {
								ItemStack held = this.minecraft.player.getMainHandItem();
								if (!held.isEmpty()) {
									send(BuiltInRegistries.ITEM.getKey(held.getItem()), true);
								}
							}
						})
				.bounds(this.rightX, bottom, (Ui.COLUMN_WIDTH - Ui.GAP) / 2, Ui.BUTTON_HEIGHT).build());
		Button reset = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.tuning.reset"),
						button -> ClientPlayNetworking.send(TuningPayloads.TuneCategory.reset(this.context.tagId())))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.tuning.reset.tooltip")))
				.bounds(this.rightX + (Ui.COLUMN_WIDTH + Ui.GAP) / 2, bottom, (Ui.COLUMN_WIDTH - Ui.GAP) / 2, Ui.BUTTON_HEIGHT)
				.build());
		addHeld.active = this.context.canEdit();
		reset.active = this.context.canEdit() && !(this.context.added().isEmpty() && this.context.removed().isEmpty());

		// ---- left: paging
		int pages = Math.max(1, (memberRows().size() + ROWS - 1) / ROWS);
		this.page = Math.clamp(this.page, 0, pages - 1);
		int pagerY = bottom;
		this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
			this.page = Math.max(0, this.page - 1);
			this.rebuildWidgets();
		}).bounds(this.leftX, pagerY, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build()).active = this.page > 0;
		this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
			this.page = Math.min(pages - 1, this.page + 1);
			this.rebuildWidgets();
		}).bounds(this.leftX + Ui.COLUMN_WIDTH - Ui.BUTTON_HEIGHT, pagerY, Ui.BUTTON_HEIGHT, Ui.BUTTON_HEIGHT).build())
				.active = this.page < pages - 1;

		rebuildLists();

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.width / 2 - 100, Math.min(bottom + Ui.ROW + Ui.GAP, this.height - Ui.BUTTON_HEIGHT - 6),
						200, Ui.BUTTON_HEIGHT).build());
	}

	private record MemberRow(Item item, boolean added, boolean removed) {
	}

	/** Effective members (added ones marked), then the excluded base items. */
	private List<MemberRow> memberRows() {
		Set<Identifier> added = new HashSet<>(this.context.added());
		Set<Identifier> removed = new HashSet<>(this.context.removed());
		List<MemberRow> members = new ArrayList<>();
		List<MemberRow> excluded = new ArrayList<>();
		BuiltInRegistries.ITEM.get(LabelResolver.itemTag(this.context.tagId())).ifPresent(named -> {
			for (Holder<Item> holder : named) {
				Item item = holder.value();
				Identifier id = BuiltInRegistries.ITEM.getKey(item);
				if (removed.contains(id)) {
					excluded.add(new MemberRow(item, false, true));
				} else {
					members.add(new MemberRow(item, false, false));
				}
			}
		});
		for (Identifier id : added) {
			BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> members.add(new MemberRow(item, true, false)));
		}
		Comparator<MemberRow> byName = Comparator.comparing(row -> row.item().getName(row.item().getDefaultInstance()).getString());
		members.sort(byName);
		excluded.sort(byName);
		members.addAll(excluded);
		return members;
	}

	private void rebuildLists() {
		for (AbstractWidget widget : this.dynamic) {
			this.removeWidget(widget);
		}
		this.dynamic.clear();
		this.icons.clear();
		boolean canEdit = this.context.canEdit();

		// members page
		List<MemberRow> rows = memberRows();
		int y = this.listTop + Ui.ROW;
		int from = this.page * ROWS;
		for (int i = from; i < Math.min(rows.size(), from + ROWS); i++) {
			MemberRow row = rows.get(i);
			Component name = row.item().getName(row.item().getDefaultInstance());
			Component text;
			String tooltipKey;
			if (row.removed()) {
				text = Component.translatable("waybettercoppergolem.tuning.excluded_row", name);
				tooltipKey = "waybettercoppergolem.tuning.restore.tooltip";
			} else if (row.added()) {
				text = Component.translatable("waybettercoppergolem.tuning.added_row", name);
				tooltipKey = "waybettercoppergolem.tuning.exclude.tooltip";
			} else {
				text = name;
				tooltipKey = "waybettercoppergolem.tuning.exclude.tooltip";
			}
			Identifier id = BuiltInRegistries.ITEM.getKey(row.item());
			boolean include = row.removed();
			Button button = Button.builder(text, b -> send(id, include))
					.tooltip(Tooltip.create(Component.translatable(tooltipKey)))
					.bounds(this.leftX + Ui.ICON_SLOT, y, Ui.COLUMN_WIDTH - Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build();
			button.active = canEdit;
			add(button);
			this.icons.add(new IconAt(new ItemStack(row.item()), this.leftX + 2, y + 2));
			y += Ui.ROW;
		}

		// search results to add
		y = this.listTop + Ui.ROW;
		Set<Item> current = new HashSet<>();
		for (MemberRow row : rows) {
			if (!row.removed()) {
				current.add(row.item());
			}
		}
		for (Item item : matches(current)) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			Button button = Button.builder(Component.translatable("waybettercoppergolem.tuning.add_row",
							item.getName(item.getDefaultInstance())), b -> send(id, true))
					.bounds(this.rightX + Ui.ICON_SLOT, y, Ui.COLUMN_WIDTH - Ui.ICON_SLOT, Ui.BUTTON_HEIGHT).build();
			button.active = canEdit;
			add(button);
			this.icons.add(new IconAt(new ItemStack(item), this.rightX + 2, y + 2));
			y += Ui.ROW;
		}
	}

	private List<Item> matches(Set<Item> exclude) {
		String needle = this.query.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) {
			return List.of();
		}
		List<Item> results = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == Items.AIR || exclude.contains(item)) {
				continue;
			}
			String name = item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT);
			if (name.contains(needle) || BuiltInRegistries.ITEM.getKey(item).getPath().contains(needle)) {
				results.add(item);
			}
		}
		results.sort(Comparator
				.comparing((Item item) -> !item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT).startsWith(needle))
				.thenComparing(item -> item.getName(item.getDefaultInstance()).getString()));
		return results.size() > ROWS ? results.subList(0, ROWS) : results;
	}

	private void add(AbstractWidget widget) {
		this.dynamic.add(widget);
		this.addRenderableWidget(widget);
	}

	private void send(Identifier itemId, boolean include) {
		ClientPlayNetworking.send(new TuningPayloads.TuneCategory(this.context.tagId(), java.util.Optional.of(itemId), include));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int top = top();
		graphics.centeredText(this.font, this.title, this.width / 2, top - 30, Ui.TEXT);
		int memberCount = (int) memberRows().stream().filter(row -> !row.removed()).count();
		Component subtitle = Component.translatable("waybettercoppergolem.tuning.subtitle",
				this.context.tagId().toString(), memberCount, this.context.added().size(), this.context.removed().size());
		graphics.centeredText(this.font, subtitle, this.width / 2, top - 18, Ui.TEXT_MUTED);
		if (!this.context.canEdit()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.tuning.readonly"),
					this.width / 2, top - 6, 0xFFFF8888);
		}
		graphics.text(this.font, Component.translatable("waybettercoppergolem.tuning.members_header"),
				this.leftX, this.listTop + Ui.ROW - 10, Ui.TEXT_HINT);
		graphics.text(this.font, Component.translatable(this.query.isBlank()
				? "waybettercoppergolem.tuning.add_hint" : "waybettercoppergolem.tuning.add_header"),
				this.rightX, this.listTop + Ui.ROW - 10, Ui.TEXT_HINT);
		for (IconAt icon : this.icons) {
			graphics.item(icon.stack(), icon.x(), icon.y());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
