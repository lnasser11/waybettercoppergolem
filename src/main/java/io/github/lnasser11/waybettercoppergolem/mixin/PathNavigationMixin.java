package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A golem that belongs to a zone with "golems stay inside" on never walks
 * a path that leaves the zone's box. Every path the mob computes (strolls,
 * walking to a chest, the brain's walk target) goes through the method
 * hooked here; when the golem is inside the box the path is cut at the
 * last node still inside, marked as not reaching its target, so vanilla
 * treats the destination as unreachable instead of walking out. A golem
 * already outside (pushed, fallen) keeps full paths so it can walk back.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
	@Shadow
	@Final
	protected Mob mob;

	@Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
			at = @At("RETURN"), cancellable = true)
	private void wbcg$clipPathToZone(Set<BlockPos> targets, int radiusOffset, boolean above, int reachRange,
			float maxPathLength, CallbackInfoReturnable<Path> cir) {
		Path path = cir.getReturnValue();
		if (path == null || !(this.mob instanceof CopperGolem golem) || !(golem.level() instanceof ServerLevel level)) {
			return;
		}
		Optional<BoundingBox> confinement = ((ZoneAwareGolem) golem).wbcg$confinement(level);
		if (confinement.isEmpty()) {
			return;
		}
		BoundingBox box = confinement.get();
		if (!box.isInside(golem.blockPosition())) {
			return; // outside already: any route home is fine
		}
		int count = path.getNodeCount();
		int inside = 0;
		while (inside < count && box.isInside(path.getNodePos(inside))) {
			inside++;
		}
		if (inside == count) {
			return;
		}
		if (inside == 0) {
			cir.setReturnValue(null);
			return;
		}
		List<Node> kept = new ArrayList<>(inside);
		for (int i = 0; i < inside; i++) {
			kept.add(path.getNode(i));
		}
		cir.setReturnValue(new Path(kept, path.getTarget(), false));
	}
}
