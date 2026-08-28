package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Editor for label categories: pick one from the dropdown, then click items
 * to add or remove them. The item list is laid out like the creative
 * inventory - grouped under the tab each item belongs to - rather than one
 * undifferentiated scroll. Players can also create and delete their own
 * categories here; membership edits go through the same world-wide tuning
 * as sneak-clicking a label frame with an item in hand.
 */
public class CategoryEditorScreen extends Screen {
	private static final int CELL = 18;
	private static final int COLS = 9;
	private static final int VISIBLE_ROWS = 7;
	private static final int PANEL_WIDTH = COLS * CELL;
	private static final int HEADER_HEIGHT = 11;
	private static final int DROPDOWN_ROWS = 8;

	private static final int IN_CATEGORY_BG = 0x6633AA55;
	private static final int ADDED_MARK = 0xFF55FF77;
	private static final int REMOVED_MARK = 0xFFFF5555;
	private static final int HOVER_BG = 0x80FFFFFF;
	private static final int PANEL_BG = 0xE0101010;
	private static final int DROPDOWN_BG = 0xF0181818;
	private static final int DROPDOWN_HOVER = 0x60FFFFFF;
	private static final int HEADER_TEXT = 0xFFFFD98B;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;

	/** One line of the item panel: either a group heading or a row of items. */
	private sealed interface Row {
		record Header(Component title) implements Row {
		}

		record Items(List<Item> items) implements Row {
		}
	}

	private record Group(Component title, List<Item> items) {
	}

	private List<CategoryPayloads.Entry> categories = List.of();
	private int selected;
	private Set<Identifier> added = Set.of();
	private Set<Identifier> removed = Set.of();

	private List<Group> allGroups = List.of();
	private List<Row> rows = List.of();
	private int scrollRow;

	private EditBox searchBox;
	private EditBox newNameBox;
	private Button dropdownButton;
	private Button deleteButton;
	private boolean dropdownOpen;
	private boolean creating;

	private int panelX;
	private int panelY;

	public CategoryEditorScreen() {
		super(Component.translatable("waybettercoppergolem.editor.title"));
	}

