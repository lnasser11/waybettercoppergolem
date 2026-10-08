package io.github.lnasser11.waybettercoppergolem.sorting;

import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Duck interface implemented onto {@code CopperGolem} by mixin. Runtime-only
 * state: which copper chest this golem last picked up from, and the zone
 * it is currently working for. Nothing here is persisted to the entity.
 *
 * <p>Which zone a golem works for: while carrying items, the zone of the
 * copper chest it took them from (so it still delivers to the right room
 * after walking out of the box); while empty-handed, the zone it is
 * standing in, so it can start serving whichever room it wandered into.
 * Outside every zone it behaves as before: default settings, vanilla
 * search volume around itself.
 */
public interface ZoneAwareGolem {
	void wbcg$setZoneChest(BlockPos pos);

	@Nullable BlockPos wbcg$zoneChest();

	Optional<Zones.ZoneRef> wbcg$zone(ServerLevel level);

	ZoneSettings wbcg$zoneSettings(ServerLevel level);

	/**
	 * Where this golem looks for chests: the zone's box, or the vanilla
	 * volume around the golem when it is in no zone.
	 */
	AABB wbcg$searchArea(ServerLevel level, int horizontal, int vertical);

	/** Game time before which this golem won't look for misplaced stacks. */
	long wbcg$nextReorganizeTime();

	void wbcg$setNextReorganizeTime(long gameTime);
}
