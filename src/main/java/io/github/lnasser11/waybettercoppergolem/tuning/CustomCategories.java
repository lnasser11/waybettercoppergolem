package io.github.lnasser11.waybettercoppergolem.tuning;

import com.mojang.serialization.Codec;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Categories players invent themselves, stored per world as id to display
 * name. They have no backing item tag: membership comes entirely from
 * {@link CategoryTuning}'s added-items overrides, which is why
 * {@code stack.is(tag)} returning false for a non-existent tag is exactly
 * the behavior we want.
 */
public final class CustomCategories {
	public static final String NAMESPACE = "wbcg_custom";
	public static final int MAX_NAME_LENGTH = 32;
	private static final int MAX_CATEGORIES = 64;

	public static final Codec<Map<Identifier, String>> CODEC =
			Codec.unboundedMap(Identifier.CODEC, Codec.STRING);

	private CustomCategories() {
	}

	public static boolean isCustom(Identifier id) {
		return id.getNamespace().equals(NAMESPACE);
	}

	public static Map<Identifier, String> all(ServerLevel level) {
		Map<Identifier, String> stored =
				level.getServer().overworld().getAttached(WayBetterCopperGolem.CUSTOM_CATEGORIES);
		return stored == null ? Map.of() : stored;
	}

	public static @org.jspecify.annotations.Nullable String nameOf(ServerLevel level, Identifier id) {
		return all(level).get(id);
	}

	/**
	 * Creates a category from a player-typed name, deriving a stable id from
	 * it. Returns the new id, or null if the name is unusable or a category
	 * with that id already exists.
	 */
	public static @org.jspecify.annotations.Nullable Identifier create(ServerLevel level, String displayName) {
		String trimmed = displayName.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
			return null;
		}
		String path = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
				.replaceAll("^_+|_+$", "");
		if (path.isEmpty()) {
			return null;
		}
		Map<Identifier, String> current = all(level);
		if (current.size() >= MAX_CATEGORIES) {
			return null;
		}
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, path);
		if (current.containsKey(id)) {
			return null;
		}
		Map<Identifier, String> updated = new HashMap<>(current);
		updated.put(id, trimmed);
		level.getServer().overworld().setAttached(WayBetterCopperGolem.CUSTOM_CATEGORIES, Map.copyOf(updated));
		return id;
	}

	/** Removes a custom category and the item memberships that defined it. */
	public static boolean delete(ServerLevel level, Identifier id) {
		Map<Identifier, String> current = all(level);
		if (!current.containsKey(id)) {
			return false;
		}
		Map<Identifier, String> updated = new HashMap<>(current);
		updated.remove(id);
		if (updated.isEmpty()) {
			level.getServer().overworld().removeAttached(WayBetterCopperGolem.CUSTOM_CATEGORIES);
		} else {
			level.getServer().overworld().setAttached(WayBetterCopperGolem.CUSTOM_CATEGORIES, Map.copyOf(updated));
		}
		CategoryTuning.reset(level, id);
		return true;
	}

	/** Custom categories that currently contain this item. */
	public static List<Identifier> containing(ServerLevel level, net.minecraft.world.item.ItemStack stack) {
		return all(level).keySet().stream()
				.filter(id -> CategoryTuning.matches(level, id, stack))
				.sorted(java.util.Comparator.comparing(Identifier::getPath))
				.toList();
	}
}
