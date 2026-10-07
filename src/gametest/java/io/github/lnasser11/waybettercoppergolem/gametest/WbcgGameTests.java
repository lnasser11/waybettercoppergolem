package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side game tests for the label model, the learn pass and the tool.
 * Run with {@code ./gradlew runGameTest}. Each test gets an empty 8x8x8
 * structure; positions below are relative to it.
 */
public final class WbcgGameTests {
	private static final Identifier C_INGOTS = Identifier.fromNamespaceAndPath("c", "ingots");
	private static final Identifier C_INGOTS_IRON = Identifier.fromNamespaceAndPath("c", "ingots/iron");

	// ---------------------------------------------------------------- learn: inference

	@GameTest
	public void inferSingleItemIsExact(GameTestHelper helper) {
		Optional<RoomLearner.Inference> result = RoomLearner.infer(helper.getLevel(),
				counts(Items.IRON_INGOT, 64));
		helper.assertTrue(result.isPresent(), "expected a label");
		helper.assertValueEqual(result.get().label(), ChestLabel.exact(id(Items.IRON_INGOT)), "label");
		helper.assertTrue(result.get().misplaced().isEmpty(), "no outliers");
		helper.succeed();
	}

	@GameTest
	public void inferMixedIngotsPicksNarrowestCoveringTag(GameTestHelper helper) {
		Optional<RoomLearner.Inference> result = RoomLearner.infer(helper.getLevel(),
				counts(Items.IRON_INGOT, 64, Items.COPPER_INGOT, 32, Items.GOLD_INGOT, 16));
		helper.assertTrue(result.isPresent(), "expected a label");
		helper.assertValueEqual(result.get().label().tagId(), Optional.of(C_INGOTS), "tag");
		// The sample item is the most common one.
		helper.assertValueEqual(result.get().label().itemId(), Optional.of(id(Items.IRON_INGOT)), "sample item");
		helper.succeed();
	}

	@GameTest
	public void inferToleratesOneStrayKind(GameTestHelper helper) {
		Optional<RoomLearner.Inference> result = RoomLearner.infer(helper.getLevel(),
				counts(Items.IRON_INGOT, 64, Items.COPPER_INGOT, 32, Items.GOLD_INGOT, 16, Items.DIRT, 3));
		helper.assertTrue(result.isPresent(), "expected a label despite the dirt");
		helper.assertValueEqual(result.get().label().tagId(), Optional.of(C_INGOTS), "tag");
		helper.assertValueEqual(result.get().misplaced(), List.of(Items.DIRT), "misplaced");
		helper.succeed();
	}

	@GameTest
	public void inferGivesUpOnTrulyMixedContents(GameTestHelper helper) {
		Optional<RoomLearner.Inference> result = RoomLearner.infer(helper.getLevel(),
				counts(Items.DIRT, 10, Items.BOW, 1, Items.CAKE, 1));
		helper.assertTrue(result.isEmpty(), "dirt, a bow and a cake share no category");
		helper.succeed();
	}

	@GameTest
	public void inferTwoKindsWithoutCommonTagIsSkippedNotTolerated(GameTestHelper helper) {
		Optional<RoomLearner.Inference> result = RoomLearner.infer(helper.getLevel(),
				counts(Items.IRON_INGOT, 64, Items.DIRT, 3));
		helper.assertTrue(result.isEmpty(), "tolerance needs at least three kinds");
		helper.succeed();
	}

	// ---------------------------------------------------------------- learn: scan + apply

