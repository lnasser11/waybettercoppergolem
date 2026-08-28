package io.github.lnasser11.waybettercoppergolem.label;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Associates item frames with the chests they hang on and maintains the
 * per-chest label cache.
 *
 * <p>Association rule: a frame labels a chest if and only if its support
 * block (the block it physically hangs on) is that chest. For double chests
 * the two halves' labels are combined.
 *
 * <p>The frames are the source of truth while present; the resolved labels
 * are cached in a persistent attachment on the chest block entity so a
 * destroyed frame (creeper, etc.) does not erase the chest's category.
 */
public final class ChestLabels {
	private ChestLabels() {
	}

	public static boolean isLabelableChest(BlockState state) {
		return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
	}

	/** Frames whose support block is exactly {@code chestPos}. */
	public static List<ItemFrame> labelFrames(ServerLevel level, BlockPos chestPos) {
		AABB searchBox = new AABB(chestPos).inflate(1.0);
		return level.getEntitiesOfClass(ItemFrame.class, searchBox,
				frame -> frame.getPos().relative(frame.getDirection().getOpposite()).equals(chestPos));
	}

	/**
	 * Labels for the single chest block at {@code chestPos}, refreshing the
	 * attachment cache from frames when any are present. An empty list means
	 * the chest is unlabeled (vanilla behavior applies).
	 */
	public static List<ChestLabel> labelsForHalf(ServerLevel level, BlockPos chestPos) {
		BlockEntity blockEntity = level.getBlockEntity(chestPos);
		if (blockEntity == null) {
			return List.of();
		}
		List<ItemFrame> frames = labelFrames(level, chestPos);
		if (frames.isEmpty()) {
			List<ChestLabel> cached = blockEntity.getAttached(WayBetterCopperGolem.CHEST_LABELS);
			return cached == null ? List.of() : cached;
		}
		List<ChestLabel> labels = new ArrayList<>(frames.size());
		for (ItemFrame frame : frames) {
			labels.add(labelFromFrame(frame));
		}
		List<ChestLabel> cached = blockEntity.getAttached(WayBetterCopperGolem.CHEST_LABELS);
		if (!labels.equals(cached)) {
			blockEntity.setAttached(WayBetterCopperGolem.CHEST_LABELS, List.copyOf(labels));
		}
		return labels;
	}

	/**
	 * Effective labels for the (possibly double) chest at {@code chestPos}:
	 * the union of both halves' labels.
	 */
	public static List<ChestLabel> effectiveLabels(ServerLevel level, BlockPos chestPos, BlockState state) {
		List<ChestLabel> labels = new ArrayList<>(labelsForHalf(level, chestPos));
		if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE) {
			for (ChestLabel other : labelsForHalf(level, ChestBlock.getConnectedBlockPos(chestPos, state))) {
				if (!labels.contains(other)) {
					labels.add(other);
				}
			}
		}
		return labels;
	}

	public static ChestLabel labelFromFrame(ItemFrame frame) {
		ItemStack framed = frame.getItem();
		net.minecraft.resources.Identifier tagId = frame.getAttached(WayBetterCopperGolem.FRAME_TAG);
		if (framed.isEmpty()) {
			// A category frame keeps its category while empty, so the shown
			// item can be swapped freely (pop it out, put any icon in).
			return tagId == null ? ChestLabel.catchAll() : ChestLabel.tagOnly(tagId);
		}
		net.minecraft.resources.Identifier itemId = BuiltInRegistries.ITEM.getKey(framed.getItem());
		return tagId == null ? ChestLabel.exact(itemId) : ChestLabel.tag(itemId, tagId);
	}

	/**
	 * Advances the frame to the next cycle stop (exact item, then the
	 * item's categories narrow to broad) and refreshes the chest's cached
	 * labels. The chosen stop is stored as the tag's id, so mods coming and
	 * going never silently change what an existing frame means; a stored
	 * tag no longer offered for this item falls back to restarting the
	 * cycle. Returns the new label.
	 */
	public static ChestLabel cycleFrame(ServerLevel level, ItemFrame frame, BlockPos chestPos) {
		ItemStack framed = frame.getItem();
		if (framed.isEmpty()) {
			return ChestLabel.catchAll();
		}
		List<net.minecraft.tags.TagKey<net.minecraft.world.item.Item>> stops =
				LabelResolver.orderedTags(framed.getItem());
		net.minecraft.resources.Identifier current = frame.getAttached(WayBetterCopperGolem.FRAME_TAG);
		int currentIndex = -1; // -1 = exact item
		for (int i = 0; i < stops.size(); i++) {
			if (stops.get(i).location().equals(current)) {
				currentIndex = i;
				break;
			}
		}
		int nextIndex = currentIndex + 1;
		if (nextIndex >= stops.size()) {
			frame.removeAttached(WayBetterCopperGolem.FRAME_TAG); // back to exact
		} else {
			frame.setAttached(WayBetterCopperGolem.FRAME_TAG, stops.get(nextIndex).location());
		}
		labelsForHalf(level, chestPos);
		return labelFromFrame(frame);
	}
}
