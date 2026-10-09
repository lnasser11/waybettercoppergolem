package io.github.lnasser11.waybettercoppergolem.label;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The chest-owned label store.
 *
 * <p>Each chest half carries a {@link ChestLabelSet} attachment. Explicit
 * sets are authoritative and never change on their own. When a half has no
 * explicit set, its labels are <em>derived</em> from the item frames
 * hanging on it: the framed item is the sample, and the chest contents
 * pick how broadly it counts (the narrowest conventional or curated
 * vanilla tag of the framed item that covers every distinct item in the
 * chest, else the exact item). Preset categories are never chosen
 * automatically; they are big buckets a player picks on purpose.
 *
 * <p>Frames are never clicked by this mod. A frame labels a chest if and
 * only if its support block is that chest. Derived labels are cached on
 * the chest so a destroyed frame does not erase them, and the cache is
 * refreshed when a frame or the chest is used, when the learn pass scans
 * the chest, or when a golem evaluates it.
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
				frame -> supportPos(frame).equals(chestPos));
	}

	/** The block an item frame hangs on. */
	public static BlockPos supportPos(ItemFrame frame) {
		return frame.getPos().relative(frame.getDirection().getOpposite());
	}

	/**
	 * The label set of the single chest block at {@code chestPos}. Explicit
	 * sets are returned as stored; otherwise the derived set is recomputed
	 * from the frames present (and cached), or the cached set is returned
	 * when no frame remains. {@link ChestLabelSet#NONE} means unlabeled:
	 * vanilla behavior applies.
	 */
	public static ChestLabelSet labelSetForHalf(ServerLevel level, BlockPos chestPos) {
		BlockEntity blockEntity = level.getBlockEntity(chestPos);
		if (blockEntity == null) {
			return ChestLabelSet.NONE;
		}
		ChestLabelSet stored = blockEntity.getAttached(WayBetterCopperGolem.CHEST_LABELS);
		if (stored != null && stored.explicit()) {
			return stored;
		}
		List<ItemFrame> frames = labelFrames(level, chestPos);
		if (frames.isEmpty()) {
			return stored == null ? ChestLabelSet.NONE : stored;
		}
		ChestLabelSet migrated = migrateLegacyFrameTags(frames);
		ChestLabelSet current = migrated != null ? migrated : derive(level, chestPos, frames);
		if (!current.equals(stored)) {
			blockEntity.setAttached(WayBetterCopperGolem.CHEST_LABELS, current);
		}
		return current;
	}

	/** Labels of one chest half; see {@link #labelSetForHalf}. */
	public static List<ChestLabel> labelsForHalf(ServerLevel level, BlockPos chestPos) {
		return labelSetForHalf(level, chestPos).labels();
	}

	/**
	 * Effective label set for the (possibly double) chest at
	 * {@code chestPos}: the union of both halves' labels, explicit if
	 * either half is.
	 */
	public static ChestLabelSet effectiveLabelSet(ServerLevel level, BlockPos chestPos, BlockState state) {
		ChestLabelSet first = labelSetForHalf(level, chestPos);
		BlockPos otherPos = otherHalf(chestPos, state);
		if (otherPos == null) {
			return first;
		}
		ChestLabelSet second = labelSetForHalf(level, otherPos);
		if (second.isEmpty()) {
			return first;
		}
		if (first.isEmpty()) {
			return second;
		}
		List<ChestLabel> union = new ArrayList<>(first.labels());
		for (ChestLabel label : second.labels()) {
			if (!union.contains(label)) {
				union.add(label);
			}
		}
		return new ChestLabelSet(union, first.explicit() || second.explicit());
	}

	/**
	 * The cached label set of the (possibly double) chest, read straight
	 * from the synced attachments without re-deriving anything, so it works
	 * on the client too (HUD). Union of both halves, explicit if either is.
	 */
	public static ChestLabelSet cachedLabelSet(net.minecraft.world.level.Level level, BlockPos chestPos, BlockState state) {
		List<ChestLabel> union = new ArrayList<>();
		boolean explicit = false;
		for (BlockPos half : halves(chestPos, state)) {
			BlockEntity blockEntity = level.getBlockEntity(half);
			ChestLabelSet set = blockEntity == null ? null : blockEntity.getAttached(WayBetterCopperGolem.CHEST_LABELS);
			if (set == null) {
				continue;
			}
			explicit |= set.explicit();
			for (ChestLabel label : set.labels()) {
				if (!union.contains(label)) {
					union.add(label);
				}
			}
		}
		return new ChestLabelSet(union, explicit);
	}

	/** Effective labels for the (possibly double) chest at {@code chestPos}. */
	public static List<ChestLabel> effectiveLabels(ServerLevel level, BlockPos chestPos, BlockState state) {
		return effectiveLabelSet(level, chestPos, state).labels();
	}

	/**
	 * Sets the chest's labels on purpose. Both halves of a double chest get
	 * the same explicit set so the container never disagrees with itself,
	 * and frames on it become purely decorative.
	 */
	public static void setExplicit(ServerLevel level, BlockPos chestPos, BlockState state, List<ChestLabel> labels) {
		ChestLabelSet set = ChestLabelSet.explicit(labels);
		for (BlockPos half : halves(chestPos, state)) {
			BlockEntity blockEntity = level.getBlockEntity(half);
			if (blockEntity != null) {
				blockEntity.setAttached(WayBetterCopperGolem.CHEST_LABELS, set);
			}
			for (ItemFrame frame : labelFrames(level, half)) {
				frame.removeAttached(WayBetterCopperGolem.FRAME_TAG);
			}
		}
	}

	/**
	 * Forgets everything stored on the chest, then re-derives from whatever
	 * frames hang on it. Returns the resulting effective set.
	 */
	public static ChestLabelSet clear(ServerLevel level, BlockPos chestPos, BlockState state) {
		for (BlockPos half : halves(chestPos, state)) {
			BlockEntity blockEntity = level.getBlockEntity(half);
			if (blockEntity != null) {
				blockEntity.removeAttached(WayBetterCopperGolem.CHEST_LABELS);
			}
		}
		return effectiveLabelSet(level, chestPos, state);
	}

	/**
	 * Whether golems may hang an item frame on this (possibly double) chest:
	 * true unless either half carries the editor's "no golem frames" mark.
	 * Works on both sides (the mark is synced).
	 */
	public static boolean golemFramesAllowed(net.minecraft.world.level.Level level, BlockPos chestPos, BlockState state) {
		for (BlockPos half : halves(chestPos, state)) {
			BlockEntity blockEntity = level.getBlockEntity(half);
			if (blockEntity != null && Boolean.TRUE.equals(blockEntity.getAttached(WayBetterCopperGolem.CHEST_NO_GOLEM_FRAMES))) {
				return false;
			}
		}
		return true;
	}

	/** Allows or forbids golem frames on the whole (possibly double) chest. */
	public static void setGolemFramesAllowed(ServerLevel level, BlockPos chestPos, BlockState state, boolean allowed) {
		for (BlockPos half : halves(chestPos, state)) {
			BlockEntity blockEntity = level.getBlockEntity(half);
			if (blockEntity == null) {
				continue;
			}
			if (allowed) {
				blockEntity.removeAttached(WayBetterCopperGolem.CHEST_NO_GOLEM_FRAMES);
			} else {
				blockEntity.setAttached(WayBetterCopperGolem.CHEST_NO_GOLEM_FRAMES, true);
			}
		}
	}

	/** Recomputes derived labels for the chest at {@code pos}, if it is one. */
	public static void refresh(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!isLabelableChest(state)) {
			return;
		}
		BlockPos other = otherHalf(pos, state);
		if (other != null && !level.isLoaded(other)) {
			return; // chunk edge during load; the golem path refreshes later
		}
		for (BlockPos half : halves(pos, state)) {
			labelSetForHalf(level, half);
		}
	}

	/** Called when a frame is placed, loaded, or its item changes. */
	public static void onFrameChanged(ServerLevel level, ItemFrame frame) {
		refresh(level, supportPos(frame));
	}

	/**
	 * The label one frame contributes to an unlabeled chest holding
	 * {@code contents}: empty frame = catch-all, cobweb = off-limits,
	 * otherwise the narrowest non-preset stop of the framed item that covers
	 * every item in the chest, falling back to the exact item.
	 */
	public static ChestLabel deriveFromFrame(ServerLevel level, ItemStack framed, Set<Item> contents) {
		if (framed.isEmpty()) {
			return ChestLabel.catchAll();
		}
		Item framedItem = framed.getItem();
		Identifier itemId = BuiltInRegistries.ITEM.getKey(framedItem);
		ChestLabel exact = ChestLabel.exact(itemId);
		if (exact.isOffLimits() || contents.isEmpty()
				|| contents.stream().allMatch(item -> item == framedItem)) {
			return exact;
		}
		for (TagKey<Item> tag : LabelResolver.orderedTags(framedItem)) {
			if (LabelResolver.isPresetCategory(tag)) {
				continue;
			}
			Identifier tagId = tag.location();
			if (contents.stream().allMatch(item -> CategoryTuning.matches(level, tagId, new ItemStack(item)))) {
				return ChestLabel.tag(itemId, tagId);
			}
		}
		return exact;
	}

	/** Distinct items in the whole (possibly double) chest at {@code pos}. */
	public static Set<Item> distinctContents(ServerLevel level, BlockPos pos) {
		Container container = container(level, pos);
		Set<Item> items = new LinkedHashSet<>();
		if (container != null) {
			for (ItemStack stack : container) {
				if (!stack.isEmpty()) {
					items.add(stack.getItem());
				}
			}
		}
		return items;
	}

	/** The full container at {@code pos}, spanning both halves of a double chest. */
	public static @Nullable Container container(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		BlockPos other = otherHalf(pos, state);
		if (state.getBlock() instanceof ChestBlock chest && (other == null || level.isLoaded(other))) {
			Container combined = ChestBlock.getContainer(chest, state, level, pos, true);
			if (combined != null) {
				return combined;
			}
		}
		return level.getBlockEntity(pos) instanceof Container container ? container : null;
	}

	/** The positions of every half of the chest at {@code pos} (one or two). */
	public static List<BlockPos> halves(BlockPos pos, BlockState state) {
		BlockPos other = otherHalf(pos, state);
		return other == null ? List.of(pos) : List.of(pos, other);
	}

	private static @Nullable BlockPos otherHalf(BlockPos pos, BlockState state) {
		if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) == ChestType.SINGLE) {
			return null;
		}
		return ChestBlock.getConnectedBlockPos(pos, state);
	}

	private static ChestLabelSet derive(ServerLevel level, BlockPos chestPos, List<ItemFrame> frames) {
		Set<Item> contents = distinctContents(level, chestPos);
		List<ChestLabel> labels = new ArrayList<>(frames.size());
		for (ItemFrame frame : frames) {
			ChestLabel label = deriveFromFrame(level, frame.getItem(), contents);
			if (!labels.contains(label)) {
				labels.add(label);
			}
		}
		return ChestLabelSet.derived(labels);
	}

	/**
	 * Worlds from before the chest-owned model stored the chosen tag on the
	 * frame itself. The first time such a frame is seen, its chest gets the
	 * equivalent explicit set and the frame attachment is dropped, so the
	 * player's old choices survive and the frame becomes decorative.
	 */
	private static @Nullable ChestLabelSet migrateLegacyFrameTags(List<ItemFrame> frames) {
		boolean anyLegacy = frames.stream().anyMatch(frame -> frame.hasAttached(WayBetterCopperGolem.FRAME_TAG));
		if (!anyLegacy) {
			return null;
		}
		List<ChestLabel> labels = new ArrayList<>(frames.size());
		for (ItemFrame frame : frames) {
			ItemStack framed = frame.getItem();
			Identifier tagId = frame.removeAttached(WayBetterCopperGolem.FRAME_TAG);
			ChestLabel label;
			if (framed.isEmpty()) {
				label = ChestLabel.catchAll();
			} else {
				Identifier itemId = BuiltInRegistries.ITEM.getKey(framed.getItem());
				label = tagId == null ? ChestLabel.exact(itemId) : ChestLabel.tag(itemId, tagId);
			}
			if (!labels.contains(label)) {
				labels.add(label);
			}
		}
		return ChestLabelSet.explicit(labels);
	}
}
