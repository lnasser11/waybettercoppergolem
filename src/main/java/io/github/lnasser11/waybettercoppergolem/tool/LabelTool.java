package io.github.lnasser11.waybettercoppergolem.tool;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * The label tool: a configurable vanilla item (a feather by default) held
 * in the main hand while sneaking.
 *
 * <table>
 *   <tr><th></th><th>chest / trapped chest</th><th>copper chest</th><th>air / other block</th></tr>
 *   <tr><td>sneak-left-click</td><td>copy labels (unlabeled: clear clipboard)</td>
 *       <td>copy zone settings</td><td>—</td></tr>
 *   <tr><td>sneak-right-click</td><td>paste labels (replace, explicit)</td>
 *       <td>paste zone settings</td><td>show the clipboard (the picker, once it exists)</td></tr>
 * </table>
 *
 * <p>Every gesture lands on a block or the air, never on an item frame, so
 * vanilla frame handling and click-through mods are untouched. Sneaking
 * with an item in hand does nothing to a chest in vanilla, and a left
 * click only starts mining, which is cancelled here, so no vanilla
 * behavior is lost.
 */
public final class LabelTool {
	private LabelTool() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register(LabelTool::onAttackBlock);
		UseBlockCallback.EVENT.register(LabelTool::onUseBlock);
		UseItemCallback.EVENT.register(LabelTool::onUseItem);
	}

	private static boolean holdingTool(Player player, Level level, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && !player.isSpectator()
				&& WbcgConfig.isTool(level, player.getMainHandItem());
	}

	/** Sneak-left-click: copy. */
	private static InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand,
			BlockPos pos, Direction direction) {
		if (!holdingTool(player, level, hand)) {
			return InteractionResult.PASS;
		}
		BlockState state = level.getBlockState(pos);
		boolean chest = ChestLabels.isLabelableChest(state);
		boolean copperChest = state.is(BlockTags.COPPER_CHESTS);
		if (!chest && !copperChest) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			if (chest) {
				copyLabels(serverPlayer, serverLevel, pos, state);
			} else {
				copyZone(serverPlayer, serverLevel, pos);
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Sneak-right-click on a block: paste. */
	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
			BlockHitResult hit) {
		if (!holdingTool(player, level, hand)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		boolean chest = ChestLabels.isLabelableChest(state);
		boolean copperChest = state.is(BlockTags.COPPER_CHESTS);
		if (!chest && !copperChest) {
			return InteractionResult.PASS; // falls through to onUseItem (the picker)
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			if (chest) {
				pasteLabels(serverPlayer, serverLevel, pos, state);
			} else {
				pasteZone(serverPlayer, serverLevel, pos);
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Sneak-right-click with nothing useful in front: show what the tool carries. */
	private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (!holdingTool(player, level, hand)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendOverlayMessage(describeClipboard(Clipboard.of(serverPlayer)));
		}
		return InteractionResult.SUCCESS;
	}

	private static void copyLabels(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, pos, state);
		Clipboard clipboard = Clipboard.of(player);
		if (labels.isEmpty()) {
			Clipboard.set(player, clipboard.withLabels(Optional.empty()));
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.cleared_clipboard"));
			particles(level, pos, ParticleTypes.WAX_OFF);
			return;
		}
		Clipboard.set(player, clipboard.withLabels(Optional.of(labels.labels())));
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.copied",
				LabelResolver.listNames(labels.labels())));
		particles(level, pos, ParticleTypes.WAX_ON);
	}

	private static void pasteLabels(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		Clipboard clipboard = Clipboard.of(player);
		if (clipboard.labels().isEmpty()) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.nothing_to_paste"));
			return;
		}
		List<ChestLabel> labels = clipboard.labels().get();
		if (labels.isEmpty()) {
			ChestLabelSet now = ChestLabels.clear(level, pos, state);
			String key = now.isEmpty()
					? "waybettercoppergolem.tool.cleared_chest"
					: "waybettercoppergolem.tool.cleared_chest_frames";
			player.sendOverlayMessage(Component.translatable(key, LabelResolver.listNames(now.labels())));
			particles(level, pos, ParticleTypes.WAX_OFF);
			return;
		}
		ChestLabels.setExplicit(level, pos, state, labels);
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.pasted",
				LabelResolver.listNames(labels)));
		particles(level, pos, ParticleTypes.HAPPY_VILLAGER);
	}

	private static void copyZone(ServerPlayer player, ServerLevel level, BlockPos pos) {
		ZoneSettings settings = Zones.at(level, pos);
		Clipboard.set(player, Clipboard.of(player).withZone(settings));
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.copied_zone",
				Zones.describe(settings)));
		particles(level, pos, ParticleTypes.WAX_ON);
	}

	private static void pasteZone(ServerPlayer player, ServerLevel level, BlockPos pos) {
		Clipboard clipboard = Clipboard.of(player);
		if (clipboard.zone().isEmpty()) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.nothing_to_paste_zone"));
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return;
		}
		ZoneSettings settings = clipboard.zone().get();
		Zones.store(blockEntity, settings);
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.pasted_zone",
				Zones.describe(settings)));
		particles(level, pos, ParticleTypes.HAPPY_VILLAGER);
	}

	/** "Clipboard: Iron Ingot · zone: radius 32 …", or the empty hint. */
	public static Component describeClipboard(Clipboard clipboard) {
		if (clipboard.isEmpty()) {
			return Component.translatable("waybettercoppergolem.tool.clipboard_empty");
		}
		Component labels = clipboard.labels()
				.map(list -> list.isEmpty()
						? Component.translatable("waybettercoppergolem.tool.clipboard_clear_marker")
						: LabelResolver.listNames(list))
				.orElse(null);
		Component zone = clipboard.zone().map(Zones::describe).orElse(null);
		if (labels != null && zone != null) {
			return Component.translatable("waybettercoppergolem.tool.clipboard_both", labels, zone);
		}
		if (labels != null) {
			return Component.translatable("waybettercoppergolem.tool.clipboard", labels);
		}
		return Component.translatable("waybettercoppergolem.tool.clipboard_zone", zone);
	}

	private static void particles(ServerLevel level, BlockPos pos, ParticleOptions type) {
		Vec3 center = Vec3.atCenterOf(pos);
		level.sendParticles(type, center.x, center.y, center.z, 12, 0.4, 0.4, 0.4, 0.0);
	}
}
