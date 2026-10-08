package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.label.LabelSuggestions;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads.Suggestion;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.learn.RoomLearner;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.tool.ChestEditor;
import io.github.lnasser11.waybettercoppergolem.tool.GuideBook;
import io.github.lnasser11.waybettercoppergolem.tool.Onboarding;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;
import io.github.lnasser11.waybettercoppergolem.tuning.TuningNet;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneOverview;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSimulation;
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
		helper.assertValueEqual(LabelResolver.presetCategories().size(), 14, "fourteen presets");
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

	// ---------------------------------------------------------------- tuning screen (server side)

	@GameTest
	public void tuningChangesNeedOpAndRoundTrip(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Identifier redstone = Identifier.fromNamespaceAndPath("wbcg", "redstone");
		CategoryTuning.reset(level, redstone);
		ServerPlayer player = mockPlayer(helper);

		helper.assertFalse(TuningNet.canEdit(player), "mock player is not an operator");
		TuningNet.change(player, redstone, Optional.of(id(Items.GLOWSTONE)), true);
		helper.assertFalse(CategoryTuning.matches(level, redstone, new ItemStack(Items.GLOWSTONE)),
				"non-op change rejected");

		// The mock player cannot be made an operator in the test server, so the
		// tweak itself (what change() runs after the permission check) is
		// exercised directly.
		helper.assertTrue(TuningNet.applyTweak(level, redstone, Optional.of(id(Items.GLOWSTONE)), true) != null, "added confirmation");
		helper.assertTrue(CategoryTuning.matches(level, redstone, new ItemStack(Items.GLOWSTONE)), "added");
		TuningNet.applyTweak(level, redstone, Optional.of(id(Items.PISTON)), false);
		helper.assertFalse(CategoryTuning.matches(level, redstone, new ItemStack(Items.PISTON)), "base item excluded");
		CategoryTuning.TagOverride override = CategoryTuning.overridesFor(level, redstone);
		helper.assertValueEqual(override.added(), java.util.Set.of(id(Items.GLOWSTONE)), "added set");
		helper.assertValueEqual(override.removed(), java.util.Set.of(id(Items.PISTON)), "removed set");
		helper.assertTrue(TuningNet.applyTweak(level, redstone, Optional.of(Identifier.fromNamespaceAndPath("nomod", "x")), true) == null,
				"unknown item: no-op");

		TuningNet.applyTweak(level, redstone, Optional.empty(), false);
		helper.assertTrue(CategoryTuning.overridesFor(level, redstone).isEmpty(), "reset dropped the tweaks");
		helper.succeed();
	}

	// ---------------------------------------------------------------- overview + simulation

	@GameTest
	public void overviewListsProblemsFirst(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(0, 1, 0));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		BlockPos fine = chest(helper, new BlockPos(1, 1, 3), Items.IRON_INGOT, 10);
		ChestLabels.setExplicit(level, fine, level.getBlockState(fine), List.of(ChestLabel.exact(id(Items.IRON_INGOT))));
		BlockPos misplaced = chest(helper, new BlockPos(3, 1, 3), Items.IRON_INGOT, 10, Items.DIRT, 1);
		ChestLabels.setExplicit(level, misplaced, level.getBlockState(misplaced), List.of(ChestLabel.exact(id(Items.IRON_INGOT))));
		BlockPos unlabeled = chest(helper, new BlockPos(5, 1, 3), Items.CAKE, 1);
		BlockPos empty = chest(helper, new BlockPos(7, 1, 3));
		ChestLabels.setExplicit(level, empty, level.getBlockState(empty), List.of(ChestLabel.catchAll()));

		ZonePayloads.Overview overview = ZoneOverview.build(level, new Zones.ZoneRef(anchor, Zones.all(level).get(anchor)));
		helper.assertValueEqual(overview.copperChests(), 1, "copper chests counted");
		helper.assertValueEqual(overview.entries().size(), 4, "four regular chests");
		helper.assertValueEqual(overview.entries().get(0).pos(), unlabeled, "unlabeled first");
		helper.assertTrue(overview.entries().get(0).problems().contains("unlabeled"), "flagged unlabeled");
		helper.assertValueEqual(overview.entries().get(1).pos(), misplaced, "misplaced second");
		helper.assertTrue(overview.entries().get(1).problems().contains("misplaced"), "flagged misplaced");
		ZonePayloads.Entry fineEntry = overview.entries().stream().filter(e -> e.pos().equals(fine)).findFirst().orElseThrow();
		ZonePayloads.Entry misplacedEntry = overview.entries().get(1);
		helper.assertTrue(fineEntry.problems().contains("duplicate") && misplacedEntry.problems().contains("duplicate"),
				"two chests with the same label set are flagged duplicate");
		ZonePayloads.Entry emptyEntry = overview.entries().stream().filter(e -> e.pos().equals(empty)).findFirst().orElseThrow();
		helper.assertValueEqual(emptyEntry.problems(), List.of("empty"), "catch-all empty chest: only 'empty'");
		helper.assertValueEqual(fineEntry.stacks(), 1, "stack count");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	@GameTest
	public void simulationPredictsDestinationsAndMergesMoves(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos source = copperChest(helper, new BlockPos(0, 1, 0));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		ChestBlockEntity copper = helper.getBlockEntity(new BlockPos(0, 1, 0), ChestBlockEntity.class);
		copper.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
		copper.setItem(1, new ItemStack(Items.IRON_INGOT, 10));
		copper.setItem(2, new ItemStack(Items.CAKE, 1));
		BlockPos ironChest = chest(helper, new BlockPos(4, 1, 4));
		ChestLabels.setExplicit(level, ironChest, level.getBlockState(ironChest), List.of(ChestLabel.exact(id(Items.IRON_INGOT))));
		BlockPos offLimits = chest(helper, new BlockPos(6, 1, 6));
		ChestLabels.setExplicit(level, offLimits, level.getBlockState(offLimits), List.of(ChestLabel.exact(id(Items.COBWEB))));

		ZonePayloads.Simulation simulation = ZoneSimulation.build(level, new Zones.ZoneRef(source, Zones.all(level).get(source)));
		helper.assertValueEqual(simulation.sourceChests(), 1, "one copper chest");
		helper.assertValueEqual(simulation.moves().size(), 2, "iron merged into one move, cake another");
		ZonePayloads.Move cake = simulation.moves().get(0);
		helper.assertValueEqual(cake.item(), id(Items.CAKE), "nowhere-to-go moves come first");
		helper.assertTrue(cake.to().isEmpty(), "cake has no destination (off-limits chest refused)");
		ZonePayloads.Move iron = simulation.moves().get(1);
		helper.assertValueEqual(iron.count(), 74, "counts merged");
		helper.assertValueEqual(iron.to(), Optional.of(ironChest), "iron goes to the iron chest");
		helper.assertValueEqual(iron.toLabels(), List.of(ChestLabel.exact(id(Items.IRON_INGOT))), "destination labels");
		Zones.remove(level, source);
		helper.succeed();
	}

	// ---------------------------------------------------------------- phase 4: defaults, onboarding, guide

	@GameTest
	public void worldDefaultsSeedNewZonesAndApplyToAll(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		Zones.setDefaults(level, ZoneSettings.DEFAULT);
		BlockPos first = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, first, new Zone(structureBox(helper), ZoneSettings.DEFAULT));

		ZoneSettings tidy = ZoneSettings.DEFAULT.withTidyInside(true);
		Zones.setDefaults(level, tidy);
		helper.assertValueEqual(Zones.defaults(level), tidy, "defaults stored");
		Zones.remove(level, first);
		Zones.ZoneRef created = Zones.zoneForCopperChest(level, first);
		helper.assertTrue(created.settings().tidyInside(), "new zone starts from the world defaults");

		BoundingBox box = created.area();
		int changed = Zones.applyToAll(level, ZoneSettings.DEFAULT.withDryRun(true));
		helper.assertTrue(changed >= 1, "at least this zone changed");
		Zone after = Zones.all(level).get(first);
		helper.assertTrue(after.settings().dryRun() && !after.settings().tidyInside(), "settings replaced");
		helper.assertValueEqual(after.area(), box, "area untouched");

		Zones.setDefaults(level, ZoneSettings.DEFAULT);
		helper.assertValueEqual(Zones.defaults(level), ZoneSettings.DEFAULT, "defaults reset");
		Zones.remove(level, first);
		helper.succeed();
	}

	@GameTest
	public void onboardingHintsShowOnce(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		helper.assertFalse(Onboarding.hasSeen(player, false), "frame hint not seen yet");
		Onboarding.hintFrame(player);
		helper.assertTrue(Onboarding.hasSeen(player, false), "frame hint recorded");
		Onboarding.hintFrame(player); // no exception, no second record change
		helper.assertFalse(Onboarding.hasSeen(player, true), "tool hint independent");
		Onboarding.hintTool(player);
		helper.assertTrue(Onboarding.hasSeen(player, true) && Onboarding.hasSeen(player, false), "both recorded");
		helper.succeed();
	}

	@GameTest
	public void guideBookHasAllPages(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		ItemStack book = GuideBook.create(player);
		helper.assertTrue(book.is(Items.WRITTEN_BOOK), "a written book");
		var content = book.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
		helper.assertTrue(content != null && content.pages().size() == GuideBook.PAGES, "all pages present");
		helper.succeed();
	}

	// ---------------------------------------------------------------- golems at work
	//
	// Real copper golems in a walled 8x8 room, ticking vanilla's transport
	// behavior with the mod's hooks: labels decide the destination, the zone
	// box bounds the search, dry run moves nothing. A trip is ~3 s at each
	// chest plus walking, and a stack moves 16 at a time, so these tests take
	// up to a minute each.

	private static final int GOLEM_TIMEOUT = 3000;

	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemSortsIntoLabeledChests(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 20, Items.COBBLESTONE, 30);
		BlockPos ingots = chest(helper, new BlockPos(6, 1, 1));
		BlockPos cobble = chest(helper, new BlockPos(6, 1, 6));
		label(level, ingots, ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS));
		label(level, cobble, ChestLabel.exact(id(Items.COBBLESTONE)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.failIfEver(() -> {
			helper.assertValueEqual(count(level, ingots, Items.COBBLESTONE), 0, "cobblestone among the ingots");
			helper.assertValueEqual(count(level, cobble, Items.IRON_INGOT), 0, "ingots among the cobblestone");
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(count(level, ingots, Items.IRON_INGOT), 20, "iron ingots in the ingot chest");
			helper.assertValueEqual(count(level, cobble, Items.COBBLESTONE), 30, "cobblestone in the cobblestone chest");
			helper.assertValueEqual(count(level, source, Items.IRON_INGOT) + count(level, source, Items.COBBLESTONE), 0,
					"copper chest emptied");
		});
	}

	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemIgnoresChestsOutsideItsZone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.COBBLESTONE, 30);
		// The perfect destination sits outside the zone box (x > 4); a plain chest sits inside.
		BlockPos outside = chest(helper, new BlockPos(6, 1, 3));
		BlockPos inside = chest(helper, new BlockPos(3, 1, 6));
		label(level, outside, ChestLabel.exact(id(Items.COBBLESTONE)));
		BoundingBox box = BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(4, 7, 7)));
		Zones.put(level, source, new Zone(box, ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(2, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.failIfEver(() -> helper.assertValueEqual(count(level, outside, Items.COBBLESTONE), 0,
				"cobblestone in the chest outside the zone"));
		helper.succeedWhen(() -> helper.assertValueEqual(count(level, inside, Items.COBBLESTONE), 30,
				"cobblestone in the unlabeled chest inside the zone"));
	}

	@GameTest(maxTicks = 1200)
	public void golemMovesNothingInDryRun(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 20);
		BlockPos ingots = chest(helper, new BlockPos(6, 1, 3));
		label(level, ingots, ChestLabel.tag(id(Items.IRON_INGOT), C_INGOTS));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withDryRun(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.runAfterDelay(800, () -> {
			helper.assertValueEqual(count(level, source, Items.IRON_INGOT), 20, "copper chest untouched in dry run");
			helper.assertValueEqual(count(level, ingots, Items.IRON_INGOT), 0, "nothing delivered in dry run");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "golem carries nothing in dry run");
			helper.succeed();
		});
	}

	/**
	 * A 24×24 storage room (its own structure, floor and walls included)
	 * with three copper chests of mixed loot, a wall of labeled chests two
	 * high on the far side, and three golems working at once. Everything
	 * must end up in the right chest and nothing in a wrong one.
	 */
	@GameTest(structure = "waybettercoppergolem_tests:big_room", maxTicks = 6000)
	public void threeGolemsSortABigRoom(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BoundingBox box = BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(23, 5, 23)));
		for (Map.Entry<BlockPos, Zone> entry : Zones.all(level).entrySet()) {
			if (entry.getValue().area().intersects(box)) {
				Zones.remove(level, entry.getKey());
			}
		}
		BlockPos sourceA = copperChest(helper, new BlockPos(3, 1, 3));
		BlockPos sourceB = copperChest(helper, new BlockPos(3, 1, 20));
		BlockPos sourceC = copperChest(helper, new BlockPos(12, 1, 12));
		fill(helper, sourceA, Items.IRON_INGOT, 32, Items.COBBLESTONE, 32, Items.OAK_PLANKS, 32);
		fill(helper, sourceB, Items.GOLD_INGOT, 32, Items.WHEAT, 32, Items.COBBLESTONE, 32);
		fill(helper, sourceC, Items.IRON_INGOT, 32, Items.GOLD_INGOT, 32, Items.WHEAT, 32);

		// The chest wall at x=21: a second row on top tests the raised-chest line of sight.
		BlockPos iron = chest(helper, new BlockPos(21, 1, 4));
		BlockPos gold = chest(helper, new BlockPos(21, 2, 4));
		BlockPos cobble = chest(helper, new BlockPos(21, 1, 10));
		BlockPos planks = chest(helper, new BlockPos(21, 2, 10));
		BlockPos wheat = chest(helper, new BlockPos(21, 1, 16));
		label(level, iron, ChestLabel.exact(id(Items.IRON_INGOT)));
		label(level, gold, ChestLabel.exact(id(Items.GOLD_INGOT)));
		label(level, cobble, ChestLabel.exact(id(Items.COBBLESTONE)));
		label(level, planks, ChestLabel.tag(id(Items.OAK_PLANKS), Identifier.withDefaultNamespace("planks")));
		label(level, wheat, ChestLabel.exact(id(Items.WHEAT)));
		Zones.put(level, sourceA, new Zone(box, ZoneSettings.DEFAULT));

		List<CopperGolem> golems = List.of(
				spawnGolem(helper, new BlockPos(8, 1, 8)),
				spawnGolem(helper, new BlockPos(8, 1, 16)),
				spawnGolem(helper, new BlockPos(16, 1, 12)));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, sourceA);
			golems.forEach(CopperGolem::discard);
		});
		Map<BlockPos, Item> expected = Map.of(iron, Items.IRON_INGOT, gold, Items.GOLD_INGOT,
				cobble, Items.COBBLESTONE, planks, Items.OAK_PLANKS, wheat, Items.WHEAT);
		helper.failIfEver(() -> {
			for (Map.Entry<BlockPos, Item> entry : expected.entrySet()) {
				for (Item wrong : expected.values()) {
					if (wrong != entry.getValue()) {
						helper.assertValueEqual(count(level, entry.getKey(), wrong), 0,
								wrong + " in the " + entry.getValue() + " chest");
					}
				}
			}
		});
		long start = level.getGameTime();
		helper.succeedWhen(() -> {
			helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 64, "iron delivered");
			helper.assertValueEqual(count(level, gold, Items.GOLD_INGOT), 64, "gold delivered");
			helper.assertValueEqual(count(level, cobble, Items.COBBLESTONE), 64, "cobblestone delivered");
			helper.assertValueEqual(count(level, planks, Items.OAK_PLANKS), 32, "planks delivered");
			helper.assertValueEqual(count(level, wheat, Items.WHEAT), 64, "wheat delivered");
			WayBetterCopperGolem.LOGGER.info("[gametest] three golems sorted 288 items across the big room in {} ticks",
					level.getGameTime() - start);
		});
	}

	/** A stone floor at y=0 and glass walls around the structure, so the golem stays in the room. */
	private static void buildRoom(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				if (x == 0 || x == 7 || z == 0 || z == 7) {
					for (int y = 1; y <= 2; y++) {
						helper.setBlock(new BlockPos(x, y, z), Blocks.GLASS);
					}
				}
			}
		}
	}

	private static CopperGolem spawnGolem(GameTestHelper helper, BlockPos relative) {
		@SuppressWarnings("unchecked")
		EntityType<CopperGolem> golemType = (EntityType<CopperGolem>) BuiltInRegistries.ENTITY_TYPE
				.getValue(Identifier.withDefaultNamespace("copper_golem"));
		return helper.spawn(golemType, relative);
	}

	private static void label(ServerLevel level, BlockPos chestAbs, ChestLabel... labels) {
		ChestLabels.setExplicit(level, chestAbs, level.getBlockState(chestAbs), List.of(labels));
	}

	/** Fills a container's slots from 0 at the absolute position. */
	private static void fill(GameTestHelper helper, BlockPos abs, Object... itemsAndCounts) {
		if (helper.getLevel().getBlockEntity(abs) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container) {
			int slot = 0;
			for (int i = 0; i < itemsAndCounts.length; i += 2) {
				container.setItem(slot++, new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
			}
			container.setChanged();
		}
	}

	/** How many of the item a container at the absolute position holds. */
	private static int count(ServerLevel level, BlockPos abs, Item item) {
		if (!(level.getBlockEntity(abs) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container)) {
			return 0;
		}
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	// ---------------------------------------------------------------- vertical reach

	/** The zone screen's stepper changes the zone's reach through the menu, clamped to the allowed range. */
	@GameTest
	public void reachStepperChangesTheZone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		ServerPlayer player = mockPlayer(helper);
		Zones.ZoneRef ref = Zones.zoneForCopperChest(level, anchor);
		ZoneSettingsMenu menu = new ZoneSettingsMenu(1, net.minecraft.world.inventory.ContainerLevelAccess.create(level, anchor),
				anchor, ZoneSettingsMenu.dataFor(ref));

		menu.clickMenuButton(player, ZoneSettingsMenu.BUTTON_REACH_UP);
		helper.assertValueEqual(Zones.settingsAt(level, anchor).verticalReach(), ZoneSettings.DEFAULT_VERTICAL_REACH + 1, "one up");
		helper.assertValueEqual(menu.settings().verticalReach(), ZoneSettings.DEFAULT_VERTICAL_REACH + 1, "menu data follows");
		for (int i = 0; i < ZoneSettings.MAX_VERTICAL_REACH + 5; i++) {
			menu.clickMenuButton(player, ZoneSettingsMenu.BUTTON_REACH_UP);
		}
		helper.assertValueEqual(Zones.settingsAt(level, anchor).verticalReach(), ZoneSettings.MAX_VERTICAL_REACH, "clamped at the top");
		for (int i = 0; i < ZoneSettings.MAX_VERTICAL_REACH + 5; i++) {
			menu.clickMenuButton(player, ZoneSettingsMenu.BUTTON_REACH_DOWN);
		}
		helper.assertValueEqual(Zones.settingsAt(level, anchor).verticalReach(), ZoneSettings.MIN_VERTICAL_REACH, "clamped at the bottom");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	/** A golem on the floor delivers into a chest four blocks up when the zone's reach allows it. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemReachesAHighChestWithinReach(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 16);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE);
		}
		BlockPos high = chest(helper, new BlockPos(6, 5, 3));
		label(level, high, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withVerticalReach(8)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.succeedWhen(() -> helper.assertValueEqual(count(level, high, Items.IRON_INGOT), 16, "delivered four blocks up"));
	}

	/** With the reach set to one block, the same high chest is out of reach and stays empty. */
	@GameTest(maxTicks = 1200)
	public void golemCannotReachAHighChestBeyondReach(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 16);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(new BlockPos(6, y, 3), Blocks.STONE);
		}
		BlockPos high = chest(helper, new BlockPos(6, 5, 3));
		label(level, high, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withVerticalReach(1)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.runAfterDelay(800, () -> {
			helper.assertValueEqual(count(level, high, Items.IRON_INGOT), 0, "nothing delivered beyond reach");
			helper.succeed();
		});
	}

	// ---------------------------------------------------------------- the golem button

	/** Opening the editor from a chest's screen must close that chest's menu, or the server keeps syncing it. */
	@GameTest
	public void golemButtonClosesTheChestMenu(GameTestHelper helper) {
		BlockPos chest = chest(helper, new BlockPos(2, 1, 2), Items.IRON_INGOT, 8);
		ServerPlayer player = mockPlayer(helper);
		player.teleportTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 1.5);
		ChestBlockEntity blockEntity = helper.getBlockEntity(new BlockPos(2, 1, 2), ChestBlockEntity.class);
		player.openMenu(blockEntity);
		helper.assertTrue(player.containerMenu != player.inventoryMenu, "the chest menu is open");

		ChestEditor.open(player, chest);
		helper.assertTrue(player.containerMenu == player.inventoryMenu, "the chest menu was closed before the editor opened");
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
