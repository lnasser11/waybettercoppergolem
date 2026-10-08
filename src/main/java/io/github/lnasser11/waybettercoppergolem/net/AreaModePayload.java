package io.github.lnasser11.waybettercoppergolem.net;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: where the player is in area mode. 0 = not in area
 * mode, 1 = waiting for the first corner, 2 = waiting for the second.
 * The HUD shows it and the picker stays closed while it is non-zero.
 */
public record AreaModePayload(int step) implements CustomPacketPayload {
	public static final Type<AreaModePayload> TYPE = new Type<>(WayBetterCopperGolem.id("area_mode"));
	public static final StreamCodec<ByteBuf, AreaModePayload> STREAM_CODEC =
			ByteBufCodecs.VAR_INT.map(AreaModePayload::new, AreaModePayload::step);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
