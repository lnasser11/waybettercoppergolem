package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger;
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

	/** True while the current target is a copper chest the golem fetches an item frame from. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$frameTripActive;

	/** The chest the golem is about to deposit into, kept because the target is cleared by the deposit itself. */
	@org.spongepowered.asm.mixin.Unique
	private @Nullable BlockPos wbcg$depositPos;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$REORGANIZE_SUCCESS_COOLDOWN = 600;

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
		ItemStack held = body.getMainHandItem();
		BlockPos pending = golem.wbcg$pendingFrameChest();
		if (held.isEmpty()) {
			// A frame trip: fetch a frame for the bare chest the golem last delivered into.
			if (pending == null) {
				return;
			}
			ZoneSettings settings = golem.wbcg$zoneSettings(level);
			if (!settings.hangFrames() || settings.dryRun() || !FrameHanger.wantsFrame(level, pending)) {
				golem.wbcg$setPendingFrameChest(null);
				return;
			}
			Optional<TransportItemTarget> source = FrameHanger.findFrameSource(level, body.position(),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS), wbcg$searchArea(body, level));
			if (source.isEmpty()) {
				golem.wbcg$setPendingFrameChest(null); // no frames in the zone's copper chests: nothing happens
				return;
			}
			this.wbcg$frameTripActive = true;
			cir.setReturnValue(source);
			return;
		}
		if (pending != null && FrameHanger.isFrame(held)) {
			// Carrying the frame: the destination is the chest it is meant for.
			TransportItemTarget chest = TransportItemTarget.tryCreatePossibleTarget(pending, level);
			if (chest != null && FrameHanger.wantsFrame(level, pending)) {
				cir.setReturnValue(Optional.of(chest));
				return;
			}
			golem.wbcg$setPendingFrameChest(null); // the chest changed its mind; the frame is delivered like any item
		}
		cir.setReturnValue(SortingEngine.findDepositTarget(
				level, body.position(), body.getMainHandItem(), this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				wbcg$searchArea(body, level)));
	}

	@org.spongepowered.asm.mixin.Unique
	private net.minecraft.world.phys.AABB wbcg$searchArea(PathfinderMob body, ServerLevel level) {
		return ((ZoneAwareGolem) body).wbcg$searchArea(level, this.horizontalSearchDistance, this.verticalSearchDistance);
	}

	/**
	 * Reorganize-existing-chests: when the golem is empty-handed and vanilla
	 * found no copper chest worth visiting, offer a labeled chest containing
	 * a misplaced stack as the pickup source instead. Slow, low-priority
	 * background work: it only runs when the dump queue is idle, and backs
	 * off for a minute when the storage room is already tidy.
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
		if (!settings.reorganize() || now < golem.wbcg$nextReorganizeTime()) {
			return;
		}
		Optional<TransportItemTarget> source = SortingEngine.findMisplacedSource(
				level, body.position(), this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				wbcg$searchArea(body, level));
		if (source.isPresent()) {
			this.wbcg$reorganizeActive = true;
			cir.setReturnValue(source);
		} else {
			golem.wbcg$setNextReorganizeTime(now + WBCG$REORGANIZE_IDLE_COOLDOWN);
		}
	}

	/**
	 * Mid-trip target validation checks the source block type predicate
	 * (copper chests) while the hand is empty; a reorganize pickup source is
	 * a regular chest, so it needs a pass of its own.
	 */
	@Inject(method = "isWantedBlock", at = @At("HEAD"), cancellable = true)
	private void wbcg$reorganizeWantedBlock(PathfinderMob mob, BlockState block,
			CallbackInfoReturnable<Boolean> cir) {
		if (this.wbcg$reorganizeActive && mob instanceof CopperGolem
				&& mob.getMainHandItem().isEmpty()
				&& io.github.lnasser11.waybettercoppergolem.label.ChestLabels.isLabelableChest(block)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "stopTargetingCurrentTarget", at = @At("TAIL"))
	private void wbcg$clearReorganizeFlag(PathfinderMob body, CallbackInfo ci) {
		this.wbcg$reorganizeActive = false;
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
		if (this.target.state().is(BlockTags.COPPER_CHESTS)) {
			golem.wbcg$setZoneChest(this.target.pos());
			golem.wbcg$joinZoneAt(level, this.target.pos());
		}
		ZoneSettings settings = golem.wbcg$zoneSettings(level);

		if (settings.dryRun()) {
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

		if (this.wbcg$frameTripActive) {
			// Frame trip: take exactly one frame, not the first stack.
			this.wbcg$frameTripActive = false;
			ItemStack frame = FrameHanger.takeFrame(container);
			if (frame.isEmpty()) {
				this.stopTargetingCurrentTarget(body);
			} else {
				body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, frame);
				body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
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
				ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(), 16));
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
		if (!wbcg$isFrameDelivery(body)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		golem.wbcg$setPendingFrameChest(null);
		if (FrameHanger.hang(level, body, this.target)) {
			this.clearMemoriesAfterMatchingTargetFound(body);
		} else {
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

	/** After a delivery into a labeled chest with a bare front face, remember it for a frame trip. */
	@org.spongepowered.asm.mixin.Unique
	private void wbcg$rememberBareChest(PathfinderMob body) {
		BlockPos deposited = this.wbcg$depositPos;
		this.wbcg$depositPos = null;
		if (deposited == null || !(body instanceof CopperGolem) || !(body.level() instanceof ServerLevel level)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		ZoneSettings settings = golem.wbcg$zoneSettings(level);
		if (settings.hangFrames() && !settings.dryRun() && FrameHanger.wantsFrame(level, deposited)) {
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
