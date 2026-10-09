package io.github.lnasser11.waybettercoppergolem.sorting;

import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Duck interface implemented onto {@code CopperGolem} by mixin: which
 * copper chest this golem last picked up from, and the zone it works for.
 *
 * <p>Which zone a golem works for: once it has taken from a copper chest
 * inside a zone it <em>belongs</em> to that zone (the anchor is stored in
 * a persistent attachment) until the zone is removed, wherever it is
 * standing. Before that: while carrying items, the zone of the copper
 * chest it took them from; while empty-handed, the zone it is standing
 * in, so it can start serving whichever room it wandered into. Outside
 * every zone it behaves as before: default settings, vanilla search
 * volume around itself.
 *
 * <p>A golem that belongs to a zone whose {@link ZoneSettings#stayInside()}
 * is on never paths outside the zone's box ({@link #wbcg$confinement}).
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

	/** The anchor of the zone this golem belongs to, if it has joined one. */
	@Nullable BlockPos wbcg$homeZoneAnchor();

	/**
	 * Makes the zone containing {@code copperChest} this golem's zone, if
	 * there is one and the golem has none yet (or its zone is gone).
	 */
	void wbcg$joinZoneAt(ServerLevel level, BlockPos copperChest);

	/**
	 * The box this golem must stay inside: its zone's area when it belongs
	 * to a zone with "golems stay inside" on, otherwise empty.
	 */
	Optional<BoundingBox> wbcg$confinement(ServerLevel level);

	/**
	 * A labeled chest this golem delivered into whose front face has no
	 * frame yet, waiting for a frame trip (see {@link FrameHanger}).
	 * Runtime only.
	 */
	@Nullable BlockPos wbcg$pendingFrameChest();

	void wbcg$setPendingFrameChest(@Nullable BlockPos pos);

	/** The home chest of the tidy stack this golem is carrying, if it is on a tidy trip. Runtime only. */
	@Nullable BlockPos wbcg$tidyDestination();

	void wbcg$setTidyDestination(@Nullable BlockPos pos);
}
