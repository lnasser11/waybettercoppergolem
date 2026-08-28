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
				.sorted(Comparator
						.comparingInt(LabelResolver::tagSize)
						.thenComparing((TagKey<Item> tag) -> tag.location().getPath().split("/").length,
								Comparator.reverseOrder())
						.thenComparing(tag -> tag.location().toString()))
				.toList());
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

	private static int tagSize(TagKey<Item> tag) {
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
	 * specific. Exact item = 0, tag labels = tag member count, catch-all =
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
		return Math.max(1, tagSize(itemTag(label.tagId().get())));
	}

	/** Friendly name for a category tag: lang entry for wbcg, "#id" otherwise. */
	public static Component tagName(Identifier tagId) {
		if (tagId.getNamespace().equals(CATEGORY_NAMESPACE)) {
			return Component.translatable("waybettercoppergolem.category." + tagId.getPath());
		}
		return Component.literal("#" + tagId);
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
