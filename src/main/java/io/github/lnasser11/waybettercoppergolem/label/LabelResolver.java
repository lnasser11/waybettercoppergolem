package io.github.lnasser11.waybettercoppergolem.label;

import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves items to their conventional ({@code c:} namespace) item tags and
 * evaluates {@link ChestLabel} matches. Tag lists are cached per item and the
 * cache is cleared when datapack tags reload.
 */
public final class LabelResolver {
	/** Anything at or above this is "broader than any real tag" when ranking. */
	public static final int CATCH_ALL_SPECIFICITY = Integer.MAX_VALUE - 1;
	public static final int NO_MATCH = Integer.MAX_VALUE;

	private static final String CONVENTIONAL_NAMESPACE = "c";
	private static final Map<Item, List<TagKey<Item>>> TAG_CACHE = new ConcurrentHashMap<>();

	private LabelResolver() {
	}

	public static void invalidateCaches() {
		TAG_CACHE.clear();
	}

	/**
	 * The {@code c:} tags this item belongs to, ordered narrow to broad:
	 * fewest member items first, deeper tag paths breaking ties
	 * (e.g. {@code c:ingots/iron} before {@code c:ingots}).
	 */
	public static List<TagKey<Item>> orderedTags(Item item) {
		return TAG_CACHE.computeIfAbsent(item, it -> it.builtInRegistryHolder().tags()
				.filter(tag -> tag.location().getNamespace().equals(CONVENTIONAL_NAMESPACE))
				.sorted(Comparator
						.comparingInt(LabelResolver::tagSize)
						.thenComparing((TagKey<Item> tag) -> tag.location().getPath().split("/").length,
								Comparator.reverseOrder())
						.thenComparing(tag -> tag.location().toString()))
				.toList());
	}

	private static int tagSize(TagKey<Item> tag) {
		return BuiltInRegistries.ITEM.get(tag).map(HolderSet.Named::size).orElse(0);
	}

	/** Number of cycle stops for a framed item: exact + one per conventional tag. */
	public static int cycleLength(Item item) {
		return 1 + orderedTags(item).size();
	}

	/**
	 * Whether {@code stack} belongs to the category {@code label} declares.
	 * Catch-all labels match nothing here; their fallback role is handled by
	 * the destination ranking, not by direct matching.
	 */
	public static boolean matches(ChestLabel label, ItemStack stack) {
		if (label.isCatchAll() || stack.isEmpty()) {
			return false;
		}
		Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
		int level = clampLevel(label, labelItem);
		if (level == 0) {
			return stack.is(labelItem);
		}
		return stack.is(orderedTags(labelItem).get(level - 1));
	}

	/**
	 * Rank of this label as a destination for {@code stack}: lower is more
	 * specific. Exact item = 0, tag labels = tag member count, catch-all =
	 * {@link #CATCH_ALL_SPECIFICITY}, no match = {@link #NO_MATCH}.
	 */
	public static int specificity(ChestLabel label, ItemStack stack) {
		if (label.isCatchAll()) {
			return CATCH_ALL_SPECIFICITY;
		}
		if (!matches(label, stack)) {
			return NO_MATCH;
		}
		Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
		int level = clampLevel(label, labelItem);
		return level == 0 ? 0 : Math.max(1, tagSize(orderedTags(labelItem).get(level - 1)));
	}

	private static int clampLevel(ChestLabel label, Item labelItem) {
		return Math.clamp(label.expansionLevel(), 0, orderedTags(labelItem).size());
	}

	/** Actionbar text describing a label, e.g. "Label: #c:ingots/iron". */
	public static Component describe(ChestLabel label) {
		if (label.isOffLimits()) {
			return Component.translatable("waybettercoppergolem.label.off_limits");
		}
		if (label.isCatchAll()) {
			return Component.translatable("waybettercoppergolem.label.catch_all");
		}
		Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
		int level = clampLevel(label, labelItem);
		if (level == 0) {
			return Component.translatable("waybettercoppergolem.label.exact", labelItem.getName(labelItem.getDefaultInstance()));
		}
		Identifier tagId = orderedTags(labelItem).get(level - 1).location();
		return Component.translatable("waybettercoppergolem.label.tag", "#" + tagId);
	}

	/** Compact name for one label, used in the multi-label summary line. */
	public static Component shortName(ChestLabel label) {
		if (label.isOffLimits()) {
			return Component.translatable("waybettercoppergolem.label.short.off_limits");
		}
		if (label.isCatchAll()) {
			return Component.translatable("waybettercoppergolem.label.short.catch_all");
		}
		Item labelItem = BuiltInRegistries.ITEM.getValue(label.itemId().orElseThrow());
		int level = clampLevel(label, labelItem);
		if (level == 0) {
			return labelItem.getName(labelItem.getDefaultInstance());
		}
		return Component.literal("#" + orderedTags(labelItem).get(level - 1).location());
	}
}
