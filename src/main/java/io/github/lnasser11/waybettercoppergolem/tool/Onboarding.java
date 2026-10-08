package io.github.lnasser11.waybettercoppergolem.tool;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Two one-time chat hints per player: the first time they hang a frame on
 * a chest, and the first time they use the label tool. Each ends with a
 * clickable "/wbcg guide" that hands out the written guide. Remembered in
 * a persistent player attachment so nobody is told twice.
 */
public final class Onboarding {
	public record State(boolean frameHinted, boolean toolHinted) {
		public static final State NONE = new State(false, false);
		public static final Codec<State> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("frame", false).forGetter(State::frameHinted),
				Codec.BOOL.optionalFieldOf("tool", false).forGetter(State::toolHinted)
		).apply(instance, State::new));
	}

	private Onboarding() {
	}

	private static State state(ServerPlayer player) {
		State state = player.getAttached(WayBetterCopperGolem.ONBOARDING);
		return state == null ? State.NONE : state;
	}

	/** Called when a player places an item frame on a chest. */
	public static void hintFrame(ServerPlayer player) {
		State state = state(player);
		if (state.frameHinted()) {
			return;
		}
		player.setAttached(WayBetterCopperGolem.ONBOARDING, new State(true, state.toolHinted()));
		player.sendSystemMessage(hint(Component.translatable("waybettercoppergolem.onboarding.frame",
				toolName(player))));
	}

	/** Called on the first gesture with the label tool. */
	public static void hintTool(ServerPlayer player) {
		State state = state(player);
		if (state.toolHinted()) {
			return;
		}
		player.setAttached(WayBetterCopperGolem.ONBOARDING, new State(state.frameHinted(), true));
		player.sendSystemMessage(hint(Component.translatable("waybettercoppergolem.onboarding.tool",
				toolName(player))));
	}

	public static boolean hasSeen(ServerPlayer player, boolean tool) {
		State state = state(player);
		return tool ? state.toolHinted() : state.frameHinted();
	}

	private static Component toolName(ServerPlayer player) {
		var item = WbcgConfig.toolItem(player.level());
		return item.getName(item.getDefaultInstance());
	}

	private static Component hint(Component body) {
		MutableComponent line = Component.literal("[WBCG] ").withStyle(ChatFormatting.GOLD).append(body.copy().withStyle(ChatFormatting.WHITE));
		return line.append(" ").append(Component.translatable("waybettercoppergolem.onboarding.guide_link").withStyle(style -> style
				.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/wbcg guide"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/wbcg guide")))));
	}
}
