package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The client's mirror of the server settings it needs: which item is the
 * label tool. Set from the join-time config payload; falls back to the
 * feather when none arrives (a server running an older build), and resets
 * on disconnect so a different server's choice is not carried over.
 */
public final class ClientConfig {
	private static volatile Item toolItem = Items.FEATHER;

	private ClientConfig() {
	}

	public static Item toolItem() {
		return toolItem;
	}

	public static boolean isTool(ItemStack stack) {
		return !stack.isEmpty() && stack.is(toolItem);
	}

	static void setToolItem(Identifier id) {
		toolItem = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.FEATHER);
	}

	static void reset() {
		toolItem = BuiltInRegistries.ITEM.getOptional(WbcgConfig.DEFAULT_TOOL).orElse(Items.FEATHER);
	}
}
