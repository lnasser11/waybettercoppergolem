package io.github.lnasser11.waybettercoppergolem.label;

import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves items to the categories a label frame can cycle through, and
 * evaluates {@link ChestLabel} matches (including per-world category
 * overrides). Cycle stops for an item are, narrow to broad: its {@code c:}
 * conventional tags, a curated allowlist of content-shaped
 * {@code minecraft:} tags, and the {@code wbcg:} preset categories -
 * mechanics tags (mineable/enchantable/...) never appear.
 */
public final class LabelResolver {
	/** Anything at or above this is "broader than any real tag" when ranking. */
	public static final int CATCH_ALL_SPECIFICITY = Integer.MAX_VALUE - 1;
	public static final int NO_MATCH = Integer.MAX_VALUE;

	public static final String CATEGORY_NAMESPACE = "wbcg";
	private static final String CONVENTIONAL_NAMESPACE = "c";
	/** Content-category vanilla tags allowed as cycle stops. */
	private static final Set<String> CURATED_MINECRAFT_TAGS = Set.of(
			"logs", "planks", "wool", "wool_carpets", "saplings", "leaves", "flowers", "small_flowers",
			"signs", "hanging_signs", "boats", "beds", "candles", "banners", "rails", "arrows", "fishes",
			"doors", "trapdoors", "fences", "fence_gates", "walls", "stairs", "slabs", "buttons",
			"terracotta", "concrete", "glazed_terracotta", "coals", "lanterns", "chains", "anvil",
			"shulker_boxes", "skulls", "bundles", "meat", "decorated_pot_sherds",
			"wooden_stairs", "wooden_slabs", "wooden_doors", "wooden_fences", "wooden_trapdoors",
			"wooden_buttons", "wooden_pressure_plates");

	private static final Map<Item, List<TagKey<Item>>> TAG_CACHE = new ConcurrentHashMap<>();

	private LabelResolver() {
	}

	public static void invalidateCaches() {
		TAG_CACHE.clear();
	}

	/**
	 * The cycle stops for this item, ordered narrow to broad: fewest member
	 * items first, deeper tag paths breaking ties.
	 */
	public static List<TagKey<Item>> orderedTags(Item item) {
		return TAG_CACHE.computeIfAbsent(item, it -> it.builtInRegistryHolder().tags()
				.filter(LabelResolver::isCycleStop)
				.filter(tag -> tagSize(tag) > 1) // a one-item tag says nothing the exact item doesn't
				.sorted(NARROW_TO_BROAD)
				.toList());
	}

	/**
	 * The preset categories from most to least specific. Presets are broad
	 * and overlap on purpose (a wooden slab is Wood and a Building Block),
	 * so between two matching presets this order decides, not their size:
	 * growing a category never reshuffles where things go. Presets rank
	 * below every plain tag, which keeps a narrow tag like Ingots › Iron
	 * ahead of any preset.
	 */
	public static final List<String> PRESET_PRIORITY = List.of(
			"brewing", "combat", "farming", "mob_drops", "nether_and_end", "food", "redstone",
			"ores_and_minerals", "technical", "tools_and_gear", "decoration", "wood", "stone", "building_blocks");

	/** Rank below which only plain tags fall; presets sit at this plus their priority index. */
	public static final int PRESET_RANK_BASE = 100_000;

	/**
	 * How specific a tag is as a destination: member count for plain tags,
	 * a fixed slot by {@link #PRESET_PRIORITY} for presets (an unlisted
	 * preset, from a datapack, ranks by size after the listed ones).
	 */
	public static int rank(TagKey<Item> tag) {
		if (!isPresetCategory(tag)) {
			return Math.max(1, tagSize(tag));
		}
		int index = PRESET_PRIORITY.indexOf(tag.location().getPath());
		return PRESET_RANK_BASE + (index >= 0 ? index : PRESET_PRIORITY.size() + Math.max(1, tagSize(tag)));
	}

	/** Most specific first ({@link #rank}), deeper tag paths breaking ties, then by id. */
	public static final Comparator<TagKey<Item>> NARROW_TO_BROAD = Comparator
			.comparingInt(LabelResolver::rank)
			.thenComparing((TagKey<Item> tag) -> tag.location().getPath().split("/").length,
					Comparator.reverseOrder())
			.thenComparing(tag -> tag.location().toString());

	/** The mod's preset categories, sorted by id (needs bound tags: server, or client after login). */
	public static List<TagKey<Item>> presetCategories() {
		return BuiltInRegistries.ITEM.getTags()
				.map(named -> named.key())
				.filter(LabelResolver::isPresetCategory)
				.sorted(Comparator.comparing(tag -> tag.location().getPath()))
				.toList();
	}

	/** Whether a label (from a client, say) names things that exist. */
	public static boolean isValid(ChestLabel label) {
		if (label.itemId().isPresent() && !BuiltInRegistries.ITEM.containsKey(label.itemId().get())) {
			return false;
		}
		if (label.tagId().isPresent()) {
			return label.itemId().isPresent() && BuiltInRegistries.ITEM.get(itemTag(label.tagId().get())).isPresent();
		}
		return true;
	}

