package io.github.lnasser11.waybettercoppergolem.learn;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a storage room and proposes a label for every chest from what it
 * already contains. Pure inference; nothing is written here.
 *
 * <p>Per chest (double chests counted once):
 * <ol>
 *   <li>empty → skipped;</li>
 *   <li>one kind of item → that exact item;</li>
 *   <li>several kinds → the narrowest tag or preset category that covers
 *       all of them (honoring the world's category tuning);</li>
 *   <li>nothing covers all of them and there are at least three kinds →
 *       retry once with each single kind left out; the narrowest result
 *       wins and the left-out kind is reported as misplaced;</li>
 *   <li>still nothing → skipped as mixed.</li>
 * </ol>
 * Catch-all and off-limits are never inferred. Chests with explicit labels
 * are skipped unless {@code overwrite} is set.
 */
public final class RoomLearner {
	/** Hard cap so a huge radius cannot stall the server. */
	private static final int MAX_CHESTS = 2000;

	public enum SkipReason {
		EMPTY, MIXED, EXPLICIT
	}

	public record Proposal(BlockPos pos, ChestLabel label, ChestLabelSet current, List<Item> misplaced) {
	}

	public record Skipped(BlockPos pos, SkipReason reason) {
	}

	public record Report(BlockPos center, int radius, List<Proposal> proposals, List<Skipped> skipped,
			boolean truncated) {
		public boolean isEmpty() {
			return proposals.isEmpty() && skipped.isEmpty();
		}
	}

	private RoomLearner() {
	}

	public static Report scan(ServerLevel level, BlockPos center, int radius, boolean overwrite) {
		List<Proposal> proposals = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();
		boolean truncated = false;
		int seen = 0;
		AABB area = new AABB(center).inflate(radius, radius, radius);
		Set<BlockPos> handled = new HashSet<>();

		List<BlockPos> chests = new ArrayList<>();
		for (ChunkPos chunkPos : ChunkPos.rangeClosed(ChunkPos.containing(center), Math.floorDiv(radius, 16) + 1).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)
						|| !ChestLabels.isLabelableChest(blockEntity.getBlockState())) {
					continue;
				}
				BlockPos pos = blockEntity.getBlockPos();
				if (area.contains(pos.getX(), pos.getY(), pos.getZ())) {
					chests.add(pos);
				}
			}
		}
		chests.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)));

		for (BlockPos pos : chests) {
			BlockState state = level.getBlockState(pos);
			BlockPos canonical = canonicalHalf(pos, state);
			if (!handled.add(canonical)) {
				continue; // other half of a double chest already handled
			}
			if (++seen > MAX_CHESTS) {
				truncated = true;
				break;
			}
			ChestLabelSet current = ChestLabels.effectiveLabelSet(level, canonical, state);
			if (current.explicit() && !overwrite) {
				skipped.add(new Skipped(canonical, SkipReason.EXPLICIT));
				continue;
			}
			Map<Item, Integer> counts = countContents(level, canonical);
			if (counts.isEmpty()) {
				skipped.add(new Skipped(canonical, SkipReason.EMPTY));
				continue;
			}
			Optional<Inference> inferred = infer(level, counts);
			if (inferred.isEmpty()) {
				skipped.add(new Skipped(canonical, SkipReason.MIXED));
				continue;
			}
			proposals.add(new Proposal(canonical, inferred.get().label(), current, inferred.get().misplaced()));
		}
		return new Report(center, radius, List.copyOf(proposals), List.copyOf(skipped), truncated);
	}

	/** One inferred label: {@code breadth} is 0 for exact, else the tag's member count. */
	public record Inference(ChestLabel label, int breadth, List<Item> misplaced) {
	}

	/** Label inference for one chest's contents; see the class comment. */
	public static Optional<Inference> infer(ServerLevel level, Map<Item, Integer> counts) {
		Optional<Inference> direct = inferCovering(level, counts, List.of());
		if (direct.isPresent() || counts.size() < 3) {
			return direct;
		}
		Inference best = null;
		for (Item outlier : counts.keySet()) {
			Map<Item, Integer> rest = new LinkedHashMap<>(counts);
			rest.remove(outlier);
			Optional<Inference> attempt = inferCovering(level, rest, List.of(outlier));
			if (attempt.isPresent() && (best == null || attempt.get().breadth() < best.breadth())) {
				best = attempt.get();
			}
		}
		return Optional.ofNullable(best);
	}

	private static Optional<Inference> inferCovering(ServerLevel level, Map<Item, Integer> counts, List<Item> misplaced) {
		if (counts.isEmpty()) {
			return Optional.empty();
		}
		Item sample = mostCommon(counts);
		Identifier sampleId = BuiltInRegistries.ITEM.getKey(sample);
		if (counts.size() == 1) {
			return Optional.of(new Inference(ChestLabel.exact(sampleId), 0, misplaced));
		}
		Set<TagKey<Item>> candidates = new LinkedHashSet<>();
		for (Item item : counts.keySet()) {
			candidates.addAll(LabelResolver.orderedTags(item));
		}
		List<TagKey<Item>> ordered = new ArrayList<>(candidates);
		ordered.sort(LabelResolver.NARROW_TO_BROAD);
		for (TagKey<Item> tag : ordered) {
			Identifier tagId = tag.location();
			boolean coversAll = counts.keySet().stream()
					.allMatch(item -> CategoryTuning.matches(level, tagId, new ItemStack(item)));
			if (coversAll) {
				return Optional.of(new Inference(ChestLabel.tag(sampleId, tagId),
						Math.max(1, LabelResolver.tagSize(tag)), misplaced));
			}
		}
		return Optional.empty();
	}

	private static Item mostCommon(Map<Item, Integer> counts) {
		return counts.entrySet().stream()
				.max(Comparator.comparingInt(Map.Entry<Item, Integer>::getValue))
				.orElseThrow().getKey();
	}

	/** Item → total count across the whole (possibly double) chest, insertion-ordered. */
	public static Map<Item, Integer> countContents(ServerLevel level, BlockPos pos) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		Container container = ChestLabels.container(level, pos);
		if (container != null) {
			for (ItemStack stack : container) {
				if (!stack.isEmpty()) {
					counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
				}
			}
		}
		return counts;
	}

	/** The half of a double chest that stands for the whole container. */
	public static BlockPos canonicalHalf(BlockPos pos, BlockState state) {
		if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) == ChestType.SINGLE) {
			return pos;
		}
		BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
		return pos.compareTo(other) <= 0 ? pos : other;
	}

	/** True when the position still holds a chest whose labels learn may write. */
	public static boolean stillApplicable(ServerLevel level, BlockPos pos, boolean overwrite) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!ChestLabels.isLabelableChest(state)) {
			return false;
		}
		if (overwrite) {
			return true;
		}
		@Nullable ChestLabelSet current = ChestLabels.effectiveLabelSet(level, pos, state);
		return !current.explicit();
	}
}
