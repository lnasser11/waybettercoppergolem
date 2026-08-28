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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Editor for label categories. The item panel never shows the whole item
 * registry: you land on the items already in the selected category, and
 * browse one section at a time - the creative inventory's tabs, listed by
 * name with their sizes - or type to search across everything. A scrollbar
 * makes the extent of whatever you're looking at visible.
 */
public class CategoryEditorScreen extends Screen {
	private static final int CELL = 18;
	private static final int COLS = 13;
	private static final int VISIBLE_ROWS = 7;
	private static final int GRID_WIDTH = COLS * CELL;
	private static final int GRID_HEIGHT = VISIBLE_ROWS * CELL;
	private static final int SECTION_WIDTH = 124;
	private static final int SECTION_ROW_MAX = 12;
	private static final int SECTION_ROW_MIN = 9;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int GAP = 4;
	private static final int TOTAL_WIDTH = SECTION_WIDTH + GAP + GRID_WIDTH + SCROLLBAR_WIDTH;
	private static final int DROPDOWN_ROWS = 8;

	private static final int IN_CATEGORY_BG = 0x6633AA55;
	private static final int ADDED_MARK = 0xFF55FF77;
	private static final int REMOVED_MARK = 0xFFFF5555;
	private static final int HOVER_BG = 0x80FFFFFF;
	private static final int PANEL_BG = 0xE0101010;
	private static final int SECTION_BG = 0xE0161616;
	private static final int SECTION_SELECTED = 0x50FFFFFF;
	private static final int SECTION_HOVER = 0x28FFFFFF;
	private static final int DROPDOWN_BG = 0xF0181818;
	private static final int SCROLL_TRACK = 0x60000000;
	private static final int SCROLL_THUMB = 0xFF8A8A8A;
	private static final int ACCENT = 0xFFFFD98B;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;

	/** The "In this category" pseudo-section always sits first. */
	private static final int SECTION_IN_CATEGORY = 0;

	private record Section(Component title, List<Item> items) {
	}

	private List<CategoryPayloads.Entry> categories = List.of();
	private int selectedCategory;
	private Set<Identifier> added = Set.of();
	private Set<Identifier> removed = Set.of();

	private List<Section> tabSections = List.of();
	private List<Section> sections = List.of();
	private int selectedSection = SECTION_IN_CATEGORY;
	private int sectionScroll;
	private int sectionRow = SECTION_ROW_MAX;

	private List<Item> shown = List.of();
	private int scrollRow;
	private boolean draggingThumb;

	private EditBox searchBox;
	private EditBox newNameBox;
	private Button dropdownButton;
	private Button deleteButton;
	private boolean dropdownOpen;
	private boolean creating;

	private int leftX;
	private int gridX;
	private int contentY;
	private int topY;

	public CategoryEditorScreen() {
		super(Component.translatable("waybettercoppergolem.editor.title"));
	}

