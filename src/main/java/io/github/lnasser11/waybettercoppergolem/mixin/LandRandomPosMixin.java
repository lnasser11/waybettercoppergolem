package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * A golem that belongs to a zone with "golems stay inside" on only strolls
 * to spots inside the zone's box. Vanilla's random stroll picks a spot
 * around the mob; when that spot is outside the box this redraws a few
 * times and, failing that, cancels the stroll (the behavior tries again a
 * few seconds later). A golem perched on a chest does not stroll at all.
 * Any other mob is untouched.
 */
@Mixin(LandRandomPos.class)
public abstract class LandRandomPosMixin {
	@Unique
	private static final int WBCG$RETRIES = 8;
	/** Set while redrawing, so the nested calls return vanilla's pick unfiltered. */
	@Unique
	private static final ThreadLocal<Boolean> WBCG$REDRAWING = ThreadLocal.withInitial(() -> false);

	@Inject(method = "getPos(Lnet/minecraft/world/entity/PathfinderMob;IILjava/util/function/ToDoubleFunction;)Lnet/minecraft/world/phys/Vec3;",
			at = @At("RETURN"), cancellable = true)
	private static void wbcg$keepStrollInsideZone(PathfinderMob mob, int horizontalDist, int verticalDist,
			ToDoubleFunction<BlockPos> positionWeight, CallbackInfoReturnable<Vec3> cir) {
		if (WBCG$REDRAWING.get() || !(mob instanceof CopperGolem) || !(mob.level() instanceof ServerLevel level)) {
			return;
		}
		if (((ZoneAwareGolem) mob).wbcg$isPerched(level)) {
			cir.setReturnValue(null); // a statue does not stroll
			return;
		}
		Optional<BoundingBox> confinement = ((ZoneAwareGolem) mob).wbcg$confinement(level);
		if (confinement.isEmpty()) {
			return;
		}
		BoundingBox box = confinement.get();
		Vec3 picked = cir.getReturnValue();
		if (picked != null && box.isInside(BlockPos.containing(picked))) {
			return;
		}
		WBCG$REDRAWING.set(true);
		try {
			for (int i = 0; i < WBCG$RETRIES; i++) {
				Vec3 again = LandRandomPos.getPos(mob, horizontalDist, verticalDist, positionWeight);
				if (again != null && box.isInside(BlockPos.containing(again))) {
					cir.setReturnValue(again);
					return;
				}
			}
		} finally {
			WBCG$REDRAWING.set(false);
		}
		cir.setReturnValue(null);
	}
}
