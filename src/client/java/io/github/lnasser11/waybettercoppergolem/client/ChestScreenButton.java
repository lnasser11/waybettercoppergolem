package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.client.mixin.AbstractContainerScreenAccessor;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/**
 * The golem button beside every chest inventory screen. The client
 * remembers the last chest block it right-clicked; when a generic chest
 * screen opens right after, the button appears next to it and asks the
 * server to open the editor (regular chest) or the zone screen (copper
 * chest) for that block. Click-through mods redirect the click to the
 * block on the client too, so the position is known either way.
 */
public final class ChestScreenButton {
	private static final long CLICK_TO_SCREEN_MILLIS = 1500;
	private static final int WIDTH = 44;

	private static @Nullable BlockPos lastChest;
	private static boolean lastWasCopper;
	private static long lastClickAt;

	private ChestScreenButton() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide()) {
				BlockState state = level.getBlockState(hit.getBlockPos());
				boolean chest = ChestLabels.isLabelableChest(state);
				boolean copper = state.is(BlockTags.COPPER_CHESTS);
				if (chest || copper) {
					lastChest = hit.getBlockPos().immutable();
					lastWasCopper = copper;
					lastClickAt = System.currentTimeMillis();
				}
			}
			return InteractionResult.PASS;
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ContainerScreen container) {
				addButton(container);
			}
		});
	}

	private static void addButton(ContainerScreen screen) {
		BlockPos pos = lastChest;
		if (pos == null || System.currentTimeMillis() - lastClickAt > CLICK_TO_SCREEN_MILLIS) {
			return;
		}
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
		int x = accessor.wbcg$leftPos() + accessor.wbcg$imageWidth() + Ui.GAP;
		int y = accessor.wbcg$topPos();
		String tooltipKey = lastWasCopper
				? "waybettercoppergolem.chest_button.zone" : "waybettercoppergolem.chest_button.labels";
		Button button = Button.builder(Component.translatable("waybettercoppergolem.chest_button"),
						b -> ClientPlayNetworking.send(new EditorPayloads.OpenEditor(pos)))
				.tooltip(Tooltip.create(Component.translatable(tooltipKey)))
				.bounds(x, y, WIDTH, Ui.BUTTON_HEIGHT).build();
		Screens.getWidgets((Screen) screen).add(button);
	}
}
