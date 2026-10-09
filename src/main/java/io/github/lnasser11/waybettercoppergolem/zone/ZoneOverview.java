package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Entry;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the zone overview: every chest in the zone's box with its labels
 * and what is wrong with it, problems first, then nearest to the anchor.
 */
public final class ZoneOverview {
	private static final int MAX_ENTRIES = 300;

	private record Chests(List<BlockPos> regular, int copper) {
	}

	private ZoneOverview() {
	}

	public static void send(ServerPlayer player, ServerLevel level, Zones.ZoneRef zone) {
		if (ServerPlayNetworking.canSend(player, ZonePayloads.Overview.TYPE)) {
			ServerPlayNetworking.send(player, build(level, zone, ZoneAccess.canEdit(zone, player)
					|| !io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().labelsRequireZoneOwnership()));
		}
	}

	public static ZonePayloads.Overview build(ServerLevel level, Zones.ZoneRef zone) {
		return build(level, zone, true);
	}

	public static ZonePayloads.Overview build(ServerLevel level, Zones.ZoneRef zone, boolean canEdit) {
		Chests chests = chestsIn(level, zone.area());
		List<Entry> entries = new ArrayList<>();
		Map<List<ChestLabel>, Integer> labelUses = new HashMap<>();
		List<Object[]> raw = new ArrayList<>();
		for (BlockPos pos : chests.regular()) {
			BlockState state = level.getBlockState(pos);
			ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, pos, state);
			if (!labels.isEmpty() && !labels.isOffLimits() && labels.labels().stream().noneMatch(ChestLabel::isCatchAll)) {
				labelUses.merge(labels.labels(), 1, Integer::sum);
			}
			raw.add(new Object[] {pos, labels});
		}
		for (Object[] item : raw) {
			BlockPos pos = (BlockPos) item[0];
			ChestLabelSet labels = (ChestLabelSet) item[1];
			List<String> problems = new ArrayList<>();
			int stacks = 0;
			boolean misplaced = false;
			Container container = ChestLabels.container(level, pos);
			if (container != null) {
				for (ItemStack stack : container) {
					if (stack.isEmpty()) {
						continue;
					}
					stacks++;
					if (!misplaced && !labels.isEmpty() && !labels.isOffLimits()
							&& labels.labels().stream().noneMatch(ChestLabel::isCatchAll)
							&& labels.labels().stream().noneMatch(label -> LabelResolver.matches(level, label, stack))) {
						misplaced = true;
					}
				}
			}
			if (labels.isEmpty()) {
				problems.add("unlabeled");
			}
			if (misplaced) {
				problems.add("misplaced");
			}
			if (!labels.isEmpty() && labelUses.getOrDefault(labels.labels(), 0) > 1) {
				problems.add("duplicate");
			}
			if (stacks == 0) {
				problems.add("empty");
			}
			entries.add(new Entry(pos, labels, problems, stacks));
		}
		BlockPos anchor = zone.anchor();
		entries.sort(Comparator.comparingInt(ZoneOverview::priority)
				.thenComparingDouble((Entry entry) -> entry.pos().distSqr(anchor)));
		if (entries.size() > MAX_ENTRIES) {
			entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
		}
		int tidyMoves = io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.planTidy(level, Set.of(),
				Zones.toAABB(zone.area()), io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize()).size();
		return new ZonePayloads.Overview(anchor, zone.area(), chests.copper(), entries, canEdit, tidyMoves);
	}

	private static int priority(Entry entry) {
		if (entry.problems().contains("unlabeled")) {
			return 0;
		}
		if (entry.problems().contains("misplaced")) {
			return 1;
		}
		if (entry.problems().contains("duplicate")) {
			return 2;
		}
		return 3;
	}

	/** Regular chests (one position per double chest) and the number of copper chests in the box. */
	private static Chests chestsIn(ServerLevel level, BoundingBox box) {
		List<BlockPos> regular = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		int copper = 0;
		ChunkPos minChunk = ChunkPos.containing(new BlockPos(box.minX(), box.minY(), box.minZ()));
		ChunkPos maxChunk = ChunkPos.containing(new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
		for (ChunkPos chunkPos : ChunkPos.rangeClosed(minChunk, maxChunk).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity) || !box.isInside(blockEntity.getBlockPos())) {
					continue;
				}
				BlockState state = blockEntity.getBlockState();
				if (state.is(BlockTags.COPPER_CHESTS)) {
					copper++;
				} else if (ChestLabels.isLabelableChest(state)) {
					BlockPos canonical = RoomLearner.canonicalHalf(blockEntity.getBlockPos(), state);
					if (seen.add(canonical)) {
						regular.add(canonical);
					}
				}
			}
		}
		return new Chests(regular, copper);
	}

	/** Copper chest positions inside the box (one per double chest). */
	public static List<BlockPos> copperChestsIn(ServerLevel level, BoundingBox box) {
		List<BlockPos> result = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ChunkPos minChunk = ChunkPos.containing(new BlockPos(box.minX(), box.minY(), box.minZ()));
		ChunkPos maxChunk = ChunkPos.containing(new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
		for (ChunkPos chunkPos : ChunkPos.rangeClosed(minChunk, maxChunk).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (blockEntity instanceof ChestBlockEntity && box.isInside(blockEntity.getBlockPos())
						&& blockEntity.getBlockState().is(BlockTags.COPPER_CHESTS)) {
					BlockPos canonical = RoomLearner.canonicalHalf(blockEntity.getBlockPos(), blockEntity.getBlockState());
					if (seen.add(canonical)) {
						result.add(canonical);
					}
				}
			}
		}
		return result;
	}
}
