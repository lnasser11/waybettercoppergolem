package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Sorting-zone settings, attached to a copper chest block entity. They apply
 * to any golem operating out of that copper chest (the golem remembers the
 * last copper chest it picked up from).
 */
public record ZoneSettings(int searchRadius, int verticalReach, boolean reorganize, boolean tidyInside, boolean dryRun, int carryAmount) {
	public static final int MAX_SEARCH_RADIUS = 48;
	public static final int MAX_VERTICAL_REACH = 6;
	public static final int MAX_CARRY_AMOUNT = 64;
	public static final ZoneSettings DEFAULT = new ZoneSettings(32, 4, true, false, false, 16);

	public static final Codec<ZoneSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("search_radius", DEFAULT.searchRadius()).forGetter(ZoneSettings::searchRadius),
			Codec.INT.optionalFieldOf("vertical_reach", DEFAULT.verticalReach()).forGetter(ZoneSettings::verticalReach),
			Codec.BOOL.optionalFieldOf("reorganize", DEFAULT.reorganize()).forGetter(ZoneSettings::reorganize),
			Codec.BOOL.optionalFieldOf("tidy_inside", DEFAULT.tidyInside()).forGetter(ZoneSettings::tidyInside),
			Codec.BOOL.optionalFieldOf("dry_run", DEFAULT.dryRun()).forGetter(ZoneSettings::dryRun),
			Codec.INT.optionalFieldOf("carry_amount", DEFAULT.carryAmount()).forGetter(ZoneSettings::carryAmount)
	).apply(instance, ZoneSettings::new));

	public ZoneSettings {
		searchRadius = Math.clamp(searchRadius, 4, MAX_SEARCH_RADIUS);
		verticalReach = Math.clamp(verticalReach, 1, MAX_VERTICAL_REACH);
		carryAmount = Math.clamp(carryAmount, 1, MAX_CARRY_AMOUNT);
	}
}
