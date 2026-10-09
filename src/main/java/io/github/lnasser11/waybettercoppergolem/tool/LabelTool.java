package io.github.lnasser11.waybettercoppergolem.tool;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneAccess;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The label tool: a configurable vanilla item (a feather by default) held
 * in the main hand while sneaking.
 *
 * <table>
 *   <tr><th></th><th>chest / trapped chest</th><th>copper chest</th><th>air / other block</th></tr>
 *   <tr><td>sneak-left-click</td><td>copy labels (unlabeled: clear clipboard)</td>
 *       <td>copy the zone's settings</td><td>—</td></tr>
 *   <tr><td>sneak-right-click</td><td>paste labels (replace, explicit)</td>
 *       <td>paste settings into the zone</td><td>show the clipboard (the picker, once it exists)</td></tr>
 * </table>
 *
 * <p>In <em>area mode</em> (entered from the zone screen) the next two
 * sneak-right-clicks on any block are the corners of the zone's box
 * instead, and a sneak-right-click on the air cancels.
 *
 * <p>Every gesture lands on a block or the air, never on an item frame, so
 * vanilla frame handling and click-through mods are untouched. Sneaking
 * with an item in hand does nothing to a chest in vanilla, and a left
 * click only starts mining, which is cancelled here, so no vanilla
 * behavior is lost.
 */
public final class LabelTool {
	private static final long AREA_MODE_TTL_MILLIS = 60 * 1000;
	/**
	 * A use-item packet arriving this soon after a corner click is the tail
	 * of that same click (vanilla sends one when the block use passed), not
	 * a cancel.
	 */
	private static final long CORNER_CLICK_GRACE_MILLIS = 300;

	private record AreaSelection(ResourceKey<Level> dimension, BlockPos anchor, @Nullable BlockPos first,
			long expiresAt, long lastClickAt) {
		static AreaSelection start(ResourceKey<Level> dimension, BlockPos anchor) {
			long now = System.currentTimeMillis();
			return new AreaSelection(dimension, anchor, null, now + AREA_MODE_TTL_MILLIS, now);
		}

		AreaSelection withFirst(BlockPos first) {
			long now = System.currentTimeMillis();
			return new AreaSelection(dimension, anchor, first, now + AREA_MODE_TTL_MILLIS, now);
		}

		boolean expired() {
			return System.currentTimeMillis() > expiresAt;
		}

		boolean justClicked() {
			return System.currentTimeMillis() - lastClickAt < CORNER_CLICK_GRACE_MILLIS;
		}
	}

	private static final Map<UUID, AreaSelection> AREA_SELECTIONS = new ConcurrentHashMap<>();

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

	// ---------------------------------------------------------------- area mode

	/** Starts area mode for the zone anchored at {@code anchor}. */
	public static void beginAreaSelection(ServerPlayer player, ServerLevel level, BlockPos anchor) {
		AREA_SELECTIONS.put(player.getUUID(), AreaSelection.start(level.dimension(), anchor.immutable()));
		player.sendSystemMessage(Component.translatable("waybettercoppergolem.area.begin",
				WbcgConfig.toolItem(level).getName(WbcgConfig.toolItem(level).getDefaultInstance())));
		syncAreaMode(player, 1);
	}

