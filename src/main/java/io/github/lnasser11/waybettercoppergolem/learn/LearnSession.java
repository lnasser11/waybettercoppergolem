package io.github.lnasser11.waybettercoppergolem.learn;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.Proposal;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.Report;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner.Skipped;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The learn flow: scan, show a clickable preview in chat, keep the
 * proposal around for a couple of minutes, apply on request. Shared by
 * {@code /wbcg learn} and the zone screen's "Learn this zone" button.
 */
public final class LearnSession {
	private static final long TTL_MILLIS = 2 * 60 * 1000;
	private static final int PREVIEW_LINES = 15;

	private record Pending(Report report, ResourceKey<Level> dimension, boolean overwrite, long expiresAt) {
		boolean expired() {
			return System.currentTimeMillis() > expiresAt;
		}
	}

	private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

	private LearnSession() {
	}

	public static boolean allowed(ServerPlayer player) {
		return !WbcgConfig.get().learnRequiresOp() || Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	/** Scans a cube around the player, stores the proposal, and prints the preview. */
	public static void preview(ServerPlayer player, ServerLevel level, BlockPos center, int radius, boolean overwrite) {
		preview(player, level, new BoundingBox(center).inflatedBy(radius),
				Component.translatable("waybettercoppergolem.learn.scope.radius", radius), overwrite);
	}

	/** Scans {@code area}, stores the proposal for the player, and prints the preview. */
	public static void preview(ServerPlayer player, ServerLevel level, BoundingBox area, Component scope,
			boolean overwrite) {
		Report report = RoomLearner.scan(level, area, overwrite);
		if (report.isEmpty()) {
			PENDING.remove(player.getUUID());
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.none", scope));
			return;
		}
		PENDING.put(player.getUUID(), new Pending(report, level.dimension(), overwrite,
				System.currentTimeMillis() + TTL_MILLIS));

		player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.header",
				report.proposals().size(), report.skipped().size(), scope).withStyle(ChatFormatting.GOLD));
		int shown = 0;
		for (Proposal proposal : report.proposals()) {
			if (shown++ >= PREVIEW_LINES) {
				player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.more",
						report.proposals().size() - PREVIEW_LINES).withStyle(ChatFormatting.GRAY));
				break;
			}
			player.sendSystemMessage(proposalLine(proposal));
		}
		if (!report.skipped().isEmpty()) {
			MutableComponent line = Component.translatable("waybettercoppergolem.learn.skipped_header")
					.withStyle(ChatFormatting.GRAY);
			int count = 0;
			for (Skipped skipped : report.skipped()) {
				if (count++ >= PREVIEW_LINES) {
					line.append(" ").append(Component.translatable("waybettercoppergolem.learn.more",
							report.skipped().size() - PREVIEW_LINES));
					break;
				}
				line.append(" ").append(posLink(skipped.pos()))
						.append(Component.translatable("waybettercoppergolem.learn.skip." + skipped.reason().name().toLowerCase())
								.withStyle(ChatFormatting.GRAY));
			}
			player.sendSystemMessage(line);
		}
		if (report.truncated()) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.truncated")
					.withStyle(ChatFormatting.RED));
		}
		if (!report.proposals().isEmpty()) {
			player.sendSystemMessage(Component.literal("")
					.append(button("waybettercoppergolem.learn.apply_button", "/wbcg learn apply", ChatFormatting.GREEN))
					.append("  ")
					.append(button("waybettercoppergolem.learn.cancel_button", "/wbcg learn cancel", ChatFormatting.RED)));
		}
	}

	/** Writes the pending proposal. Returns how many chests were labeled, or -1 if nothing was pending. */
	public static int apply(ServerPlayer player) {
		Pending pending = PENDING.remove(player.getUUID());
		if (pending == null || pending.expired()) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.nothing_pending")
					.withStyle(ChatFormatting.RED));
			return -1;
		}
		ServerLevel level = player.level().getServer().getLevel(pending.dimension());
		if (level == null) {
			return -1;
		}
		int applied = 0;
		int stale = 0;
		for (Proposal proposal : pending.report().proposals()) {
			if (!RoomLearner.stillApplicable(level, proposal.pos(), pending.overwrite())) {
				stale++;
				continue;
			}
			ChestLabels.setExplicit(level, proposal.pos(), level.getBlockState(proposal.pos()), List.of(proposal.label()));
			applied++;
		}
		player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.applied", applied)
				.withStyle(ChatFormatting.GREEN));
		if (stale > 0) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.stale", stale)
					.withStyle(ChatFormatting.GRAY));
		}
		return applied;
	}

	public static boolean cancel(ServerPlayer player) {
		boolean had = PENDING.remove(player.getUUID()) != null;
		player.sendSystemMessage(Component.translatable(had
				? "waybettercoppergolem.learn.cancelled" : "waybettercoppergolem.learn.nothing_pending"));
		return had;
	}

	/** A short burst of bright particles over a chest so the player can find it. */
	public static void highlight(ServerPlayer player, ServerLevel level, BlockPos pos) {
		double x = pos.getX() + 0.5;
		double z = pos.getZ() + 0.5;
		for (int i = 0; i < 6; i++) {
			level.sendParticles(player, ParticleTypes.END_ROD, true, true,
					x, pos.getY() + 0.6 + i * 0.35, z, 4, 0.25, 0.1, 0.25, 0.0);
		}
	}

	private static Component proposalLine(Proposal proposal) {
		MutableComponent line = Component.literal(" ").append(posLink(proposal.pos()))
				.append(" ").append(LabelResolver.shortName(proposal.label()).copy().withStyle(ChatFormatting.AQUA));
		if (!proposal.current().isEmpty()) {
			line.append(" ").append(Component.translatable("waybettercoppergolem.learn.was",
					LabelResolver.listNames(proposal.current().labels())).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (!proposal.misplaced().isEmpty()) {
			MutableComponent names = Component.empty();
			for (int i = 0; i < proposal.misplaced().size(); i++) {
				Item item = proposal.misplaced().get(i);
				if (i > 0) {
					names.append(", ");
				}
				names.append(item.getName(item.getDefaultInstance()));
			}
			line.append(" ").append(Component.translatable("waybettercoppergolem.learn.misplaced", names)
					.withStyle(ChatFormatting.YELLOW));
		}
		return line;
	}

	/** "[x y z]" that highlights the chest when clicked. */
	private static Component posLink(BlockPos pos) {
		String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		return Component.literal("[" + coords + "]").withStyle(style -> style
				.withColor(ChatFormatting.DARK_AQUA)
				.withClickEvent(new ClickEvent.RunCommand("/wbcg highlight " + coords))
				.withHoverEvent(new HoverEvent.ShowText(
						Component.translatable("waybettercoppergolem.learn.highlight_hint"))));
	}

	private static Component button(String key, String command, ChatFormatting color) {
		return Component.translatable(key).withStyle(style -> style
				.withColor(color).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}
}
