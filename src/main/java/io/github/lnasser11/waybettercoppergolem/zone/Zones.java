package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.jspecify.annotations.Nullable;

public final class Zones {
	private Zones() {
	}

	/** Settings stored on the copper chest at {@code pos}, or defaults. */
	public static ZoneSettings at(ServerLevel level, @Nullable BlockPos pos) {
		if (pos == null || !level.isLoaded(pos) || !level.getBlockState(pos).is(BlockTags.COPPER_CHESTS)) {
			return ZoneSettings.DEFAULT;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return ZoneSettings.DEFAULT;
		}
		ZoneSettings settings = blockEntity.getAttached(WayBetterCopperGolem.ZONE_SETTINGS);
		return settings == null ? ZoneSettings.DEFAULT : settings;
	}

	public static void store(BlockEntity copperChest, ZoneSettings settings) {
		copperChest.setAttached(WayBetterCopperGolem.ZONE_SETTINGS, settings);
	}

	/**
	 * Nearest copper chest to {@code center}, or null. Used to bind a golem
	 * to its sorting zone even before its first successful pickup, so zone
	 * settings (dry-run included) always apply to golems working a room.
	 */
	public static net.minecraft.core.@Nullable BlockPos findNearestCopperChest(
			ServerLevel level, net.minecraft.core.BlockPos center, int radius) {
		net.minecraft.core.BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (net.minecraft.world.level.ChunkPos chunkPos : net.minecraft.world.level.ChunkPos.rangeClosed(
				net.minecraft.world.level.ChunkPos.containing(center), Math.floorDiv(radius, 16) + 1).toList()) {
			net.minecraft.world.level.chunk.LevelChunk chunk =
					level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				if (!blockEntity.getBlockState().is(BlockTags.COPPER_CHESTS)) {
					continue;
				}
				net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
				double distSq = pos.distToCenterSqr(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5);
				if (distSq < bestDistSq && distSq <= (double) radius * radius) {
					best = pos;
					bestDistSq = distSq;
				}
			}
		}
		return best;
	}
}
