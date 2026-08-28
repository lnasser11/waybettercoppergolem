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
}
