package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads.Move;
import io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger;
import io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.TransportItemTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "What would the golems do right now?" For every stack in every copper
 * chest of the zone, the destination the sorting engine would pick from
 * that copper chest, using the same ranking the golems use. Identical
 * (item, from, to) moves are merged. It ignores whether a golem could
 * physically reach either chest, and whether earlier moves fill a chest
 * up, so it is a preview, not a promise. With reorganize on, the misplaced
 * stacks and where they would go are listed too; with tidy on, the pending
 * consolidations across sibling chests and the chests whose stacks are out
 * of order. While the zone's golems hang
 * frames, item frames in the copper chests are supplies, not cargo, and
 * are left out.
 */
public final class ZoneSimulation {
	private static final int MAX_MOVES = 200;

	private record Key(Identifier item, BlockPos from, Optional<BlockPos> to) {
	}

	private ZoneSimulation() {
	}

	public static void send(ServerPlayer player, ServerLevel level, Zones.ZoneRef zone) {
		if (ServerPlayNetworking.canSend(player, ZonePayloads.Simulation.TYPE)) {
			ServerPlayNetworking.send(player, build(level, zone));
		}
	}

	public static ZonePayloads.Simulation build(ServerLevel level, Zones.ZoneRef zone) {
		AABB area = Zones.toAABB(zone.area());
		List<BlockPos> sources = ZoneOverview.copperChestsIn(level, zone.area());
		Map<Key, Integer> merged = new LinkedHashMap<>();
		Map<Optional<BlockPos>, List<io.github.lnasser11.waybettercoppergolem.label.ChestLabel>> labelsOf = new LinkedHashMap<>();
		for (BlockPos source : sources) {
			Container container = ChestLabels.container(level, source);
			if (container == null) {
				continue;
			}
			Vec3 from = Vec3.atCenterOf(source);
			for (ItemStack stack : container) {
				if (stack.isEmpty() || (zone.settings().hangFrames() && FrameHanger.isFrame(stack))) {
					continue;
				}
				Optional<TransportItemTarget> target = SortingEngine.findDepositTarget(level, from, stack,
						ChestLabels::isLabelableChest, Set.of(), Set.of(), area);
				Optional<BlockPos> to = target.map(TransportItemTarget::pos);
				if (to.isPresent()) {
					labelsOf.computeIfAbsent(to, key -> ChestLabels.effectiveLabels(level, key.get(), target.get().state()));
				}
				merged.merge(new Key(BuiltInRegistries.ITEM.getKey(stack.getItem()), source, to), stack.getCount(), Integer::sum);
			}
		}
		List<Move> moves = new ArrayList<>();
		for (Map.Entry<Key, Integer> entry : merged.entrySet()) {
			if (moves.size() >= MAX_MOVES) {
				break;
			}
			Key key = entry.getKey();
			moves.add(new Move(key.item(), entry.getValue(), key.from(), key.to(),
					labelsOf.getOrDefault(key.to(), List.of())));
		}
		if (zone.settings().reorganize()) {
			for (SortingEngine.ReorganizeMove fix : SortingEngine.planReorganize(level, area,
					io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize())) {
				if (moves.size() >= MAX_MOVES) {
					break;
				}
				moves.add(new Move(BuiltInRegistries.ITEM.getKey(fix.stack().getItem()), fix.stack().getCount(),
						fix.source().pos(), Optional.of(fix.destination().pos()),
						ChestLabels.effectiveLabels(level, fix.destination().pos(), fix.destination().state()), Move.REORGANIZE));
			}
		}
		if (zone.settings().tidyInside()) {
			for (SortingEngine.TidyMove tidy : SortingEngine.planTidy(level, Set.of(), area,
					io.github.lnasser11.waybettercoppergolem.config.WbcgConfig.get().golemCarrySize())) {
				if (moves.size() >= MAX_MOVES) {
					break;
				}
				moves.add(new Move(BuiltInRegistries.ITEM.getKey(tidy.stack().getItem()), tidy.stack().getCount(),
						tidy.source().pos(), Optional.of(tidy.home().pos()),
						ChestLabels.effectiveLabels(level, tidy.home().pos(), tidy.home().state()), Move.TIDY));
			}
		}
		if (zone.settings().tidyInside()) {
			for (TransportItemTarget untidy : SortingEngine.untidyChests(level, Set.of(), area)) {
				if (moves.size() >= MAX_MOVES) {
					break;
				}
				int stacks = 0;
				for (ItemStack stack : untidy.container()) {
					if (!stack.isEmpty()) {
						stacks++;
					}
				}
				ItemStack sample = io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.sampleFor(level, untidy.container(), List.of());
				moves.add(new Move(BuiltInRegistries.ITEM.getKey(sample.getItem()), stacks, untidy.pos(), Optional.of(untidy.pos()),
						ChestLabels.effectiveLabels(level, untidy.pos(), untidy.state()), Move.SORT));
			}
		}
		// Moves with nowhere to go first: those are the ones the player needs to act on; then deliveries, reorganize, tidy, sorting.
		moves.sort(java.util.Comparator.comparingInt(Move::order));
		return new ZonePayloads.Simulation(zone.anchor(), sources.size(), moves);
	}
}
