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
		ZoneSettings settings = ((ZoneAwareGolem) body).wbcg$zoneSettings(level);
		cir.setReturnValue(SortingEngine.findDepositTarget(
				level, body, body.getMainHandItem(), this.destinationBlockType,
				wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
				wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
				settings.searchRadius(), this.verticalSearchDistance));
	}

	/** Labels are authoritative at arrival time too. */
	@Redirect(method = "doReachedTargetInteraction", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/behavior/TransportItemsBetweenContainers;matchesLeavingItemsRequirement(Lnet/minecraft/world/entity/PathfinderMob;Lnet/minecraft/world/Container;)Z"))
	private boolean wbcg$labelAwareAccept(PathfinderMob body, Container container) {
		if (body instanceof CopperGolem && this.target != null && body.level() instanceof ServerLevel level) {
			return SortingEngine.acceptsDeposit(level, this.target, body);
		}
		return matchesLeavingItemsRequirement(body, container);
	}

	/**
	 * Pickup choke point: remembers the golem's zone chest, and in dry-run
	 * mode logs the intended move without touching the container.
	 */
	@Inject(method = "pickUpItems", at = @At("HEAD"), cancellable = true)
	private void wbcg$onPickup(PathfinderMob body, Container container, CallbackInfo ci) {
		if (!(body instanceof CopperGolem) || this.target == null
				|| !(body.level() instanceof ServerLevel level)) {
			return;
		}
		ZoneAwareGolem golem = (ZoneAwareGolem) body;
		if (this.target.state().is(BlockTags.COPPER_CHESTS)) {
			golem.wbcg$setZoneChest(this.target.pos());
		}
		if (!golem.wbcg$zoneSettings(level).dryRun()) {
			return;
		}
		ItemStack would = wbcg$peekFirstStack(container);
		if (!would.isEmpty()) {
			Optional<TransportItemTarget> destination = SortingEngine.findDepositTarget(
					level, body, would, this.destinationBlockType,
					wbcg$memory(body, MemoryModuleType.VISITED_BLOCK_POSITIONS),
					wbcg$memory(body, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS),
					golem.wbcg$zoneSettings(level).searchRadius(), this.verticalSearchDistance);
			SortingEngine.logWouldMove(body, would, this.target.pos(),
					destination.map(TransportItemTarget::pos).orElse(null));
		}
		// Cooldown instead of pickup, so dry-run doesn't spin on the same chest.
		body.getBrain().setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 200);
		ci.cancel();
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
