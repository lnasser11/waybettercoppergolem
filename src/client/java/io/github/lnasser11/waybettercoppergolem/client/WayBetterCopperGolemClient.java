package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.client.gui.screens.MenuScreens;

public class WayBetterCopperGolemClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(WayBetterCopperGolem.ZONE_SETTINGS_MENU, ZoneSettingsScreen::new);
	}
}
