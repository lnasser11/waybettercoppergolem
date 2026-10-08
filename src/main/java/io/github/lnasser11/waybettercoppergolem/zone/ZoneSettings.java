package io.github.lnasser11.waybettercoppergolem.zone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Behavior settings of a sorting zone. The zone's <em>area</em> (which
 * chests belong) lives next to these in {@link Zone}; the settings are the
 * part the label tool copies from one zone to another.
 *
 * <p>{@code verticalReach} is how many blocks above or below itself a golem
 * can reach into a chest (1–{@value #MAX_VERTICAL_REACH}); the zone screen
 * has a stepper for it. Golems still need a clear view of the chest's face.
 */
public record ZoneSettings(int verticalReach, boolean reorganize, boolean tidyInside, boolean dryRun) {
	public static final int MIN_VERTICAL_REACH = 1;
	public static final int MAX_VERTICAL_REACH = 16;
	public static final int DEFAULT_VERTICAL_REACH = 6;
	public static final ZoneSettings DEFAULT = new ZoneSettings(DEFAULT_VERTICAL_REACH, true, false, false);

	public static final Codec<ZoneSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.optionalFieldOf("vertical_reach", DEFAULT.verticalReach()).forGetter(ZoneSettings::verticalReach),
			Codec.BOOL.optionalFieldOf("reorganize", DEFAULT.reorganize()).forGetter(ZoneSettings::reorganize),
			Codec.BOOL.optionalFieldOf("tidy_inside", DEFAULT.tidyInside()).forGetter(ZoneSettings::tidyInside),
			Codec.BOOL.optionalFieldOf("dry_run", DEFAULT.dryRun()).forGetter(ZoneSettings::dryRun)
	).apply(instance, ZoneSettings::new));

	public ZoneSettings {
		verticalReach = Math.clamp(verticalReach, MIN_VERTICAL_REACH, MAX_VERTICAL_REACH);
	}

	public ZoneSettings withVerticalReach(int value) {
		return new ZoneSettings(value, reorganize, tidyInside, dryRun);
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
