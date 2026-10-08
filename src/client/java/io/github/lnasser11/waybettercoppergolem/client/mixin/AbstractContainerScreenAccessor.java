package io.github.lnasser11.waybettercoppergolem.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a container screen's panel position, to place the golem button beside it. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int wbcg$leftPos();

	@Accessor("topPos")
	int wbcg$topPos();

	@Accessor("imageWidth")
	int wbcg$imageWidth();
}
