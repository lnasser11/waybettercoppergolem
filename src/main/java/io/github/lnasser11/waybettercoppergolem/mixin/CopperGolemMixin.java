package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Optional;

/**
 * Runtime-only zone tracking for golems; see {@link ZoneAwareGolem} for
 * which zone a golem works for. Nothing here is written to the entity's
 * saved data.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements ZoneAwareGolem {
	@Unique
	private static final int WBCG$ZONE_CACHE_TICKS = 40;

	@Unique
	private @Nullable BlockPos wbcg$zoneChest;
	@Unique
	private Optional<Zones.ZoneRef> wbcg$cachedZone = Optional.empty();
	@Unique
	private long wbcg$zoneCachedAt;
	@Unique
	private boolean wbcg$zoneCachedWhileCarrying;
	@Unique
	private boolean wbcg$zoneCacheDirty = true;
	@Unique
	private long wbcg$nextReorganizeTime;

	@Override
	public void wbcg$setZoneChest(BlockPos pos) {
		if (!pos.equals(this.wbcg$zoneChest)) {
			this.wbcg$zoneChest = pos.immutable();
			this.wbcg$zoneCacheDirty = true;
		}
	}

	@Override
	public @Nullable BlockPos wbcg$zoneChest() {
		return this.wbcg$zoneChest;
	}

	@Override
	public long wbcg$nextReorganizeTime() {
		return this.wbcg$nextReorganizeTime;
	}

	@Override
	public void wbcg$setNextReorganizeTime(long gameTime) {
		this.wbcg$nextReorganizeTime = gameTime;
	}

	@Override
	public Optional<Zones.ZoneRef> wbcg$zone(ServerLevel level) {
		CopperGolem self = (CopperGolem) (Object) this;
		boolean carrying = !self.getMainHandItem().isEmpty();
		long now = level.getGameTime();
		if (this.wbcg$zoneCacheDirty || carrying != this.wbcg$zoneCachedWhileCarrying
				|| now - this.wbcg$zoneCachedAt >= WBCG$ZONE_CACHE_TICKS || now < this.wbcg$zoneCachedAt) {
			Optional<Zones.ZoneRef> bySource = Zones.zoneAt(level, this.wbcg$zoneChest);
			Optional<Zones.ZoneRef> byPosition = Zones.zoneAt(level, self.blockPosition());
			this.wbcg$cachedZone = carrying
					? bySource.or(() -> byPosition)
					: byPosition.or(() -> bySource);
			this.wbcg$zoneCachedAt = now;
			this.wbcg$zoneCachedWhileCarrying = carrying;
			this.wbcg$zoneCacheDirty = false;
		}
		return this.wbcg$cachedZone;
	}

	@Override
	public ZoneSettings wbcg$zoneSettings(ServerLevel level) {
		return wbcg$zone(level).map(Zones.ZoneRef::settings).orElse(ZoneSettings.DEFAULT);
	}

	@Override
	public AABB wbcg$searchArea(ServerLevel level, int horizontal, int vertical) {
		CopperGolem self = (CopperGolem) (Object) this;
		return wbcg$zone(level)
				.map(zone -> Zones.toAABB(zone.area()))
				.orElseGet(() -> new AABB(self.blockPosition()).inflate(horizontal, vertical, horizontal));
	}
}