	@Override
	protected void init() {
		super.init();
		this.panelX = this.width / 2 - PANEL_WIDTH / 2;
		int y = Math.max(24, this.height / 2 - 118);

		this.dropdownButton = this.addRenderableWidget(Button.builder(selectedName(),
						button -> {
							this.dropdownOpen = !this.dropdownOpen;
							this.creating = false;
							rebuildWidgets();
						})
				.bounds(this.panelX, y, PANEL_WIDTH - 44, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("+"), button -> startCreating())
				.bounds(this.panelX + PANEL_WIDTH - 42, y, 20, 20).build());
		this.deleteButton = this.addRenderableWidget(Button.builder(Component.literal("✕"),
						button -> deleteSelected())
				.bounds(this.panelX + PANEL_WIDTH - 20, y, 20, 20).build());
		this.deleteButton.active = selectedEntry() != null && selectedEntry().custom();
		y += 24;

		if (this.creating) {
			this.newNameBox = new EditBox(this.font, this.panelX, y, PANEL_WIDTH - 44, 18,
					Component.translatable("waybettercoppergolem.editor.new_name"));
			this.newNameBox.setMaxLength(32);
			this.addRenderableWidget(this.newNameBox);
			this.setInitialFocus(this.newNameBox);
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.editor.create"),
							button -> confirmCreate())
					.bounds(this.panelX + PANEL_WIDTH - 42, y, 42, 20).build());
		} else {
			String previous = this.searchBox == null ? "" : this.searchBox.getValue();
			this.searchBox = new EditBox(this.font, this.panelX, y, PANEL_WIDTH, 18,
					Component.translatable("waybettercoppergolem.editor.search"));
			this.searchBox.setValue(previous);
			this.searchBox.setResponder(text -> rebuildRows());
			this.addRenderableWidget(this.searchBox);
		}
		y += 22;
		this.panelY = y;

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.panelX, this.panelY + VISIBLE_ROWS * CELL + 20, PANEL_WIDTH, 20).build());

		if (this.allGroups.isEmpty()) {
			this.allGroups = buildGroups();
		}
		rebuildRows();
		if (this.categories.isEmpty()) {
			ClientPlayNetworking.send(new CategoryPayloads.ListRequest());
		}
	}

	/**
	 * Items grouped by the creative tab they appear in, so the panel reads
	 * like the creative inventory. Tabs are only populated once the client
	 * has built them; if that has not happened, everything lands in one
	 * group rather than showing an empty screen.
	 */
	private List<Group> buildGroups() {
		List<Group> groups = new ArrayList<>();
		Set<Item> seen = new HashSet<>();
		for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
			if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
				continue;
			}
			List<Item> items = new ArrayList<>();
			for (ItemStack stack : tab.getDisplayItems()) {
				Item item = stack.getItem();
				if (item != Items.AIR && seen.add(item)) {
					items.add(item);
				}
			}
			if (!items.isEmpty()) {
				groups.add(new Group(tab.getDisplayName(), items));
			}
		}
		List<Item> leftovers = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item != Items.AIR && !seen.contains(item)) {
				leftovers.add(item);
			}
		}
		if (!leftovers.isEmpty()) {
			groups.add(new Group(Component.translatable("waybettercoppergolem.editor.other_items"), leftovers));
		}
		return groups;
	}

	/** Re-lays out the panel: filtered items, wrapped into rows under headings. */
	private void rebuildRows() {
		String query = this.searchBox == null ? "" : this.searchBox.getValue().toLowerCase(Locale.ROOT).trim();
		List<Row> built = new ArrayList<>();
		for (Group group : this.allGroups) {
			List<Item> matching = new ArrayList<>();
			for (Item item : group.items()) {
				if (query.isEmpty() || matchesQuery(item, query)) {
					matching.add(item);
				}
			}
			if (matching.isEmpty()) {
				continue;
			}
			built.add(new Row.Header(group.title()));
			for (int i = 0; i < matching.size(); i += COLS) {
				built.add(new Row.Items(List.copyOf(matching.subList(i, Math.min(i + COLS, matching.size())))));
			}
		}
		this.rows = List.copyOf(built);
		this.scrollRow = Math.clamp(this.scrollRow, 0, maxScroll());
	}

	private static boolean matchesQuery(Item item, String query) {
		return item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT).contains(query)
				|| BuiltInRegistries.ITEM.getKey(item).toString().contains(query);
	}

	private int maxScroll() {
		return Math.max(0, this.rows.size() - VISIBLE_ROWS);
	}

	private CategoryPayloads.@org.jspecify.annotations.Nullable Entry selectedEntry() {
		return this.categories.isEmpty() ? null
				: this.categories.get(Math.clamp(this.selected, 0, this.categories.size() - 1));
	}

	private Component selectedName() {
		CategoryPayloads.Entry entry = selectedEntry();
		return entry == null ? Component.translatable("waybettercoppergolem.editor.no_categories") : entry.name();
	}

	/** Called by the client packet handler when the category list arrives. */
	public void acceptList(CategoryPayloads.ListSync sync) {
		Identifier previous = selectedEntry() == null ? null : selectedEntry().id();
		this.categories = sync.entries();
		this.selected = 0;
		if (previous != null) {
			for (int i = 0; i < this.categories.size(); i++) {
				if (this.categories.get(i).id().equals(previous)) {
					this.selected = i;
					break;
				}
			}
		}
		requestOverrides();
		rebuildWidgets();
	}

	/** Called by the client packet handler when a category's overrides arrive. */
	public void acceptSync(CategoryPayloads.Sync sync) {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry != null && entry.id().equals(sync.tagId())) {
			this.added = new HashSet<>(sync.added());
			this.removed = new HashSet<>(sync.removed());
		}
	}

	private void requestOverrides() {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry != null) {
			this.added = Set.of();
			this.removed = Set.of();
			ClientPlayNetworking.send(new CategoryPayloads.Query(entry.id()));
		}
	}

	private void selectCategory(int index) {
		this.selected = index;
		this.dropdownOpen = false;
		this.scrollRow = 0;
		requestOverrides();
		rebuildWidgets();
	}

	private void startCreating() {
		this.creating = true;
		this.dropdownOpen = false;
		rebuildWidgets();
	}

	private void confirmCreate() {
		if (this.newNameBox != null && !this.newNameBox.getValue().isBlank()) {
			ClientPlayNetworking.send(new CategoryPayloads.Create(this.newNameBox.getValue()));
		}
		this.creating = false;
		rebuildWidgets();
	}

	private void deleteSelected() {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry != null && entry.custom()) {
			ClientPlayNetworking.send(new CategoryPayloads.Delete(entry.id()));
		}
	}

	private boolean isInCategory(Item item) {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry == null) {
			return false;
		}
		Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
		if (this.removed.contains(itemId)) {
			return false;
		}
		if (this.added.contains(itemId)) {
			return true;
		}
		return item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, entry.id()));
	}

	/** The item under the cursor, or null; headers and gaps count as nothing. */
	private @org.jspecify.annotations.Nullable Item itemAt(double mouseX, double mouseY) {
		int row = (int) Math.floor((mouseY - this.panelY) / CELL);
		if (row < 0 || row >= VISIBLE_ROWS || this.scrollRow + row >= this.rows.size()) {
			return null;
		}
		if (!(this.rows.get(this.scrollRow + row) instanceof Row.Items items)) {
			return null;
		}
		int col = (int) Math.floor((mouseX - this.panelX) / CELL);
		return col < 0 || col >= items.items().size() ? null : items.items().get(col);
	}

	private int dropdownHeight() {
		return Math.min(this.categories.size(), DROPDOWN_ROWS) * 12 + 2;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.dropdownOpen && event.button() == 0) {
			int top = this.dropdownButton.getY() + this.dropdownButton.getHeight();
			int index = (int) Math.floor((event.y() - top - 1) / 12.0);
			if (event.x() >= this.panelX && event.x() <= this.panelX + PANEL_WIDTH
					&& index >= 0 && index < Math.min(this.categories.size(), DROPDOWN_ROWS)) {
				selectCategory(index);
				return true;
			}
			this.dropdownOpen = false;
			rebuildWidgets();
			return true;
		}
		Item clicked = itemAt(event.x(), event.y());
		if (clicked != null && event.button() == 0) {
			CategoryPayloads.Entry entry = selectedEntry();
			if (entry != null) {
				ClientPlayNetworking.send(new CategoryPayloads.Toggle(
						entry.id(), BuiltInRegistries.ITEM.getKey(clicked)));
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		this.scrollRow = Math.clamp(this.scrollRow - (int) Math.signum(vertical), 0, maxScroll());
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, Math.max(8, this.panelY - 68), TEXT);

		int panelBottom = this.panelY + VISIBLE_ROWS * CELL;
		graphics.fill(this.panelX - 2, this.panelY - 2, this.panelX + PANEL_WIDTH + 2, panelBottom + 2, PANEL_BG);

		Item hovered = this.dropdownOpen ? null : itemAt(mouseX, mouseY);
		for (int line = 0; line < VISIBLE_ROWS && this.scrollRow + line < this.rows.size(); line++) {
			Row row = this.rows.get(this.scrollRow + line);
			int rowY = this.panelY + line * CELL;
			if (row instanceof Row.Header header) {
				graphics.text(this.font, header.title(), this.panelX + 1, rowY + CELL - HEADER_HEIGHT, HEADER_TEXT);
				continue;
			}
			List<Item> items = ((Row.Items) row).items();
			for (int col = 0; col < items.size(); col++) {
				Item item = items.get(col);
				int x = this.panelX + col * CELL;
				if (isInCategory(item)) {
					graphics.fill(x, rowY, x + CELL, rowY + CELL, IN_CATEGORY_BG);
				}
				Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
				if (this.added.contains(itemId)) {
					graphics.fill(x + CELL - 4, rowY, x + CELL, rowY + 4, ADDED_MARK);
				} else if (this.removed.contains(itemId)) {
					graphics.fill(x + CELL - 4, rowY, x + CELL, rowY + 4, REMOVED_MARK);
				}
				if (item == hovered) {
					graphics.fill(x, rowY, x + CELL, rowY + CELL, HOVER_BG);
				}
				graphics.item(new ItemStack(item), x + 1, rowY + 1);
			}
		}

		int footerY = panelBottom + 6;
		if (hovered != null) {
			graphics.centeredText(this.font, Component.translatable(
							isInCategory(hovered) ? "waybettercoppergolem.editor.in_category"
									: "waybettercoppergolem.editor.not_in_category",
							hovered.getName(hovered.getDefaultInstance())),
					this.width / 2, footerY, TEXT);
		} else {
			graphics.centeredText(this.font,
					Component.translatable(this.creating ? "waybettercoppergolem.editor.new_hint"
							: "waybettercoppergolem.editor.hint"),
					this.width / 2, footerY, TEXT_DIM);
		}

		if (this.dropdownOpen && !this.categories.isEmpty()) {
			int top = this.dropdownButton.getY() + this.dropdownButton.getHeight();
			int shown = Math.min(this.categories.size(), DROPDOWN_ROWS);
			graphics.fill(this.panelX, top, this.panelX + PANEL_WIDTH, top + dropdownHeight(), DROPDOWN_BG);
			for (int i = 0; i < shown; i++) {
				int entryY = top + 1 + i * 12;
				if (mouseX >= this.panelX && mouseX <= this.panelX + PANEL_WIDTH
						&& mouseY >= entryY && mouseY < entryY + 12) {
					graphics.fill(this.panelX, entryY, this.panelX + PANEL_WIDTH, entryY + 12, DROPDOWN_HOVER);
				}
				graphics.text(this.font, this.categories.get(i).name(), this.panelX + 3, entryY + 2,
						i == this.selected ? HEADER_TEXT : TEXT);
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
