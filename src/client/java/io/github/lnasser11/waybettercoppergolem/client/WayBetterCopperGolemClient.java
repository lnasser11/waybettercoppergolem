package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.net.ConfigPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

public class WayBetterCopperGolemClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(WayBetterCopperGolem.ZONE_SETTINGS_MENU, ZoneSettingsScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE,
				(payload, context) -> WbcgConfig.applyRemote(payload.toolItem()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WbcgConfig.resetRemote());
		UseItemCallback.EVENT.register(WayBetterCopperGolemClient::openPickerOnAirClick);
	}

	/**
	 * Sneak-right-click the air with the label tool → the picker. Returns
	 * PASS so the use packet still reaches the server (which cancels area
	 * mode or shows the clipboard as usual).
	 */
	private static InteractionResult openPickerOnAirClick(Player player, Level level, InteractionHand hand) {
		if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
				|| player.isSpectator() || !WbcgConfig.isTool(level, player.getMainHandItem())) {
			return InteractionResult.PASS;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hitResult != null && minecraft.hitResult.getType() != HitResult.Type.MISS) {
			return InteractionResult.PASS;
		}
		minecraft.setScreenAndShow(new LabelPickerScreen());
		return InteractionResult.PASS;
	}
}
