package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.net.ConfigPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.MenuScreens;

public class WayBetterCopperGolemClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(WayBetterCopperGolem.ZONE_SETTINGS_MENU, ZoneSettingsScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE,
				(payload, context) -> WbcgConfig.applyRemote(payload.toolItem()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WbcgConfig.resetRemote());
	}
}