	/** Tells the client where the player is in area mode (0 = out), for the HUD and the picker. */
	private static void syncAreaMode(ServerPlayer player, int step) {
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
				io.github.lnasser11.waybettercoppergolem.net.AreaModePayload.TYPE)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
					new io.github.lnasser11.waybettercoppergolem.net.AreaModePayload(step));
		}
	}

	public static boolean inAreaMode(Player player) {
		AreaSelection selection = AREA_SELECTIONS.get(player.getUUID());
		return selection != null && !selection.expired();
	}

	/** Handles a corner click; returns false when the player is not in area mode. */
	private static boolean handleAreaClick(ServerPlayer player, ServerLevel level, BlockPos pos) {
		AreaSelection selection = AREA_SELECTIONS.get(player.getUUID());
		if (selection == null) {
			return false;
		}
		if (selection.expired() || !selection.dimension().equals(level.dimension())) {
			AREA_SELECTIONS.remove(player.getUUID());
			syncAreaMode(player, 0);
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.area.expired"));
			return true;
		}
		if (selection.first() == null) {
			AREA_SELECTIONS.put(player.getUUID(), selection.withFirst(pos.immutable()));
			cornerParticles(level, player, pos);
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.area.first"));
			syncAreaMode(player, 2);
			return true;
		}
		AREA_SELECTIONS.remove(player.getUUID());
		syncAreaMode(player, 0);
		BoundingBox area = Zone.areaFromCorners(selection.first(), pos);
		boolean expanded = !area.isInside(selection.anchor());
		if (expanded) {
			area = area.encapsulate(selection.anchor());
		}
		Zone zone = Zones.all(level).getOrDefault(selection.anchor(), Zone.defaultAround(selection.anchor()));
		// Setting the area of a zone nobody owns yet makes it yours (when you may create zones).
		zone = ZoneAccess.claimIfUnowned(level, new Zones.ZoneRef(selection.anchor(), zone), player).zone();
		if (!ZoneAccess.canEdit(new Zones.ZoneRef(selection.anchor(), zone), player)) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.denied"));
			return true;
		}
		Zones.put(level, selection.anchor(), zone.withArea(area));
		io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem.LOGGER.info("[zone] {} set the area of the zone at {} to {}",
				ZoneAccess.nameOf(player), selection.anchor(), Zones.describeArea(area).getString());
		cornerParticles(level, player, pos);
		Zones.showOutline(player, level, area);
		player.sendSystemMessage(Component.translatable(
				expanded ? "waybettercoppergolem.area.done_expanded" : "waybettercoppergolem.area.done",
				Zones.describeArea(area)));
		return true;
	}

	private static boolean cancelAreaMode(ServerPlayer player) {
		AreaSelection selection = AREA_SELECTIONS.get(player.getUUID());
		if (selection == null) {
			return false;
		}
		if (selection.justClicked()) {
			return true; // the use-item tail of the corner click itself; nothing to cancel
		}
		AREA_SELECTIONS.remove(player.getUUID());
		syncAreaMode(player, 0);
		player.sendSystemMessage(Component.translatable("waybettercoppergolem.area.cancelled"));
		return true;
	}

	// ---------------------------------------------------------------- gestures

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
			Onboarding.hintTool(serverPlayer);
			if (chest) {
				copyLabels(serverPlayer, serverLevel, pos, state);
			} else {
				copyZone(serverPlayer, serverLevel, pos);
			}
		}
		return InteractionResult.SUCCESS;
	}

	/** Sneak-right-click on a block: a corner in area mode, otherwise paste. */
	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
			BlockHitResult hit) {
		if (!holdingTool(player, level, hand)) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hit.getBlockPos();
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
				&& handleAreaClick(serverPlayer, serverLevel, pos)) {
			return InteractionResult.SUCCESS;
		}
		BlockState state = level.getBlockState(pos);
		boolean chest = ChestLabels.isLabelableChest(state);
		boolean copperChest = state.is(BlockTags.COPPER_CHESTS);
		if (!chest && !copperChest) {
			return InteractionResult.PASS; // falls through to onUseItem (the picker)
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			Onboarding.hintTool(serverPlayer);
			if (chest) {
				pasteLabels(serverPlayer, serverLevel, pos, state);
			} else {
				pasteZone(serverPlayer, serverLevel, pos);
			}
		}
		return InteractionResult.SUCCESS;
	}

	/**
	 * Sneak-right-click with nothing useful in front: cancel area mode, or
	 * show what the tool carries. The client side passes so the picker
	 * (registered client-side) gets its turn when the click hits the air.
	 */
	private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		if (!holdingTool(player, level, hand) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		Onboarding.hintTool(serverPlayer);
		if (!cancelAreaMode(serverPlayer)) {
			serverPlayer.sendOverlayMessage(describeClipboard(Clipboard.of(serverPlayer)));
		}
		return InteractionResult.SUCCESS;
	}

	/** The picker's choice arrives here; validated, then stored and confirmed. */
	public static void applyPickerChoice(ServerPlayer player, Optional<List<ChestLabel>> labels) {
		if (labels.isPresent()) {
			List<ChestLabel> list = labels.get();
			if (list.size() > 8 || !list.stream().allMatch(LabelResolver::isValid)) {
				return;
			}
		}
		Clipboard updated = Clipboard.of(player).withLabels(labels);
		Clipboard.set(player, updated);
		player.sendOverlayMessage(describeClipboard(updated));
	}

	// ---------------------------------------------------------------- labels

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
		if (!ZoneAccess.canEditLabelsAt(level, player, pos)) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.access.labels_denied"));
			return;
		}
		List<ChestLabel> labels = clipboard.labels().get();
		if (labels.isEmpty()) {
			ChestLabelSet now = ChestLabels.clear(level, pos, state);
			io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem.LOGGER.info("[labels] {} cleared the labels of the chest at {} (tool)",
					ZoneAccess.nameOf(player), pos);
			String key = now.isEmpty()
					? "waybettercoppergolem.tool.cleared_chest"
					: "waybettercoppergolem.tool.cleared_chest_frames";
			player.sendOverlayMessage(Component.translatable(key, LabelResolver.listNames(now.labels())));
			particles(level, pos, ParticleTypes.WAX_OFF);
			return;
		}
		ChestLabels.setExplicit(level, pos, state, labels);
		io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem.LOGGER.info("[labels] {} labeled the chest at {} as {} (tool)",
				ZoneAccess.nameOf(player), pos, LabelResolver.listNames(labels).getString());
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.pasted",
				LabelResolver.listNames(labels)));
		particles(level, pos, ParticleTypes.HAPPY_VILLAGER);
	}

	// ---------------------------------------------------------------- zones

	private static void copyZone(ServerPlayer player, ServerLevel level, BlockPos pos) {
		ZoneSettings settings = Zones.settingsAt(level, pos);
		Clipboard.set(player, Clipboard.of(player).withZone(settings));
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.copied_zone",
				Zones.describe(settings)));
		particles(level, pos, ParticleTypes.WAX_ON);
	}

	/** Pastes settings into the zone this copper chest belongs to (creating one if needed); the area is untouched. */
	private static void pasteZone(ServerPlayer player, ServerLevel level, BlockPos pos) {
		Clipboard clipboard = Clipboard.of(player);
		if (clipboard.zone().isEmpty()) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.nothing_to_paste_zone"));
			return;
		}
		ZoneSettings settings = clipboard.zone().get();
		Optional<Zones.ZoneRef> found = Zones.zoneForCopperChest(level, pos, player);
		if (found.isEmpty()) {
			return;
		}
		Zones.ZoneRef ref = found.get();
		if (!ZoneAccess.canEdit(ref, player)) {
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.access.denied"));
			return;
		}
		Zones.put(level, ref.anchor(), ref.zone().withSettings(settings));
		io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem.LOGGER.info("[zone] {} pasted settings onto the zone at {}: {}",
				ZoneAccess.nameOf(player), ref.anchor(), Zones.describe(settings).getString());
		player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.pasted_zone",
				Zones.describe(settings)));
		particles(level, pos, ParticleTypes.HAPPY_VILLAGER);
	}

	// ---------------------------------------------------------------- feedback

	/** "Clipboard: Iron Ingot · zone: reorganize on …", or the empty hint. */
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

	private static void cornerParticles(ServerLevel level, ServerPlayer player, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		level.sendParticles(player, ParticleTypes.END_ROD, true, true,
				center.x, center.y + 0.5, center.z, 20, 0.3, 0.5, 0.3, 0.0);
	}
}
