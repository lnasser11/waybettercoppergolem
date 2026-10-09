package io.github.lnasser11.waybettercoppergolem.sorting;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which chest each golem is heading to, so several golems in one room
 * spread out instead of converging on the same chest and queueing or
 * jostling in the same corridor. A claim is set when a golem starts
 * travelling to a target and released when it stops targeting it; claims
 * also expire on their own, so a golem that vanishes mid-trip cannot
 * block a chest for good. Claims are a preference, not a lock: when every
 * candidate is claimed the nearest one is used, exactly as before.
 */
public final class GolemClaims {
	/** Longer than any trip; a stale claim is harmless after this. */
	private static final long EXPIRY_TICKS = 1200;

	private record Claim(UUID golem, long expiresAt) {
	}

	private static final Map<GlobalPos, Claim> CLAIMS = new ConcurrentHashMap<>();

	private GolemClaims() {
	}

	public static void claim(ServerLevel level, BlockPos pos, Entity golem) {
		release(golem);
		CLAIMS.put(new GlobalPos(level.dimension(), pos.immutable()), new Claim(golem.getUUID(), level.getGameTime() + EXPIRY_TICKS));
		if (CLAIMS.size() > 1024) {
			long now = level.getGameTime();
			CLAIMS.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
		}
	}

	public static void release(Entity golem) {
		CLAIMS.entrySet().removeIf(entry -> entry.getValue().golem().equals(golem.getUUID()));
	}

	/** Whether another golem is heading to the chest at {@code pos} (either half of a double chest counts). */
	public static boolean claimedByOther(ServerLevel level, BlockPos pos, Entity golem) {
		Claim claim = CLAIMS.get(new GlobalPos(level.dimension(), pos));
		return claim != null && !claim.golem().equals(golem.getUUID()) && claim.expiresAt() >= level.getGameTime();
	}

	/** Every chest position some other golem is heading to in this dimension. */
	public static Set<BlockPos> claimedByOthers(ServerLevel level, Entity golem) {
		Set<BlockPos> result = new HashSet<>();
		long now = level.getGameTime();
		for (Map.Entry<GlobalPos, Claim> entry : CLAIMS.entrySet()) {
			if (entry.getKey().dimension().equals(level.dimension()) && !entry.getValue().golem().equals(golem.getUUID())
					&& entry.getValue().expiresAt() >= now) {
				result.add(entry.getKey().pos());
			}
		}
		return result;
	}

	/** Forgets every claim (tests). */
	public static void clear() {
		CLAIMS.clear();
	}
}
