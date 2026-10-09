package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-dimension zone registry: anchor copper chest → {@link Zone}.
 * Stored in a persistent attachment on the level and synced to clients so
 * the HUD can name the zone a chest belongs to.
 *
 * <p>Lookup is by containment: a position belongs to the zone whose box
 * contains it, nearest anchor first when boxes overlap. Zones whose anchor
 * is no longer a copper chest are dropped the next time they are seen.
 */
public final class Zones {
	/** A zone together with the anchor it is stored under. */
	public record ZoneRef(BlockPos anchor, Zone zone) {
		public ZoneSettings settings() {
			return zone.settings();
		}

		public BoundingBox area() {
			return zone.area();
		}
	}

	private record Entry(BlockPos anchor, Zone zone) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("anchor").forGetter(Entry::anchor),
				Zone.CODEC.fieldOf("zone").forGetter(Entry::zone)
		).apply(instance, Entry::new));
	}

	/** Anchor → zone, serialized as a list because NBT map keys must be strings. */
	public static final Codec<Map<BlockPos, Zone>> CODEC = Entry.CODEC.listOf().xmap(
			entries -> {
				Map<BlockPos, Zone> map = new HashMap<>();
				for (Entry entry : entries) {
					map.put(entry.anchor(), entry.zone());
				}
				return Map.copyOf(map);
			},
			map -> map.entrySet().stream().map(e -> new Entry(e.getKey(), e.getValue())).toList());

	public static final StreamCodec<ByteBuf, Map<BlockPos, Zone>> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

	private Zones() {
	}

	/** All zones of this dimension (immutable snapshot). */
	public static Map<BlockPos, Zone> all(Level level) {
		Map<BlockPos, Zone> zones = level.getAttached(WayBetterCopperGolem.ZONES);
		return zones == null ? Map.of() : zones;
	}

	/**
	 * The zone containing {@code pos}, nearest anchor first. Works on either
	 * logical side (the registry is synced), but only the server prunes
	 * zones whose anchor chest is gone.
	 */
	public static Optional<ZoneRef> zoneAt(Level level, @Nullable BlockPos pos) {
		if (pos == null) {
			return Optional.empty();
		}
		ZoneRef best = null;
		double bestDist = Double.MAX_VALUE;
		List<BlockPos> stale = null;
		for (Map.Entry<BlockPos, Zone> entry : all(level).entrySet()) {
			if (!entry.getValue().contains(pos)) {
				continue;
			}
			if (level instanceof ServerLevel serverLevel && anchorGone(serverLevel, entry.getKey())) {
				(stale == null ? (stale = new ArrayList<>()) : stale).add(entry.getKey());
				continue;
			}
			double dist = entry.getKey().distSqr(pos);
			if (dist < bestDist) {
				best = new ZoneRef(entry.getKey(), entry.getValue());
				bestDist = dist;
			}
		}
		if (stale != null && level instanceof ServerLevel serverLevel) {
			stale.forEach(anchor -> remove(serverLevel, anchor));
		}
		if (best == null && level instanceof ServerLevel serverLevel) {
			return migrateLegacy(serverLevel, pos);
		}
		return Optional.ofNullable(best);
	}

	/**
	 * The zone a copper chest belongs to, creating a default one anchored at
	 * the chest when no zone contains it. Used when a player opens the zone
	 * screen or pastes settings, so there is always something to edit.
	 */
	public static ZoneRef zoneForCopperChest(ServerLevel level, BlockPos copperChest) {
		return zoneAt(level, copperChest).orElseGet(() -> {
			Zone zone = new Zone(Zone.defaultArea(copperChest), defaults(level));
			put(level, copperChest, zone);
			return new ZoneRef(copperChest, zone);
		});
	}

	/** The settings new zones start from in this world (stored on the overworld). */
	public static ZoneSettings defaults(ServerLevel level) {
		ZoneSettings stored = level.getServer().overworld().getAttached(WayBetterCopperGolem.ZONE_DEFAULTS);
		return stored == null ? ZoneSettings.DEFAULT : stored;
	}

	public static void setDefaults(ServerLevel level, ZoneSettings settings) {
		ServerLevel overworld = level.getServer().overworld();
		if (settings.equals(ZoneSettings.DEFAULT)) {
			overworld.removeAttached(WayBetterCopperGolem.ZONE_DEFAULTS);
		} else {
			overworld.setAttached(WayBetterCopperGolem.ZONE_DEFAULTS, settings);
		}
	}

	/** Gives every zone of this dimension the same settings (areas untouched). Returns how many changed. */
	public static int applyToAll(ServerLevel level, ZoneSettings settings) {
		Map<BlockPos, Zone> updated = new HashMap<>();
		int changed = 0;
		for (Map.Entry<BlockPos, Zone> entry : all(level).entrySet()) {
			if (!entry.getValue().settings().equals(settings)) {
				changed++;
			}
			updated.put(entry.getKey(), entry.getValue().withSettings(settings));
		}
		if (changed > 0) {
			level.setAttached(WayBetterCopperGolem.ZONES, Map.copyOf(updated));
		}
		return changed;
	}

	/** Settings in force at {@code pos}, or defaults outside every zone. */
	public static ZoneSettings settingsAt(ServerLevel level, @Nullable BlockPos pos) {
		return zoneAt(level, pos).map(ZoneRef::settings).orElse(ZoneSettings.DEFAULT);
	}

	public static void put(ServerLevel level, BlockPos anchor, Zone zone) {
		Map<BlockPos, Zone> updated = new HashMap<>(all(level));
		updated.put(anchor.immutable(), zone);
		level.setAttached(WayBetterCopperGolem.ZONES, Map.copyOf(updated));
	}

	public static void remove(ServerLevel level, BlockPos anchor) {
		Map<BlockPos, Zone> updated = new HashMap<>(all(level));
		if (updated.remove(anchor) != null) {
			if (updated.isEmpty()) {
				level.removeAttached(WayBetterCopperGolem.ZONES);
			} else {
				level.setAttached(WayBetterCopperGolem.ZONES, Map.copyOf(updated));
			}
		}
	}

	/** The world-space box of a zone, for chest searches. */
	public static AABB toAABB(BoundingBox area) {
		return AABB.of(area);
	}

	/** "reorganize on · tidy off · dry run off · reach 6 · stay inside on". */
	public static Component describe(ZoneSettings settings) {
		return Component.translatable("waybettercoppergolem.zone.summary",
				onOff(settings.reorganize()), onOff(settings.tidyInside()), onOff(settings.dryRun()), settings.verticalReach(),
				onOff(settings.stayInside()));
	}

	/** "20 × 6 × 14". */
	public static Component describeArea(BoundingBox area) {
		return Component.translatable("waybettercoppergolem.zone.area_size",
				area.getXSpan(), area.getYSpan(), area.getZSpan());
	}

	private static Component onOff(boolean value) {
		return value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
	}

	/**
	 * Draws the box's twelve edges with particles, visible only to the
	 * player. Long edges are sampled more sparsely so a maximal zone stays
	 * a few hundred particles.
	 */
	public static void showOutline(ServerPlayer player, ServerLevel level, BoundingBox area) {
		for (net.minecraft.world.phys.Vec3 point : outlinePoints(area)) {
			level.sendParticles(player, ParticleTypes.END_ROD, true, true,
					point.x, point.y, point.z, 1, 0, 0, 0, 0.0);
		}
	}

	/** Sample points along the box's twelve edges (shared by server and client drawing). */
	public static List<net.minecraft.world.phys.Vec3> outlinePoints(BoundingBox area) {
		List<net.minecraft.world.phys.Vec3> points = new ArrayList<>();
		double x0 = area.minX();
		double y0 = area.minY();
		double z0 = area.minZ();
		double x1 = area.maxX() + 1;
		double y1 = area.maxY() + 1;
		double z1 = area.maxZ() + 1;
		for (double y : new double[] {y0, y1}) {
			edge(points, x0, y, z0, x1, y, z0);
			edge(points, x0, y, z1, x1, y, z1);
			edge(points, x0, y, z0, x0, y, z1);
			edge(points, x1, y, z0, x1, y, z1);
		}
		edge(points, x0, y0, z0, x0, y1, z0);
		edge(points, x1, y0, z0, x1, y1, z0);
		edge(points, x0, y0, z1, x0, y1, z1);
		edge(points, x1, y0, z1, x1, y1, z1);
		return points;
	}

	private static void edge(List<net.minecraft.world.phys.Vec3> points,
			double ax, double ay, double az, double bx, double by, double bz) {
		double length = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
		int steps = (int) Math.max(1, Math.min(length, 32));
		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			points.add(new net.minecraft.world.phys.Vec3(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t));
		}
	}

	private static boolean anchorGone(ServerLevel level, BlockPos anchor) {
		return level.isLoaded(anchor) && !level.getBlockState(anchor).is(BlockTags.COPPER_CHESTS);
	}

	/**
	 * Worlds from before zones had areas kept settings on each copper
	 * chest. The first time such a chest is looked up and no zone contains
	 * it, it becomes the anchor of a default-sized zone with those settings.
	 */
	private static Optional<ZoneRef> migrateLegacy(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos) || !level.getBlockState(pos).is(BlockTags.COPPER_CHESTS)) {
			return Optional.empty();
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null || !blockEntity.hasAttached(WayBetterCopperGolem.ZONE_SETTINGS)) {
			return Optional.empty();
		}
		ZoneSettings legacy = blockEntity.removeAttached(WayBetterCopperGolem.ZONE_SETTINGS);
		Zone zone = new Zone(Zone.defaultArea(pos), legacy == null ? ZoneSettings.DEFAULT : legacy);
		put(level, pos, zone);
		return Optional.of(new ZoneRef(pos, zone));
	}
}
