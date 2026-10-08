package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.label.LabelSuggestions;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads.Suggestion;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.tool.ChestEditor;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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

	// ---------------------------------------------------------------- zones

	@GameTest
	public void zoneResolvesByContainmentNearestAnchorFirst(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos near = copperChest(helper, new BlockPos(1, 1, 1));
		BlockPos far = copperChest(helper, new BlockPos(6, 1, 6));
		BoundingBox box = structureBox(helper);
		Zones.put(level, near, new Zone(box, ZoneSettings.DEFAULT.withDryRun(true)));
		Zones.put(level, far, new Zone(box, ZoneSettings.DEFAULT));

		BlockPos probe = helper.absolutePos(new BlockPos(2, 1, 2));
		Optional<Zones.ZoneRef> ref = Zones.zoneAt(level, probe);
		helper.assertTrue(ref.isPresent(), "inside both boxes");
		helper.assertValueEqual(ref.get().anchor(), near, "nearest anchor wins");
		helper.assertTrue(Zones.settingsAt(level, probe).dryRun(), "settings come from that zone");
		helper.assertTrue(Zones.zoneAt(level, helper.absolutePos(new BlockPos(7, 7, 7)).above(20)).isEmpty(),
				"outside every box");

		// An anchor that is no longer a copper chest drops out on the next lookup.
		helper.setBlock(new BlockPos(1, 1, 1), Blocks.AIR);
		helper.assertValueEqual(Zones.zoneAt(level, probe).map(Zones.ZoneRef::anchor), Optional.of(far),
				"stale anchor pruned");
		Zones.remove(level, far);
		helper.succeed();
	}

	@GameTest
	public void copperChestWithoutZoneGetsDefaultZoneOnce(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos chest = copperChest(helper, new BlockPos(3, 1, 3));
		Zones.ZoneRef first = Zones.zoneForCopperChest(level, chest);
		helper.assertValueEqual(first.anchor(), chest, "anchored at the chest");
		helper.assertValueEqual(first.area(), Zone.defaultArea(chest), "default area");
		helper.assertValueEqual(Zones.zoneForCopperChest(level, chest).anchor(), chest, "same zone next time");
		// Another copper chest inside the box joins it instead of making its own.
		BlockPos other = copperChest(helper, new BlockPos(5, 1, 3));
		helper.assertValueEqual(Zones.zoneForCopperChest(level, other).anchor(), chest, "shares the zone");
		Zones.remove(level, chest);
		helper.succeed();
	}

	@GameTest
	public void legacyPerChestSettingsMigrateIntoAZone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos chest = copperChest(helper, new BlockPos(3, 1, 3));
		level.getBlockEntity(chest).setAttached(WayBetterCopperGolem.ZONE_SETTINGS, ZoneSettings.DEFAULT.withTidyInside(true));

		Optional<Zones.ZoneRef> ref = Zones.zoneAt(level, chest);
		helper.assertTrue(ref.isPresent(), "migrated on lookup");
		helper.assertValueEqual(ref.get().anchor(), chest, "anchored at the old chest");
		helper.assertTrue(ref.get().settings().tidyInside(), "old settings kept");
		helper.assertFalse(level.getBlockEntity(chest).hasAttached(WayBetterCopperGolem.ZONE_SETTINGS),
				"legacy attachment removed");
		Zones.remove(level, chest);
		helper.succeed();
	}

	@GameTest
	public void toolPastesSettingsIntoTheZoneNotTheChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		BlockPos member = copperChest(helper, new BlockPos(6, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT));

		ServerPlayer player = toolPlayer(helper);
		Clipboard.set(player, Clipboard.EMPTY.withZone(ZoneSettings.DEFAULT.withDryRun(true)));
		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(member));

		Zone zone = Zones.all(level).get(anchor);
		helper.assertTrue(zone != null && zone.settings().dryRun(), "anchor's zone updated");
		helper.assertValueEqual(zone.area(), structureBox(helper), "area untouched");
		helper.assertValueEqual(Zones.all(level).containsKey(member), false, "no second zone created");

		// Copy reads through the zone too.
		Clipboard.set(player, Clipboard.EMPTY);
		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, member, Direction.UP);
		helper.assertTrue(Clipboard.of(player).zone().map(ZoneSettings::dryRun).orElse(false), "copied zone settings");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	@GameTest
	public void areaFromCornersOrdersAndClamps(GameTestHelper helper) {
		BoundingBox box = Zone.areaFromCorners(new BlockPos(10, 70, 10), new BlockPos(-5, 60, 20));
		helper.assertValueEqual(box, new BoundingBox(-5, 60, 10, 10, 70, 20), "corners in any order");
		BoundingBox clamped = Zone.areaFromCorners(new BlockPos(0, 64, 0), new BlockPos(1000, 64, -1000));
		helper.assertValueEqual(clamped.getXSpan(), Zone.MAX_SPAN, "x clamped");
		helper.assertValueEqual(clamped.getZSpan(), Zone.MAX_SPAN, "z clamped");
		helper.assertValueEqual(clamped.minX(), 0, "keeps the first corner's side (x)");
		helper.assertValueEqual(clamped.maxZ(), 0, "keeps the first corner's side (z)");
		helper.succeed();
	}

	@GameTest
	public void areaModeTakesTwoCornersAndIncludesTheAnchor(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(0, 1, 0));
		Zones.put(level, anchor, Zone.defaultAround(anchor));
		ServerPlayer player = toolPlayer(helper);
		LabelTool.beginAreaSelection(player, level, anchor);
		helper.assertTrue(LabelTool.inAreaMode(player), "in area mode");

		BlockPos corner1 = helper.absolutePos(new BlockPos(2, 1, 2));
		BlockPos corner2 = helper.absolutePos(new BlockPos(6, 4, 6));
		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(corner1));
		helper.assertTrue(LabelTool.inAreaMode(player), "still in area mode after one corner");
		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(corner2));
		helper.assertFalse(LabelTool.inAreaMode(player), "area mode ends after two corners");

		BoundingBox expected = BoundingBox.fromCorners(corner1, corner2).encapsulate(anchor);
		helper.assertValueEqual(Zones.all(level).get(anchor).area(), expected, "box spans the corners and the anchor");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	@GameTest
	public void golemSearchesInsideItsZoneBox(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(0, 1, 0));
		BoundingBox box = structureBox(helper);
		Zones.put(level, anchor, new Zone(box, ZoneSettings.DEFAULT.withDryRun(true)));

		@SuppressWarnings("unchecked")
		EntityType<CopperGolem> golemType = (EntityType<CopperGolem>) BuiltInRegistries.ENTITY_TYPE
				.getValue(Identifier.withDefaultNamespace("copper_golem"));
		CopperGolem golem = helper.spawn(golemType, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		helper.assertValueEqual(aware.wbcg$zone(level).map(Zones.ZoneRef::anchor), Optional.of(anchor), "standing in the zone");
		helper.assertValueEqual(aware.wbcg$searchArea(level, 32, 8), Zones.toAABB(box), "search box is the zone box");
		helper.assertTrue(aware.wbcg$zoneSettings(level).dryRun(), "zone settings apply");

		Zones.remove(level, anchor);
		golem.discard();
		helper.succeed();
	}

	// ---------------------------------------------------------------- picker + names

	@GameTest
	public void tagNamesAreFriendly(GameTestHelper helper) {
		helper.assertValueEqual(LabelResolver.tagName(C_INGOTS_IRON).getString(), "Ingots › Iron", "nested c: tag");
		helper.assertValueEqual(LabelResolver.tagName(Identifier.withDefaultNamespace("wooden_slabs")).getString(),
				"Wooden Slabs", "vanilla tag");
		helper.assertValueEqual(LabelResolver.tagName(Identifier.fromNamespaceAndPath("wbcg", "redstone")).getString(),
				"Redstone", "preset uses the lang entry");
		helper.assertTrue(LabelResolver.orderedTags(Items.IRON_INGOT).stream()
				.allMatch(tag -> LabelResolver.tagSize(tag) > 1), "no single-member stops");
		helper.assertValueEqual(LabelResolver.presetCategories().size(), 12, "twelve presets");
		helper.succeed();
	}

	@GameTest
	public void pickerChoiceIsValidatedAndStored(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		Clipboard.set(player, Clipboard.EMPTY);

		LabelTool.applyPickerChoice(player, Optional.of(List.of(ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS))));
		helper.assertValueEqual(Clipboard.of(player).labels(),
				Optional.of(List.of(ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS))), "valid tag label stored");

		LabelTool.applyPickerChoice(player, Optional.of(List.of(
				ChestLabel.tag(id(Items.IRON_INGOT), Identifier.fromNamespaceAndPath("c", "no_such_tag")))));
		helper.assertValueEqual(Clipboard.of(player).labels().map(l -> l.getFirst().tagId()),
				Optional.of(Optional.of(C_INGOTS)), "unknown tag rejected, clipboard unchanged");

		LabelTool.applyPickerChoice(player, Optional.of(List.of(
				ChestLabel.exact(Identifier.fromNamespaceAndPath("nomod", "nothing")))));
		helper.assertValueEqual(Clipboard.of(player).labels().map(l -> l.getFirst().tagId()),
				Optional.of(Optional.of(C_INGOTS)), "unknown item rejected");

		LabelTool.applyPickerChoice(player, Optional.of(List.of()));
		helper.assertTrue(Clipboard.of(player).isClearMarker(), "remove-labels marker stored");

		LabelTool.applyPickerChoice(player, Optional.of(List.of(ChestLabel.catchAll())));
		helper.assertValueEqual(Clipboard.of(player).labels(), Optional.of(List.of(ChestLabel.catchAll())), "catch-all stored");

		LabelTool.applyPickerChoice(player, Optional.empty());
		helper.assertTrue(Clipboard.of(player).labels().isEmpty(), "clipboard emptied");
		helper.succeed();
	}

	// ---------------------------------------------------------------- HUD data

	@GameTest
	public void cachedLabelSetUnionsBothHalvesWithoutDeriving(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// setBlock skips placement logic, so pair the halves explicitly: facing
		// north, the LEFT half connects clockwise (east) to the RIGHT half.
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
		BlockPos left = helper.absolutePos(new BlockPos(2, 1, 2));
		BlockPos right = helper.absolutePos(new BlockPos(3, 1, 2));
		helper.assertTrue(ChestLabels.halves(left, level.getBlockState(left)).size() == 2, "double chest formed");

		helper.assertTrue(ChestLabels.cachedLabelSet(level, left, level.getBlockState(left)).isEmpty(),
				"nothing cached yet");
		level.getBlockEntity(left).setAttached(WayBetterCopperGolem.CHEST_LABELS,
				ChestLabelSet.derived(List.of(ChestLabel.exact(id(Items.IRON_INGOT)))));
		level.getBlockEntity(right).setAttached(WayBetterCopperGolem.CHEST_LABELS,
				ChestLabelSet.explicit(List.of(ChestLabel.catchAll())));

		ChestLabelSet union = ChestLabels.cachedLabelSet(level, right, level.getBlockState(right));
		helper.assertValueEqual(union.labels(), List.of(ChestLabel.catchAll(), ChestLabel.exact(id(Items.IRON_INGOT))),
				"both halves, clicked half first");
		helper.assertTrue(union.explicit(), "explicit if either half is");
		helper.succeed();
	}

	@GameTest
	public void outlinePointsCoverAllTwelveEdges(GameTestHelper helper) {
		BoundingBox box = new BoundingBox(0, 0, 0, 3, 1, 2); // spans 4 x 2 x 3
		List<net.minecraft.world.phys.Vec3> points = Zones.outlinePoints(box);
		// Edge lengths 4, 2, 3 → 5, 3, 4 samples each; four edges per axis.
		helper.assertValueEqual(points.size(), 4 * 5 + 4 * 3 + 4 * 4, "sample count");
		for (net.minecraft.world.phys.Vec3 point : points) {
			boolean onX = point.x == 0 || point.x == 4;
			boolean onY = point.y == 0 || point.y == 2;
			boolean onZ = point.z == 0 || point.z == 3;
			helper.assertTrue((onX ? 1 : 0) + (onY ? 1 : 0) + (onZ ? 1 : 0) >= 2, "every point lies on an edge: " + point);
		}
		BoundingBox huge = new BoundingBox(0, 0, 0, 127, 127, 127);
		helper.assertTrue(Zones.outlinePoints(huge).size() <= 12 * 33, "long edges are sampled sparsely");
		helper.succeed();
	}

	// ---------------------------------------------------------------- per-chest editor

	@GameTest
	public void suggestionsRankByCoverageThenNarrowness(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// 3 stacks of iron ingots, 1 copper ingot, 1 dirt: 5 stacks total.
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2),
				Items.IRON_INGOT, 64, Items.IRON_INGOT, 64, Items.IRON_INGOT, 10, Items.COPPER_INGOT, 5, Items.DIRT, 1);
		List<Suggestion> suggestions = LabelSuggestions.forChest(level, chest);
		helper.assertFalse(suggestions.isEmpty(), "has suggestions");
		helper.assertTrue(suggestions.stream().allMatch(s -> s.totalStacks() == 5), "total is the stack count");
		Suggestion ingots = suggestions.stream()
				.filter(s -> s.label().tagId().equals(Optional.of(C_INGOTS))).findFirst().orElseThrow();
		helper.assertValueEqual(ingots.coveredStacks(), 4, "ingots covers iron + copper stacks");
		helper.assertValueEqual(ingots.label().itemId(), Optional.of(id(Items.IRON_INGOT)), "sample is the most common");
		Suggestion exact = suggestions.stream()
				.filter(s -> s.label().equals(ChestLabel.exact(id(Items.IRON_INGOT)))).findFirst().orElseThrow();
		helper.assertValueEqual(exact.coveredStacks(), 3, "exact iron covers its stacks");
		helper.assertTrue(suggestions.indexOf(ingots) < suggestions.indexOf(exact), "more coverage ranks first");
		// A tag covering the same 4 stacks but broader than c:ingots must not outrank it.
		for (Suggestion s : suggestions) {
			if (s.coveredStacks() == 4 && s.label().tagId().isPresent()) {
				helper.assertTrue(suggestions.indexOf(ingots) <= suggestions.indexOf(s), "narrowest first among equals");
			}
		}
		helper.assertTrue(LabelSuggestions.forChest(level, chest(helper, new BlockPos(5, 1, 2))).isEmpty(),
				"empty chest: no suggestions");
		helper.succeed();
	}

	@GameTest
	public void editorAppliesLabelsAndCopiesToClipboard(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2), Items.IRON_INGOT, 10);
		ServerPlayer player = mockPlayer(helper);
		player.setPos(Vec3.atCenterOf(chest.above()));
		Clipboard.set(player, Clipboard.EMPTY);

		List<ChestLabel> labels = List.of(ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS), ChestLabel.catchAll());
		ChestEditor.apply(player, chest, labels);
		ChestLabelSet set = ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest));
		helper.assertTrue(set.explicit(), "explicit");
		helper.assertValueEqual(set.labels(), labels, "labels applied");
		helper.assertValueEqual(Clipboard.of(player).labels(), Optional.of(labels), "copied to the clipboard");

		ChestEditor.apply(player, chest, List.of(ChestLabel.exact(Identifier.fromNamespaceAndPath("nomod", "x"))));
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).labels(), labels,
				"invalid label rejected");

		player.setPos(Vec3.atCenterOf(chest).add(40, 0, 0));
		ChestEditor.apply(player, chest, List.of());
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).labels(), labels,
				"out of reach: ignored");

		player.setPos(Vec3.atCenterOf(chest.above()));
		ChestEditor.apply(player, chest, List.of());
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(), "cleared");
		helper.assertTrue(Clipboard.of(player).isClearMarker(), "clipboard holds the clear marker");
		helper.succeed();
	}

	// ---------------------------------------------------------------- helpers

	private static BlockPos copperChest(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.COPPER_CHEST.asList().getFirst());
		return helper.absolutePos(relative);
	}

	/** The test structure's own 8x8x8 box, in world coordinates. */
	private static BoundingBox structureBox(GameTestHelper helper) {
		return BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(7, 7, 7)));
	}

	/** Zones are per dimension and tests share one, so drop any zone overlapping this structure first. */
	private static void clearZonesAround(GameTestHelper helper) {
		BoundingBox mine = structureBox(helper);
		for (Map.Entry<BlockPos, Zone> entry : Zones.all(helper.getLevel()).entrySet()) {
			if (entry.getValue().area().intersects(mine)) {
				Zones.remove(helper.getLevel(), entry.getKey());
			}
		}
	}


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
