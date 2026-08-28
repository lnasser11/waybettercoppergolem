package io.github.lnasser11.waybettercoppergolem.sorting;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Label-aware destination selection and acceptance for copper golems.
 * Mirrors the candidate enumeration and validity rules of the vanilla
 * {@code TransportItemsBetweenContainers.getTransportTarget}, replacing
 * nearest-first with a specificity ranking:
 *
 * <ol>
 *   <li>labeled chest matching the held item, narrowest label first
 *       (exact item, then tags by member count), full chests skipped so a
 *       full narrow chest cascades to a broader one;</li>
 *   <li>catch-all chest (empty item frame);</li>
 *   <li>unlabeled chest under the vanilla rule (empty, or already contains
 *       the same item).</li>
 * </ol>
 *
 * <p>Distance breaks ties within a rank. Labeled chests never accept items
 * that match none of their labels: labels are authoritative.
 */
public final class SortingEngine {
	/** Rank for unlabeled chests: after every label, including catch-all. */
	private static final long UNLABELED_RANK = (long) LabelResolver.CATCH_ALL_SPECIFICITY + 1;

	private SortingEngine() {
	}

	public static Optional<TransportItemTarget> findDepositTarget(
			ServerLevel level, PathfinderMob golem, ItemStack held,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			int horizontalRadius, int verticalRadius) {
		AABB searchArea = new AABB(golem.blockPosition()).inflate(horizontalRadius, verticalRadius, horizontalRadius);
		TransportItemTarget best = null;
		long bestRank = Long.MAX_VALUE;
		boolean bestContainsItem = false;
		double bestDistSq = Double.MAX_VALUE;

		for (ChunkPos chunkPos : ChunkPos.rangeClosed(
				ChunkPos.containing(golem.blockPosition()), Math.floorDiv(horizontalRadius, 16) + 1).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)) {
					continue;
				}
				TransportItemTarget candidate = validCandidate(
						level, blockEntity, destinationBlockType, visited, unreachable, searchArea);
				if (candidate == null) {
					continue;
				}
				long rank = depositRank(level, candidate, held);
				if (rank == Long.MAX_VALUE) {
					continue;
				}
				// Between equally-labeled chests, prefer the one already holding
				// this item, so twin chests consolidate instead of scattering.
				boolean containsItem = containsSameItem(candidate.container(), held);
				double distSq = candidate.pos().distToCenterSqr(golem.position());
				boolean better = rank < bestRank
						|| (rank == bestRank && containsItem && !bestContainsItem)
						|| (rank == bestRank && containsItem == bestContainsItem && distSq < bestDistSq);
				if (better) {
					best = candidate;
					bestRank = rank;
					bestContainsItem = containsItem;
					bestDistSq = distSq;
				}
			}
		}
		if (best != null) {
			WayBetterCopperGolem.LOGGER.debug("[WBCG-DEBUG] deposit target for {} -> {} (rank {})",
					held.getItem(), best.pos(), bestRank);
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Nearest copper chest that actually has something to collect. Vanilla's
	 * source search ignores contents, so an idle golem burns a full walk plus
	 * a three-second shrug on an empty copper chest before it can consider
	 * any other work; skipping empty ones keeps cleanup responsive.
	 */
	public static Optional<TransportItemTarget> findPickupSource(
			ServerLevel level, PathfinderMob golem, Predicate<BlockState> sourceBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			int horizontalRadius, int verticalRadius) {
		AABB searchArea = new AABB(golem.blockPosition()).inflate(horizontalRadius, verticalRadius, horizontalRadius);
		TransportItemTarget best = null;
		double bestDistSq = Double.MAX_VALUE;

		for (ChunkPos chunkPos : ChunkPos.rangeClosed(
				ChunkPos.containing(golem.blockPosition()), Math.floorDiv(horizontalRadius, 16) + 1).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)) {
					continue;
				}
				TransportItemTarget candidate = validCandidate(
						level, blockEntity, sourceBlockType, visited, unreachable, searchArea);
				if (candidate == null || candidate.container().isEmpty()) {
					continue;
				}
				double distSq = candidate.pos().distToCenterSqr(golem.position());
				if (distSq < bestDistSq) {
					best = candidate;
					bestDistSq = distSq;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * When nothing accepts the held item (its labeled chest is full or its
	 * category was tuned away mid-carry), the golem brings it back to a
	 * copper chest instead of standing around holding it forever - the zone
	 * chest if it can take the stack, else the nearest copper chest with
	 * room.
	 */
	public static Optional<TransportItemTarget> findReturnTarget(
			ServerLevel level, PathfinderMob golem, ItemStack held, @Nullable BlockPos preferred,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			int horizontalRadius, int verticalRadius) {
		Predicate<BlockState> copperChests = state -> state.is(net.minecraft.tags.BlockTags.COPPER_CHESTS);
		AABB searchArea = new AABB(golem.blockPosition()).inflate(horizontalRadius, verticalRadius, horizontalRadius);
		if (preferred != null && level.isLoaded(preferred)) {
			BlockEntity blockEntity = level.getBlockEntity(preferred);
			if (blockEntity != null) {
				TransportItemTarget zoneChest = validCandidate(
						level, blockEntity, copperChests, visited, unreachable, searchArea);
				if (zoneChest != null && canAcceptAny(zoneChest.container(), held)) {
					return Optional.of(zoneChest);
				}
			}
		}
		TransportItemTarget best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (ChunkPos chunkPos : ChunkPos.rangeClosed(
				ChunkPos.containing(golem.blockPosition()), Math.floorDiv(horizontalRadius, 16) + 1).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)) {
					continue;
				}
				TransportItemTarget candidate = validCandidate(
						level, blockEntity, copperChests, visited, unreachable, searchArea);
				if (candidate == null || !canAcceptAny(candidate.container(), held)) {
					continue;
				}
				double distSq = candidate.pos().distToCenterSqr(golem.position());
				if (distSq < bestDistSq) {
					best = candidate;
					bestDistSq = distSq;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/** Vanilla validity rules: in area, resolvable container, unvisited, unlocked. */
	private static @Nullable TransportItemTarget validCandidate(
			ServerLevel level, BlockEntity blockEntity,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable, AABB searchArea) {
		BlockPos pos = blockEntity.getBlockPos();
		if (!searchArea.contains(pos.getX(), pos.getY(), pos.getZ())) {
			return null;
		}
		BlockState state = blockEntity.getBlockState();
		if (!destinationBlockType.test(state)) {
			return null;
		}
		if (isVisited(level, pos, state, visited, unreachable)) {
			return null;
		}
		TransportItemTarget target = TransportItemTarget.tryCreatePossibleTarget(blockEntity, level);
		if (target == null) {
			return null;
		}
		if (target.blockEntity() instanceof BaseContainerBlockEntity container && container.isLocked()) {
			return null;
		}
		return target;
	}

	/** Checks both halves of a double chest, like the vanilla behavior does. */
	private static boolean isVisited(Level level, BlockPos pos, BlockState state,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable) {
		GlobalPos here = new GlobalPos(level.dimension(), pos);
		if (visited.contains(here) || unreachable.contains(here)) {
			return true;
		}
		if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE) {
			GlobalPos other = new GlobalPos(level.dimension(), ChestBlock.getConnectedBlockPos(pos, state));
			return visited.contains(other) || unreachable.contains(other);
		}
		return false;
	}

	/** Lower is better; {@link Long#MAX_VALUE} means "not a valid destination". */
	private static long depositRank(ServerLevel level, TransportItemTarget target, ItemStack held) {
		Container container = target.container();
		List<ChestLabel> labels = ChestLabels.effectiveLabels(level, target.pos(), target.state());
		if (labels.stream().anyMatch(ChestLabel::isOffLimits)) {
			return Long.MAX_VALUE;
		}
		if (labels.isEmpty()) {
			boolean vanillaAccepts = container.isEmpty() || containsSameItem(container, held);
			return vanillaAccepts && canAcceptAny(container, held) ? UNLABELED_RANK : Long.MAX_VALUE;
		}
		long rank = Long.MAX_VALUE;
		for (ChestLabel label : labels) {
			int specificity = LabelResolver.specificity(level, label, held);
			if (specificity == LabelResolver.NO_MATCH && !label.isCatchAll()) {
				continue;
			}
			rank = Math.min(rank, specificity);
		}
		if (rank == Long.MAX_VALUE || !canAcceptAny(container, held)) {
			return Long.MAX_VALUE;
		}
		return rank;
	}

	/**
	 * Arrival-time acceptance, replacing the vanilla "empty or same item"
	 * rule for labeled chests. The container may have changed since the
	 * target was selected, so this re-checks before any transfer.
	 */
	public static boolean acceptsDeposit(ServerLevel level, TransportItemTarget target, PathfinderMob golem) {
		ItemStack held = golem.getMainHandItem();
		List<ChestLabel> labels = ChestLabels.effectiveLabels(level, target.pos(), target.state());
		if (labels.stream().anyMatch(ChestLabel::isOffLimits)) {
			return false;
		}
		if (labels.isEmpty()) {
			Container container = target.container();
			return container.isEmpty() || containsSameItem(container, held);
		}
		boolean accepted = labels.stream()
				.anyMatch(label -> label.isCatchAll() || LabelResolver.matches(level, label, held));
		return accepted && canAcceptAny(target.container(), held);
	}

	/**
	 * Nearest labeled chest holding a stack that matches none of its labels,
	 * verified to have somewhere better for that stack to go. Chests with a
	 * catch-all label never have misplaced contents. Copper chests and
	 * unlabeled chests are never touched.
	 */
	public static Optional<TransportItemTarget> findMisplacedSource(
			ServerLevel level, PathfinderMob golem,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			int horizontalRadius, int verticalRadius) {
		AABB searchArea = new AABB(golem.blockPosition()).inflate(horizontalRadius, verticalRadius, horizontalRadius);
		record MisplacedCandidate(TransportItemTarget target, ItemStack stack, double distSq) {
		}
		List<MisplacedCandidate> candidates = new java.util.ArrayList<>();

		for (ChunkPos chunkPos : ChunkPos.rangeClosed(
				ChunkPos.containing(golem.blockPosition()), Math.floorDiv(horizontalRadius, 16) + 1).toList()) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)
						|| !ChestLabels.isLabelableChest(blockEntity.getBlockState())) {
					continue;
				}
				TransportItemTarget candidate = validCandidate(
						level, blockEntity, ChestLabels::isLabelableChest, visited, unreachable, searchArea);
				if (candidate == null) {
					continue;
				}
				ItemStack misplaced = firstMisplacedStack(level, candidate);
				if (!misplaced.isEmpty()) {
					candidates.add(new MisplacedCandidate(candidate, misplaced,
							candidate.pos().distToCenterSqr(golem.position())));
				}
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble(MisplacedCandidate::distSq));

		for (MisplacedCandidate candidate : candidates) {
			// Only worth picking up if a better home actually exists right now.
			Set<GlobalPos> excludingSource = new java.util.HashSet<>(visited);
			excludingSource.add(new GlobalPos(level.dimension(), candidate.target().pos()));
			BlockState state = candidate.target().state();
			if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE) {
				excludingSource.add(new GlobalPos(level.dimension(),
						ChestBlock.getConnectedBlockPos(candidate.target().pos(), state)));
			}
			ItemStack preview = candidate.stack().copyWithCount(Math.min(candidate.stack().getCount(), 16));
			if (findDepositTarget(level, golem, preview, destinationBlockType,
					excludingSource, unreachable, horizontalRadius, verticalRadius).isPresent()) {
				return Optional.of(candidate.target());
			}
		}
		return Optional.empty();
	}

	/**
	 * The first stack in this labeled chest that matches none of its labels;
	 * empty if the chest is unlabeled, has a catch-all label, or is tidy.
	 */
	public static ItemStack firstMisplacedStack(ServerLevel level, TransportItemTarget target) {
		List<ChestLabel> labels = ChestLabels.effectiveLabels(level, target.pos(), target.state());
		if (labels.isEmpty() || labels.stream().anyMatch(label -> label.isCatchAll() || label.isOffLimits())) {
			return ItemStack.EMPTY;
		}
		for (ItemStack stack : target.container()) {
			if (stack.isEmpty()) {
				continue;
			}
			boolean matchesAnyLabel = labels.stream().anyMatch(label -> LabelResolver.matches(level, label, stack));
			if (!matchesAnyLabel) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	public static boolean containsSameItem(Container container, ItemStack stack) {
		for (ItemStack other : container) {
			if (ItemStack.isSameItem(other, stack)) {
				return true;
			}
		}
		return false;
	}

	/** Whether at least one item of {@code stack} fits into the container. */
	public static boolean canAcceptAny(Container container, ItemStack stack) {
		for (ItemStack other : container) {
			if (other.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(other, stack) && other.getCount() < other.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Merges partial stacks (same item and components) and closes slot gaps
	 * within one container. Runs entirely inside one server tick and only
	 * moves counts between existing stacks, so nothing is created or lost.
	 */
	public static void tidyContainer(Container container) {
		int size = container.getContainerSize();
		boolean changed = false;
		for (int i = 0; i < size; i++) {
			ItemStack into = container.getItem(i);
			if (into.isEmpty() || into.getCount() >= into.getMaxStackSize()) {
				continue;
			}
			for (int j = i + 1; j < size && into.getCount() < into.getMaxStackSize(); j++) {
				ItemStack from = container.getItem(j);
				if (from.isEmpty() || !ItemStack.isSameItemSameComponents(into, from)) {
					continue;
				}
				int moved = Math.min(into.getMaxStackSize() - into.getCount(), from.getCount());
				into.grow(moved);
				from.shrink(moved);
				if (from.isEmpty()) {
					container.setItem(j, ItemStack.EMPTY);
				}
				changed = true;
			}
		}
		int write = 0;
		for (int read = 0; read < size; read++) {
			ItemStack stack = container.getItem(read);
			if (stack.isEmpty()) {
				continue;
			}
			if (read != write) {
				container.setItem(write, stack);
				container.setItem(read, ItemStack.EMPTY);
				changed = true;
			}
			write++;
		}
		if (changed) {
			container.setChanged();
		}
	}

	public static void logWouldMove(PathfinderMob golem, ItemStack stack, BlockPos from, @Nullable BlockPos to) {
		String item = stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem());
		String source = posString(golem.level(), from);
		if (to == null) {
			WayBetterCopperGolem.LOGGER.info("[DRY-RUN] would take {} from {} but found no destination", item, source);
		} else {
			WayBetterCopperGolem.LOGGER.info("[DRY-RUN] would move {} from {} to {}", item, source, posString(golem.level(), to));
		}
	}

	private static String posString(Level level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
				+ "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
}
