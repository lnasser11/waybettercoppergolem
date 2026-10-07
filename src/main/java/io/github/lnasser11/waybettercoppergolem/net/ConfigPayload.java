package io.github.lnasser11.waybettercoppergolem.net;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import io.netty.buffer.ByteBuf;

/**
 * Server → client, sent once on join: the server-side settings the client
 * needs to mirror. Today that is only which item acts as the label tool,
 * so the HUD and the picker react to the right item.
 */
public record ConfigPayload(Identifier toolItem) implements CustomPacketPayload {
	public static final Type<ConfigPayload> TYPE = new Type<>(WayBetterCopperGolem.id("config"));
	public static final StreamCodec<ByteBuf, ConfigPayload> STREAM_CODEC =
			Identifier.STREAM_CODEC.map(ConfigPayload::new, ConfigPayload::toolItem);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
