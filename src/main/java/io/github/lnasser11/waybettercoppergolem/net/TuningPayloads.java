package io.github.lnasser11.waybettercoppergolem.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** Payloads behind the category tuning screen. */
public final class TuningPayloads {
	private TuningPayloads() {
	}

	/** Client → server: show me this tag's tweaks. */
	public record OpenTuning(Identifier tagId) implements CustomPacketPayload {
		public static final Type<OpenTuning> TYPE = new Type<>(WayBetterCopperGolem.id("open_tuning"));
		public static final StreamCodec<ByteBuf, OpenTuning> STREAM_CODEC =
				Identifier.STREAM_CODEC.map(OpenTuning::new, OpenTuning::tagId);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server → client: this world's tweaks for one tag. The client already
	 * knows the base members from its synced tags; members = base − removed
	 * + added. {@code canEdit} says whether this player may change them.
	 */
	public record TuningContext(Identifier tagId, List<Identifier> added, List<Identifier> removed, boolean canEdit)
			implements CustomPacketPayload {
		public static final Type<TuningContext> TYPE = new Type<>(WayBetterCopperGolem.id("tuning_context"));
		private static final Codec<TuningContext> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("tag").forGetter(TuningContext::tagId),
				Identifier.CODEC.listOf().fieldOf("added").forGetter(TuningContext::added),
				Identifier.CODEC.listOf().fieldOf("removed").forGetter(TuningContext::removed),
				Codec.BOOL.fieldOf("can_edit").forGetter(TuningContext::canEdit)
		).apply(instance, TuningContext::new));
		public static final StreamCodec<ByteBuf, TuningContext> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client → server: include or exclude one item in a tag's category; an
	 * absent item means "drop every tweak of this tag". Operators only.
	 */
	public record TuneCategory(Identifier tagId, Optional<Identifier> itemId, boolean include)
			implements CustomPacketPayload {
		public static final Type<TuneCategory> TYPE = new Type<>(WayBetterCopperGolem.id("tune_category"));
		private static final Codec<TuneCategory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("tag").forGetter(TuneCategory::tagId),
				Identifier.CODEC.optionalFieldOf("item").forGetter(TuneCategory::itemId),
				Codec.BOOL.fieldOf("include").forGetter(TuneCategory::include)
		).apply(instance, TuneCategory::new));
		public static final StreamCodec<ByteBuf, TuneCategory> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		public static TuneCategory reset(Identifier tagId) {
			return new TuneCategory(tagId, Optional.empty(), false);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
