package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger;
import io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims;
import io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The behavior is generic, but in vanilla only copper golems instantiate it;
 * every injection below additionally guards on {@code instanceof CopperGolem}
 * so another mod reusing the behavior keeps stock semantics.
 */
@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {
	@Shadow
	private TransportItemsBetweenContainers.@Nullable TransportItemTarget target;

	@Shadow
	@Final
	private Predicate<BlockState> destinationBlockType;

	@Shadow
	@Final
	private Predicate<BlockState> sourceBlockType;

	@Shadow
	@Final
	private int horizontalSearchDistance;

	@Shadow
	@Final
	private int verticalSearchDistance;

	@Shadow
	private static boolean matchesLeavingItemsRequirement(PathfinderMob body, Container container) {
		throw new AssertionError();
	}

	@Shadow
	protected abstract void stopTargetingCurrentTarget(PathfinderMob body);

	@Shadow
	protected abstract void clearMemoriesAfterMatchingTargetFound(PathfinderMob body);

	/** True while the current target is a reorganize pickup (a labeled chest, not a copper chest). */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$reorganizeActive;

	/** True while the current target is the source chest of a tidy move (a labeled chest with a minority stack). */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$tidyActive;

	/** The move planned for the current tidy pickup. */
	@org.spongepowered.asm.mixin.Unique
	private SortingEngine.@Nullable TidyMove wbcg$tidyPlan;

	/** True while the current target is a labeled chest the golem visits only to sort its stacks in place. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$sortActive;

	/** True while the current target is a copper chest the golem fetches an item frame from. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$frameTripActive;

	/** The chest the golem is about to deposit into, kept because the target is cleared by the deposit itself. */
	@org.spongepowered.asm.mixin.Unique
	private @Nullable BlockPos wbcg$depositPos;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$REORGANIZE_SUCCESS_COOLDOWN = 200;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$REORGANIZE_IDLE_COOLDOWN = 1200;

	/**
	 * A golem working for a zone only sees chests inside the zone's box:
	 * the vanilla search volume (used for the copper-chest pickup) is cut
	 * down to the zone. Outside every zone the vanilla volume stands.
	 */
	@Inject(method = "getTargetSearchArea", at = @At("RETURN"), cancellable = true)
	private void wbcg$clipSearchAreaToZone(PathfinderMob body, CallbackInfoReturnable<net.minecraft.world.phys.AABB> cir) {
		if (body instanceof CopperGolem && body.level() instanceof ServerLevel level) {
			((ZoneAwareGolem) body).wbcg$zone(level).ifPresent(zone ->
					cir.setReturnValue(cir.getReturnValue().intersect(
							io.github.lnasser11.waybettercoppergolem.zone.Zones.toAABB(zone.area()))));
		}
	}

	/**
	 * While a golem is holding an item, destination selection is ours:
	 * ranked by label specificity instead of nearest-blind, within the
	 * zone's box.
	 */
	@Inject(method = "getTransportTarget", at = @At("HEAD"), cancellable = true)
	private void wbcg$labelAwareDestination(ServerLevel level, PathfinderMob body,
			CallbackInfoReturnable<Optional<TransportItemTarget>> cir) {
		if (!(body instanceof CopperGolem)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		if (io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemsRequireZone()
				&& golem.wbcg$zone(level).isEmpty()) {
			cir.setReturnValue(Optional.empty()); // golems outside every zone do nothing on this server
			return;
		}
		ItemStack held = body.getMainHandItem();
		BlockPos pending = golem.wbcg$pendingFrameChest();
		if (held.isEmpty()) {
			golem.wbcg$setTidyDestination(null);
			golem.wbcg$setFrameReturnChest(null);
			ZoneSettings settings = golem.wbcg$zoneSettings(level);
			// A frame trip: fetch a frame for the bare chest the golem last delivered into.
			if (pending != null) {
				if (settings.hangFrames() && !settings.dryRun() && FrameHanger.wantsFrame(level, pending)) {
					Optional<TransportItemTarget> source = FrameHanger.findFrameSource(level, body.position(),
							wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS), wbcg$searchArea(body, level));
					if (source.isPresent()) {
						this.wbcg$frameTripActive = true;
						cir.setReturnValue(source);
						return;
					}
				}
				golem.wbcg$setPendingFrameChest(null); // nothing wanted or no frames in the zone's copper chests
			}
			// The copper chest to take from: vanilla's nearest-first, but spread over several golems and
			// skipping chests that hold only frame supplies. No candidate at all: vanilla finds none either,
			// and the reorganize / tidy search below gets its turn.
			Optional<TransportItemTarget> source = SortingEngine.findSource(level, body.position(), this.sourceBlockType,
					wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
					wbcg$searchArea(body, level), GolemClaims.claimedByOthers(level, body), settings.hangFrames());
			if (source.isPresent()) {
				cir.setReturnValue(source);
			}
			return;
		}
		BlockPos tidyHome = golem.wbcg$tidyDestination();
		if (tidyHome != null) {
			// Carrying a tidy stack: it goes to the home chest it was planned for, not just any sibling.
			TransportItemTarget home = TransportItemTarget.tryCreatePossibleTarget(tidyHome, level);
			if (home != null && !wbcg$isUnreachable(body, level, tidyHome) && this.destinationBlockType.test(home.state())
					&& SortingEngine.acceptsDeposit(level, home, body)) {
				cir.setReturnValue(Optional.of(home));
				return;
			}
			golem.wbcg$setTidyDestination(null); // the home changed; the stack is delivered like any item
		}
		if (pending != null && FrameHanger.isFrame(held)) {
			// Carrying the frame: the destination is the chest it is meant for.
			TransportItemTarget chest = TransportItemTarget.tryCreatePossibleTarget(pending, level);
			if (chest != null && !wbcg$isUnreachable(body, level, pending) && FrameHanger.wantsFrame(level, pending)) {
				cir.setReturnValue(Optional.of(chest));
				return;
			}
			golem.wbcg$setPendingFrameChest(null); // the chest changed its mind (front taken, frame hung by someone) or cannot be reached: give up
		}
		if (FrameHanger.isFrame(held) && golem.wbcg$frameReturnChest() != null) {
			// Giving up: the frame goes back to the copper chest it came from.
			TransportItemTarget back = TransportItemTarget.tryCreatePossibleTarget(golem.wbcg$frameReturnChest(), level);
			if (back != null && !wbcg$isUnreachable(body, level, back.pos()) && back.state().is(BlockTags.COPPER_CHESTS)
					&& SortingEngine.canAcceptAny(back.container(), held)) {
				cir.setReturnValue(Optional.of(back));
				return;
			}
			golem.wbcg$setFrameReturnChest(null); // the copper chest is gone or full; the frame is delivered like any item
		}
		cir.setReturnValue(SortingEngine.findDepositTarget(
				level, body.position(), body.getMainHandItem(), this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				wbcg$searchArea(body, level), GolemClaims.claimedByOthers(level, body)));
	}

	/**
	 * Whether vanilla has marked this chest unreachable for the golem. The remembered chests (tidy home, pending
	 * frame chest, frame return chest) skip the normal search, so they have to consult this memory themselves;
	 * otherwise a chest that became unreachable mid-trip would be re-targeted every tick for good.
	 */
	@org.spongepowered.asm.mixin.Unique
	private static boolean wbcg$isUnreachable(PathfinderMob body, ServerLevel level, BlockPos pos) {
		return wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS).contains(new GlobalPos(level.dimension(), pos));
	}

	/** A golem that starts travelling to a chest claims it, so the others prefer another one. */
	@Inject(method = "onStartTravelling", at = @At("TAIL"))
	private void wbcg$claimTarget(PathfinderMob body, CallbackInfo ci) {
		if (body instanceof CopperGolem && this.target != null && body.level() instanceof ServerLevel level) {
			GolemClaims.claim(level, this.target.pos(), body);
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private net.minecraft.world.phys.AABB wbcg$searchArea(PathfinderMob body, ServerLevel level) {
		return ((ZoneAwareGolem) body).wbcg$searchArea(level, this.horizontalSearchDistance, this.verticalSearchDistance);
	}

	/**
	 * Reorganize-existing-chests: when the golem is empty-handed and vanilla
	 * found no copper chest worth visiting, offer a labeled chest containing
	 * a misplaced stack as the pickup source instead. Background work that
	 * only runs when the dump queue is idle: one move per ten seconds, a
	 * minute's pause when the storage room is already tidy.
	 */
	@Inject(method = "getTransportTarget", at = @At("RETURN"), cancellable = true)
	private void wbcg$reorganizeSource(ServerLevel level, PathfinderMob body,
			CallbackInfoReturnable<Optional<TransportItemTarget>> cir) {
		if (!(body instanceof CopperGolem) || !body.getMainHandItem().isEmpty()
				|| cir.getReturnValue().isPresent()) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		ZoneSettings settings = golem.wbcg$zoneSettings(level);
		long now = level.getGameTime();
		if ((!settings.reorganize() && !settings.tidyInside()) || now < golem.wbcg$nextReorganizeTime()) {
			return;
		}
		if (settings.reorganize()) {
			Optional<TransportItemTarget> source = SortingEngine.findMisplacedSource(
					level, body.position(), this.destinationBlockType,
					wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
					wbcg$searchArea(body, level));
			if (source.isPresent()) {
				this.wbcg$reorganizeActive = true;
				cir.setReturnValue(source);
				return;
			}
		}
		if (settings.tidyInside()) {
			// Consolidation across sibling chests: one minority stack per trip into its home chest.
			Optional<SortingEngine.TidyMove> move = SortingEngine.findTidyMove(level, body.position(),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
					wbcg$searchArea(body, level),
					io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize());
			if (move.isPresent()) {
				this.wbcg$tidyActive = true;
				this.wbcg$tidyPlan = move.get();
				cir.setReturnValue(Optional.of(move.get().source()));
				return;
			}
			// Nothing to consolidate: visit a chest whose stacks are out of order and sort it in place.
			Optional<TransportItemTarget> untidy = SortingEngine.findUntidyChest(level, body.position(),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS), wbcg$searchArea(body, level));
			if (untidy.isPresent()) {
				this.wbcg$sortActive = true;
				cir.setReturnValue(untidy);
				return;
			}
		}
		golem.wbcg$setNextReorganizeTime(now + WBCG$REORGANIZE_IDLE_COOLDOWN);
	}

	/**
	 * Mid-trip target validation checks the source block type predicate
	 * (copper chests) while the hand is empty; a reorganize pickup source is
	 * a regular chest, so it needs a pass of its own.
	 */
	@Inject(method = "isWantedBlock", at = @At("HEAD"), cancellable = true)
	private void wbcg$reorganizeWantedBlock(PathfinderMob mob, BlockState block,
			CallbackInfoReturnable<Boolean> cir) {
		if ((this.wbcg$reorganizeActive || this.wbcg$tidyActive || this.wbcg$sortActive) && mob instanceof CopperGolem
				&& mob.getMainHandItem().isEmpty()
				&& io.github.lnasser11.waybettercoppergolem.label.ChestLabels.isLabelableChest(block)) {
			cir.setReturnValue(true);
		}
		if (mob instanceof CopperGolem && wbcg$isFrameReturn(mob) && block.is(BlockTags.COPPER_CHESTS)) {
			cir.setReturnValue(true);
		}
	}

	/** While bringing an unused frame back to the copper chest it came from, and that chest is the current target. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$isFrameReturn(PathfinderMob body) {
		BlockPos back = ((ZoneAwareGolem) body).wbcg$frameReturnChest();
		return back != null && this.target != null && back.equals(this.target.pos())
				&& FrameHanger.isFrame(body.getMainHandItem());
	}

	@Inject(method = "stopTargetingCurrentTarget", at = @At("TAIL"))
	private void wbcg$clearReorganizeFlag(PathfinderMob body, CallbackInfo ci) {
		if (body instanceof CopperGolem) {
			GolemClaims.release(body);
		}
		this.wbcg$reorganizeActive = false;
		this.wbcg$tidyActive = false;
		this.wbcg$sortActive = false;
		this.wbcg$frameTripActive = false;
	}

	/**
	 * Vanilla only "sees" a chest when a ray to one of its face centers hits
	 * the chest block itself — but those endpoints sit on the block-grid
	 * plane, 1/16 outside the chest's inset collision box, and rays to the
	 * far faces of an elevated chest pass through the chests below it. Any
	 * chest two or more blocks up in a chest wall is therefore "invisible"
	 * and gets marked unreachable. For golems, also accept an unobstructed
	 * ray to a point just outside a face: a clear view of the face counts,
	 * while grabbing through solid walls stays impossible.
	 */
	@Inject(method = "canSeeAnyTargetSide", at = @At("HEAD"), cancellable = true)
	private void wbcg$relaxedLineOfSight(TransportItemTarget target, Level level, PathfinderMob body,
			Vec3 eyePosition, CallbackInfoReturnable<Boolean> cir) {
		if (!(body instanceof CopperGolem)) {
			return;
		}
		Vec3 center = Vec3.atCenterOf(target.pos());
		boolean visible = net.minecraft.core.Direction.stream()
				.map(direction -> center.add(
						0.55 * direction.getStepX(), 0.55 * direction.getStepY(), 0.55 * direction.getStepZ()))
				.map(point -> level.clip(new net.minecraft.world.level.ClipContext(eyePosition, point,
						net.minecraft.world.level.ClipContext.Block.COLLIDER,
						net.minecraft.world.level.ClipContext.Fluid.NONE, body)))
				.anyMatch(hit -> hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
						|| (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
								&& hit.getBlockPos().equals(target.pos())));
		cir.setReturnValue(visible);
	}

	@Inject(method = "markVisitedBlockPosAsUnreachable", at = @At("HEAD"))
	private void wbcg$debugUnreachable(PathfinderMob body, Level level,
			net.minecraft.core.BlockPos target, CallbackInfo ci) {
		if (body instanceof CopperGolem) {
			io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem.LOGGER.debug(
					"[WBCG-DEBUG] golem at {} marked {} unreachable (holding {})",
					body.blockPosition(), target, body.getMainHandItem());
		}
	}

	/** Labels are authoritative at arrival time too. */
	@Redirect(method = "doReachedTargetInteraction", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/behavior/TransportItemsBetweenContainers;matchesLeavingItemsRequirement(Lnet/minecraft/world/entity/PathfinderMob;Lnet/minecraft/world/Container;)Z"))
	private boolean wbcg$labelAwareAccept(PathfinderMob body, Container container) {
		if (body instanceof CopperGolem && this.target != null && body.level() instanceof ServerLevel level) {
			if (wbcg$isFrameDelivery(body)) {
				return FrameHanger.wantsFrame(level, this.target.pos());
			}
			if (wbcg$isFrameReturn(body)) {
				return SortingEngine.canAcceptAny(this.target.container(), body.getMainHandItem());
			}
			return SortingEngine.acceptsDeposit(level, this.target, body);
		}
		return matchesLeavingItemsRequirement(body, container);
	}

	/**
	 * Pickup choke point: remembers the golem's zone chest, logs instead of
	 * moving in dry-run mode, and performs the misplaced-stack pickup when
	 * the target is a reorganize source. Every cancel path clears the
	 * current target so the golem doesn't re-interact with the same chest.
	 */
	@Inject(method = "pickUpItems", at = @At("HEAD"), cancellable = true)
	private void wbcg$onPickup(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!(body instanceof CopperGolem) || this.target == null
				|| !(body.level() instanceof ServerLevel level)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		boolean reorganize = this.wbcg$reorganizeActive;
		boolean tidy = this.wbcg$tidyActive;
		boolean sort = this.wbcg$sortActive;
		SortingEngine.TidyMove tidyPlan = this.wbcg$tidyPlan;
		if (this.target.state().is(BlockTags.COPPER_CHESTS)) {
			golem.wbcg$setZoneChest(this.target.pos());
			golem.wbcg$joinZoneAt(level, this.target.pos());
		}
		ZoneSettings settings = golem.wbcg$zoneSettings(level);

		if (settings.dryRun()) {
			if (sort) {
				SortingEngine.logWouldSort(body, this.target.pos());
				golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
				this.stopTargetingCurrentTarget(body);
				body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 200);
				ci.cancel();
				return;
			}
			if (tidy && tidyPlan != null) {
				SortingEngine.logWouldTidy(body, tidyPlan.stack(), this.target.pos(), tidyPlan.home().pos());
				golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
				this.stopTargetingCurrentTarget(body);
				body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 200);
				ci.cancel();
				return;
			}
			ItemStack would = reorganize
					? wbcg$previewStack(SortingEngine.firstMisplacedStack(level, this.target))
					: wbcg$peekFirstStack(container);
			if (!would.isEmpty()) {
				Optional<TransportItemTarget> destination = SortingEngine.findDepositTarget(
						level, body.position(), would, this.destinationBlockType,
						wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
						wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
						wbcg$searchArea(body, level));
				SortingEngine.logWouldMove(body, would, this.target.pos(),
						destination.map(TransportItemTarget::pos).orElse(null));
			}
			if (reorganize) {
				golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
			}
			// Keep the visited memory (so the next search moves on to another
			// chest), drop the target, and cool down instead of picking up.
			this.stopTargetingCurrentTarget(body);
			body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 200);
			ci.cancel();
			return;
		}

		if (sort) {
			// A sorting visit: nothing is picked up, the chest's stacks are put in order in place.
			this.wbcg$sortActive = false;
			SortingEngine.tidyContainer(container);
			golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
			this.stopTargetingCurrentTarget(body);
			ci.cancel();
			return;
		}

		if (tidy && tidyPlan != null) {
			// Tidy pickup: merge partial stacks first (the old tidy), then take the planned minority stack.
			this.wbcg$tidyActive = false;
			this.wbcg$tidyPlan = null;
			SortingEngine.tidyContainer(tidyPlan.source().container());
			SortingEngine.tidyContainer(tidyPlan.home().container());
			ItemStack taken = SortingEngine.takeTidyStack(level, tidyPlan,
					io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize());
			if (taken.isEmpty()) {
				this.stopTargetingCurrentTarget(body); // the chests changed since the plan; nothing to do here
			} else {
				body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, taken);
				body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				golem.wbcg$setTidyDestination(tidyPlan.home().pos());
				golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
				this.clearMemoriesAfterMatchingTargetFound(body);
			}
			ci.cancel();
			return;
		}

		if (this.wbcg$frameTripActive) {
			// Frame trip: take exactly one frame, not the first stack.
			this.wbcg$frameTripActive = false;
			ItemStack frame = FrameHanger.takeFrame(container);
			if (frame.isEmpty()) {
				this.stopTargetingCurrentTarget(body);
			} else {
				body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, frame);
				body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				golem.wbcg$setFrameReturnChest(this.target.pos());
				this.clearMemoriesAfterMatchingTargetFound(body);
			}
			ci.cancel();
			return;
		}

		if (!reorganize && settings.hangFrames()) {
			// Frames in copper chests are supplies while golems hang them: the normal pickup skips them.
			ItemStack taken = FrameHanger.takeFirstNonFrame(container,
					io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize());
			if (taken.isEmpty()) {
				this.stopTargetingCurrentTarget(body);
			} else {
				body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, taken);
				body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				this.clearMemoriesAfterMatchingTargetFound(body);
			}
			ci.cancel();
			return;
		}

		if (!reorganize) {
			return; // vanilla pickup from the copper chest
		}

		// Reorganize pickup: take the misplaced stack, not the first stack.
		ItemStack misplaced = SortingEngine.firstMisplacedStack(level, this.target);
		if (misplaced.isEmpty()) {
			// Contents changed since the target was chosen; nothing to fix here.
			this.stopTargetingCurrentTarget(body);
			ci.cancel();
			return;
		}
		int slot = 0;
		for (ItemStack stack : container) {
			if (stack == misplaced) {
				ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(),
						io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize()));
				body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, taken);
				body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				container.setChanged();
				break;
			}
			slot++;
		}
		if (settings.tidyInside()) {
			SortingEngine.tidyContainer(container);
		}
		golem.wbcg$setNextReorganizeTime(level.getGameTime() + WBCG$REORGANIZE_SUCCESS_COOLDOWN);
		this.clearMemoriesAfterMatchingTargetFound(body);
		ci.cancel();
	}

	/**
	 * Optional tidy-inside: after the golem finishes a normal pickup or
	 * deposit, merge partial stacks and close gaps in that container.
	 * Off by default; never runs in dry-run mode.
	 */
	@Inject(method = "pickUpItems", at = @At("TAIL"))
	private void wbcg$tidyAfterPickup(PathfinderMob body, Container container, CallbackInfo ci) {
		wbcg$maybeTidy(body, container);
	}

	/**
	 * A frame delivery hangs the frame on the chest's front face instead of
	 * storing it inside. If the chest no longer wants one (someone hung a
	 * frame meanwhile, the front got blocked, the chest emptied), the golem
	 * keeps the frame and the normal flow finds it a chest.
	 */
	@Inject(method = "putDownItem", at = @At("HEAD"), cancellable = true)
	private void wbcg$hangFrameInsteadOfStoring(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!(body instanceof CopperGolem) || this.target == null || !(body.level() instanceof ServerLevel level)) {
			return;
		}
		this.wbcg$depositPos = this.target.pos();
		ZoneAwareGolem depositing = (ZoneAwareGolem) body;
		if (this.target.pos().equals(depositing.wbcg$tidyDestination())) {
			depositing.wbcg$setTidyDestination(null);
		}
		if (wbcg$isFrameReturn(body)) {
			depositing.wbcg$setFrameReturnChest(null); // vanilla stores the frame back into the copper chest
			return;
		}
		if (!wbcg$isFrameDelivery(body)) {
			return;
		}
		depositing.wbcg$setPendingFrameChest(null);
		if (FrameHanger.hang(level, body, this.target)) {
			depositing.wbcg$setFrameReturnChest(null);
			this.clearMemoriesAfterMatchingTargetFound(body);
		} else {
			// The front got taken while the golem was on its way: give up, the frame goes back where it came from.
			this.stopTargetingCurrentTarget(body);
		}
		ci.cancel();
	}

	@Inject(method = "putDownItem", at = @At("TAIL"))
	private void wbcg$tidyAfterDeposit(PathfinderMob body, Container container, CallbackInfo ci) {
		wbcg$maybeTidy(body, container);
		wbcg$rememberBareChest(body);
	}

	/** While holding a frame meant for the pending chest, and that chest is the current target. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$isFrameDelivery(PathfinderMob body) {
		BlockPos pending = ((ZoneAwareGolem) body).wbcg$pendingFrameChest();
		return pending != null && this.target != null && pending.equals(this.target.pos())
				&& FrameHanger.isFrame(body.getMainHandItem());
	}

	/**
	 * After a delivery: a labeled chest with a bare front face is remembered
	 * for a frame trip, and a chest holding a misplaced stack makes the next
	 * reorganize search run at once instead of after the cooldown, so a
	 * stack the golem just saw out of place is moved right away.
	 */
	@org.spongepowered.asm.mixin.Unique
	private void wbcg$rememberBareChest(PathfinderMob body) {
		BlockPos deposited = this.wbcg$depositPos;
		this.wbcg$depositPos = null;
		if (deposited == null || !(body instanceof CopperGolem) || !(body.level() instanceof ServerLevel level)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		ZoneSettings settings = golem.wbcg$zoneSettings(level);
		if (settings.reorganize() && !settings.dryRun()) {
			TransportItemTarget visited = TransportItemTarget.tryCreatePossibleTarget(deposited, level);
			if (visited != null && !SortingEngine.firstMisplacedStack(level, visited).isEmpty()) {
				golem.wbcg$setNextReorganizeTime(level.getGameTime());
			}
		}
		// Only with an empty hand: a remainder left in hand (the chest filled up) is cargo, and if it happens to be
		// frames it must not be mistaken for the single frame of a frame trip.
		if (settings.hangFrames() && !settings.dryRun() && body.getMainHandItem().isEmpty() && FrameHanger.wantsFrame(level, deposited)) {
			golem.wbcg$setPendingFrameChest(deposited);
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private void wbcg$maybeTidy(PathfinderMob body, Container container) {
		if (body instanceof CopperGolem && body.level() instanceof ServerLevel level) {
			ZoneSettings settings = ((ZoneAwareGolem) body).wbcg$zoneSettings(level);
			if (settings.tidyInside() && !settings.dryRun()) {
				SortingEngine.tidyContainer(container);
			}
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static ItemStack wbcg$previewStack(ItemStack stack) {
		return stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(Math.min(stack.getCount(), 16));
	}

	/**
	 * Vanilla golems pick up at most 16 items per trip; the config decides
	 * (64 by default, a whole stack). The method is static and shared by any
	 * mob using this behavior, which in vanilla is only the copper golem.
	 */
	@ModifyConstant(method = "pickupItemFromContainer", constant = @Constant(intValue = 16))
	private static int wbcg$carrySize(int original) {
		return io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize();
	}

	/**
	 * Vanilla only reaches a chest whose collision box (inflated 0.5 blocks
	 * vertically) touches the golem. Raising that to the zone's vertical
	 * reach lets golems serve chest walls 4-5 blocks tall from the floor;
	 * the line-of-sight check still prevents grabbing through blocks.
	 */
	@ModifyConstant(method = "isWithinTargetDistance", constant = @Constant(doubleValue = 0.5))
	private double wbcg$verticalReach(double original, double distance, TransportItemTarget target,
			Level level, PathfinderMob body, Vec3 fromPos) {
		if (body instanceof CopperGolem && body.level() instanceof ServerLevel serverLevel) {
			return ((ZoneAwareGolem) body).wbcg$zoneSettings(serverLevel).verticalReach();
		}
		return original;
	}

	@SuppressWarnings("unchecked")
	private static Set<GlobalPos> wbcg$memory(PathfinderMob body, MemoryModuleType<Set<GlobalPos>> type) {
		return body.getBrain().getMemory(type).orElse(Set.of());
	}

	private static ItemStack wbcg$peekFirstStack(Container container) {
		for (ItemStack stack : container) {
			if (!stack.isEmpty()) {
				return stack.copyWithCount(Math.min(stack.getCount(), 16));
			}
		}
		return ItemStack.EMPTY;
	}
}
