package io.github.lnasser11.waybettercoppergolem.tuning;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Optional;

/** Server side of the category tuning screen. */
public final class TuningNet {
	private TuningNet() {
	}

	public static boolean canEdit(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	/** Sends the tag's tweaks to the player, who then opens (or refreshes) the tuning screen. */
	public static void open(ServerPlayer player, Identifier tagId) {
		if (!(player.level() instanceof ServerLevel level)
				|| BuiltInRegistries.ITEM.get(LabelResolver.itemTag(tagId)).isEmpty()
				|| !ServerPlayNetworking.canSend(player, TuningPayloads.TuningContext.TYPE)) {
			return;
		}
		CategoryTuning.TagOverride override = CategoryTuning.overridesFor(level, tagId);
		ServerPlayNetworking.send(player, new TuningPayloads.TuningContext(tagId,
				List.copyOf(override.added()), List.copyOf(override.removed()), canEdit(player)));
	}

	/** Applies one tweak (or a reset) for an operator, confirms, and refreshes the screen. */
	public static void change(ServerPlayer player, Identifier tagId, Optional<Identifier> itemId, boolean include) {
		if (!(player.level() instanceof ServerLevel level)
				|| BuiltInRegistries.ITEM.get(LabelResolver.itemTag(tagId)).isEmpty()) {
			return;
		}
		if (!canEdit(player)) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tuning.not_allowed"));
			return;
		}
		Component confirmation = applyTweak(level, tagId, itemId, include);
		if (confirmation != null) {
			player.sendOverlayMessage(confirmation);
			open(player, tagId);
		}
	}

	/**
	 * The tweak itself, with no permission check: a reset when {@code itemId}
	 * is absent, else include/exclude. Returns the confirmation to show, or
	 * null when the item does not exist.
	 */
	public static Component applyTweak(ServerLevel level, Identifier tagId, Optional<Identifier> itemId, boolean include) {
		if (itemId.isEmpty()) {
			CategoryTuning.reset(level, tagId);
			return Component.translatable("waybettercoppergolem.command.reset", LabelResolver.tagName(tagId));
		}
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId.get());
		if (item.isEmpty()) {
			return null;
		}
		CategoryTuning.setMembership(level, tagId, item.get(), include);
		return Component.translatable(
				include ? "waybettercoppergolem.tuning.added" : "waybettercoppergolem.tuning.removed",
				item.get().getName(item.get().getDefaultInstance()), LabelResolver.tagName(tagId));
	}
}