	/** Whether this is one of the mod's {@code wbcg:} preset categories. */
	public static boolean isPresetCategory(TagKey<Item> tag) {
		return tag.location().getNamespace().equals(CATEGORY_NAMESPACE);
	}

	private static boolean isCycleStop(TagKey<Item> tag) {
		String namespace = tag.location().getNamespace();
		if (namespace.equals(CONVENTIONAL_NAMESPACE) || namespace.equals(CATEGORY_NAMESPACE)) {
			return true;
		}
		return namespace.equals("minecraft") && CURATED_MINECRAFT_TAGS.contains(tag.location().getPath());
	}

	public static TagKey<Item> itemTag(Identifier tagId) {
		return TagKey.create(Registries.ITEM, tagId);
	}

	/** Number of items in the tag (0 if the tag is unknown). */
	public static int tagSize(TagKey<Item> tag) {
		return BuiltInRegistries.ITEM.get(tag).map(HolderSet.Named::size).orElse(0);
	}

	/**
	 * Whether {@code stack} belongs to the category {@code label} declares,
	 * honoring this world's category overrides. Catch-all and off-limits
	 * labels match nothing here; their roles are handled by the ranking.
	 */
	public static boolean matches(ServerLevel level, ChestLabel label, ItemStack stack) {
		if (label.isCatchAll() || label.isOffLimits() || stack.isEmpty()) {
			return false;
		}
		if (label.tagId().isEmpty()) {
			return stack.is(BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow()));
		}
		return CategoryTuning.matches(level, label.tagId().get(), stack);
	}

	/**
	 * Rank of this label as a destination for {@code stack}: lower is more
	 * specific. Exact item = 0, tag labels = {@link #rank} (member count for
	 * plain tags, the preset priority for presets), catch-all =
	 * {@link #CATCH_ALL_SPECIFICITY}, no match = {@link #NO_MATCH}.
	 */
	public static int specificity(ServerLevel level, ChestLabel label, ItemStack stack) {
		if (label.isCatchAll()) {
			return CATCH_ALL_SPECIFICITY;
		}
		if (!matches(level, label, stack)) {
			return NO_MATCH;
		}
		if (label.tagId().isEmpty()) {
			return 0;
		}
		return rank(itemTag(label.tagId().get()));
	}

	/**
	 * Friendly name for a tag: the lang entry for presets, otherwise the
	 * path humanized ({@code c:ingots/iron} → "Ingots › Iron",
	 * {@code minecraft:wooden_slabs} → "Wooden Slabs"). The raw id belongs
	 * in a tooltip, not here.
	 */
	public static Component tagName(Identifier tagId) {
		if (tagId.getNamespace().equals(CATEGORY_NAMESPACE)) {
			return Component.translatable("waybettercoppergolem.category." + tagId.getPath());
		}
		StringBuilder name = new StringBuilder();
		for (String segment : tagId.getPath().split("/")) {
			if (!name.isEmpty()) {
				name.append(" › ");
			}
			for (String word : segment.split("_")) {
				if (word.isEmpty()) {
					continue;
				}
				if (name.length() > 0 && name.charAt(name.length() - 1) != ' ') {
					name.append(' ');
				}
				name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return Component.literal(name.toString());
	}

	/** Actionbar text describing a label, e.g. "Label: #c:ingots/iron". */
	public static Component describe(ChestLabel label) {
		if (label.isOffLimits()) {
			return Component.translatable("waybettercoppergolem.label.off_limits");
		}
		if (label.isCatchAll()) {
			return Component.translatable("waybettercoppergolem.label.catch_all");
		}
		if (label.tagId().isEmpty()) {
			Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
			return Component.translatable("waybettercoppergolem.label.exact",
					labelItem.getName(labelItem.getDefaultInstance()));
		}
		return Component.translatable("waybettercoppergolem.label.tag", tagName(label.tagId().get()));
	}

	/** "Iron Ingot, catch-all": the short names of several labels joined. */
	public static Component listNames(List<ChestLabel> labels) {
		net.minecraft.network.chat.MutableComponent joined = Component.empty();
		for (int i = 0; i < labels.size(); i++) {
			if (i > 0) {
				joined.append(", ");
			}
			joined.append(shortName(labels.get(i)));
		}
		return joined;
	}

	/** Compact name for one label, used in the multi-label summary line. */
	public static Component shortName(ChestLabel label) {
		if (label.isOffLimits()) {
			return Component.translatable("waybettercoppergolem.label.short.off_limits");
		}
		if (label.isCatchAll()) {
			return Component.translatable("waybettercoppergolem.label.short.catch_all");
		}
		if (label.tagId().isEmpty()) {
			Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
			return labelItem.getName(labelItem.getDefaultInstance());
		}
		return tagName(label.tagId().get());
	}
}
