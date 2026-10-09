package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.sorting.GolemWake;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A copper chest whose contents changed wakes the golems around it. Only
 * {@link BlockEntity} declares {@code setChanged()}, so the hook sits there
 * and filters down to chests on the server.
 */
@Mixin(BlockEntity.class)
public abstract class ChestBlockEntityMixin {
	@Inject(method = "setChanged()V", at = @At("TAIL"))
	private void wbcg$wakeGolems(CallbackInfo ci) {
		if (!((Object) this instanceof ChestBlockEntity self) || !(self.getLevel() instanceof ServerLevel level)) {
			return;
		}
		BlockState state = self.getBlockState();
		if (state.is(BlockTags.COPPER_CHESTS)) {
			GolemWake.copperChestChanged(level, self.getBlockPos(), state);
		}
	}
}
