package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;

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

	/** True while the golem is bringing an unsortable held stack back to a copper chest. */
	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$returnActive;

	@org.spongepowered.asm.mixin.Unique
	private boolean wbcg$depositWasReturn;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$RETURN_COOLDOWN = 600;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$REORGANIZE_SUCCESS_COOLDOWN = 600;

	@org.spongepowered.asm.mixin.Unique
	private static final int WBCG$REORGANIZE_IDLE_COOLDOWN = 1200;

	/**
	 * While a golem is holding an item, destination selection is ours:
	 * ranked by label specificity instead of nearest-blind.
	 */
	@Inject(method = "getTransportTarget", at = @At("HEAD"), cancellable = true)
	private void wbcg$labelAwareDestination(ServerLevel level, PathfinderMob body,
			CallbackInfoReturnable<Optional<TransportItemTarget>> cir) {
		if (!(body instanceof CopperGolem) || body.getMainHandItem().isEmpty()) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		ZoneSettings settings = golem.wbcg$zoneSettings(level);
		if (settings.vanillaMode()) {
			return; // stock destination search
		}
		Optional<TransportItemTarget> destination = SortingEngine.findDepositTarget(
				level, body, body.getMainHandItem(), this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				settings.searchRadius(), this.verticalSearchDistance);
		if (destination.isEmpty()) {
			// Nothing accepts this item right now - bring it back to a
			// copper chest instead of standing around holding it forever.
			destination = SortingEngine.findReturnTarget(
					level, body, body.getMainHandItem(), golem.wbcg$zoneChest(),
					wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
					settings.searchRadius(), this.verticalSearchDistance);
			this.wbcg$returnActive = destination.isPresent();
		}
		cir.setReturnValue(destination);
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
		if (!(body instanceof CopperGolem) || !body.getMainHandItem().isEmpty()) {
			return;
		}
		if (cir.getReturnValue().isPresent()) {
			// Bind the golem to its zone as soon as it targets a copper
			// chest, not only after a successful pickup - otherwise a golem
			// working an empty copper chest would ignore its zone settings.
			TransportItemTarget target = cir.getReturnValue().get();
			if (target.state().is(BlockTags.COPPER_CHESTS)) {
				((ZoneAwareGolem) body).wbcg$setZoneChest(target.pos());
			}
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		ZoneSettings settings = golem.wbcg$zoneSettings(level);
		long now = level.getGameTime();
		if (settings.vanillaMode() || !settings.reorganize() || now < golem.wbcg$nextReorganizeTime()) {
			return;
		}
		Optional<TransportItemTarget> source = SortingEngine.findMisplacedSource(
				level, body, this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				settings.searchRadius(), this.verticalSearchDistance);
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
		if (!(mob instanceof CopperGolem)) {
			return;
		}
		if (this.wbcg$reorganizeActive && mob.getMainHandItem().isEmpty()
				&& io.github.lnasser11.waybettercoppergolem.label.ChestLabels.isLabelableChest(block)) {
			cir.setReturnValue(true);
		}
		if (this.wbcg$returnActive && !mob.getMainHandItem().isEmpty()
				&& block.is(BlockTags.COPPER_CHESTS)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "stopTargetingCurrentTarget", at = @At("TAIL"))
	private void wbcg$clearReorganizeFlag(PathfinderMob body, CallbackInfo ci) {
		this.wbcg$reorganizeActive = false;
		this.wbcg$returnActive = false;
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
		if (body.level() instanceof ServerLevel serverLevel
				&& ((ZoneAwareGolem) body).wbcg$zoneSettings(serverLevel).vanillaMode()) {
			return; // stock line-of-sight
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
		if (body instanceof CopperGolem && this.target != null && body.level() instanceof ServerLevel level
				&& !((ZoneAwareGolem) body).wbcg$zoneSettings(level).vanillaMode()) {
			if (this.wbcg$returnActive && this.target.state().is(BlockTags.COPPER_CHESTS)) {
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
		if (this.target.state().is(BlockTags.COPPER_CHESTS)) {
			golem.wbcg$setZoneChest(this.target.pos());
		}
		ZoneSettings settings = golem.wbcg$zoneSettings(level);

		int carryAmount = settings.carryAmount();

		if (settings.dryRun()) {
			ItemStack would = reorganize
					? wbcg$previewStack(SortingEngine.firstMisplacedStack(level, this.target), carryAmount)
					: wbcg$peekFirstStack(container, carryAmount);
			if (!would.isEmpty()) {
				Optional<TransportItemTarget> destination = SortingEngine.findDepositTarget(
						level, body, would, this.destinationBlockType,
						wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
						wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
						settings.searchRadius(), this.verticalSearchDistance);
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

		if (!reorganize) {
			// Copper-chest pickup, vanilla semantics but with the zone's
			// configurable carry amount instead of the hardcoded 16.
			int slot = 0;
			for (ItemStack stack : container) {
				if (!stack.isEmpty()) {
					ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(), carryAmount));
					body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, taken);
					body.setGuaranteedDrop(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
					container.setChanged();
					break;
				}
				slot++;
			}
			this.clearMemoriesAfterMatchingTargetFound(body);
			ci.cancel();
			return;
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
				ItemStack taken = container.removeItem(slot, Math.min(stack.getCount(), carryAmount));
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

	@Inject(method = "putDownItem", at = @At("HEAD"))
	private void wbcg$captureReturnDeposit(PathfinderMob body, Container container, CallbackInfo ci) {
		// The flag is cleared by stopTargetingCurrentTarget inside
		// putDownItem, so remember it for the TAIL hook.
		this.wbcg$depositWasReturn = this.wbcg$returnActive;
	}

	@Inject(method = "putDownItem", at = @At("TAIL"))
	private void wbcg$tidyAfterDeposit(PathfinderMob body, Container container, CallbackInfo ci) {
		wbcg$maybeTidy(body, container);
		if (this.wbcg$depositWasReturn && body instanceof CopperGolem) {
			// Returned an unsortable stack to the copper chest; wait a while
			// before trying it again so the golem doesn't shuttle in a loop.
			body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, WBCG$RETURN_COOLDOWN);
			this.wbcg$depositWasReturn = false;
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private void wbcg$maybeTidy(PathfinderMob body, Container container) {
		if (body instanceof CopperGolem && body.level() instanceof ServerLevel level) {
			ZoneSettings settings = ((ZoneAwareGolem) body).wbcg$zoneSettings(level);
			if (settings.tidyInside() && !settings.dryRun() && !settings.vanillaMode()) {
				SortingEngine.tidyContainer(container);
			}
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static ItemStack wbcg$previewStack(ItemStack stack, int carryAmount) {
		return stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(Math.min(stack.getCount(), carryAmount));
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
			ZoneSettings settings = ((ZoneAwareGolem) body).wbcg$zoneSettings(serverLevel);
			return settings.vanillaMode() ? original : settings.verticalReach();
		}
		return original;
	}

	@SuppressWarnings("unchecked")
	private static Set<GlobalPos> wbcg$memory(PathfinderMob body, MemoryModuleType<Set<GlobalPos>> type) {
		return body.getBrain().getMemory(type).orElse(Set.of());
	}

	private static ItemStack wbcg$peekFirstStack(Container container, int carryAmount) {
		for (ItemStack stack : container) {
			if (!stack.isEmpty()) {
				return stack.copyWithCount(Math.min(stack.getCount(), carryAmount));
			}
		}
		return ItemStack.EMPTY;
	}
}
