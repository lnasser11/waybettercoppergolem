package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Creative-inventory-style editor for label categories: pick a category,
 * search the item list, click items to toggle their membership. Edits go
 * through the same world-wide tuning as sneak-clicking a label frame with
 * an item in hand; overrides come back from the server per category.
 */
public class CategoryEditorScreen extends Screen {
	private static final int CELL = 18;
	private static final int COLS = 9;
	private static final int GRID_ROWS = 6;
	private static final int PANEL_WIDTH = COLS * CELL;
	private static final int IN_CATEGORY_BG = 0x6633AA55;
	private static final int ADDED_MARK = 0xFF55FF77;
	private static final int REMOVED_MARK = 0xFFFF5555;
	private static final int HOVER_BG = 0x80FFFFFF;

	private final List<Identifier> categories;
	private int selected;
	private EditBox searchBox;
	private List<Item> allItems = List.of();
	private List<Item> filtered = List.of();
	private int scrollRow;
	private Set<Identifier> added = Set.of();
	private Set<Identifier> removed = Set.of();
	private int gridX;
	private int gridY;

	public CategoryEditorScreen() {
		super(Component.translatable("waybettercoppergolem.editor.title"));
		this.categories = BuiltInRegistries.ITEM.getTags()
				.map(named -> named.key().location())
				.filter(id -> id.getNamespace().equals(LabelResolver.CATEGORY_NAMESPACE))
				.sorted(Comparator.comparing(Identifier::getPath))
				.toList();
	}

	@Override
	protected void init() {
		super.init();
		this.gridX = this.width / 2 - PANEL_WIDTH / 2;
		int y = Math.max(24, this.height / 2 - 110);

		if (!this.categories.isEmpty()) {
			Identifier initial = this.categories.get(Math.min(this.selected, this.categories.size() - 1));
			this.addRenderableWidget(CycleButton.builder(LabelResolver::tagName, initial)
					.withValues(this.categories)
					.create(this.gridX, y, PANEL_WIDTH, 20,
							Component.translatable("waybettercoppergolem.editor.category"),
							(button, value) -> selectCategory(this.categories.indexOf(value))));
		}
		y += 24;
		this.searchBox = new EditBox(this.font, this.gridX, y, PANEL_WIDTH, 18,
				Component.translatable("waybettercoppergolem.editor.search"));
		this.searchBox.setResponder(text -> refilter());
		this.addRenderableWidget(this.searchBox);
		y += 22;
		this.gridY = y;

		int doneY = this.gridY + GRID_ROWS * CELL + 8;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(this.gridX, doneY, PANEL_WIDTH, 20).build());

		if (this.allItems.isEmpty()) {
			List<Item> items = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				if (item != Items.AIR) {
					items.add(item);
				}
			}
			this.allItems = items;
		}
		refilter();
		requestOverrides();
	}

	private Identifier selectedCategory() {
		return this.categories.isEmpty() ? null : this.categories.get(this.selected);
	}

	private void selectCategory(int index) {
		if (index >= 0) {
			this.selected = index;
			this.added = Set.of();
			this.removed = Set.of();
			this.scrollRow = 0;
			requestOverrides();
		}
	}

	private void requestOverrides() {
		Identifier category = selectedCategory();
		if (category != null) {
			ClientPlayNetworking.send(new CategoryPayloads.Query(category));
		}
	}

	/** Called by the client packet handler when overrides arrive. */
	public void acceptSync(CategoryPayloads.Sync sync) {
		Identifier category = selectedCategory();
		if (category != null && category.equals(sync.tagId())) {
			this.added = new HashSet<>(sync.added());
			this.removed = new HashSet<>(sync.removed());
		}
	}

	private void refilter() {
		String query = this.searchBox == null ? "" : this.searchBox.getValue().toLowerCase(Locale.ROOT).trim();
		if (query.isEmpty()) {
			this.filtered = this.allItems;
		} else {
			List<Item> result = new ArrayList<>();
			for (Item item : this.allItems) {
				String name = item.getName(item.getDefaultInstance()).getString().toLowerCase(Locale.ROOT);
				String id = BuiltInRegistries.ITEM.getKey(item).toString();
				if (name.contains(query) || id.contains(query)) {
					result.add(item);
				}
			}
			this.filtered = result;
		}
		this.scrollRow = 0;
	}

	private boolean isInCategory(Item item) {
		Identifier category = selectedCategory();
		if (category == null) {
			return false;
		}
		Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
		if (this.removed.contains(itemId)) {
			return false;
		}
		if (this.added.contains(itemId)) {
			return true;
		}
		return item.builtInRegistryHolder().is(TagKey.create(net.minecraft.core.registries.Registries.ITEM, category));
	}

	private int slotAt(double mouseX, double mouseY) {
		int col = (int) Math.floor((mouseX - this.gridX) / CELL);
		int row = (int) Math.floor((mouseY - this.gridY) / CELL);
		if (col < 0 || col >= COLS || row < 0 || row >= GRID_ROWS) {
			return -1;
		}
		int index = (this.scrollRow + row) * COLS + col;
		return index < this.filtered.size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		int slot = slotAt(event.x(), event.y());
		if (slot >= 0 && event.button() == 0) {
			Identifier category = selectedCategory();
			if (category != null) {
				Item item = this.filtered.get(slot);
				ClientPlayNetworking.send(new CategoryPayloads.Toggle(
						category, BuiltInRegistries.ITEM.getKey(item)));
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		int totalRows = Math.max(0, (this.filtered.size() + COLS - 1) / COLS - GRID_ROWS);
		this.scrollRow = Math.clamp(this.scrollRow - (int) Math.signum(vertical), 0, Math.max(0, totalRows));
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, Math.max(8, this.gridY - 62), 0xFFFFFFFF);

		graphics.fill(this.gridX - 2, this.gridY - 2,
				this.gridX + PANEL_WIDTH + 2, this.gridY + GRID_ROWS * CELL + 2, 0x90000000);

		int hovered = slotAt(mouseX, mouseY);
		for (int row = 0; row < GRID_ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				int index = (this.scrollRow + row) * COLS + col;
				if (index >= this.filtered.size()) {
					break;
				}
				Item item = this.filtered.get(index);
				int x = this.gridX + col * CELL;
				int y = this.gridY + row * CELL;
				if (isInCategory(item)) {
					graphics.fill(x, y, x + CELL, y + CELL, IN_CATEGORY_BG);
				}
				Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
				if (this.added.contains(itemId)) {
					graphics.fill(x + CELL - 4, y, x + CELL, y + 4, ADDED_MARK);
				} else if (this.removed.contains(itemId)) {
					graphics.fill(x + CELL - 4, y, x + CELL, y + 4, REMOVED_MARK);
				}
				if (index == hovered) {
					graphics.fill(x, y, x + CELL, y + CELL, HOVER_BG);
				}
				graphics.item(new ItemStack(item), x + 1, y + 1);
			}
		}

		int footerY = this.gridY + GRID_ROWS * CELL + 34;
		if (hovered >= 0) {
			Item item = this.filtered.get(hovered);
			Component status = Component.translatable(isInCategory(item)
							? "waybettercoppergolem.editor.in_category"
							: "waybettercoppergolem.editor.not_in_category",
					item.getName(item.getDefaultInstance()));
			graphics.centeredText(this.font, status, this.width / 2, footerY, 0xFFFFFFFF);
		} else {
			graphics.centeredText(this.font,
					Component.translatable("waybettercoppergolem.editor.hint"),
					this.width / 2, footerY, 0xFFAAAAAA);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
