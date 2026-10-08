package io.github.lnasser11.waybettercoppergolem.mixin;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a chest's derived labels in step with the frames hanging on it:
 * whenever a frame's item changes (placed in, rotated out, dropped by a
 * hit), the supporting chest re-derives its labels so the HUD and the
 * golems see the change immediately. Frames are never intercepted; this
 * runs after vanilla has done whatever the click meant.
 */
@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
	@Inject(method = "setItem(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("TAIL"))
	private void wbcg$refreshChestLabels(ItemStack stack, boolean update, CallbackInfo ci) {
		ItemFrame self = (ItemFrame) (Object) this;
		if (self.level() instanceof ServerLevel level) {
			ChestLabels.onFrameChanged(level, self);
		}
	}
}
