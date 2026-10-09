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
	@Unique
	private @Nullable BlockPos wbcg$pendingFrameChest;
	@Unique
	private @Nullable BlockPos wbcg$tidyDestination;
	@Unique
	private @Nullable BlockPos wbcg$frameReturnChest;

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
	public @Nullable BlockPos wbcg$pendingFrameChest() {
		return this.wbcg$pendingFrameChest;
	}

	@Override
	public void wbcg$setPendingFrameChest(@Nullable BlockPos pos) {
		this.wbcg$pendingFrameChest = pos == null ? null : pos.immutable();
	}

	@Override
	public @Nullable BlockPos wbcg$frameReturnChest() {
		return this.wbcg$frameReturnChest;
	}

	@Override
	public void wbcg$setFrameReturnChest(@Nullable BlockPos pos) {
		this.wbcg$frameReturnChest = pos == null ? null : pos.immutable();
	}

	@Override
	public @Nullable BlockPos wbcg$tidyDestination() {
		return this.wbcg$tidyDestination;
	}

	@Override
	public void wbcg$setTidyDestination(@Nullable BlockPos pos) {
		this.wbcg$tidyDestination = pos == null ? null : pos.immutable();
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
			WayBetterCopperGolem.LOGGER.info("[golem] {} left the zone at {} ({})", self.getUUID(), anchor,
					zone == null ? "zone removed" : "anchor is no longer a copper chest");
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
			WayBetterCopperGolem.LOGGER.info("[golem] {} joined the zone at {}", self.getUUID(), ref.anchor());
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

	@Override
	public boolean wbcg$isPerched(ServerLevel level) {
		CopperGolem self = (CopperGolem) (Object) this;
		if (!wbcg$zoneSettings(level).perchIdle() || !self.getMainHandItem().isEmpty()) {
			return false;
		}
		// A chest is 7/8 high, so a golem standing on one has the chest as its own block position.
		// (No onGround check: a bob or a hop must not open a window for a stroll off the perch.)
		BlockPos feet = self.blockPosition();
		return wbcg$isChest(level.getBlockState(feet)) || wbcg$isChest(level.getBlockState(feet.below()));
	}

	@Unique
	private static boolean wbcg$isChest(net.minecraft.world.level.block.state.BlockState state) {
		return io.github.lnasser11.waybettercoppergolem.label.ChestLabels.isLabelableChest(state) || state.is(BlockTags.COPPER_CHESTS);
	}

	/**
	 * Once a second, two chores. A confined golem standing outside its box
	 * (pushed, fallen, teleported) gets a walk target at the nearest spot
	 * inside, unless it is already walking somewhere inside; paths are not
	 * clipped while the golem is outside, so any route back is allowed. And
	 * with "idle golems perch" on, a golem with nothing to do (empty hand,
	 * transport cooldown running, no walk target) heads for the nearest
	 * free chest top in its zone and stands there like a statue; the wake
	 * on a copper chest change ends the cooldown and so the pose.
	 */
	@Inject(method = "customServerAiStep", at = @At("TAIL"))
	private void wbcg$walkBackInside(ServerLevel level, CallbackInfo ci) {
		CopperGolem self = (CopperGolem) (Object) this;
		wbcg$holdThePose(level, self);
		if (self.tickCount % WBCG$WALK_BACK_EVERY_TICKS != 0) {
			return;
		}
		wbcg$perchWhenIdle(level, self);
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

	/**
	 * Every tick while perched and idle (transport cooldown running): any
	 * walk target that is not the transport behavior's own is dropped and
	 * the navigation stopped, so nothing (a stroll, a partial step toward a
	 * stale target) moves the statue. The behavior itself erases the
	 * cooldown when there is work, and then this stays out of the way.
	 */
	@Unique
	private void wbcg$holdThePose(ServerLevel level, CopperGolem self) {
		Brain<CopperGolem> brain = self.getBrain();
		if (!brain.hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS) || !wbcg$isPerched(level)) {
			return;
		}
		if (brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
			brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		}
		if (!self.getNavigation().isDone()) {
			self.getNavigation().stop();
		}
	}

	@Unique
	private void wbcg$perchWhenIdle(ServerLevel level, CopperGolem self) {
		Optional<Zones.ZoneRef> zone = wbcg$zone(level);
		if (zone.isEmpty() || !zone.get().settings().perchIdle() || !self.getMainHandItem().isEmpty()) {
			return;
		}
		Brain<CopperGolem> brain = self.getBrain();
		if (!brain.hasMemoryValue(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS)
				|| brain.hasMemoryValue(MemoryModuleType.WALK_TARGET) || wbcg$isPerched(level)) {
			return;
		}
		BoundingBox box = zone.get().area();
		if (!box.isInside(self.blockPosition())) {
			return;
		}
		BlockPos perch = wbcg$nearestFreePerch(level, self, box);
		if (perch != null) {
			brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(new BlockPosTracker(perch.above()), WBCG$WALK_BACK_SPEED, 0));
		}
	}

	/** The nearest chest in the box with two blocks of air above it and no other golem standing on it. */
	@Unique
	private static @Nullable BlockPos wbcg$nearestFreePerch(ServerLevel level, CopperGolem self, BoundingBox box) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		net.minecraft.world.level.ChunkPos minChunk = net.minecraft.world.level.ChunkPos.containing(new BlockPos(box.minX(), box.minY(), box.minZ()));
		net.minecraft.world.level.ChunkPos maxChunk = net.minecraft.world.level.ChunkPos.containing(new BlockPos(box.maxX(), box.maxY(), box.maxZ()));
		for (net.minecraft.world.level.ChunkPos chunkPos : net.minecraft.world.level.ChunkPos.rangeClosed(minChunk, maxChunk).toList()) {
			net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
			if (chunk == null) {
				continue;
			}
			for (net.minecraft.world.level.block.entity.BlockEntity blockEntity : chunk.getBlockEntities().values()) {
				BlockPos pos = blockEntity.getBlockPos();
				if (!(blockEntity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) || !box.isInside(pos)
						|| !wbcg$isChest(blockEntity.getBlockState())) {
					continue;
				}
				double distSq = pos.distToCenterSqr(self.position());
				if (distSq >= bestDistSq || !level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.above(2)).isAir()) {
					continue;
				}
				boolean taken = level.getEntitiesOfClass(CopperGolem.class, new AABB(pos).expandTowards(0, 1, 0), other -> other != self).stream()
						.anyMatch(other -> other.blockPosition().equals(pos) || other.blockPosition().below().equals(pos));
				if (!taken) {
					best = pos.immutable();
					bestDistSq = distSq;
				}
			}
		}
		return best;
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
