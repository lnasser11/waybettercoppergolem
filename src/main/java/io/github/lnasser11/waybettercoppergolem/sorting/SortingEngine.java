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
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
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
			ServerLevel level, Vec3 from, ItemStack held,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			AABB searchArea) {
		return findDepositTarget(level, from, held, destinationBlockType, visited, unreachable, searchArea, Set.of());
	}

	/**
	 * Like {@link #findDepositTarget(ServerLevel, Vec3, ItemStack, Predicate, Set, Set, AABB)},
	 * but among equally good chests one that no other golem is heading to
	 * ({@code claimed}, see {@link GolemClaims}) is preferred over a nearer
	 * one that is, so several golems spread over twin chests.
	 */
	public static Optional<TransportItemTarget> findDepositTarget(
			ServerLevel level, Vec3 from, ItemStack held,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			AABB searchArea, Set<BlockPos> claimed) {
		TransportItemTarget best = null;
		long bestRank = Long.MAX_VALUE;
		boolean bestContainsItem = false;
		boolean bestClaimed = true;
		double bestDistSq = Double.MAX_VALUE;

		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
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
				boolean isClaimed = isClaimed(candidate, claimed);
				double distSq = candidate.pos().distToCenterSqr(from);
				boolean better = rank < bestRank
						|| (rank == bestRank && containsItem && !bestContainsItem)
						|| (rank == bestRank && containsItem == bestContainsItem && !isClaimed && bestClaimed)
						|| (rank == bestRank && containsItem == bestContainsItem && isClaimed == bestClaimed && distSq < bestDistSq);
				if (better) {
					best = candidate;
					bestRank = rank;
					bestContainsItem = containsItem;
					bestClaimed = isClaimed;
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

	private static boolean isClaimed(TransportItemTarget target, Set<BlockPos> claimed) {
		if (claimed.isEmpty()) {
			return false;
		}
		if (claimed.contains(target.pos())) {
			return true;
		}
		BlockState state = target.state();
		return state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE
				&& claimed.contains(ChestBlock.getConnectedBlockPos(target.pos(), state));
	}

	/**
	 * The copper chest to take from next: the vanilla rule (nearest chest of
	 * the source type that is not visited, unreachable or locked), with two
	 * refinements: a chest no other golem is heading to is preferred over a
	 * nearer one that is, a chest holding nothing but item frames is
	 * skipped while the zone's golems hang frames ({@code skipFrameOnly}),
	 * and an empty chest is skipped when it is close enough that
	 * {@link GolemWake} will wake the golem the moment something lands in
	 * it (vanilla walks over to look every cooldown; here the walk is
	 * saved, which also keeps a perched golem on its perch). Empty when
	 * there is no candidate at all.
	 */
	public static Optional<TransportItemTarget> findSource(
			ServerLevel level, Vec3 from, Predicate<BlockState> sourceBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable, AABB searchArea,
			Set<BlockPos> claimed, boolean skipFrameOnly) {
		return findSource(level, from, sourceBlockType, visited, unreachable, searchArea, claimed, skipFrameOnly, pos -> false);
	}

	/** As above; {@code skip} rejects chests the golem found nothing deliverable in lately (either half of a double chest). */
	public static Optional<TransportItemTarget> findSource(
			ServerLevel level, Vec3 from, Predicate<BlockState> sourceBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable, AABB searchArea,
			Set<BlockPos> claimed, boolean skipFrameOnly, Predicate<BlockPos> skip) {
		TransportItemTarget best = null;
		boolean bestClaimed = true;
		double bestDistSq = Double.MAX_VALUE;
		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity)) {
					continue;
				}
				TransportItemTarget candidate = validCandidate(level, blockEntity, sourceBlockType, visited, unreachable, searchArea);
				if (candidate == null) {
					continue;
				}
				if (candidate.container().isEmpty()) {
					if (GolemWake.covers(from, candidate.pos())) {
						continue; // nothing to take, and the golem is woken when that changes
					}
				} else if (skipFrameOnly && onlyFrames(candidate.container())) {
					continue;
				}
				if (ChestLabels.halves(candidate.pos(), candidate.state()).stream().anyMatch(skip)) {
					continue;
				}
				boolean isClaimed = isClaimed(candidate, claimed);
				double distSq = candidate.pos().distToCenterSqr(from);
				if ((!isClaimed && bestClaimed) || (isClaimed == bestClaimed && distSq < bestDistSq)) {
					best = candidate;
					bestClaimed = isClaimed;
					bestDistSq = distSq;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * The copper-chest pickup: takes up to {@code carrySize} of the first
	 * stack that {@code deliverable} accepts (frames excluded while the
	 * zone's golems hang them). Empty when no stack qualifies, so a stack
	 * with nowhere to go stays in the chest instead of in the golem's hand.
	 */
	public static ItemStack takeFirstDeliverable(Container container, int carrySize, boolean skipFrames, Predicate<ItemStack> deliverable) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty() || (skipFrames && FrameHanger.isFrame(stack)) || !deliverable.test(stack)) {
				continue;
			}
			ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(), carrySize));
			container.setChanged();
			return taken;
		}
		return ItemStack.EMPTY;
	}

	private static boolean onlyFrames(Container container) {
		for (ItemStack stack : container) {
			if (!stack.isEmpty() && !FrameHanger.isFrame(stack)) {
				return false;
			}
		}
		return true;
	}

	/** Every chunk the search box touches (the box is clamped to zone size upstream). */
	static List<ChunkPos> chunksCovering(AABB area) {
		ChunkPos min = ChunkPos.containing(BlockPos.containing(area.minX, area.minY, area.minZ));
		ChunkPos max = ChunkPos.containing(BlockPos.containing(area.maxX, area.maxY, area.maxZ));
		return ChunkPos.rangeClosed(min, max).toList();
	}

	/** Vanilla validity rules: in area, resolvable container, unvisited, unlocked. */
	static @Nullable TransportItemTarget validCandidate(
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
			ServerLevel level, Vec3 from,
			Predicate<BlockState> destinationBlockType,
			Set<GlobalPos> visited, Set<GlobalPos> unreachable,
			AABB searchArea) {
		record MisplacedCandidate(TransportItemTarget target, ItemStack stack, double distSq) {
		}
		List<MisplacedCandidate> candidates = new java.util.ArrayList<>();

		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
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
							candidate.pos().distToCenterSqr(from)));
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
			if (findDepositTarget(level, from, preview, destinationBlockType,
					excludingSource, unreachable, searchArea).isPresent()) {
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

	/** One pending reorganize move: a misplaced {@code stack} leaves {@code source} for the chest labeled for it. */
	public record ReorganizeMove(TransportItemTarget source, ItemStack stack, TransportItemTarget destination) {
	}

	/**
	 * Every misplaced stack in the area that has somewhere better to go
	 * right now: for each labeled chest, each stack matching none of its
	 * labels, with the destination the golems would pick. A preview for
	 * Simulate and Overview; capped so a huge room stays cheap.
	 */
	public static List<ReorganizeMove> planReorganize(ServerLevel level, AABB searchArea, int carrySize) {
		List<ReorganizeMove> moves = new java.util.ArrayList<>();
		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity) || !ChestLabels.isLabelableChest(blockEntity.getBlockState())) {
					continue;
				}
				BlockPos canonical = io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.canonicalHalf(
						blockEntity.getBlockPos(), blockEntity.getBlockState());
				if (!canonical.equals(blockEntity.getBlockPos())) {
					continue;
				}
				TransportItemTarget source = validCandidate(level, blockEntity, ChestLabels::isLabelableChest, Set.of(), Set.of(), searchArea);
				if (source == null) {
					continue;
				}
				List<ChestLabel> labels = ChestLabels.effectiveLabels(level, source.pos(), source.state());
				if (labels.isEmpty() || labels.stream().anyMatch(label -> label.isCatchAll() || label.isOffLimits())) {
					continue;
				}
				Set<GlobalPos> excludingSource = new java.util.HashSet<>();
				for (BlockPos half : ChestLabels.halves(source.pos(), source.state())) {
					excludingSource.add(new GlobalPos(level.dimension(), half));
				}
				for (ItemStack stack : source.container()) {
					if (stack.isEmpty() || labels.stream().anyMatch(label -> LabelResolver.matches(level, label, stack))) {
						continue;
					}
					ItemStack preview = stack.copyWithCount(Math.min(stack.getCount(), carrySize));
					findDepositTarget(level, Vec3.atCenterOf(source.pos()), preview, ChestLabels::isLabelableChest,
							excludingSource, Set.of(), searchArea)
							.ifPresent(destination -> moves.add(new ReorganizeMove(source, preview, destination)));
					if (moves.size() >= 100) {
						return moves;
					}
				}
			}
		}
		return moves;
	}

	// ---------------------------------------------------------------- tidy: consolidation across sibling chests

	/**
	 * One planned consolidation: {@code stack} (a copy, sized to what fits
	 * and to one carry) leaves {@code source} for {@code home}, a chest with
	 * the same labels that already holds more of that item.
	 */
	public record TidyMove(TransportItemTarget source, ItemStack stack, TransportItemTarget home) {
	}

	private record LabeledChest(TransportItemTarget target, List<ChestLabel> labels) {
	}

	/**
	 * Every pending consolidation in the area. For each item, every labeled
	 * chest gets a rank: its narrowest label matching the item, catch-all
	 * behind every real label, none when nothing matches. The item's
	 * <em>home</em> is the chest with the best rank that holds some of it or
	 * has room, and among equals the one holding the most of it; every other
	 * chest's stacks of that item become moves into the home while it has
	 * room. So a stray stack of dirt leaves a "Stone & Dirt" chest for the
	 * chest labeled *Dirt* (narrower), and twin chests consolidate on the
	 * one holding more. Stacks that match none of their chest's labels are
	 * reorganize's business and are left out; a chest ranked better than
	 * the home (narrower, but full) keeps its stacks; off-limits, unlabeled
	 * and copper chests take no part. Read-only.
	 */
	public static List<TidyMove> planTidy(ServerLevel level, Set<GlobalPos> unreachable, AABB searchArea, int carrySize) {
		List<LabeledChest> chests = new java.util.ArrayList<>();
		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity) || !ChestLabels.isLabelableChest(blockEntity.getBlockState())) {
					continue;
				}
				BlockPos canonical = io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.canonicalHalf(
						blockEntity.getBlockPos(), blockEntity.getBlockState());
				if (!canonical.equals(blockEntity.getBlockPos())) {
					continue; // one entry per double chest
				}
				TransportItemTarget target = validCandidate(level, blockEntity, ChestLabels::isLabelableChest, Set.of(), unreachable, searchArea);
				if (target == null) {
					continue;
				}
				List<ChestLabel> labels = ChestLabels.effectiveLabels(level, target.pos(), target.state());
				if (labels.isEmpty() || labels.stream().anyMatch(ChestLabel::isOffLimits)) {
					continue;
				}
				chests.add(new LabeledChest(target, labels));
			}
		}
		chests.sort(java.util.Comparator.comparing(chest -> chest.target().pos()));
		Map<net.minecraft.world.item.Item, int[]> counts = new java.util.LinkedHashMap<>();
		for (int i = 0; i < chests.size(); i++) {
			for (ItemStack stack : chests.get(i).target().container()) {
				if (!stack.isEmpty()) {
					counts.computeIfAbsent(stack.getItem(), item -> new int[chests.size()])[i] += stack.getCount();
				}
			}
		}
		List<TidyMove> moves = new java.util.ArrayList<>();
		for (Map.Entry<net.minecraft.world.item.Item, int[]> entry : counts.entrySet()) {
			int[] perChest = entry.getValue();
			ItemStack sample = new ItemStack(entry.getKey());
			int[] ranks = new int[chests.size()];
			int home = -1;
			for (int i = 0; i < chests.size(); i++) {
				ranks[i] = rankFor(level, chests.get(i).labels(), sample);
				if (ranks[i] == LabelResolver.NO_MATCH || (perChest[i] == 0 && room(chests.get(i).target().container(), sample) <= 0)) {
					continue;
				}
				if (home < 0 || ranks[i] < ranks[home] || (ranks[i] == ranks[home] && perChest[i] > perChest[home])) {
					home = i;
				}
			}
			if (home < 0) {
				continue;
			}
			LabeledChest homeChest = chests.get(home);
			for (int j = 0; j < chests.size(); j++) {
				if (j == home || perChest[j] == 0 || ranks[j] == LabelResolver.NO_MATCH || ranks[j] < ranks[home]) {
					continue;
				}
				for (ItemStack stack : chests.get(j).target().container()) {
					if (stack.isEmpty() || stack.getItem() != entry.getKey()) {
						continue;
					}
					int fits = Math.min(Math.min(stack.getCount(), carrySize), room(homeChest.target().container(), stack));
					if (fits > 0) {
						moves.add(new TidyMove(chests.get(j).target(), stack.copyWithCount(fits), homeChest.target()));
					}
				}
			}
		}
		return moves;
	}

	/** The chest's rank for the item: its narrowest matching label, catch-all behind every real label, {@link LabelResolver#NO_MATCH} otherwise. */
	private static int rankFor(ServerLevel level, List<ChestLabel> labels, ItemStack stack) {
		int rank = LabelResolver.NO_MATCH;
		for (ChestLabel label : labels) {
			rank = Math.min(rank, LabelResolver.specificity(level, label, stack));
		}
		return rank;
	}

	/** How many of {@code stack} the container can still take: empty slots plus space in matching partial stacks. */
	public static int room(Container container, ItemStack stack) {
		int room = 0;
		int max = stack.getMaxStackSize();
		for (ItemStack other : container) {
			if (other.isEmpty()) {
				room += max;
			} else if (ItemStack.isSameItemSameComponents(other, stack) && other.getCount() < other.getMaxStackSize()) {
				room += other.getMaxStackSize() - other.getCount();
			}
		}
		return room;
	}

	/** The pending consolidation whose source is nearest to {@code from}. */
	public static Optional<TidyMove> findTidyMove(ServerLevel level, Vec3 from, Set<GlobalPos> unreachable, AABB searchArea, int carrySize) {
		return planTidy(level, unreachable, searchArea, carrySize).stream()
				.min(java.util.Comparator.comparingDouble(move -> move.source().pos().distToCenterSqr(from)));
	}

	/**
	 * Re-checks a planned move against the chests as they are now and takes
	 * the stack out of the source. Returns what was taken (empty when the
	 * move is no longer valid).
	 */
	public static ItemStack takeTidyStack(ServerLevel level, TidyMove move, int carrySize) {
		Container source = move.source().container();
		Container home = move.home().container();
		List<ChestLabel> labels = ChestLabels.effectiveLabels(level, move.home().pos(), move.home().state());
		boolean homeStillMatches = !labels.isEmpty() && labels.stream().noneMatch(ChestLabel::isOffLimits)
				&& labels.stream().anyMatch(label -> label.isCatchAll() || LabelResolver.matches(level, label, move.stack()));
		if (!homeStillMatches) {
			return ItemStack.EMPTY;
		}
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack stack = source.getItem(slot);
			if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, move.stack())) {
				continue;
			}
			int fits = Math.min(Math.min(stack.getCount(), carrySize), room(home, stack));
			if (fits <= 0) {
				return ItemStack.EMPTY;
			}
			ItemStack taken = source.removeItem(slot, fits);
			source.setChanged();
			return taken;
		}
		return ItemStack.EMPTY;
	}

	public static void logWouldTidy(PathfinderMob golem, ItemStack stack, BlockPos from, BlockPos to) {
		WayBetterCopperGolem.LOGGER.info("[DRY-RUN] would tidy {}x {} from {} to {}", stack.getCount(),
				BuiltInRegistries.ITEM.getKey(stack.getItem()), posString(golem.level(), from), posString(golem.level(), to));
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
	 * Tidies one container in place, within one server tick: partial stacks
	 * of the same item are merged, then the stacks are laid out from slot 0
	 * grouped by item, the item with the most stacks first (ties: more items
	 * first, then by id), full stacks before partial ones within a group,
	 * and the free slots at the end. So five stacks of cobblestone come
	 * first, then three of the next block, then two of the next. Only moves
	 * counts between existing stacks, so nothing is created or lost.
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
		List<ItemStack> sorted = sortedLayout(container);
		for (int slot = 0; slot < size; slot++) {
			ItemStack wanted = slot < sorted.size() ? sorted.get(slot) : ItemStack.EMPTY;
			ItemStack current = container.getItem(slot);
			if (current != wanted && !(current.isEmpty() && wanted.isEmpty())) {
				container.setItem(slot, wanted);
				changed = true;
			}
		}
		if (changed) {
			container.setChanged();
		}
	}

	/** The container's stacks in tidy order (the live stack objects, not copies). */
	private static List<ItemStack> sortedLayout(Container container) {
		record Group(ItemStack sample, List<ItemStack> stacks, int total) {
		}
		Map<String, Group> groups = new java.util.LinkedHashMap<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String key = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + stack.getComponentsPatch().hashCode();
			Group group = groups.get(key);
			if (group != null && !ItemStack.isSameItemSameComponents(group.sample(), stack)) {
				key = key + "#" + slot; // a hash clash: keep it apart
				group = groups.get(key);
			}
			if (group == null) {
				group = new Group(stack, new java.util.ArrayList<>(), 0);
				groups.put(key, group);
			}
			group.stacks().add(stack);
			groups.put(key, new Group(group.sample(), group.stacks(), group.total() + stack.getCount()));
		}
		List<Group> ordered = new java.util.ArrayList<>(groups.values());
		ordered.sort(java.util.Comparator.<Group>comparingInt(group -> -group.stacks().size())
				.thenComparingInt(group -> -group.total())
				.thenComparing(group -> BuiltInRegistries.ITEM.getKey(group.sample().getItem()).toString()));
		List<ItemStack> layout = new java.util.ArrayList<>();
		for (Group group : ordered) {
			List<ItemStack> stacks = new java.util.ArrayList<>(group.stacks());
			stacks.sort(java.util.Comparator.comparingInt(stack -> -stack.getCount()));
			layout.addAll(stacks);
		}
		return layout;
	}

	/** Whether {@link #tidyContainer} would leave this container as it is. */
	public static boolean isTidy(Container container) {
		int size = container.getContainerSize();
		for (int i = 0; i < size; i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty() || stack.getCount() >= stack.getMaxStackSize()) {
				continue;
			}
			for (int j = i + 1; j < size; j++) {
				ItemStack other = container.getItem(j);
				if (!other.isEmpty() && ItemStack.isSameItemSameComponents(stack, other)) {
					return false; // two partial stacks (or a partial before a stack) of the same item
				}
			}
		}
		List<ItemStack> sorted = sortedLayout(container);
		for (int slot = 0; slot < size; slot++) {
			ItemStack wanted = slot < sorted.size() ? sorted.get(slot) : ItemStack.EMPTY;
			if (container.getItem(slot) != wanted && !(container.getItem(slot).isEmpty() && wanted.isEmpty())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The nearest labeled chest in the area whose contents are not in tidy
	 * order, for a sorting visit (the golem walks there and sorts it in
	 * place). Off-limits and copper chests are never touched.
	 */
	public static Optional<TransportItemTarget> findUntidyChest(ServerLevel level, Vec3 from,
			Set<GlobalPos> unreachable, AABB searchArea) {
		return untidyChests(level, unreachable, searchArea).stream()
				.min(java.util.Comparator.comparingDouble(target -> target.pos().distToCenterSqr(from)));
	}

	/** Every labeled chest in the area whose contents are not in tidy order (one entry per double chest). */
	public static List<TransportItemTarget> untidyChests(ServerLevel level, Set<GlobalPos> unreachable, AABB searchArea) {
		List<TransportItemTarget> result = new java.util.ArrayList<>();
		for (ChunkPos chunkPos : chunksCovering(searchArea)) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity) || !ChestLabels.isLabelableChest(blockEntity.getBlockState())) {
					continue;
				}
				BlockPos canonical = io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.canonicalHalf(
						blockEntity.getBlockPos(), blockEntity.getBlockState());
				if (!canonical.equals(blockEntity.getBlockPos())) {
					continue;
				}
				TransportItemTarget target = validCandidate(level, blockEntity, ChestLabels::isLabelableChest, Set.of(), unreachable, searchArea);
				if (target == null) {
					continue;
				}
				List<ChestLabel> labels = ChestLabels.effectiveLabels(level, target.pos(), target.state());
				if (labels.isEmpty() || labels.stream().anyMatch(ChestLabel::isOffLimits) || isTidy(target.container())) {
					continue;
				}
				result.add(target);
			}
		}
		return result;
	}

	public static void logWouldSort(PathfinderMob golem, BlockPos chest) {
		WayBetterCopperGolem.LOGGER.info("[DRY-RUN] would sort the stacks inside {}", posString(golem.level(), chest));
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
