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

	/** "radius 32 · reach 4 · reorganize on · tidy off · dry run off". */
	public static net.minecraft.network.chat.Component describe(ZoneSettings settings) {
		return net.minecraft.network.chat.Component.translatable("waybettercoppergolem.zone.summary",
				settings.searchRadius(), settings.verticalReach(),
				onOff(settings.reorganize()), onOff(settings.tidyInside()), onOff(settings.dryRun()));
	}

	private static net.minecraft.network.chat.Component onOff(boolean value) {
		return value ? net.minecraft.network.chat.CommonComponents.OPTION_ON
				: net.minecraft.network.chat.CommonComponents.OPTION_OFF;
	}

	public static void store(BlockEntity copperChest, ZoneSettings settings) {
		copperChest.setAttached(WayBetterCopperGolem.ZONE_SETTINGS, settings);
	}
}
