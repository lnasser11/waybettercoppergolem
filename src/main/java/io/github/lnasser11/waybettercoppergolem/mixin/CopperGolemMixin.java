package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Zone tracking for golems; see {@link ZoneAwareGolem} for which zone a
 * golem works for. The zone a golem has joined lives in a persistent
 * attachment ({@link WayBetterCopperGolem#GOLEM_ZONE}); everything else
 * here is runtime-only. A golem whose zone keeps its golems inside and
 * finds itself outside the box walks back to the nearest point inside.
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin implements ZoneAwareGolem {
	@Unique
	private static final int WBCG$ZONE_CACHE_TICKS = 40;
	@Unique
	private static final int WBCG$WALK_BACK_EVERY_TICKS = 20;
	@Unique
	private static final float WBCG$WALK_BACK_SPEED = 1.0F;

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
	public @Nullable BlockPos wbcg$homeZoneAnchor() {
		CopperGolem self = (CopperGolem) (Object) this;
		return self.getAttached(WayBetterCopperGolem.GOLEM_ZONE);
	}

	/**
	 * The zone this golem has joined, if it still exists. A zone whose
	 * registry entry is gone, or whose anchor is no longer a copper chest,
	 * releases the golem: the attachment is dropped and it may join
	 * another zone.
	 */
	@Unique
	private Optional<Zones.ZoneRef> wbcg$homeZone(ServerLevel level) {
		CopperGolem self = (CopperGolem) (Object) this;
		BlockPos anchor = self.getAttached(WayBetterCopperGolem.GOLEM_ZONE);
		if (anchor == null) {
			return Optional.empty();
		}
		Zone zone = Zones.all(level).get(anchor);
		boolean anchorGone = level.isLoaded(anchor) && !level.getBlockState(anchor).is(BlockTags.COPPER_CHESTS);
		if (zone == null || anchorGone) {
			self.removeAttached(WayBetterCopperGolem.GOLEM_ZONE);
			this.wbcg$zoneCacheDirty = true;
			WayBetterCopperGolem.LOGGER.debug("[WBCG-DEBUG] golem {} left the removed zone at {}", self.getUUID(), anchor);
			return Optional.empty();
		}
		return Optional.of(new Zones.ZoneRef(anchor, zone));
	}

	@Override
	public void wbcg$joinZoneAt(ServerLevel level, BlockPos copperChest) {
		if (wbcg$homeZone(level).isPresent()) {
			return;
		}
		CopperGolem self = (CopperGolem) (Object) this;
		Zones.zoneAt(level, copperChest).ifPresent(ref -> {
			self.setAttached(WayBetterCopperGolem.GOLEM_ZONE, ref.anchor());
			this.wbcg$zoneCacheDirty = true;
			WayBetterCopperGolem.LOGGER.debug("[WBCG-DEBUG] golem {} joined the zone at {}", self.getUUID(), ref.anchor());
		});
	}

	@Override
	public Optional<BoundingBox> wbcg$confinement(ServerLevel level) {
		return wbcg$homeZone(level).filter(ref -> ref.settings().stayInside()).map(Zones.ZoneRef::area);
	}

	@Override
	public Optional<Zones.ZoneRef> wbcg$zone(ServerLevel level) {
		CopperGolem self = (CopperGolem) (Object) this;
		boolean carrying = !self.getMainHandItem().isEmpty();
		long now = level.getGameTime();
		if (this.wbcg$zoneCacheDirty || carrying != this.wbcg$zoneCachedWhileCarrying
				|| now - this.wbcg$zoneCachedAt >= WBCG$ZONE_CACHE_TICKS || now < this.wbcg$zoneCachedAt) {
			Optional<Zones.ZoneRef> home = wbcg$homeZone(level);
			if (home.isPresent()) {
				this.wbcg$cachedZone = home;
			} else {
				Optional<Zones.ZoneRef> bySource = Zones.zoneAt(level, this.wbcg$zoneChest);
				Optional<Zones.ZoneRef> byPosition = Zones.zoneAt(level, self.blockPosition());
				this.wbcg$cachedZone = carrying
						? bySource.or(() -> byPosition)
						: byPosition.or(() -> bySource);
			}
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

	/**
	 * Once a second: a confined golem standing outside its box (pushed,
	 * fallen, teleported) gets a walk target at the nearest spot inside,
	 * unless it is already walking somewhere inside. Paths are not clipped
	 * while the golem is outside, so any route back is allowed.
	 */
	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void wbcg$walkBackInside(ServerLevel level, CallbackInfo ci) {
		CopperGolem self = (CopperGolem) (Object) this;
		if (self.tickCount % WBCG$WALK_BACK_EVERY_TICKS != 0) {
			return;
		}
		Optional<BoundingBox> confinement = wbcg$confinement(level);
		if (confinement.isEmpty()) {
			return;
		}
		BoundingBox box = confinement.get();
		BlockPos here = self.blockPosition();
		if (box.isInside(here)) {
			return;
		}
		Brain<CopperGolem> brain = self.getBrain();
		Optional<WalkTarget> current = brain.getMemory(MemoryModuleType.WALK_TARGET);
		if (current.isPresent() && box.isInside(current.get().getTarget().currentBlockPosition())) {
			return;
		}
		brain.setMemory(MemoryModuleType.WALK_TARGET,
				new WalkTarget(new BlockPosTracker(wbcg$nearestInside(box, here)), WBCG$WALK_BACK_SPEED, 0));
	}

	/** The position inside the box nearest to {@code pos}, one block in from any face it was clamped to. */
	@Unique
	private static BlockPos wbcg$nearestInside(BoundingBox box, BlockPos pos) {
		return new BlockPos(
				wbcg$clampInward(pos.getX(), box.minX(), box.maxX()),
				Math.clamp(pos.getY(), box.minY(), box.maxY()),
				wbcg$clampInward(pos.getZ(), box.minZ(), box.maxZ()));
	}

	@Unique
	private static int wbcg$clampInward(int value, int min, int max) {
		if (value < min) {
			return Math.min(min + 1, max);
		}
		if (value > max) {
			return Math.max(max - 1, min);
		}
		return value;
	}
}