	@Override
	protected void init() {
		super.init();
		this.leftX = this.width / 2 - TOTAL_WIDTH / 2;
		this.gridX = this.leftX + SECTION_WIDTH + GAP;
		int headerWidth = SECTION_WIDTH + GAP + GRID_WIDTH;
		// Title, dropdown row, search, grid, footer line and Done button; kept
		// inside the screen so the Done button never falls off the bottom.
		int totalHeight = 98 + GRID_HEIGHT;
		this.topY = Math.max(2, (this.height - totalHeight) / 2);
		int y = this.topY + 12;

		this.dropdownButton = this.addRenderableWidget(Button.builder(selectedName(), button -> {
					this.dropdownOpen = !this.dropdownOpen;
					this.creating = false;
					rebuildWidgets();
				})
				.bounds(this.leftX, y, headerWidth - 44, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("+"), button -> startCreating())
				.bounds(this.leftX + headerWidth - 42, y, 20, 20).build());
		this.deleteButton = this.addRenderableWidget(Button.builder(Component.literal("x"),
						button -> deleteSelected())
				.bounds(this.leftX + headerWidth - 20, y, 20, 20).build());
		CategoryPayloads.Entry entry = selectedEntry();
		this.deleteButton.active = entry != null && entry.custom();
		y += 24;

		if (this.creating) {
			this.newNameBox = new EditBox(this.font, this.leftX, y, headerWidth - 48, 18,
					Component.translatable("waybettercoppergolem.editor.new_name"));
			this.newNameBox.setMaxLength(32);
			this.addRenderableWidget(this.newNameBox);
			this.setInitialFocus(this.newNameBox);
			this.addRenderableWidget(Button.builder(Component.translatable("waybettercoppergolem.editor.create"),
							button -> confirmCreate())
					.bounds(this.leftX + headerWidth - 46, y, 46, 20).build());
		} else {
			String previous = this.searchBox == null ? "" : this.searchBox.getValue();
			this.searchBox = new EditBox(this.font, this.leftX, y, headerWidth, 18,
					Component.translatable("waybettercoppergolem.editor.search"));
			this.searchBox.setValue(previous);
			this.searchBox.setResponder(text -> {
				this.scrollRow = 0;
				refreshShown();
			});
			this.addRenderableWidget(this.searchBox);
		}
		y += 22;
		this.contentY = y;

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.leftX, this.contentY + GRID_HEIGHT + 18, headerWidth, 20).build());

		if (this.tabSections.isEmpty()) {
			this.tabSections = buildTabSections();
		}
		rebuildSections();
		if (this.categories.isEmpty() && connected()) {
			ClientPlayNetworking.send(new CategoryPayloads.ListRequest());
		}
	}

	/**
	 * One section per creative tab, in creative's own order. Anything no tab
	 * claims (modded oddities, technical items) lands in a final catch-all
	 * section so nothing is unreachable.
	 */
	private List<Section> buildTabSections() {
		List<Section> built = new ArrayList<>();
		Set<Item> seen = new HashSet<>();
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level != null) {
			// Tab contents are built lazily, normally when the creative screen
			// first opens; without this every tab reads as empty.
			CreativeModeTabs.tryRebuildTabContents(client.level.enabledFeatures(),
					client.player != null && client.player.canUseGameMasterBlocks(),
					client.level.registryAccess());
		}
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
				built.add(new Section(tab.getDisplayName(), List.copyOf(items)));
			}
		}
		List<Item> leftovers = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (item != Items.AIR && !seen.contains(item)) {
				leftovers.add(item);
			}
		}
		if (!leftovers.isEmpty()) {
			built.add(new Section(Component.translatable("waybettercoppergolem.editor.other_items"),
					List.copyOf(leftovers)));
		}
		return List.copyOf(built);
	}

	/** Rebuilds the section list, whose first entry tracks category membership. */
	private void rebuildSections() {
		List<Item> inCategory = new ArrayList<>();
		if (selectedEntry() != null) {
			for (Item item : BuiltInRegistries.ITEM) {
				if (item != Items.AIR && isInCategory(item)) {
					inCategory.add(item);
				}
			}
		}
		List<Section> built = new ArrayList<>();
		built.add(new Section(Component.translatable("waybettercoppergolem.editor.in_this_category"),
				List.copyOf(inCategory)));
		built.addAll(this.tabSections);
		this.sections = List.copyOf(built);
		// Shrink the rows just enough to show every section at once; only an
		// unusually modded game with many tabs falls back to scrolling.
		this.sectionRow = Math.clamp(GRID_HEIGHT / Math.max(1, this.sections.size()),
				SECTION_ROW_MIN, SECTION_ROW_MAX);
		this.selectedSection = Math.clamp(this.selectedSection, 0, this.sections.size() - 1);
		refreshShown();
	}

	/** What the grid displays: search results across everything, or one section. */
	private void refreshShown() {
		String query = this.searchBox == null ? "" : this.searchBox.getValue().toLowerCase(Locale.ROOT).trim();
		if (!query.isEmpty()) {
			List<Item> results = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item != Items.AIR && matchesQuery(item, query)) {
					results.add(item);
				}
			}
			this.shown = List.copyOf(results);
		} else {
			this.shown = this.sections.isEmpty() ? List.of() : this.sections.get(this.selectedSection).items();
		}
		this.scrollRow = Math.clamp(this.scrollRow, 0, maxScroll());
	}

	private static boolean matchesQuery(Item item, String query) {
		return item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT).contains(query)
				|| BuiltInRegistries.ITEM.getKey(item).toString().contains(query);
	}

	private boolean searching() {
		return this.searchBox != null && !this.searchBox.getValue().isBlank();
	}

	private int totalRows() {
		return (this.shown.size() + COLS - 1) / COLS;
	}

	private int maxScroll() {
		return Math.max(0, totalRows() - VISIBLE_ROWS);
	}

	private CategoryPayloads.@Nullable Entry selectedEntry() {
		return this.categories.isEmpty() ? null
				: this.categories.get(Math.clamp(this.selectedCategory, 0, this.categories.size() - 1));
	}

	private Component selectedName() {
		CategoryPayloads.Entry entry = selectedEntry();
		return entry == null ? Component.translatable("waybettercoppergolem.editor.no_categories") : entry.name();
	}

	/** Called by the client packet handler when the category list arrives. */
	public void acceptList(CategoryPayloads.ListSync sync) {
		Identifier previous = selectedEntry() == null ? null : selectedEntry().id();
		this.categories = sync.entries();
		this.selectedCategory = 0;
		if (previous != null) {
			for (int i = 0; i < this.categories.size(); i++) {
				if (this.categories.get(i).id().equals(previous)) {
					this.selectedCategory = i;
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
			rebuildSections();
		}
	}

	/** Packets only go out while actually connected; init() also runs on resize. */
	private boolean connected() {
		return this.minecraft != null && this.minecraft.getConnection() != null;
	}

	private void requestOverrides() {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry != null && connected()) {
			this.added = Set.of();
			this.removed = Set.of();
			ClientPlayNetworking.send(new CategoryPayloads.Query(entry.id()));
		}
	}

	private void selectCategory(int index) {
		this.selectedCategory = index;
		this.dropdownOpen = false;
		this.selectedSection = SECTION_IN_CATEGORY;
		this.scrollRow = 0;
		requestOverrides();
		rebuildWidgets();
	}

	private void selectSection(int index) {
		this.selectedSection = index;
		this.scrollRow = 0;
		if (this.searchBox != null) {
			this.searchBox.setValue("");
		}
		refreshShown();
	}

	private void startCreating() {
		this.creating = true;
		this.dropdownOpen = false;
		rebuildWidgets();
	}

	private void confirmCreate() {
		if (this.newNameBox != null && !this.newNameBox.getValue().isBlank() && connected()) {
			ClientPlayNetworking.send(new CategoryPayloads.Create(this.newNameBox.getValue()));
		}
		this.creating = false;
		rebuildWidgets();
	}

	private void deleteSelected() {
		CategoryPayloads.Entry entry = selectedEntry();
		if (entry != null && entry.custom() && connected()) {
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

	private @Nullable Item itemAt(double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - this.gridX) / CELL);
		int row = (int) Math.floor((mouseY - this.contentY) / CELL);
		if (col < 0 || col >= COLS || row < 0 || row >= VISIBLE_ROWS) {
			return null;
		}
		int index = (this.scrollRow + row) * COLS + col;
		return index < this.shown.size() ? this.shown.get(index) : null;
	}

	private int sectionAt(double mouseX, double mouseY) {
		if (mouseX < this.leftX || mouseX > this.leftX + SECTION_WIDTH) {
			return -1;
		}
		int row = (int) Math.floor((mouseY - this.contentY) / this.sectionRow);
		int index = this.sectionScroll + row;
		return row < 0 || row >= visibleSectionRows() || index >= this.sections.size() ? -1 : index;
	}

	private int visibleSectionRows() {
		return GRID_HEIGHT / this.sectionRow;
	}

	private int scrollbarX() {
		return this.gridX + GRID_WIDTH;
	}

	private int thumbHeight() {
		if (totalRows() <= VISIBLE_ROWS) {
			return GRID_HEIGHT;
		}
		return Math.max(12, GRID_HEIGHT * VISIBLE_ROWS / totalRows());
	}

	private int thumbY() {
		if (maxScroll() == 0) {
			return this.contentY;
		}
		return this.contentY + (GRID_HEIGHT - thumbHeight()) * this.scrollRow / maxScroll();
	}

	private void scrollToThumb(double mouseY) {
		int travel = GRID_HEIGHT - thumbHeight();
		if (travel <= 0) {
			return;
		}
		double fraction = (mouseY - this.contentY - thumbHeight() / 2.0) / travel;
		this.scrollRow = Math.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.dropdownOpen && event.button() == 0) {
			int top = this.dropdownButton.getY() + this.dropdownButton.getHeight();
			int index = (int) Math.floor((event.y() - top - 1) / 12.0);
			int shownRows = Math.min(this.categories.size(), DROPDOWN_ROWS);
			if (event.x() >= this.leftX && event.x() <= this.leftX + this.dropdownButton.getWidth()
					&& index >= 0 && index < shownRows) {
				selectCategory(index);
			} else {
				this.dropdownOpen = false;
				rebuildWidgets();
			}
			return true;
		}
		if (event.button() == 0) {
			int section = sectionAt(event.x(), event.y());
			if (section >= 0) {
				selectSection(section);
				return true;
			}
			if (event.x() >= scrollbarX() && event.x() <= scrollbarX() + SCROLLBAR_WIDTH
					&& event.y() >= this.contentY && event.y() <= this.contentY + GRID_HEIGHT) {
				this.draggingThumb = true;
				scrollToThumb(event.y());
				return true;
			}
			Item clicked = itemAt(event.x(), event.y());
			if (clicked != null) {
				CategoryPayloads.Entry entry = selectedEntry();
				if (entry != null && connected()) {
					ClientPlayNetworking.send(new CategoryPayloads.Toggle(
							entry.id(), BuiltInRegistries.ITEM.getKey(clicked)));
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.draggingThumb) {
			scrollToThumb(event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.draggingThumb = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int step = (int) Math.signum(vertical);
		if (mouseX >= this.leftX && mouseX <= this.leftX + SECTION_WIDTH) {
			int maxSectionScroll = Math.max(0, this.sections.size() - visibleSectionRows());
			this.sectionScroll = Math.clamp(this.sectionScroll - step, 0, maxSectionScroll);
		} else {
			this.scrollRow = Math.clamp(this.scrollRow - step, 0, maxScroll());
		}
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title,
				this.leftX + (SECTION_WIDTH + GAP + GRID_WIDTH) / 2, this.topY, TEXT);

		renderSectionList(graphics, mouseX, mouseY);
		renderGrid(graphics, mouseX, mouseY);
		renderFooter(graphics, mouseX, mouseY);
		renderDropdown(graphics, mouseX, mouseY);
	}

	private void renderSectionList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(this.leftX, this.contentY, this.leftX + SECTION_WIDTH, this.contentY + GRID_HEIGHT, SECTION_BG);
		int hovered = this.dropdownOpen ? -1 : sectionAt(mouseX, mouseY);
		for (int row = 0; row < visibleSectionRows(); row++) {
			int index = this.sectionScroll + row;
			if (index >= this.sections.size()) {
				break;
			}
			Section section = this.sections.get(index);
			int rowY = this.contentY + row * this.sectionRow;
			if (index == this.selectedSection && !searching()) {
				graphics.fill(this.leftX, rowY, this.leftX + SECTION_WIDTH, rowY + this.sectionRow, SECTION_SELECTED);
			} else if (index == hovered) {
				graphics.fill(this.leftX, rowY, this.leftX + SECTION_WIDTH, rowY + this.sectionRow, SECTION_HOVER);
			}
			int color = index == SECTION_IN_CATEGORY ? ACCENT : (searching() ? TEXT_DIM : TEXT);
			String label = section.title().getString();
			String count = " (" + section.items().size() + ")";
			int room = SECTION_WIDTH - 6 - this.font.width(count);
			graphics.text(this.font, this.font.plainSubstrByWidth(label, room) + count,
					this.leftX + 3, rowY + (this.sectionRow - 8) / 2, color);
		}
	}

	private void renderGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(this.gridX, this.contentY, this.gridX + GRID_WIDTH, this.contentY + GRID_HEIGHT, PANEL_BG);
		Item hovered = this.dropdownOpen ? null : itemAt(mouseX, mouseY);

		if (this.shown.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable(searching()
							? "waybettercoppergolem.editor.no_results"
							: "waybettercoppergolem.editor.empty_category"),
					this.gridX + GRID_WIDTH / 2, this.contentY + GRID_HEIGHT / 2 - 4, TEXT_DIM);
		}

		for (int row = 0; row < VISIBLE_ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				int index = (this.scrollRow + row) * COLS + col;
				if (index >= this.shown.size()) {
					break;
				}
				Item item = this.shown.get(index);
				int x = this.gridX + col * CELL;
				int y = this.contentY + row * CELL;
				// In the "In this category" view everything shown is a member,
				// so the green would just tint the whole grid.
				boolean markMembership = searching() || this.selectedSection != SECTION_IN_CATEGORY;
				if (markMembership && isInCategory(item)) {
					graphics.fill(x, y, x + CELL, y + CELL, IN_CATEGORY_BG);
				}
				Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
				if (this.added.contains(itemId)) {
					graphics.fill(x + CELL - 4, y, x + CELL, y + 4, ADDED_MARK);
				} else if (this.removed.contains(itemId)) {
					graphics.fill(x + CELL - 4, y, x + CELL, y + 4, REMOVED_MARK);
				}
				if (item == hovered) {
					graphics.fill(x, y, x + CELL, y + CELL, HOVER_BG);
				}
				graphics.item(new ItemStack(item), x + 1, y + 1);
			}
		}

		graphics.fill(scrollbarX(), this.contentY, scrollbarX() + SCROLLBAR_WIDTH,
				this.contentY + GRID_HEIGHT, SCROLL_TRACK);
		graphics.fill(scrollbarX(), thumbY(), scrollbarX() + SCROLLBAR_WIDTH,
				thumbY() + thumbHeight(), SCROLL_THUMB);
	}

	private void renderFooter(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int centerX = this.leftX + (SECTION_WIDTH + GAP + GRID_WIDTH) / 2;
		int footerY = this.contentY + GRID_HEIGHT + 6;
		Item hovered = this.dropdownOpen ? null : itemAt(mouseX, mouseY);
		if (hovered != null) {
			graphics.centeredText(this.font, Component.translatable(
							isInCategory(hovered) ? "waybettercoppergolem.editor.in_category"
									: "waybettercoppergolem.editor.not_in_category",
							hovered.getName(hovered.getDefaultInstance())),
					centerX, footerY, TEXT);
		} else if (this.creating) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.editor.new_hint"),
					centerX, footerY, TEXT_DIM);
		} else if (searching()) {
			graphics.centeredText(this.font, Component.translatable(
					"waybettercoppergolem.editor.showing_search", this.shown.size()), centerX, footerY, TEXT_DIM);
		} else if (!this.sections.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("waybettercoppergolem.editor.showing_section",
							this.sections.get(this.selectedSection).title(), this.shown.size()),
					centerX, footerY, TEXT_DIM);
		}
	}

	private void renderDropdown(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!this.dropdownOpen || this.categories.isEmpty()) {
			return;
		}
		int top = this.dropdownButton.getY() + this.dropdownButton.getHeight();
		int dropWidth = this.dropdownButton.getWidth();
		int shownRows = Math.min(this.categories.size(), DROPDOWN_ROWS);
		graphics.fill(this.leftX, top, this.leftX + dropWidth, top + shownRows * 12 + 2, DROPDOWN_BG);
		for (int i = 0; i < shownRows; i++) {
			int entryY = top + 1 + i * 12;
			if (mouseX >= this.leftX && mouseX <= this.leftX + dropWidth
					&& mouseY >= entryY && mouseY < entryY + 12) {
				graphics.fill(this.leftX, entryY, this.leftX + dropWidth, entryY + 12, SECTION_HOVER);
			}
			graphics.text(this.font, this.categories.get(i).name(), this.leftX + 3, entryY + 2,
					i == this.selectedCategory ? ACCENT : TEXT);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