	@GameTest
	public void learnScanProposesAndApplyWritesExplicitLabels(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos single = chest(helper, new BlockPos(1, 1, 1), Items.DIAMOND, 5);
		BlockPos mixed = chest(helper, new BlockPos(3, 1, 1), Items.IRON_INGOT, 20, Items.GOLD_INGOT, 4);
		BlockPos empty = chest(helper, new BlockPos(5, 1, 1));
		BlockPos center = helper.absolutePos(new BlockPos(3, 1, 3));

		RoomLearner.Report report = RoomLearner.scan(level, center, 4, false);
		helper.assertValueEqual(proposalFor(report, single).map(RoomLearner.Proposal::label),
				Optional.of(ChestLabel.exact(id(Items.DIAMOND))), "single chest proposal");
		helper.assertValueEqual(proposalFor(report, mixed).flatMap(p -> p.label().tagId()),
				Optional.of(C_INGOTS), "mixed chest proposal");
		helper.assertTrue(report.skipped().stream().anyMatch(s -> s.pos().equals(empty)
				&& s.reason() == RoomLearner.SkipReason.EMPTY), "empty chest skipped");

		ServerPlayer player = mockPlayer(helper);
		LearnSession.preview(player, level, center, 4, false);
		int applied = LearnSession.apply(player);
		helper.assertTrue(applied >= 2, "applied at least the two proposals, got " + applied);

		ChestLabelSet singleLabels = ChestLabels.effectiveLabelSet(level, single, level.getBlockState(single));
		helper.assertTrue(singleLabels.explicit(), "applied labels are explicit");
		helper.assertValueEqual(singleLabels.labels(), List.of(ChestLabel.exact(id(Items.DIAMOND))), "single labels");
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, empty, level.getBlockState(empty)).isEmpty(),
				"empty chest untouched");

		// A second scan skips the now-explicit chests unless overwriting.
		RoomLearner.Report again = RoomLearner.scan(level, center, 4, false);
		helper.assertTrue(again.skipped().stream().anyMatch(s -> s.pos().equals(single)
				&& s.reason() == RoomLearner.SkipReason.EXPLICIT), "explicit chest skipped on rescan");
		helper.assertTrue(proposalFor(RoomLearner.scan(level, center, 4, true), single).isPresent(),
				"overwrite rescans explicit chests");
		helper.succeed();
	}

	// ---------------------------------------------------------------- smart frames

	@GameTest
	public void frameDerivesExactThenBroadensWithContents(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2), Items.IRON_INGOT, 10);
		ItemFrame frame = hangFrame(helper, chest, Items.IRON_INGOT);

		ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertFalse(labels.explicit(), "frame labels are derived");
		helper.assertValueEqual(labels.labels(), List.of(ChestLabel.exact(id(Items.IRON_INGOT))), "exact first");

		ChestBlockEntity blockEntity = helper.getBlockEntity(new BlockPos(2, 1, 2), ChestBlockEntity.class);
		blockEntity.setItem(1, new ItemStack(Items.COPPER_INGOT, 10));
		labels = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertValueEqual(labels.labels().getFirst().tagId(), Optional.of(C_INGOTS), "broadens to ingots");

		blockEntity.setItem(1, ItemStack.EMPTY);
		frame.setItem(new ItemStack(Items.DIAMOND));
		labels = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertValueEqual(labels.labels(), List.of(ChestLabel.exact(id(Items.DIAMOND))), "follows the frame item");
		helper.succeed();
	}

	@GameTest
	public void frameNeverDerivesPresetCategory(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// Iron ingot + raw iron share only the Ores & Minerals preset.
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2), Items.IRON_INGOT, 10, Items.RAW_IRON, 10);
		hangFrame(helper, chest, Items.IRON_INGOT);
		ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertValueEqual(labels.labels(), List.of(ChestLabel.exact(id(Items.IRON_INGOT))),
				"falls back to exact instead of a preset");
		helper.succeed();
	}

	@GameTest
	public void explicitLabelsIgnoreFrames(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2));
		ChestLabels.setExplicit(level, chest, level.getBlockState(chest), List.of(ChestLabel.exact(id(Items.CAKE))));
		hangFrame(helper, chest, Items.DIAMOND);
		ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertTrue(labels.explicit(), "still explicit");
		helper.assertValueEqual(labels.labels(), List.of(ChestLabel.exact(id(Items.CAKE))), "frame ignored");
		helper.succeed();
	}

	// ---------------------------------------------------------------- the tool

	@GameTest
	public void toolCopiesAndPastesLabels(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos source = chest(helper, new BlockPos(1, 1, 1));
		BlockPos target = chest(helper, new BlockPos(4, 1, 1));
		BlockPos unlabeled = chest(helper, new BlockPos(6, 1, 1));
		List<ChestLabel> labels = List.of(ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS_IRON));
		ChestLabels.setExplicit(level, source, level.getBlockState(source), labels);

		ServerPlayer player = toolPlayer(helper);
		// Paste with an empty clipboard: nothing happens.
		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(target));
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, target, level.getBlockState(target)).isEmpty(),
				"empty clipboard pastes nothing");

		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, source, Direction.UP);
		helper.assertValueEqual(Clipboard.of(player).labels(), Optional.of(labels), "clipboard after copy");
		helper.assertTrue(level.getBlockState(source).is(Blocks.CHEST), "copy did not break the chest");

		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(target));
		ChestLabelSet pasted = ChestLabels.effectiveLabelSet(level, target, level.getBlockState(target));
		helper.assertTrue(pasted.explicit(), "pasted labels are explicit");
		helper.assertValueEqual(pasted.labels(), labels, "pasted labels");

		// Copying an unlabeled chest clears the clipboard.
		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, unlabeled, Direction.UP);
		helper.assertTrue(Clipboard.of(player).labels().isEmpty(), "clipboard cleared");

		// Without sneaking the tool does nothing.
		player.setShiftKeyDown(false);
		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, target, Direction.UP);
		helper.assertTrue(Clipboard.of(player).labels().isEmpty(), "no copy without sneaking");
		helper.succeed();
	}

	@GameTest
	public void toolClearMarkerRemovesExplicitLabels(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2));
		ChestLabels.setExplicit(level, chest, level.getBlockState(chest), List.of(ChestLabel.exact(id(Items.CAKE))));
		ServerPlayer player = toolPlayer(helper);
		Clipboard.set(player, Clipboard.EMPTY.withLabels(Optional.of(List.of())));

		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(chest));
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"explicit labels removed");
		helper.succeed();
	}

	// ---------------------------------------------------------------- helpers

	private static Map<Item, Integer> counts(Object... itemsAndCounts) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < itemsAndCounts.length; i += 2) {
			counts.put((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]);
		}
		return counts;
	}

	/** Places a chest at the relative position, fills slots from 0, returns the absolute position. */
	private static BlockPos chest(GameTestHelper helper, BlockPos relative, Object... itemsAndCounts) {
		helper.setBlock(relative, Blocks.CHEST);
		ChestBlockEntity blockEntity = helper.getBlockEntity(relative, ChestBlockEntity.class);
		int slot = 0;
		for (int i = 0; i < itemsAndCounts.length; i += 2) {
			blockEntity.setItem(slot++, new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
		}
		return helper.absolutePos(relative);
	}

	/** Hangs a frame on the north face of the chest at the absolute position, showing {@code item}. */
	private static ItemFrame hangFrame(GameTestHelper helper, BlockPos chestAbs, Item item) {
		ServerLevel level = helper.getLevel();
		ItemFrame frame = new ItemFrame(level, chestAbs.north(), Direction.NORTH);
		level.addFreshEntity(frame);
		frame.setItem(new ItemStack(item));
		return frame;
	}

	/**
	 * The only mock that comes with a (dummy) network connection, which the
	 * chat messages sent by learn and the tool need. Deprecated upstream,
	 * but the replacement has no connection and crashes on sendSystemMessage.
	 */
	@SuppressWarnings("removal")
	private static ServerPlayer mockPlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	private static ServerPlayer toolPlayer(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
		player.setShiftKeyDown(true);
		return player;
	}

	private static BlockHitResult hit(BlockPos pos) {
		return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
	}

	private static Optional<RoomLearner.Proposal> proposalFor(RoomLearner.Report report, BlockPos pos) {
		return report.proposals().stream().filter(p -> p.pos().equals(pos)).findFirst();
	}

	private static Identifier id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item);
	}
}
