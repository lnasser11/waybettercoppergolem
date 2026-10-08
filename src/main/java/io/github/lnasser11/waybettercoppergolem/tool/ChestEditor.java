package io.github.lnasser11.waybettercoppergolem.tool;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.label.LabelSuggestions;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Server side of the per-chest editor and the zone screen when opened from
 * the golem button inside a chest's inventory screen.
 */
public final class ChestEditor {
	private static final double MAX_DISTANCE_SQ = 10.0 * 10.0;

	private ChestEditor() {
	}

	/** The golem button: a regular chest gets the editor, a copper chest its zone screen. */
	public static void open(ServerPlayer player, BlockPos pos) {
		if (!(player.level() instanceof ServerLevel level) || !inReach(player, level, pos)) {
			return;
		}
		// The button sits in the chest's own screen. The editor is a plain screen, not a
		// container menu, so the chest menu must be closed on both sides first; otherwise
		// the server keeps syncing a 90-slot chest into whatever menu the client opens next.
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
		BlockState state = level.getBlockState(pos);
		if (ChestLabels.isLabelableChest(state)) {
			sendContext(player, level, pos, state);
		} else if (state.is(BlockTags.COPPER_CHESTS)) {
			openZoneScreen(player, level, pos);
		}
	}

	/** Opens (or refreshes) the editor for a regular chest. */
	public static void sendContext(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
		if (!ServerPlayNetworking.canSend(player, EditorPayloads.EditorContext.TYPE)) {
			return;
		}
		ChestLabelSet current = ChestLabels.effectiveLabelSet(level, pos, state);
		ServerPlayNetworking.send(player, new EditorPayloads.EditorContext(
				pos, current, LabelSuggestions.forChest(level, pos)));
	}

	/** The zone screen for the zone this copper chest belongs to (creating a default one if needed). */
	public static void openZoneScreen(ServerPlayer player, ServerLevel level, BlockPos pos) {
		Zones.ZoneRef zone = Zones.zoneForCopperChest(level, pos);
		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, p) -> new ZoneSettingsMenu(
						containerId, ContainerLevelAccess.create(level, pos), zone.anchor(), ZoneSettingsMenu.dataFor(zone)),
				Component.translatable("waybettercoppergolem.settings.title")));
		Zones.showOutline(player, level, zone.area());
	}

	/**
	 * Applies the editor's choice: an empty list clears the chest, anything
	 * else becomes its explicit labels. The same goes onto the clipboard so
	 * the next chests can be pasted, and the editor is refreshed.
	 */
	public static void apply(ServerPlayer player, BlockPos pos, List<ChestLabel> labels) {
		if (!(player.level() instanceof ServerLevel level) || !inReach(player, level, pos)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (!ChestLabels.isLabelableChest(state) || labels.size() > 8 || !labels.stream().allMatch(LabelResolver::isValid)) {
			return;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		if (labels.isEmpty()) {
			ChestLabelSet now = ChestLabels.clear(level, pos, state);
			String key = now.isEmpty()
					? "waybettercoppergolem.tool.cleared_chest" : "waybettercoppergolem.tool.cleared_chest_frames";
			player.sendOverlayMessage(Component.translatable(key, LabelResolver.listNames(now.labels())));
			level.sendParticles(ParticleTypes.WAX_OFF, center.x, center.y, center.z, 12, 0.4, 0.4, 0.4, 0.0);
			Clipboard.set(player, Clipboard.of(player).withLabels(Optional.of(List.of())));
		} else {
			ChestLabels.setExplicit(level, pos, state, labels);
			player.sendOverlayMessage(Component.translatable("waybettercoppergolem.tool.pasted",
					LabelResolver.listNames(labels)));
			level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y, center.z, 12, 0.4, 0.4, 0.4, 0.0);
			Clipboard.set(player, Clipboard.of(player).withLabels(Optional.of(labels)));
		}
		sendContext(player, level, pos, state);
	}

	/** Within ten blocks, or anywhere inside the zone the player is standing in (the overview works a whole room). */
	private static boolean inReach(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_DISTANCE_SQ) {
			return true;
		}
		return Zones.zoneAt(level, player.blockPosition()).map(zone -> zone.zone().contains(pos)).orElse(false);
	}
}
