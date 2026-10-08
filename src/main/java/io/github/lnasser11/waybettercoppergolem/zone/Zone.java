package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A sorting zone: a box in the world plus the behavior settings golems
 * obey inside it. Anchored at one copper chest (the key in
 * {@link Zones}); every copper chest inside the box shares the zone, and
 * golems working for the zone only take from copper chests inside the box
 * and only deposit or reorganize inside it.
 */
public record Zone(BoundingBox area, ZoneSettings settings) {
	/** Default box around a new anchor, matching the vanilla search volume. */
	public static final int DEFAULT_HALF_HORIZONTAL = 32;
	public static final int DEFAULT_HALF_VERTICAL = 8;
	/** Longest allowed edge, so one zone can't make golems scan half a server. */
	public static final int MAX_SPAN = 128;

	public static final Codec<Zone> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BoundingBox.CODEC.fieldOf("area").forGetter(Zone::area),
			ZoneSettings.CODEC.optionalFieldOf("settings", ZoneSettings.DEFAULT).forGetter(Zone::settings)
	).apply(instance, Zone::new));

	public static Zone defaultAround(BlockPos anchor) {
		return new Zone(defaultArea(anchor), ZoneSettings.DEFAULT);
	}

	public static BoundingBox defaultArea(BlockPos anchor) {
		return new BoundingBox(anchor).inflatedBy(DEFAULT_HALF_HORIZONTAL, DEFAULT_HALF_VERTICAL, DEFAULT_HALF_HORIZONTAL);
	}

	/**
	 * The box spanned by two corners, inclusive, cut down to {@link #MAX_SPAN}
	 * per axis (keeping the side nearest the first corner).
	 */
	public static BoundingBox areaFromCorners(BlockPos first, BlockPos second) {
		BlockPos limited = new BlockPos(
				clampTowards(first.getX(), second.getX()),
				clampTowards(first.getY(), second.getY()),
				clampTowards(first.getZ(), second.getZ()));
		return BoundingBox.fromCorners(first, limited);
	}

	private static int clampTowards(int from, int to) {
		int delta = to - from;
		int limited = Math.clamp(delta, -(MAX_SPAN - 1), MAX_SPAN - 1);
		return from + limited;
	}

	public Zone withSettings(ZoneSettings settings) {
		return new Zone(area, settings);
	}

	public Zone withArea(BoundingBox area) {
		return new Zone(area, settings);
	}

	public boolean contains(BlockPos pos) {
		return area.isInside(pos);
	}
}
