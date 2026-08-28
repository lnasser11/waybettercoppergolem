package io.github.lnasser11.waybettercoppergolem.label;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * One label on a chest, as declared by an item frame mounted on it.
 *
 * <p>{@code itemId} empty means the frame is empty: the chest is a catch-all.
 * {@code expansionLevel} selects how broadly the framed item is interpreted:
 * 0 = exact item only, 1..n = the n-th conventional item tag the item belongs
 * to, ordered narrow to broad (see {@link LabelResolver#orderedTags}).
 *
 * <p>Persisted on the chest block entity via the Fabric attachment API, so a
 * destroyed frame does not erase the chest's category.
 */
public record ChestLabel(Optional<Identifier> itemId, int expansionLevel) {
	public static final Codec<ChestLabel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.optionalFieldOf("item").forGetter(ChestLabel::itemId),
			Codec.INT.optionalFieldOf("level", 0).forGetter(ChestLabel::expansionLevel)
	).apply(instance, ChestLabel::new));

	public static ChestLabel catchAll() {
		return new ChestLabel(Optional.empty(), 0);
	}

	public static ChestLabel of(Identifier itemId, int expansionLevel) {
		return new ChestLabel(Optional.of(itemId), expansionLevel);
	}

	public boolean isCatchAll() {
		return itemId.isEmpty();
	}

	/** A framed cobweb marks the chest fully off-limits to golems. */
	public boolean isOffLimits() {
		return itemId.map(id -> id.equals(Identifier.withDefaultNamespace("cobweb"))).orElse(false);
	}
}
