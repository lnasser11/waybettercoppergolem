package io.github.lnasser11.waybettercoppergolem.sorting;

import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jspecify.annotations.Nullable;

/**
 * Duck interface implemented onto {@code CopperGolem} by mixin. Runtime-only
 * state: which copper chest this golem last worked out of, and that chest's
 * cached zone settings. Nothing here is persisted to the entity.
 */
public interface ZoneAwareGolem {
	void wbcg$setZoneChest(BlockPos pos);

	@Nullable BlockPos wbcg$zoneChest();

	ZoneSettings wbcg$zoneSettings(ServerLevel level);
}
