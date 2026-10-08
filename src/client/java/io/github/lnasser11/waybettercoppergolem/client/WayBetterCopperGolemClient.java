package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.net.AreaModePayload;
import io.github.lnasser11.waybettercoppergolem.net.ConfigPayload;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;

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
	/** 0 = not in area mode, 1 = waiting for corner 1, 2 = waiting for corner 2 (from the server). */
	private static volatile int areaModeStep;

	public static int areaModeStep() {
		return areaModeStep;
	}

	@Override
	public void onInitializeClient() {
		MenuScreens.register(WayBetterCopperGolem.ZONE_SETTINGS_MENU, ZoneSettingsScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE,
				(payload, context) -> WbcgConfig.applyRemote(payload.toolItem()));
		ClientPlayNetworking.registerGlobalReceiver(AreaModePayload.TYPE,
				(payload, context) -> areaModeStep = payload.step());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			WbcgConfig.resetRemote();
			areaModeStep = 0;
		});
		ClientPlayNetworking.registerGlobalReceiver(EditorPayloads.EditorContext.TYPE,
				(payload, context) -> LabelPickerScreen.openOrUpdate(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(TuningPayloads.TuningContext.TYPE,
				(payload, context) -> CategoryTuningScreen.openOrUpdate(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(ZonePayloads.Overview.TYPE,
				(payload, context) -> ZoneOverviewScreen.openOrUpdate(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(ZonePayloads.Simulation.TYPE,
				(payload, context) -> SimulationScreen.openOrUpdate(context.client(), payload));
		UseItemCallback.EVENT.register(WayBetterCopperGolemClient::openPickerOnAirClick);
		ChestScreenButton.register();
		ToolHud.register();
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
		if (areaModeStep > 0) {
			return InteractionResult.PASS; // the air click cancels area mode on the server instead
		}
		minecraft.setScreenAndShow(LabelPickerScreen.forClipboard());
		return InteractionResult.PASS;
	}
}
