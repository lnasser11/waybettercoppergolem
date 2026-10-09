package io.github.lnasser11.waybettercoppergolem.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** The three payloads behind the per-chest editor. */
public final class EditorPayloads {
	private EditorPayloads() {
	}

	/** Client → server: "open the editor for the chest at pos" (the golem button in a chest screen). */
	public record OpenEditor(BlockPos pos) implements CustomPacketPayload {
		public static final Type<OpenEditor> TYPE = new Type<>(WayBetterCopperGolem.id("open_editor"));
		public static final StreamCodec<ByteBuf, OpenEditor> STREAM_CODEC =
				BlockPos.STREAM_CODEC.map(OpenEditor::new, OpenEditor::pos);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One label proposed from the chest's contents and how much of them it covers. */
	public record Suggestion(ChestLabel label, int coveredStacks, int totalStacks) {
		public static final Codec<Suggestion> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ChestLabel.CODEC.fieldOf("label").forGetter(Suggestion::label),
				Codec.INT.fieldOf("covered").forGetter(Suggestion::coveredStacks),
				Codec.INT.fieldOf("total").forGetter(Suggestion::totalStacks)
		).apply(instance, Suggestion::new));
	}

	/**
	 * Server → client: everything the editor shows for one chest;
	 * {@code canEdit} is false for a read-only look, {@code golemFrames}
	 * is the chest's frame switch.
	 */
	public record EditorContext(BlockPos pos, ChestLabelSet current, List<Suggestion> suggestions, boolean canEdit,
			boolean golemFrames) implements CustomPacketPayload {
		public static final Type<EditorContext> TYPE = new Type<>(WayBetterCopperGolem.id("editor_context"));
		private static final Codec<EditorContext> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(EditorContext::pos),
				ChestLabelSet.CODEC.fieldOf("current").forGetter(EditorContext::current),
				Suggestion.CODEC.listOf().fieldOf("suggestions").forGetter(EditorContext::suggestions),
				Codec.BOOL.optionalFieldOf("can_edit", true).forGetter(EditorContext::canEdit),
				Codec.BOOL.optionalFieldOf("golem_frames", true).forGetter(EditorContext::golemFrames)
		).apply(instance, EditorContext::new));
		public static final StreamCodec<ByteBuf, EditorContext> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: allow or forbid golems to hang an item frame on this chest. */
	public record SetChestFrames(BlockPos pos, boolean allowed) implements CustomPacketPayload {
		public static final Type<SetChestFrames> TYPE = new Type<>(WayBetterCopperGolem.id("set_chest_frames"));
		private static final Codec<SetChestFrames> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(SetChestFrames::pos),
				Codec.BOOL.fieldOf("allowed").forGetter(SetChestFrames::allowed)
		).apply(instance, SetChestFrames::new));
		public static final StreamCodec<ByteBuf, SetChestFrames> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client → server: set the chest's labels. An empty list clears them
	 * (back to frames or vanilla); otherwise they become explicit. The same
	 * labels also land on the clipboard, so the next chests can be pasted.
	 */
	public record SetChestLabels(BlockPos pos, List<ChestLabel> labels) implements CustomPacketPayload {
		public static final Type<SetChestLabels> TYPE = new Type<>(WayBetterCopperGolem.id("set_chest_labels"));
		private static final Codec<SetChestLabels> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(SetChestLabels::pos),
				ChestLabel.CODEC.listOf().fieldOf("labels").forGetter(SetChestLabels::labels)
		).apply(instance, SetChestLabels::new));
		public static final StreamCodec<ByteBuf, SetChestLabels> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
