package io.github.lnasser11.waybettercoppergolem.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.Optional;

/**
 * Client → server: what the picker chose for the label slot of the
 * player's clipboard. Empty = empty the slot; an empty list = the
 * "remove labels" marker; otherwise the labels to paste.
 */
public record SetClipboardPayload(Optional<List<ChestLabel>> labels) implements CustomPacketPayload {
	public static final Type<SetClipboardPayload> TYPE = new Type<>(WayBetterCopperGolem.id("set_clipboard"));

	private static final Codec<SetClipboardPayload> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChestLabel.CODEC.listOf().optionalFieldOf("labels").forGetter(SetClipboardPayload::labels)
	).apply(instance, SetClipboardPayload::new));

	public static final StreamCodec<ByteBuf, SetClipboardPayload> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

	public static SetClipboardPayload of(ChestLabel label) {
		return new SetClipboardPayload(Optional.of(List.of(label)));
	}

	public static SetClipboardPayload removeLabelsMarker() {
		return new SetClipboardPayload(Optional.of(List.of()));
	}

	public static SetClipboardPayload empty() {
		return new SetClipboardPayload(Optional.empty());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
