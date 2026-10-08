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
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Optional;

/** Payloads behind the zone overview and the simulation. */
public final class ZonePayloads {
	private ZonePayloads() {
	}

	/** Client → server: build and send the overview of the zone anchored here. */
	public record OpenOverview(BlockPos anchor) implements CustomPacketPayload {
		public static final Type<OpenOverview> TYPE = new Type<>(WayBetterCopperGolem.id("open_overview"));
		public static final StreamCodec<ByteBuf, OpenOverview> STREAM_CODEC =
				BlockPos.STREAM_CODEC.map(OpenOverview::new, OpenOverview::anchor);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: simulate what golems would do with the zone's copper chests right now. */
	public record RunSimulation(BlockPos anchor) implements CustomPacketPayload {
		public static final Type<RunSimulation> TYPE = new Type<>(WayBetterCopperGolem.id("run_simulation"));
		public static final StreamCodec<ByteBuf, RunSimulation> STREAM_CODEC =
				BlockPos.STREAM_CODEC.map(RunSimulation::new, RunSimulation::anchor);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One chest of the zone. Problems are lang-key suffixes: unlabeled, misplaced, duplicate, empty. */
	public record Entry(BlockPos pos, ChestLabelSet labels, List<String> problems, int stacks) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
				ChestLabelSet.CODEC.fieldOf("labels").forGetter(Entry::labels),
				Codec.STRING.listOf().fieldOf("problems").forGetter(Entry::problems),
				Codec.INT.fieldOf("stacks").forGetter(Entry::stacks)
		).apply(instance, Entry::new));

		public boolean hasProblem() {
			return problems.stream().anyMatch(p -> !p.equals("empty"));
		}
	}

	/** Server → client: the zone's chests, problems first. */
	public record Overview(BlockPos anchor, BoundingBox area, int copperChests, List<Entry> entries)
			implements CustomPacketPayload {
		public static final Type<Overview> TYPE = new Type<>(WayBetterCopperGolem.id("zone_overview"));
		private static final Codec<Overview> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("anchor").forGetter(Overview::anchor),
				BoundingBox.CODEC.fieldOf("area").forGetter(Overview::area),
				Codec.INT.fieldOf("copper_chests").forGetter(Overview::copperChests),
				Entry.CODEC.listOf().fieldOf("entries").forGetter(Overview::entries)
		).apply(instance, Overview::new));
		public static final StreamCodec<ByteBuf, Overview> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One predicted move: {@code count} of {@code item} from a copper chest to a chest, or nowhere. */
	public record Move(Identifier item, int count, BlockPos from, Optional<BlockPos> to, List<ChestLabel> toLabels) {
		public static final Codec<Move> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Identifier.CODEC.fieldOf("item").forGetter(Move::item),
				Codec.INT.fieldOf("count").forGetter(Move::count),
				BlockPos.CODEC.fieldOf("from").forGetter(Move::from),
				BlockPos.CODEC.optionalFieldOf("to").forGetter(Move::to),
				ChestLabel.CODEC.listOf().optionalFieldOf("to_labels", List.of()).forGetter(Move::toLabels)
		).apply(instance, Move::new));
	}

	/** Server → client: the simulation result. */
	public record Simulation(BlockPos anchor, int sourceChests, List<Move> moves) implements CustomPacketPayload {
		public static final Type<Simulation> TYPE = new Type<>(WayBetterCopperGolem.id("zone_simulation"));
		private static final Codec<Simulation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BlockPos.CODEC.fieldOf("anchor").forGetter(Simulation::anchor),
				Codec.INT.fieldOf("source_chests").forGetter(Simulation::sourceChests),
				Move.CODEC.listOf().fieldOf("moves").forGetter(Simulation::moves)
		).apply(instance, Simulation::new));
		public static final StreamCodec<ByteBuf, Simulation> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
