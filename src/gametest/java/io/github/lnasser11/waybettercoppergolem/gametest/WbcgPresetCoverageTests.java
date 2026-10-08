package io.github.lnasser11.waybettercoppergolem.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every item the game registers must belong to at least one preset
 * category, so a golem always has somewhere to put it once the room is
 * labeled with the presets. The presets are the {@code wbcg:} item tags;
 * membership is decided by the mod's own {@link CategoryTuning#matches}
 * (the bound tag plus this world's tweaks), and the item list comes from
 * the live {@code minecraft:item} registry, mods included. New preset
 * files are picked up automatically through {@link LabelResolver#presetCategories()}.
 */
public final class WbcgPresetCoverageTests {
	private static final String LOG_PREFIX = "[preset-coverage]";

	/** Items that can never sit in a chest, with the reason; everything else must be covered. */
	private static final Map<Item, String> EXCLUDED = Map.of(
			Items.AIR, "minecraft:air is the empty slot, not an item that can be stored");

	// ---------------------------------------------------------------- the check

	/** Registry items (minus the exclusions) that none of {@code presets} contains. */
	static List<Identifier> uncovered(ServerLevel level, List<TagKey<Item>> presets) {
		List<Identifier> gaps = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (EXCLUDED.containsKey(item)) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			boolean covered = presets.stream().anyMatch(preset -> CategoryTuning.matches(level, preset.location(), stack));
			if (!covered) {
				gaps.add(BuiltInRegistries.ITEM.getKey(item));
			}
		}
		return gaps;
	}

	@GameTest
	public void everyItemHasAPresetCategory(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		helper.assertFalse(presets.isEmpty(), "no preset categories are bound");
		EXCLUDED.forEach((item, reason) -> WayBetterCopperGolem.LOGGER.info("{} excluded {}: {}",
				LOG_PREFIX, BuiltInRegistries.ITEM.getKey(item), reason));
		logReport(level, presets);

		List<Identifier> gaps = uncovered(level, presets);
		for (Identifier gap : gaps) {
			WayBetterCopperGolem.LOGGER.info("{} uncovered {}", LOG_PREFIX, gap);
		}
		helper.assertTrue(gaps.isEmpty(), gaps.size() + " of " + BuiltInRegistries.ITEM.size()
				+ " items belong to no preset category: " + gaps);
		helper.succeed();
	}

	/**
	 * The check itself must notice a hole: run it on an in-memory copy of the
	 * preset list with one category removed, and it must report exactly the
	 * items that category alone covered. (A category nested inside another,
	 * like stone inside building_blocks, covers nothing alone; that is fine,
	 * but at least one category must.)
	 */
	@GameTest
	public void coverageCheckNoticesAMissingCategory(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		boolean anyGap = false;
		for (TagKey<Item> dropped : presets) {
			List<TagKey<Item>> without = presets.stream().filter(preset -> !preset.equals(dropped)).toList();
			List<Identifier> expected = new ArrayList<>();
			for (Item item : BuiltInRegistries.ITEM) {
				ItemStack stack = new ItemStack(item);
				if (!EXCLUDED.containsKey(item)
						&& CategoryTuning.matches(level, dropped.location(), stack)
						&& without.stream().noneMatch(other -> CategoryTuning.matches(level, other.location(), stack))) {
					expected.add(BuiltInRegistries.ITEM.getKey(item));
				}
			}
			List<Identifier> gaps = uncovered(level, without);
			helper.assertValueEqual(gaps, expected, "items reported when " + dropped.location() + " is dropped");
			anyGap |= !gaps.isEmpty();
			WayBetterCopperGolem.LOGGER.info("{} without {}: {} items uncovered, e.g. {}",
					LOG_PREFIX, dropped.location(), gaps.size(), gaps.subList(0, Math.min(3, gaps.size())));
		}
		helper.assertTrue(anyGap, "dropping a category never left a gap, so the check cannot be catching anything");
		helper.succeed();
	}

	/** Overlapping presets resolve by the fixed priority list, never by how big they happen to be. */
	@GameTest
	public void presetsResolveByPriorityNotSize(GameTestHelper helper) {
		List<TagKey<Item>> presets = LabelResolver.presetCategories();
		for (TagKey<Item> preset : presets) {
			helper.assertTrue(LabelResolver.PRESET_PRIORITY.contains(preset.location().getPath()),
					preset.location() + " is a shipped preset but has no entry in PRESET_PRIORITY");
			helper.assertTrue(LabelResolver.rank(preset) >= LabelResolver.PRESET_RANK_BASE,
					preset.location() + " must rank below every plain tag");
		}
		java.util.Set<Integer> ranks = new java.util.HashSet<>();
		for (TagKey<Item> preset : presets) {
			helper.assertTrue(ranks.add(LabelResolver.rank(preset)), "two presets share a rank: " + preset.location());
		}
		// Redstone dust is in Redstone and in Ores & Minerals; Redstone is listed first, whatever the sizes.
		TagKey<Item> redstone = LabelResolver.itemTag(Identifier.fromNamespaceAndPath("wbcg", "redstone"));
		TagKey<Item> ores = LabelResolver.itemTag(Identifier.fromNamespaceAndPath("wbcg", "ores_and_minerals"));
		helper.assertTrue(LabelResolver.rank(redstone) < LabelResolver.rank(ores), "Redstone beats Ores & Minerals");
		helper.assertTrue(LabelResolver.rank(LabelResolver.itemTag(Identifier.fromNamespaceAndPath("c", "ingots")))
				< LabelResolver.rank(redstone), "a plain tag beats any preset");
		helper.succeed();
	}

	/** Every id and tag a preset file names must exist in this version, or it is dead weight. */
	@GameTest
	public void presetFilesNameOnlyThingsThatExist(GameTestHelper helper) {
		List<String> stale = new ArrayList<>();
		for (TagKey<Item> preset : LabelResolver.presetCategories()) {
			String path = "/data/" + preset.location().getNamespace() + "/tags/item/" + preset.location().getPath() + ".json";
			try (InputStream in = WbcgPresetCoverageTests.class.getResourceAsStream(path)) {
				if (in == null) {
					continue; // a preset supplied by a datapack rather than the mod's resources
				}
				JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonArray values = json.getAsJsonArray("values");
				List<String> seen = new ArrayList<>();
				for (JsonElement element : values) {
					String id = element.isJsonObject() ? element.getAsJsonObject().get("id").getAsString() : element.getAsString();
					if (seen.contains(id)) {
						stale.add(preset.location() + " lists " + id + " twice");
					}
					seen.add(id);
					if (id.startsWith("#")) {
						TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(id.substring(1)));
						if (BuiltInRegistries.ITEM.get(tag).isEmpty()) {
							stale.add(preset.location() + " -> " + id + " (tag does not exist)");
						}
					} else if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(id))) {
						stale.add(preset.location() + " -> " + id + " (item does not exist)");
					}
				}
			} catch (Exception e) {
				stale.add(preset.location() + ": " + e);
			}
		}
		for (String entry : stale) {
			WayBetterCopperGolem.LOGGER.info("{} stale {}", LOG_PREFIX, entry);
		}
		helper.assertTrue(stale.isEmpty(), "stale preset entries: " + stale);
		helper.succeed();
	}

	// ---------------------------------------------------------------- report lines for humans

	/** Sizes, overlaps (which category wins under the narrowest-tag rule) and per-category members. */
	private static void logReport(ServerLevel level, List<TagKey<Item>> presets) {
		for (TagKey<Item> preset : presets) {
			WayBetterCopperGolem.LOGGER.info("{} size {} = {}", LOG_PREFIX, preset.location(), LabelResolver.tagSize(preset));
		}
		Map<Identifier, List<TagKey<Item>>> membership = new TreeMap<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (EXCLUDED.containsKey(item)) {
				continue;
			}
			ItemStack stack = new ItemStack(item);
			List<TagKey<Item>> in = presets.stream()
					.filter(preset -> CategoryTuning.matches(level, preset.location(), stack)).toList();
			if (!in.isEmpty()) {
				membership.put(BuiltInRegistries.ITEM.getKey(item), in);
			}
		}
		Map<Identifier, List<Identifier>> members = new LinkedHashMap<>();
		membership.forEach((item, in) -> {
			if (in.size() > 1) {
				List<TagKey<Item>> ranked = in.stream().sorted(LabelResolver.NARROW_TO_BROAD).toList();
				boolean tie = LabelResolver.rank(ranked.get(0)) == LabelResolver.rank(ranked.get(1));
				WayBetterCopperGolem.LOGGER.info("{} overlap {} in {} wins {}{}", LOG_PREFIX, item,
						in.stream().map(tag -> tag.location().getPath()).toList(),
						ranked.get(0).location().getPath(), tie ? " (TIE)" : "");
			}
			for (TagKey<Item> tag : in) {
				members.computeIfAbsent(tag.location(), key -> new ArrayList<>()).add(item);
			}
		});
		members.forEach((tag, items) -> WayBetterCopperGolem.LOGGER.info("{} members {} = {}", LOG_PREFIX, tag, items));
	}
}
