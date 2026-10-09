package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may change a zone. The player who creates a zone (opens its screen
 * first, or sets its area) owns it; the owner can trust other players by
 * name. Settings, area, learn and label edits inside the box need the
 * owner, a trusted player, or permission level 2 (operators), who can also
 * take a zone over. Everyone can still look at the screens and use the
 * HUD. Names are stored next to the ids so the screens can show them for
 * players who are offline.
 *
 * <p>A zone without an owner (from before ownership existed) is claimed by
 * the first player who opens its screen or pastes settings into it; until
 * then its settings are operators' to change and the labels inside it
 * follow the old rule (anyone), as they did before ownership existed.
 */
public record ZoneAccess(Optional<NameAndId> owner, List<NameAndId> trusted) {
	public static final ZoneAccess NONE = new ZoneAccess(Optional.empty(), List.of());
	/** Upper bound on the trusted list, so a packet cannot grow a zone without end. */
	public static final int MAX_TRUSTED = 32;

	public static final Codec<ZoneAccess> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			NameAndId.CODEC.optionalFieldOf("owner").forGetter(ZoneAccess::owner),
			NameAndId.CODEC.listOf().optionalFieldOf("trusted", List.of()).forGetter(ZoneAccess::trusted)
	).apply(instance, ZoneAccess::new));

	public ZoneAccess {
		trusted = List.copyOf(trusted);
	}

	public static boolean isOperator(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	/** Whether this player may change the zone: operator, owner or trusted. */
	public boolean canEdit(ServerPlayer player) {
		return canEdit(player.getUUID(), isOperator(player));
	}

	/** The permission rule itself: operators always, otherwise the owner and the trusted. */
	public boolean canEdit(UUID player, boolean operator) {
		if (operator) {
			return true;
		}
		if (owner.isPresent() && owner.get().id().equals(player)) {
			return true;
		}
		return trusted.stream().anyMatch(entry -> entry.id().equals(player));
	}

	public boolean isOwner(UUID player) {
		return owner.isPresent() && owner.get().id().equals(player);
	}

	public boolean isTrusted(UUID player) {
		return trusted.stream().anyMatch(entry -> entry.id().equals(player));
	}

	public ZoneAccess withOwner(NameAndId newOwner) {
		List<NameAndId> rest = trusted.stream().filter(entry -> !entry.id().equals(newOwner.id())).toList();
		return new ZoneAccess(Optional.of(newOwner), rest);
	}

	/** Adds a trusted player (no duplicates, never the owner, at most {@link #MAX_TRUSTED}). */
	public ZoneAccess withTrusted(NameAndId player) {
		if (isOwner(player.id()) || isTrusted(player.id()) || trusted.size() >= MAX_TRUSTED) {
			return this;
		}
		List<NameAndId> updated = new ArrayList<>(trusted);
		updated.add(player);
		return new ZoneAccess(owner, updated);
	}

	public ZoneAccess withoutTrusted(UUID player) {
		return new ZoneAccess(owner, trusted.stream().filter(entry -> !entry.id().equals(player)).toList());
	}

	// ---------------------------------------------------------------- rules on the world

	/** Whether this player may change the zone anchored at {@code ref}. */
	public static boolean canEdit(Zones.ZoneRef ref, ServerPlayer player) {
		return ref.zone().access().canEdit(player);
	}

	/**
	 * Whether this player may change the labels of the chest at {@code pos}:
	 * inside an owned zone it is the zone's rule (when the config asks for
	 * it); outside every zone, and inside a zone nobody has claimed yet,
	 * anyone may, as before ownership existed.
	 */
	public static boolean canEditLabelsAt(ServerLevel level, ServerPlayer player, BlockPos pos) {
		if (!WbcgConfig.get().labelsRequireZoneOwnership()) {
			return true;
		}
		return Zones.zoneAt(level, pos)
				.filter(ref -> ref.zone().access().owner().isPresent())
				.map(ref -> canEdit(ref, player)).orElse(true);
	}

	/** Whether this player may create a zone (or claim one without an owner) right now. */
	public static boolean mayCreate(ServerLevel level, ServerPlayer player) {
		if (isOperator(player)) {
			return true;
		}
		if (WbcgConfig.get().zonesRequireOpToCreate()) {
			return false;
		}
		return countOwned(level.getServer(), player.getUUID()) < WbcgConfig.get().maxZonesPerPlayer();
	}

	/** Zones owned by the player across every dimension. */
	public static int countOwned(MinecraftServer server, UUID player) {
		int count = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Zone zone : Zones.all(level).values()) {
				if (zone.access().isOwner(player)) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Makes the player the owner of a zone that has none, when they are
	 * allowed to create zones. Returns the zone as it is afterwards.
	 */
	public static Zones.ZoneRef claimIfUnowned(ServerLevel level, Zones.ZoneRef ref, ServerPlayer player) {
		if (ref.zone().access().owner().isPresent() || !mayCreate(level, player)) {
			return ref;
		}
		Zone claimed = ref.zone().withAccess(ref.zone().access().withOwner(new NameAndId(player.getGameProfile())));
		Zones.put(level, ref.anchor(), claimed);
		WayBetterCopperGolem.LOGGER.info("[zone] {} now owns the zone at {} ({})",
				player.getGameProfile().name(), ref.anchor(), level.dimension().identifier());
		return new Zones.ZoneRef(ref.anchor(), claimed);
	}

	/** The player's name for log lines. */
	public static String nameOf(ServerPlayer player) {
		return player.getGameProfile().name();
	}
}
