package io.github.lnasser11.waybettercoppergolem.sorting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Golems notice new items at once. Vanilla remembers a chest it has
 * visited for five minutes and idles for seven seconds after finding
 * nothing, so items dropped into a copper chest it already looked at can
 * sit there for minutes. When a copper chest's contents change, every
 * golem nearby forgets that chest and its idle cooldown, and the next
 * tick it searches again.
 */
public final class GolemWake {
	/** Vanilla's own expiry for visited positions, kept when a set is rewritten. */
	private static final long VISITED_EXPIRY_TICKS = 6000;
	/** A chest changing many times in one tick (a hopper, a player sorting) wakes golems once. */
	private static final int THROTTLE_TICKS = 10;
	private static final double HORIZONTAL_RANGE = 48;
	private static final double VERTICAL_RANGE = 16;

	private static final Map<GlobalPos, Long> LAST_WAKE = new ConcurrentHashMap<>();

	private GolemWake() {
	}

	/** Called when the contents of the copper chest at {@code pos} changed. */
	public static void copperChestChanged(ServerLevel level, BlockPos pos, BlockState state) {
		GlobalPos key = new GlobalPos(level.dimension(), pos.immutable());
		long now = level.getGameTime();
		Long last = LAST_WAKE.get(key);
		if (last != null && now - last < THROTTLE_TICKS) {
			return;
		}
		LAST_WAKE.put(key, now);
		if (LAST_WAKE.size() > 4096) {
			LAST_WAKE.entrySet().removeIf(entry -> now - entry.getValue() > THROTTLE_TICKS);
		}
		Set<GlobalPos> halves = new HashSet<>();
		halves.add(key);
		if (state.getValueOrElse(ChestBlock.TYPE, ChestType.SINGLE) != ChestType.SINGLE) {
			halves.add(new GlobalPos(level.dimension(), ChestBlock.getConnectedBlockPos(pos, state)));
		}
		AABB around = new AABB(pos).inflate(HORIZONTAL_RANGE, VERTICAL_RANGE, HORIZONTAL_RANGE);
		for (CopperGolem golem : level.getEntitiesOfClass(CopperGolem.class, around)) {
			wake(golem.getBrain(), halves);
		}
	}

	/** Forget these positions and any idle cooldown, so the transport behavior may start next tick. */
	static void wake(Brain<?> brain, Set<GlobalPos> positions) {
		forget(brain, MemoryModuleType.VISITED_BLOCK_POSITIONS, positions);
		forget(brain, MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS, positions);
		brain.eraseMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
	}

	private static void forget(Brain<?> brain, MemoryModuleType<Set<GlobalPos>> memory, Set<GlobalPos> positions) {
		Set<GlobalPos> remembered = brain.getMemory(memory).orElse(null);
		if (remembered == null || remembered.stream().noneMatch(positions::contains)) {
			return;
		}
		Set<GlobalPos> kept = new HashSet<>(remembered);
		kept.removeAll(positions);
		if (kept.isEmpty()) {
			brain.eraseMemory(memory);
		} else {
			brain.setMemoryWithExpiry(memory, kept, VISITED_EXPIRY_TICKS);
		}
	}
}
