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

	/** C2S: ask for the list of categories that exist right now. */
	public record ListRequest() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ListRequest> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_list_request"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, ListRequest> CODEC =
				StreamCodec.unit(new ListRequest());

		@Override
		public CustomPacketPayload.Type<ListRequest> type() {
			return TYPE;
		}
	}

	/** C2S: create a player-made category with this name. */
	public record Create(String name) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Create> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_create"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Create> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), Create::name,
				Create::new);

		@Override
		public CustomPacketPayload.Type<Create> type() {
			return TYPE;
		}
	}

	/** C2S: delete a player-made category. */
	public record Delete(Identifier tagId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Delete> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_delete"));
		public static final StreamCodec<io.netty.buffer.ByteBuf, Delete> CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, Delete::tagId,
				Delete::new);

		@Override
		public CustomPacketPayload.Type<Delete> type() {
			return TYPE;
		}
	}

	/** One selectable category, as the editor screen shows it. */
	public record Entry(Identifier id, net.minecraft.network.chat.Component name, boolean custom) {
		public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Entry> CODEC =
				StreamCodec.composite(
						Identifier.STREAM_CODEC, Entry::id,
						net.minecraft.network.chat.ComponentSerialization.STREAM_CODEC, Entry::name,
						ByteBufCodecs.BOOL, Entry::custom,
						Entry::new);
	}

	/** S2C: every category the editor can choose from. */
	public record ListSync(List<Entry> entries) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ListSync> TYPE =
				new CustomPacketPayload.Type<>(WayBetterCopperGolem.id("category_list_sync"));
		public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, ListSync> CODEC =
				StreamCodec.composite(
						Entry.CODEC.apply(ByteBufCodecs.list()), ListSync::entries,
						ListSync::new);

		@Override
		public CustomPacketPayload.Type<ListSync> type() {
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
