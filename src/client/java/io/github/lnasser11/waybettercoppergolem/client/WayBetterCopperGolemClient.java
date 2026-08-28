package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.screens.MenuScreens;

public class WayBetterCopperGolemClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		MenuScreens.register(WayBetterCopperGolem.ZONE_SETTINGS_MENU, ZoneSettingsScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(CategoryPayloads.Sync.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof CategoryEditorScreen editor) {
				editor.acceptSync(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(CategoryPayloads.ListSync.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof CategoryEditorScreen editor) {
				editor.acceptList(payload);
			}
		});
	}
}
