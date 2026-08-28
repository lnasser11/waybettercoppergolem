package io.github.lnasser11.waybettercoppergolem.label;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * One label on a chest, as declared by an item frame mounted on it.
 *
 * <p>Meanings:
 * <ul>
 *   <li>{@code itemId} empty — the frame is empty: catch-all chest;</li>
 *   <li>{@code itemId} = cobweb — the chest is off-limits to golems;</li>
 *   <li>{@code itemId} present, {@code tagId} empty — exact-item label;</li>
 *   <li>{@code tagId} present — the chest holds that item tag's category
 *       (a {@code c:} tag, a curated {@code minecraft:} tag, or a
 *       {@code wbcg:} preset category), possibly tuned by the world's
 *       category overrides.</li>
 * </ul>
 *
 * <p>The resolved tag id is stored directly (never a position in some
 * ordered list), so installing or removing mods can't silently change what
 * an existing frame means. Persisted on the chest block entity via the
 * Fabric attachment API, so a destroyed frame does not erase the category.
 */
public record ChestLabel(Optional<Identifier> itemId, Optional<Identifier> tagId) {
	private static final Identifier COBWEB = Identifier.withDefaultNamespace("cobweb");

	public static final Codec<ChestLabel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.optionalFieldOf("item").forGetter(ChestLabel::itemId),
			Identifier.CODEC.optionalFieldOf("tag").forGetter(ChestLabel::tagId)
	).apply(instance, ChestLabel::new));

	public static ChestLabel catchAll() {
		return new ChestLabel(Optional.empty(), Optional.empty());
	}

	public static ChestLabel exact(Identifier itemId) {
		return new ChestLabel(Optional.of(itemId), Optional.empty());
	}

	public static ChestLabel tag(Identifier itemId, Identifier tagId) {
		return new ChestLabel(Optional.of(itemId), Optional.of(tagId));
	}

	public boolean isCatchAll() {
		return itemId.isEmpty();
	}

	/** A framed cobweb marks the chest fully off-limits to golems. */
	public boolean isOffLimits() {
		return itemId.map(COBWEB::equals).orElse(false);
	}

	public boolean isTagLabel() {
		return tagId.isPresent() && !isOffLimits();
	}
}
