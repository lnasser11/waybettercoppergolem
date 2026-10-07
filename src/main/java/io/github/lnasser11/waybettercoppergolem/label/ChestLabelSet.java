package io.github.lnasser11.waybettercoppergolem.label;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * The labels stored on one chest block entity, and how they got there.
 *
 * <p>{@code explicit} labels were set on purpose (pasted with the label
 * tool, applied by the learn pass, picked in the picker, or set by a
 * command) and are never touched by item frames. Non-explicit labels are
 * <em>derived</em> from the frames hanging on the chest and are
 * recomputed whenever a frame or the chest contents change.
 *
 * <p>Persisted on the chest via the Fabric attachment API and synced to
 * clients so the HUD can show labels without a round trip. Worlds from
 * before the chest-owned model stored a bare list; that decodes as a
 * derived set.
 */
public record ChestLabelSet(List<ChestLabel> labels, boolean explicit) {
	public static final ChestLabelSet NONE = new ChestLabelSet(List.of(), false);

	private static final Codec<ChestLabelSet> RECORD_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChestLabel.CODEC.listOf().fieldOf("labels").forGetter(ChestLabelSet::labels),
			Codec.BOOL.optionalFieldOf("explicit", false).forGetter(ChestLabelSet::explicit)
	).apply(instance, ChestLabelSet::new));

	public static final Codec<ChestLabelSet> CODEC = Codec.withAlternative(RECORD_CODEC,
			ChestLabel.CODEC.listOf(), labels -> new ChestLabelSet(labels, false));

	public static final StreamCodec<ByteBuf, ChestLabelSet> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

	public ChestLabelSet {
		labels = List.copyOf(labels);
	}

	public static ChestLabelSet explicit(List<ChestLabel> labels) {
		return new ChestLabelSet(labels, true);
	}

	public static ChestLabelSet derived(List<ChestLabel> labels) {
		return new ChestLabelSet(labels, false);
	}

	public boolean isEmpty() {
		return labels.isEmpty();
	}

	public boolean isOffLimits() {
		return labels.stream().anyMatch(ChestLabel::isOffLimits);
	}
}
