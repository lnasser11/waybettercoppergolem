package io.github.lnasser11.waybettercoppergolem.sorting;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Golems hang item frames on the chests they serve (zone setting "Golems
 * hang frames", off by default).
 *
 * <p>After a golem delivers into an explicitly labeled chest whose front
 * face is bare, the chest is remembered on the golem. On its next trip the
 * golem fetches one item frame (glow frames count too) from a copper chest
 * in the zone, carries it in hand like any item, and hangs it on the
 * chest's front face showing one of the chest's own items: the first stack
 * matching an exact-item label, otherwise the most common item inside. One
 * unit of that item leaves the chest. Explicit labels never follow frames,
 * so the chest's labels do not change.
 *
 * <p>Never touched: frames already hanging, off-limits and catch-all chests
 * (a catch-all holds anything, so no single item describes it), copper
 * chests, chests marked "no golem frames" in the editor, chests with
 * nothing inside or whose front face is taken (a solid block, a slab, a
 * fluid, another hanging entity). A golem that arrives with a frame and
 * finds the front taken gives up: it carries the frame back to the copper
 * chest it came from. While the setting is on, frames in copper chests are
 * supplies: the normal pickup skips them.
 */
public final class FrameHanger {
	private FrameHanger() {
	}

	public static boolean isFrame(ItemStack stack) {
		return stack.is(Items.ITEM_FRAME) || stack.is(Items.GLOW_ITEM_FRAME);
	}

	/**
	 * Whether the chest at {@code chestPos} should get a frame: a regular
	 * chest, explicitly labeled, neither off-limits nor catch-all, with air
	 * in front of its face, no frame on that face yet, and something inside
	 * to show.
	 */
	public static boolean wantsFrame(ServerLevel level, BlockPos chestPos) {
		if (!level.isLoaded(chestPos)) {
			return false;
		}
		BlockState state = level.getBlockState(chestPos);
		if (!ChestLabels.isLabelableChest(state)) {
			return false;
		}
		ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, chestPos, state);
		if (!labels.explicit() || labels.isEmpty() || labels.isOffLimits()
				|| labels.labels().stream().anyMatch(ChestLabel::isCatchAll)) {
			return false;
		}
		if (!ChestLabels.golemFramesAllowed(level, chestPos, state)) {
			return false;
		}
		Direction facing = state.getValue(ChestBlock.FACING);
		if (!frontIsFree(level, chestPos, facing)) {
			return false;
		}
		Container container = ChestLabels.container(level, chestPos);
		return container != null && !sampleFor(level, container, labels.labels()).isEmpty();
	}

	/**
	 * Whether a frame could hang on the chest's front face right now: the
	 * block in front is air or something a frame can share (no solid block,
	 * no fluid, nothing the frame would collide with such as a slab), and no
	 * frame hangs on that face yet. Golems give up on a chest whose front is
	 * taken; the chest itself may still be perfectly reachable.
	 */
	public static boolean frontIsFree(ServerLevel level, BlockPos chestPos, Direction facing) {
		BlockPos front = chestPos.relative(facing);
		BlockState inFront = level.getBlockState(front);
		if (!inFront.isAir() && (inFront.isSolid() || !inFront.getFluidState().isEmpty())) {
			return false;
		}
		if (ChestLabels.labelFrames(level, chestPos).stream().anyMatch(frame -> frame.getDirection() == facing)) {
			return false;
		}
		return new ItemFrame(level, front, facing).survives();
	}

	/**
	 * The chest's own item to show in the frame: the first stack matching an
	 * exact-item label, else the first stack of the most common item inside.
	 * The returned stack is the container's live stack (empty if none).
	 */
	public static ItemStack sampleFor(ServerLevel level, Container container, List<ChestLabel> labels) {
		for (ChestLabel label : labels) {
			if (label.itemId().isPresent() && label.tagId().isEmpty() && !label.isOffLimits()) {
				for (ItemStack stack : container) {
					if (!stack.isEmpty() && LabelResolver.matches(level, label, stack)) {
						return stack;
					}
				}
			}
		}
		Map<Item, Integer> counts = new LinkedHashMap<>();
		Map<Item, ItemStack> first = new HashMap<>();
		for (ItemStack stack : container) {
			if (stack.isEmpty()) {
				continue;
			}
			counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			first.putIfAbsent(stack.getItem(), stack);
		}
		Item best = null;
		int bestCount = 0;
		for (Map.Entry<Item, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > bestCount) {
				best = entry.getKey();
				bestCount = entry.getValue();
			}
		}
		return best == null ? ItemStack.EMPTY : first.get(best);
	}

	/**
	 * The nearest copper chest in the area holding item frames. The visited
	 * memory is ignored on purpose (a frame trip is a deliberate one-off
	 * and the chest may well have just been visited); unreachable chests
	 * are still skipped.
	 */
	public static Optional<TransportItemTarget> findFrameSource(ServerLevel level, Vec3 from,
			Set<GlobalPos> unreachable, AABB searchArea) {
		TransportItemTarget best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (ChunkPos chunkPos : SortingEngine.chunksCovering(searchArea)) {
			LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof ChestBlockEntity) || !blockEntity.getBlockState().is(BlockTags.COPPER_CHESTS)) {
					continue;
				}
				TransportItemTarget candidate = SortingEngine.validCandidate(level, blockEntity,
						state -> state.is(BlockTags.COPPER_CHESTS), Set.of(), unreachable, searchArea);
				if (candidate == null || !holdsFrame(candidate.container())) {
					continue;
				}
				double distSq = candidate.pos().distToCenterSqr(from);
				if (distSq < bestDistSq) {
					best = candidate;
					bestDistSq = distSq;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	public static boolean holdsFrame(Container container) {
		for (ItemStack stack : container) {
			if (isFrame(stack)) {
				return true;
			}
		}
		return false;
	}

	/** Removes one frame from the container and returns it (empty if there is none). */
	public static ItemStack takeFrame(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (isFrame(container.getItem(slot))) {
				ItemStack taken = container.removeItem(slot, 1);
				container.setChanged();
				return taken;
			}
		}
		return ItemStack.EMPTY;
	}

	/**
	 * The vanilla pickup, frames excluded: takes up to {@code carrySize} of
	 * the first stack that is not an item frame (empty if only frames are
	 * left).
	 */
	public static ItemStack takeFirstNonFrame(Container container, int carrySize) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (!stack.isEmpty() && !isFrame(stack)) {
				ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(), carrySize));
				container.setChanged();
				return taken;
			}
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Hangs the frame in the golem's hand on the chest's front face, showing
	 * one of the chest's items (which leaves the chest). Returns false, with
	 * nothing changed, when the chest no longer wants a frame or the frame
	 * cannot hang there.
	 */
	public static boolean hang(ServerLevel level, PathfinderMob golem, TransportItemTarget chest) {
		ItemStack held = golem.getMainHandItem();
		if (!isFrame(held) || !wantsFrame(level, chest.pos())) {
			return false;
		}
		BlockState state = level.getBlockState(chest.pos());
		Direction facing = state.getValue(ChestBlock.FACING);
		Container container = chest.container();
		List<ChestLabel> labels = ChestLabels.effectiveLabels(level, chest.pos(), state);
		int slot = -1;
		ItemStack sample = sampleFor(level, container, labels);
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (container.getItem(i) == sample) {
				slot = i;
				break;
			}
		}
		if (slot < 0) {
			return false;
		}
		BlockPos at = chest.pos().relative(facing);
		ItemFrame frame = held.is(Items.GLOW_ITEM_FRAME) ? new GlowItemFrame(level, at, facing) : new ItemFrame(level, at, facing);
		if (!frame.survives()) {
			return false;
		}
		ItemStack shown = container.removeItem(slot, 1);
		container.setChanged();
		level.addFreshEntity(frame);
		frame.setItem(shown);
		frame.playPlacementSound();
		golem.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		WayBetterCopperGolem.LOGGER.debug("[WBCG-DEBUG] golem hung a frame showing {} on {}", shown.getItem(), chest.pos());
		return true;
	}
}
