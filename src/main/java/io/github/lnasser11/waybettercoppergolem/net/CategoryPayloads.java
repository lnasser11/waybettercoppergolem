package io.github.lnasser11.waybettercoppergolem.net;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Packets for the category editor screen. The menu-button trick used by the
 * zone settings can only carry small ints; the editor needs to name items
 * and tags, so it gets real payloads.
 */
public final class CategoryPayloads {
	private CategoryPayloads() {
	}

	/** C2S: toggle one item's membership in a category. */
	public record Toggle(Identifier tagId, Identifier itemId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Toggle> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_toggle"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Toggle> CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, Toggle::tagId,
				Identifier.STREAM_CODEC, Toggle::itemId,
				Toggle::new);

		@Override
		public CustomPacketPayload.Type<Toggle> type() {
			return TYPE;
		}
	}

	/** C2S: ask for a category's current overrides. */
	public record Query(Identifier tagId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Query> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_query"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Query> CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, Query::tagId,
				Query::new);

		@Override
		public CustomPacketPayload.Type<Query> type() {
			return TYPE;
		}
	}

	/** S2C: a category's current overrides. */
	public record Sync(Identifier tagId, List<Identifier> added, List<Identifier> removed)
			implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Sync> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_sync"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Sync> CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, Sync::tagId,
				Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::added,
				Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::removed,
				Sync::new);

		@Override
		public CustomPacketPayload.Type<Sync> type() {
			return TYPE;
		}
	}
}
