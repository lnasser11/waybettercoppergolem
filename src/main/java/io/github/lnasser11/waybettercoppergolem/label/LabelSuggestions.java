package io.github.lnasser11.waybettercoppergolem.label;

import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads.Suggestion;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Labels worth offering for a chest, judged by how many of its stacks they
 * would cover: the exact item of the most common kind, then every tag or
 * preset any of the contents belongs to, best coverage first and
 * narrowest first among equals. Unlike the learn pass this never refuses:
 * a mixed chest still gets its top few candidates, with the numbers.
 */
public final class LabelSuggestions {
	private static final int MAX_SUGGESTIONS = 6;

	private LabelSuggestions() {
	}

	public static List<Suggestion> forChest(ServerLevel level, BlockPos pos) {
		Container container = ChestLabels.container(level, pos);
		if (container == null) {
			return List.of();
		}
		// item → number of non-empty stacks holding it
		Map<Item, Integer> stacks = new LinkedHashMap<>();
		for (ItemStack stack : container) {
			if (!stack.isEmpty()) {
				stacks.merge(stack.getItem(), 1, Integer::sum);
			}
		}
		if (stacks.isEmpty()) {
			return List.of();
		}
		int total = stacks.values().stream().mapToInt(Integer::intValue).sum();
		Item mostCommon = stacks.entrySet().stream()
				.max(Comparator.comparingInt(Map.Entry<Item, Integer>::getValue)).orElseThrow().getKey();

		record Candidate(ChestLabel label, int covered, int breadth) {
		}
		List<Candidate> candidates = new ArrayList<>();
		candidates.add(new Candidate(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(mostCommon)),
				stacks.get(mostCommon), 0));

		Set<TagKey<Item>> tags = new LinkedHashSet<>();
		for (Item item : stacks.keySet()) {
			tags.addAll(LabelResolver.orderedTags(item));
		}
		for (TagKey<Item> tag : tags) {
			Identifier tagId = tag.location();
			int covered = 0;
			Item sample = null;
			int sampleStacks = -1;
			for (Map.Entry<Item, Integer> entry : stacks.entrySet()) {
				if (CategoryTuning.matches(level, tagId, new ItemStack(entry.getKey()))) {
					covered += entry.getValue();
					if (entry.getValue() > sampleStacks) {
						sample = entry.getKey();
						sampleStacks = entry.getValue();
					}
				}
			}
			if (covered > 0 && sample != null) {
				candidates.add(new Candidate(ChestLabel.tag(BuiltInRegistries.ITEM.getKey(sample), tagId),
						covered, Math.max(1, LabelResolver.tagSize(tag))));
			}
		}
		candidates.sort(Comparator.comparingInt(Candidate::covered).reversed()
				.thenComparingInt(Candidate::breadth));

		List<Suggestion> suggestions = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (suggestions.size() >= MAX_SUGGESTIONS) {
				break;
			}
			if (suggestions.stream().noneMatch(s -> s.label().equals(candidate.label()))) {
				suggestions.add(new Suggestion(candidate.label(), candidate.covered(), total));
			}
		}
		return suggestions;
	}
}
