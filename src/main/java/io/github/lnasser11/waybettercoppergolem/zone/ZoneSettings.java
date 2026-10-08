package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Behavior settings of a sorting zone. The zone's <em>area</em> (which
 * chests belong) lives next to these in {@link Zone}; the settings are the
 * part the label tool copies from one zone to another.
 *
 * <p>{@code verticalReach} is how high a golem can reach into a chest wall
 * (1–6). It defaults to the maximum and is no longer exposed in the zone
 * screen; the field stays so older worlds keep their value.
 */
public record ZoneSettings(int verticalReach, boolean reorganize, boolean tidyInside, boolean dryRun) {
	public static final int MAX_VERTICAL_REACH = 6;
	public static final ZoneSettings DEFAULT = new ZoneSettings(MAX_VERTICAL_REACH, true, false, false);

	public static final Codec<ZoneSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("vertical_reach", DEFAULT.verticalReach()).forGetter(ZoneSettings::verticalReach),
			Codec.BOOL.optionalFieldOf("reorganize", DEFAULT.reorganize()).forGetter(ZoneSettings::reorganize),
			Codec.BOOL.optionalFieldOf("tidy_inside", DEFAULT.tidyInside()).forGetter(ZoneSettings::tidyInside),
			Codec.BOOL.optionalFieldOf("dry_run", DEFAULT.dryRun()).forGetter(ZoneSettings::dryRun)
	).apply(instance, ZoneSettings::new));

	public ZoneSettings {
		verticalReach = Math.clamp(verticalReach, 1, MAX_VERTICAL_REACH);
	}

	public ZoneSettings withReorganize(boolean value) {
		return new ZoneSettings(verticalReach, value, tidyInside, dryRun);
	}

	public ZoneSettings withTidyInside(boolean value) {
		return new ZoneSettings(verticalReach, reorganize, value, dryRun);
	}

	public ZoneSettings withDryRun(boolean value) {
		return new ZoneSettings(verticalReach, reorganize, tidyInside, value);
	}
}
