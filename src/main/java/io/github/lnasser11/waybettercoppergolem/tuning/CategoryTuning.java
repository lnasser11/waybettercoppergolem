package io.github.lnasser11.waybettercoppergolem.tuning;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-world fine-tuning of label categories: players add or remove single
 * items from any tag-based category (sneak-click a label frame while
 * holding the item). Overrides live in a persistent attachment on the
 * overworld, apply server-wide to every chest labeled with that tag, and
 * are silently dropped by vanilla if the mod is removed. The base tag data
 * is never modified.
 */
public final class CategoryTuning {
	/** Items explicitly added to / removed from one category. */
	public record TagOverride(Set<Identifier> added, Set<Identifier> removed) {
		public static final TagOverride EMPTY = new TagOverride(Set.of(), Set.of());
		public static final Codec<TagOverride> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.listOf().optionalFieldOf("added", List.of())
						.xmap(l -> (Set<Identifier>) new HashSet<>(l), List::copyOf).forGetter(TagOverride::added),
				Identifier.CODEC.listOf().optionalFieldOf("removed", List.of())
						.xmap(l -> (Set<Identifier>) new HashSet<>(l), List::copyOf).forGetter(TagOverride::removed)
		).apply(instance, TagOverride::new));

		public boolean isEmpty() {
			return added.isEmpty() && removed.isEmpty();
		}
	}

	public static final Codec<Map<Identifier, TagOverride>> CODEC =
			Codec.unboundedMap(Identifier.CODEC, TagOverride.CODEC);

	public enum ToggleResult {
		ADDED, REMOVED
	}

	private CategoryTuning() {
	}

	private static Map<Identifier, TagOverride> overrides(ServerLevel level) {
		Map<Identifier, TagOverride> stored =
				level.getServer().overworld().getAttached(WayBetterCopperGolem.CATEGORY_OVERRIDES);
		return stored == null ? Map.of() : stored;
	}

	public static TagOverride overridesFor(ServerLevel level, Identifier tagId) {
		return overrides(level).getOrDefault(tagId, TagOverride.EMPTY);
	}

	/** Category membership: base tag, minus removed items, plus added items. */
	public static boolean matches(ServerLevel level, Identifier tagId, ItemStack stack) {
		TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
		Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
		TagOverride override = overridesFor(level, tagId);
		if (override.removed().contains(itemId)) {
			return false;
		}
		return stack.is(tag) || override.added().contains(itemId);
	}

	/**
	 * Flips {@code item}'s membership in the category: an item currently in
	 * the category gets excluded, one outside gets included. Undoing a
	 * previous tweak simply drops the override entry.
	 */
	public static ToggleResult toggle(ServerLevel level, Identifier tagId, Item item) {
		Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
		boolean inBaseTag = item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId));
		TagOverride current = overridesFor(level, tagId);
		Set<Identifier> added = new HashSet<>(current.added());
		Set<Identifier> removed = new HashSet<>(current.removed());

		ToggleResult result;
		if (removed.remove(itemId)) {
			result = ToggleResult.ADDED; // was excluded; back in
		} else if (added.remove(itemId)) {
			result = ToggleResult.REMOVED; // was custom-added; back out
		} else if (inBaseTag) {
			removed.add(itemId);
			result = ToggleResult.REMOVED;
		} else {
			added.add(itemId);
			result = ToggleResult.ADDED;
		}
		store(level, tagId, new TagOverride(added, removed));
		return result;
	}

	/** Explicitly include or exclude an item, for the /wbcg command. */
	public static void setMembership(ServerLevel level, Identifier tagId, Item item, boolean include) {
		Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
		boolean inBaseTag = item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId));
		TagOverride current = overridesFor(level, tagId);
		Set<Identifier> added = new HashSet<>(current.added());
		Set<Identifier> removed = new HashSet<>(current.removed());
		if (include) {
			removed.remove(itemId);
			if (!inBaseTag) {
				added.add(itemId);
			}
		} else {
			added.remove(itemId);
			if (inBaseTag) {
				removed.add(itemId);
			}
		}
		store(level, tagId, new TagOverride(added, removed));
	}

	public static void reset(ServerLevel level, Identifier tagId) {
		store(level, tagId, TagOverride.EMPTY);
	}

	private static void store(ServerLevel level, Identifier tagId, TagOverride override) {
		Map<Identifier, TagOverride> updated = new HashMap<>(overrides(level));
		if (override.isEmpty()) {
			updated.remove(tagId);
		} else {
			updated.put(tagId, override);
		}
		ServerLevel overworld = level.getServer().overworld();
		if (updated.isEmpty()) {
			overworld.removeAttached(WayBetterCopperGolem.CATEGORY_OVERRIDES);
		} else {
			overworld.setAttached(WayBetterCopperGolem.CATEGORY_OVERRIDES, Map.copyOf(updated));
		}
	}
}
