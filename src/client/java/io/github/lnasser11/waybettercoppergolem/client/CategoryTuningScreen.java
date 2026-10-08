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
import java.util.Optional;
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
	private static final int MIN_ROWS = 3;
	private static final int MAX_ROWS = 12;
	/** Rows per page, chosen to fit the screen. */
	private int rows = MIN_ROWS;

	/** The tuning screen currently open, if any, so the server's refresh can update it in place. */
	private static @Nullable CategoryTuningScreen open;

	private final List<AbstractWidget> dynamic = new ArrayList<>();
	private TuningContext context;
	private int page;
	private String query = "";
	private Panel panel = new Panel(0, 0, 0, 0);
	private int leftX;
	private int rightX;
	private int membersLabelY;
	private int addLabelY;
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

	@Override
	protected void init() {
		super.init();
		open = this;
		this.dynamic.clear();
		boolean canEdit = this.context.canEdit();

		// section label, search/pager row, rows, actions row (+ a read-only notice line)
		int fixed = Ui.SECTION_LABEL + Ui.ROW + Ui.GAP + Ui.ROW + (canEdit ? 0 : Ui.SECTION_LABEL);
		this.rows = Ui.rowsThatFit(this.height, fixed, MIN_ROWS, MAX_ROWS);
		int contentHeight = fixed + this.rows * Ui.ROW;
		this.panel = Panel.centered(this.width, this.height, Ui.PANEL_WIDTH, contentHeight);
		this.leftX = this.panel.contentX();
		this.rightX = this.leftX + Ui.COLUMN_WIDTH + Ui.GAP;
		int top = this.panel.contentTop() + (canEdit ? 0 : Ui.SECTION_LABEL);
		this.membersLabelY = top;
		this.addLabelY = top;
		this.listTop = top + Ui.SECTION_LABEL + Ui.ROW;

		// ---- right: search box above the add list
		EditBox search = new EditBox(this.font, this.rightX, top + Ui.SECTION_LABEL, Ui.COLUMN_WIDTH, Ui.BUTTON_HEIGHT,
				Component.translatable("waybettercoppergolem.picker.search"));
		search.setHint(Component.translatable("waybettercoppergolem.tuning.search_hint"));
		search.setMaxLength(40);
		search.setValue(this.query);
		search.setResponder(text -> {
			this.query = text;
			rebuildLists();
		});
		this.addRenderableWidget(search);

		// ---- left: pager in the row above the member list
		List<MemberRow> rows = memberRows();
		int pages = Math.max(1, (rows.size() + this.rows - 1) / this.rows);
		this.page = Math.clamp(this.page, 0, pages - 1);
		int pagerY = top + Ui.SECTION_LABEL;
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

		// ---- actions row: add held · reset · done
		int actionsY = this.listTop + this.rows * Ui.ROW + Ui.GAP;
		int third = (Ui.PANEL_WIDTH - 2 * Ui.GAP) / 3;
		Button addHeld = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.tuning.add_held"), button -> {
							if (this.minecraft != null && this.minecraft.player != null) {
								ItemStack held = this.minecraft.player.getMainHandItem();
								if (!held.isEmpty()) {
									send(BuiltInRegistries.ITEM.getKey(held.getItem()), true);
								}
							}
						})
				.bounds(this.leftX, actionsY, third, Ui.BUTTON_HEIGHT).build());
		Button reset = this.addRenderableWidget(Button.builder(
						Component.translatable("waybettercoppergolem.tuning.reset"),
						button -> ClientPlayNetworking.send(TuningPayloads.TuneCategory.reset(this.context.tagId())))
				.tooltip(Tooltip.create(Component.translatable("waybettercoppergolem.tuning.reset.tooltip")))
				.bounds(this.leftX + third + Ui.GAP, actionsY, third, Ui.BUTTON_HEIGHT).build());
		addHeld.active = canEdit;
		reset.active = canEdit && !(this.context.added().isEmpty() && this.context.removed().isEmpty());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.leftX + 2 * (third + Ui.GAP), actionsY, third, Ui.BUTTON_HEIGHT).build());
	}

	/** The panel goes under the widgets, so it is drawn with the background. */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		this.panel.draw(graphics);
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
		boolean canEdit = this.context.canEdit();

		// members page
		List<MemberRow> rows = memberRows();
		int y = this.listTop;
		int from = this.page * this.rows;
		for (int i = from; i < Math.min(rows.size(), from + this.rows); i++) {
			MemberRow row = rows.get(i);
			Identifier id = BuiltInRegistries.ITEM.getKey(row.item());
			boolean include = row.removed();
			ListRow widget = new ListRow(this.leftX, y, Ui.COLUMN_WIDTH, row.item().getName(row.item().getDefaultInstance()),
					() -> send(id, include))
					.icon(new ItemStack(row.item()))
					.enabled(canEdit);
			if (row.removed()) {
				widget.secondary(Component.translatable("waybettercoppergolem.tuning.tag_excluded"), Ui.ATTENTION)
						.accent(Ui.ATTENTION).primaryColor(Ui.TEXT_MUTED)
						.tooltip(Component.translatable("waybettercoppergolem.tuning.restore.tooltip"));
			} else if (row.added()) {
				widget.secondary(Component.translatable("waybettercoppergolem.tuning.tag_added"), Ui.OK)
						.accent(Ui.OK)
						.tooltip(Component.translatable("waybettercoppergolem.tuning.exclude.tooltip"));
			} else {
				widget.tooltip(Component.translatable("waybettercoppergolem.tuning.exclude.tooltip"));
			}
			add(widget);
			y += Ui.ROW;
		}

		// search results to add
		y = this.listTop;
		Set<Item> current = new HashSet<>();
		for (MemberRow row : rows) {
			if (!row.removed()) {
				current.add(row.item());
			}
		}
		for (Item item : matches(current)) {
			Identifier id = BuiltInRegistries.ITEM.getKey(item);
			add(new ListRow(this.rightX, y, Ui.COLUMN_WIDTH, item.getName(item.getDefaultInstance()), () -> send(id, true))
					.icon(new ItemStack(item))
					.secondary(Component.translatable("waybettercoppergolem.tuning.tag_add"), Ui.OK)
					.enabled(canEdit));
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
		return results.size() > this.rows ? results.subList(0, this.rows) : results;
	}

	private void add(AbstractWidget widget) {
		this.dynamic.add(widget);
		this.addRenderableWidget(widget);
	}

	private void send(Identifier itemId, boolean include) {
		ClientPlayNetworking.send(new TuningPayloads.TuneCategory(this.context.tagId(), Optional.of(itemId), include));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int memberCount = (int) memberRows().stream().filter(row -> !row.removed()).count();
		this.panel.header(graphics, this.font, this.title, Component.translatable("waybettercoppergolem.tuning.subtitle",
				this.context.tagId().toString(), memberCount, this.context.added().size(), this.context.removed().size()));
		if (!this.context.canEdit()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.tuning.readonly"),
					this.panel.centerX(), this.panel.contentTop(), Ui.PROBLEM);
		}
		int pages = Math.max(1, (memberRows().size() + this.rows - 1) / this.rows);
		Panel.sectionLabel(graphics, this.font, Component.translatable("waybettercoppergolem.tuning.members_header",
				this.page + 1, pages), this.leftX, this.membersLabelY);
		Panel.sectionLabel(graphics, this.font, Component.translatable(this.query.isBlank()
				? "waybettercoppergolem.tuning.add_hint" : "waybettercoppergolem.tuning.add_header"), this.rightX, this.addLabelY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
