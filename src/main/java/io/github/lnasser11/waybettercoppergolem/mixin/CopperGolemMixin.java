package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Runtime-only zone tracking for golems: the copper chest a golem last
 * picked up from decides which zone settings it obeys. Nothing here is
 * written to the entity's saved data.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements ZoneAwareGolem {
	@Unique
	private @Nullable BlockPos wbcg$zoneChest;
	@Unique
	private ZoneSettings wbcg$cachedSettings = ZoneSettings.DEFAULT;
	@Unique
	private long wbcg$settingsCachedAt = Long.MIN_VALUE;

	@Override
	public void wbcg$setZoneChest(BlockPos pos) {
		if (!pos.equals(this.wbcg$zoneChest)) {
			this.wbcg$zoneChest = pos.immutable();
			this.wbcg$settingsCachedAt = Long.MIN_VALUE;
		}
	}

	@Override
	public @Nullable BlockPos wbcg$zoneChest() {
		return this.wbcg$zoneChest;
	}

	@Unique
	private long wbcg$nextReorganizeTime;

	@Override
	public long wbcg$nextReorganizeTime() {
		return this.wbcg$nextReorganizeTime;
	}

	@Override
	public void wbcg$setNextReorganizeTime(long gameTime) {
		this.wbcg$nextReorganizeTime = gameTime;
	}

	@Override
	public ZoneSettings wbcg$zoneSettings(ServerLevel level) {
		long now = level.getGameTime();
		if (now - this.wbcg$settingsCachedAt >= 100) {
			this.wbcg$cachedSettings = Zones.at(level, this.wbcg$zoneChest);
			this.wbcg$settingsCachedAt = now;
		}
		return this.wbcg$cachedSettings;
	}
}
