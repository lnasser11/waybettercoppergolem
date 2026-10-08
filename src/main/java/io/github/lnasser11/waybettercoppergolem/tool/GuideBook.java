package io.github.lnasser11.waybettercoppergolem.tool;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game guide: a written book whose pages come from the lang file,
 * so it reads in the player's language and names the configured tool
 * item. Handed out by {@code /wbcg guide} and linked from the onboarding
 * hints.
 */
public final class GuideBook {
	/** Lang keys {@code waybettercoppergolem.guide.page.1 … N}. */
	public static final int PAGES = 7;

	private GuideBook() {
	}

	public static ItemStack create(ServerPlayer player) {
		var tool = WbcgConfig.toolItem(player.level());
		Component toolName = tool.getName(tool.getDefaultInstance());
		List<Filterable<Component>> pages = new ArrayList<>();
		for (int i = 1; i <= PAGES; i++) {
			pages.add(Filterable.passThrough(Component.translatable("waybettercoppergolem.guide.page." + i, toolName)));
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
				Filterable.passThrough(Component.translatable("waybettercoppergolem.guide.title").getString()),
				"Way Better Copper Golem", 0, pages, true));
		return book;
	}

	/** Puts the guide in the player's inventory, or drops it at their feet when it is full. */
	public static void give(ServerPlayer player) {
		ItemStack book = create(player);
		if (!player.addItem(book)) {
			player.drop(book, false);
		}
	}
}
