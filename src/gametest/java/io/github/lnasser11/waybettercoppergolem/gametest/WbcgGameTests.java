package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
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
import io.github.lnasser11.waybettercoppergolem.zone.ZoneAccess;
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

	/** The copied chest's "no golem frames" switch rides along with its labels, by feather and by the overview's Paste. */
	@GameTest
	public void toolCopiesAndPastesTheFrameSwitch(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos source = chest(helper, new BlockPos(1, 1, 1));
		BlockPos target = chest(helper, new BlockPos(4, 1, 1));
		BlockPos viaOverview = chest(helper, new BlockPos(6, 1, 1));
		BlockPos plain = chest(helper, new BlockPos(1, 1, 4));
		List<ChestLabel> labels = List.of(ChestLabel.exact(id(Items.IRON_INGOT)));
		ChestLabels.setExplicit(level, source, level.getBlockState(source), labels);
		ChestLabels.setGolemFramesAllowed(level, source, level.getBlockState(source), false);
		ChestLabels.setExplicit(level, plain, level.getBlockState(plain), labels);

		ServerPlayer player = toolPlayer(helper);
		player.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(3, 1, 3))));
		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, source, Direction.UP);
		helper.assertValueEqual(Clipboard.of(player).labels(), Optional.of(labels), "labels copied");
		helper.assertTrue(Clipboard.of(player).noGolemFrames(), "the frame switch was copied");

		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(target));
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, target, level.getBlockState(target)).labels(), labels, "labels pasted");
		helper.assertFalse(ChestLabels.golemFramesAllowed(level, target, level.getBlockState(target)), "frame switch pasted by the feather");

		ChestEditor.pasteClipboard(player, viaOverview);
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, viaOverview, level.getBlockState(viaOverview)).labels(), labels, "labels pasted from the overview");
		helper.assertFalse(ChestLabels.golemFramesAllowed(level, viaOverview, level.getBlockState(viaOverview)), "frame switch pasted from the overview");

		// Copying a chest that allows frames, then pasting, allows them again on the target.
		AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, plain, Direction.UP);
		helper.assertFalse(Clipboard.of(player).noGolemFrames(), "frames allowed on the clipboard");
		UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(target));
		helper.assertTrue(ChestLabels.golemFramesAllowed(level, target, level.getBlockState(target)), "frames allowed again after pasting");

		// The picker never carries the switch: labels from it paste with frames allowed.
		Clipboard.set(player, Clipboard.of(player).withLabels(Optional.of(labels), true));
		LabelTool.applyPickerChoice(player, Optional.of(labels));
		helper.assertFalse(Clipboard.of(player).noGolemFrames(), "the picker resets the frame switch");
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
	public void editorAppliesLabelsAndLeavesTheClipboardAlone(GameTestHelper helper) {
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
		helper.assertValueEqual(Clipboard.of(player), Clipboard.EMPTY, "the editor does not touch the clipboard");

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
		helper.assertValueEqual(Clipboard.of(player), Clipboard.EMPTY, "still untouched after clearing");
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

	// ---------------------------------------------------------------- carry size and waking up

	/** With the default config a golem carries a whole stack: the chest goes from 0 to 64 in one delivery. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemCarriesAFullStack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 64);
		BlockPos target = chest(helper, new BlockPos(6, 1, 3));
		label(level, target, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.failIfEver(() -> {
			int delivered = count(level, target, Items.IRON_INGOT);
			helper.assertTrue(delivered == 0 || delivered == 64, "a partial delivery of " + delivered + " means a 16-item trip");
		});
		helper.succeedWhen(() -> helper.assertValueEqual(count(level, target, Items.IRON_INGOT), 64, "the whole stack in one trip"));
	}

	/**
	 * A golem that remembers the copper chest as "visited" and is in its idle
	 * cooldown would ignore new items there for minutes; a content change
	 * wakes it, so the items are delivered within seconds.
	 */
	@GameTest(maxTicks = 1200)
	public void golemNoticesNewItemsInAChestItAlreadyVisited(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		BlockPos target = chest(helper, new BlockPos(6, 1, 3));
		label(level, target, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		// The stale state vanilla gets into: the chest is remembered for 6000 ticks and a cooldown is running.
		golem.getBrain().setMemoryWithExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.VISITED_BLOCK_POSITIONS,
				new java.util.HashSet<>(List.of(new net.minecraft.core.GlobalPos(level.dimension(), source))), 6000);
		golem.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, 140);
		fill(helper, source, Items.IRON_INGOT, 16);
		helper.succeedWhen(() -> helper.assertValueEqual(count(level, target, Items.IRON_INGOT), 16, "delivered despite the stale memory"));
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

	// ---------------------------------------------------------------- golems stay inside their zone
	//
	// The 8x8 room gets an inner glass wall at x=5 with a two-wide doorway, and
	// the zone's box is the part west of it (x 0..4). A golem joins the zone by
	// taking from its copper chest; from then on "golems stay inside" decides
	// whether the doorway is a way out.

	/** The zone box is the west part of the room; the east part is outside it but still has a floor. */
	private static BoundingBox westBox(GameTestHelper helper) {
		return BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(4, 7, 7)));
	}

	/** {@link #buildRoom} plus an inner glass wall at x=5 with an open doorway at z=3..4. */
	private static void buildRoomWithDoorway(GameTestHelper helper) {
		buildRoom(helper);
		for (int z = 1; z <= 6; z++) {
			if (z == 3 || z == 4) {
				continue;
			}
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(new BlockPos(5, y, z), Blocks.GLASS);
			}
		}
	}

	/**
	 * A golem that already belongs to the zone (joined at spawn: a golem that
	 * has not joined yet may stroll out during its spawn cooldown and go
	 * looking for copper chests in the neighbouring test structures), with a
	 * copper chest of iron and an iron chest to keep it busy.
	 */
	private static CopperGolem golemInWestZone(GameTestHelper helper, boolean stayInside) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoomWithDoorway(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 1));
		fill(helper, source, Items.IRON_INGOT, 16);
		BlockPos ingots = chest(helper, new BlockPos(1, 1, 6));
		label(level, ingots, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(westBox(helper), ZoneSettings.DEFAULT.withStayInside(stayInside)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, source);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		return golem;
	}

	/** Every 40 ticks after joining, tempt the golem with a walk target on the far side of the doorway. */
	private static void temptOutside(GameTestHelper helper, CopperGolem golem) {
		BlockPos outside = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.onEachTick(() -> {
			if (((ZoneAwareGolem) golem).wbcg$homeZoneAnchor() != null && helper.getTick() % 40 == 0) {
				golem.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
						new net.minecraft.world.entity.ai.memory.WalkTarget(outside, 1.0F, 0));
			}
		});
	}

	/** Taking from a copper chest inside a zone makes the golem a member of that zone, stored on the golem. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemJoinsTheZoneWhenItTakesFromACopperChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 16);
		BlockPos ingots = chest(helper, new BlockPos(6, 1, 3));
		label(level, ingots, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.assertTrue(aware.wbcg$homeZoneAnchor() == null, "no zone before the first pickup");
		helper.succeedWhen(() -> {
			helper.assertTrue(source.equals(aware.wbcg$homeZoneAnchor()), "joined the zone of the copper chest it took from");
			helper.assertTrue(source.equals(golem.getAttached(WayBetterCopperGolem.GOLEM_ZONE)), "membership stored on the golem");
			helper.assertValueEqual(aware.wbcg$zone(level).map(Zones.ZoneRef::anchor), Optional.of(source), "works for that zone");
		});
	}

	/** With the setting on, a golem that joined the zone never crosses the doorway in 1200 ticks, even when sent there. */
	@GameTest(maxTicks = 1600)
	public void confinedGolemNeverLeavesItsZone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = golemInWestZone(helper, true);
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		BlockPos anchor = helper.absolutePos(new BlockPos(1, 1, 1));
		BoundingBox box = westBox(helper);
		temptOutside(helper, golem);
		helper.runAtTickTime(5, () -> {
			// The mechanism itself: a path through the doorway is cut at the last node inside the box.
			net.minecraft.world.level.pathfinder.Path path = golem.getNavigation().createPath(helper.absolutePos(new BlockPos(6, 1, 3)), 0);
			helper.assertTrue(path != null, "a path is computed");
			helper.assertFalse(path.canReach(), "the path does not reach the far side of the doorway");
			for (int i = 0; i < path.getNodeCount(); i++) {
				helper.assertTrue(box.isInside(path.getNodePos(i)), "path node outside the zone box: " + path.getNodePos(i));
			}
			golem.getNavigation().stop();
		});
		helper.failIfEver(() -> {
			if (aware.wbcg$homeZoneAnchor() != null) {
				helper.assertTrue(box.isInside(golem.blockPosition()), "golem left its zone at " + golem.blockPosition());
			}
		});
		helper.runAfterDelay(1200, () -> {
			helper.assertTrue(anchor.equals(aware.wbcg$homeZoneAnchor()), "golem still belongs to the zone");
			helper.assertTrue(golem.getAttached(WayBetterCopperGolem.GOLEM_ZONE) != null, "membership is stored on the golem");
			helper.assertValueEqual(aware.wbcg$confinement(level).map(BoundingBox::toString), Optional.of(box.toString()),
					"confined to the zone box");
			helper.succeed();
		});
	}

	/** With the setting off, the same golem walks through the doorway when sent there. */
	@GameTest(maxTicks = 2400)
	public void unconfinedGolemLeavesItsZone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = golemInWestZone(helper, false);
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		temptOutside(helper, golem);
		helper.runAtTickTime(5, () -> {
			net.minecraft.world.level.pathfinder.Path path = golem.getNavigation().createPath(helper.absolutePos(new BlockPos(6, 1, 3)), 0);
			helper.assertTrue(path != null && path.canReach(), "with the setting off a path through the doorway is allowed");
			golem.getNavigation().stop();
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(aware.wbcg$homeZoneAnchor() != null, "joined");
			helper.assertTrue(aware.wbcg$confinement(level).isEmpty(), "not confined with the setting off");
			helper.assertFalse(westBox(helper).isInside(golem.blockPosition()), "golem still inside the zone box at " + golem.blockPosition());
		});
	}

	/** A confined golem teleported two blocks outside the box walks back through the doorway. */
	@GameTest(maxTicks = 1600)
	public void confinedGolemTeleportedOutsideWalksBack(GameTestHelper helper) {
		CopperGolem golem = golemInWestZone(helper, true);
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		BoundingBox box = westBox(helper);
		net.minecraft.world.phys.Vec3 outside = helper.absoluteVec(new net.minecraft.world.phys.Vec3(6.5, 1.0, 3.5));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(aware.wbcg$homeZoneAnchor() != null, "golem has not joined the zone yet"))
				.thenExecute(() -> golem.teleportTo(outside.x, outside.y, outside.z))
				.thenExecuteAfter(1, () -> helper.assertFalse(box.isInside(golem.blockPosition()), "golem is outside after the teleport"))
				.thenWaitUntil(() -> helper.assertTrue(box.isInside(golem.blockPosition()),
						"golem still outside its zone at " + golem.blockPosition()))
				.thenSucceed();
	}

	// ---------------------------------------------------------------- golems hang frames
	//
	// Chests placed by setBlock face north, so a frame hung by a golem sits on
	// the block north of the chest, facing north.

	private static List<ItemFrame> framesOnFront(ServerLevel level, BlockPos chestAbs) {
		return ChestLabels.labelFrames(level, chestAbs).stream().filter(frame -> frame.getDirection() == Direction.NORTH).toList();
	}

	private static List<ItemFrame> framesInRoom(GameTestHelper helper) {
		return helper.getLevel().getEntitiesOfClass(ItemFrame.class, Zones.toAABB(structureBox(helper)).inflate(1));
	}

	/** Copper chest with iron and two frames, an explicitly labeled iron chest holding ten ingots, one golem. */
	private static CopperGolem golemWithFrames(GameTestHelper helper, boolean hangFrames) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 16, Items.ITEM_FRAME, 2);
		BlockPos iron = chest(helper, new BlockPos(6, 1, 3), Items.IRON_INGOT, 10);
		label(level, iron, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withHangFrames(hangFrames)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			framesInRoom(helper).forEach(ItemFrame::discard);
			golem.discard();
		});
		return golem;
	}

	/** After delivering the iron, the golem fetches a frame and hangs it on the iron chest showing an ingot; the chest loses exactly one. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemHangsAFrameShowingTheChestsContent(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		golemWithFrames(helper, true);
		BlockPos source = helper.absolutePos(new BlockPos(1, 1, 3));
		BlockPos iron = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.failIfEver(() -> {
			helper.assertTrue(count(level, iron, Items.ITEM_FRAME) == 0, "a frame was stored inside the iron chest");
			helper.assertTrue(framesOnFront(level, iron).size() <= 1, "more than one frame on the chest");
		});
		helper.succeedWhen(() -> {
			List<ItemFrame> frames = framesOnFront(level, iron);
			helper.assertValueEqual(frames.size(), 1, "frames on the chest's front face");
			helper.assertTrue(frames.getFirst().getItem().is(Items.IRON_INGOT), "the frame shows an iron ingot");
			helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 25, "ten plus sixteen delivered, minus the one framed");
			helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 1, "one frame taken from the copper chest");
			ChestLabelSet labels = ChestLabels.effectiveLabelSet(level, iron, level.getBlockState(iron));
			helper.assertTrue(labels.explicit() && labels.labels().equals(List.of(ChestLabel.exact(id(Items.IRON_INGOT)))),
					"the chest's explicit labels did not change");
		});
	}

	/** With the setting off, frames are ordinary items and nothing is hung. */
	@GameTest(maxTicks = 1200)
	public void golemHangsNothingWithTheSettingOff(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		golemWithFrames(helper, false);
		BlockPos iron = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.runAfterDelay(800, () -> {
			helper.assertTrue(framesInRoom(helper).isEmpty(), "a frame was hung with the setting off");
			helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 26, "iron delivered, none framed");
			helper.succeed();
		});
	}

	/** A chest that already has a frame on its front face is left alone, and the copper chest keeps its frames. */
	@GameTest(maxTicks = 1200)
	public void golemLeavesAnExistingFrameAlone(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		golemWithFrames(helper, true);
		BlockPos source = helper.absolutePos(new BlockPos(1, 1, 3));
		BlockPos iron = helper.absolutePos(new BlockPos(6, 1, 3));
		hangFrame(helper, iron, Items.DIAMOND);
		helper.runAfterDelay(800, () -> {
			List<ItemFrame> frames = framesOnFront(level, iron);
			helper.assertValueEqual(frames.size(), 1, "still exactly one frame on the chest");
			helper.assertTrue(frames.getFirst().getItem().is(Items.DIAMOND), "the existing frame still shows a diamond");
			helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 26, "iron delivered, none framed");
			helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 2, "the copper chest's frames were not taken");
			helper.succeed();
		});
	}

	// ---------------------------------------------------------------- ownership and guard rails

	/** Settings and label edits inside an owned zone need the owner, a trusted player or an operator. */
	@GameTest
	public void zoneEditsNeedTheOwnerATrustedPlayerOrAnOperator(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		ServerPlayer owner = mockPlayer(helper);
		ServerPlayer other = mockPlayer(helper);
		Zones.ZoneRef ref = Zones.zoneForCopperChest(level, anchor, owner).orElseThrow();
		Zones.put(level, anchor, ref.zone().withArea(structureBox(helper)));
		helper.assertTrue(ref.zone().access().isOwner(owner.getUUID()), "the creator owns the zone");
		helper.assertTrue(Zones.zoneForCopperChest(level, anchor, other).orElseThrow().zone().access().isOwner(owner.getUUID()),
				"another player opening the zone does not take it");
		BlockPos chest = chest(helper, new BlockPos(3, 1, 1), Items.IRON_INGOT, 5);
		List<ChestLabel> iron = List.of(ChestLabel.exact(id(Items.IRON_INGOT)));
		owner.setPos(Vec3.atCenterOf(chest.above()));
		other.setPos(Vec3.atCenterOf(chest.above()));
		ZoneSettingsMenu menu = new ZoneSettingsMenu(1, net.minecraft.world.inventory.ContainerLevelAccess.create(level, anchor),
				anchor, ZoneSettingsMenu.dataFor(ref, 0));

		// A stranger: refused through the menu, the editor packet and the tool; nothing changes.
		menu.clickMenuButton(other, ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN);
		helper.assertFalse(Zones.settingsAt(level, anchor).dryRun(), "a stranger's toggle is rejected");
		menu.clickMenuButton(other, ZoneSettingsMenu.BUTTON_RESET_AREA);
		helper.assertValueEqual(Zones.all(level).get(anchor).area(), structureBox(helper), "a stranger's area reset is rejected");
		ChestEditor.apply(other, chest, iron);
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"a stranger's label edit through the editor packet is rejected");
		other.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
		other.setShiftKeyDown(true);
		Clipboard.set(other, Clipboard.EMPTY.withLabels(Optional.of(iron)));
		UseBlockCallback.EVENT.invoker().interact(other, level, InteractionHand.MAIN_HAND, hit(chest));
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"a stranger's paste with the tool is rejected");
		ZoneSettingsMenu.trust(other, anchor, new net.minecraft.server.players.NameAndId(other.getGameProfile()), true);
		helper.assertFalse(Zones.all(level).get(anchor).access().isTrusted(other.getUUID()), "a stranger cannot trust themselves");

		// The owner: everything works.
		menu.clickMenuButton(owner, ZoneSettingsMenu.BUTTON_TOGGLE_DRY_RUN);
		helper.assertTrue(Zones.settingsAt(level, anchor).dryRun(), "the owner's toggle is applied");
		ChestEditor.apply(owner, chest, iron);
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).labels(), iron,
				"the owner's label edit is applied");

		// A trusted player: like the owner, until untrusted.
		ZoneSettingsMenu.trust(owner, anchor, new net.minecraft.server.players.NameAndId(other.getGameProfile()), true);
		helper.assertTrue(Zones.all(level).get(anchor).access().isTrusted(other.getUUID()), "trusted by the owner");
		menu.clickMenuButton(other, ZoneSettingsMenu.BUTTON_TOGGLE_TIDY);
		helper.assertTrue(Zones.settingsAt(level, anchor).tidyInside(), "a trusted player's toggle is applied");
		ChestEditor.apply(other, chest, List.of());
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"a trusted player's label edit is applied");
		ZoneSettingsMenu.trust(owner, anchor, new net.minecraft.server.players.NameAndId(other.getGameProfile()), false);
		helper.assertFalse(Zones.all(level).get(anchor).access().isTrusted(other.getUUID()), "untrusted again");
		menu.clickMenuButton(other, ZoneSettingsMenu.BUTTON_TOGGLE_TIDY);
		helper.assertTrue(Zones.settingsAt(level, anchor).tidyInside(), "refused again once untrusted");

		// Operators: the rule itself lets them through whatever the lists say.
		ZoneAccess access = Zones.all(level).get(anchor).access();
		helper.assertTrue(access.canEdit(other.getUUID(), true), "an operator may edit any zone");
		helper.assertFalse(access.canEdit(other.getUUID(), false), "without operator permission a stranger may not");
		helper.assertTrue(access.withOwner(new net.minecraft.server.players.NameAndId(other.getGameProfile())).isOwner(other.getUUID()),
				"a takeover makes the operator the owner");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	/** Chests outside every zone, and inside zones nobody has claimed, follow the old rule: anyone may label them. */
	@GameTest
	public void labelsOutsideOwnedZonesStayOpen(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		ServerPlayer player = mockPlayer(helper);
		BlockPos chest = chest(helper, new BlockPos(3, 1, 3), Items.IRON_INGOT, 5);
		player.setPos(Vec3.atCenterOf(chest.above()));
		List<ChestLabel> iron = List.of(ChestLabel.exact(id(Items.IRON_INGOT)));
		ChestEditor.apply(player, chest, iron);
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).labels(), iron,
				"outside every zone anyone may label");
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		ChestEditor.apply(player, chest, List.of());
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"inside a zone nobody owns yet, anyone may label");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	/** The config switches: zones need an op to create, labels ignore ownership, one zone per player. */
	@GameTest
	public void configGuardsZoneCreationAndLabelOwnership(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		WbcgConfig before = WbcgConfig.get();
		helper.runBeforeTestEnd(() -> WbcgConfig.override(before));
		ServerPlayer player = mockPlayer(helper);
		ServerPlayer other = mockPlayer(helper);
		BlockPos first = copperChest(helper, new BlockPos(1, 1, 1));
		BlockPos second = copperChest(helper, new BlockPos(6, 1, 6));

		WbcgConfig.override(before.withZonesRequireOpToCreate(true));
		helper.assertTrue(Zones.zoneForCopperChest(level, first, player).isEmpty(), "zones need an operator to create");
		helper.assertTrue(Zones.all(level).isEmpty() || !Zones.all(level).containsKey(first), "nothing was created");

		WbcgConfig.override(before.withMaxZonesPerPlayer(1));
		Zones.ZoneRef one = Zones.zoneForCopperChest(level, first, player).orElseThrow();
		Zones.put(level, first, one.zone().withArea(BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(3, 7, 3)))));
		helper.assertTrue(Zones.zoneForCopperChest(level, second, player).isEmpty(), "the second zone is over the player's limit");
		helper.assertTrue(Zones.zoneForCopperChest(level, second, other).isPresent(), "another player may still create one");

		BlockPos chest = chest(helper, new BlockPos(2, 1, 2), Items.IRON_INGOT, 5);
		other.setPos(Vec3.atCenterOf(chest.above()));
		List<ChestLabel> iron = List.of(ChestLabel.exact(id(Items.IRON_INGOT)));
		ChestEditor.apply(other, chest, iron);
		helper.assertTrue(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).isEmpty(),
				"with labels_require_zone_ownership the stranger is refused");
		WbcgConfig.override(before.withLabelsRequireZoneOwnership(false));
		ChestEditor.apply(other, chest, iron);
		helper.assertValueEqual(ChestLabels.effectiveLabelSet(level, chest, level.getBlockState(chest)).labels(), iron,
				"without it the old rule applies");
		Zones.remove(level, first);
		Zones.remove(level, second);
		helper.succeed();
	}

	/** The learn pass runs at most once per ten seconds per player. */
	@GameTest
	public void learnIsRateLimitedPerPlayer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		chest(helper, new BlockPos(1, 1, 1), Items.DIAMOND, 5);
		BlockPos center = helper.absolutePos(new BlockPos(3, 1, 3));
		ServerPlayer player = mockPlayer(helper);
		LearnSession.resetRateLimit(player);
		LearnSession.preview(player, level, center, 4, false);
		helper.assertTrue(LearnSession.apply(player) == 1, "the first scan proposes and applies the diamond chest");
		chest(helper, new BlockPos(5, 1, 1), Items.CAKE, 5);
		LearnSession.preview(player, level, center, 4, false);
		helper.assertTrue(LearnSession.apply(player) < 0, "the second scan within ten seconds is refused, so there is nothing to apply");
		LearnSession.resetRateLimit(player);
		LearnSession.preview(player, level, center, 4, false);
		helper.assertTrue(LearnSession.apply(player) == 1, "after the wait the scan runs again");
		helper.succeed();
	}

	/** With golems_require_zone, a golem outside every zone does nothing. */
	@GameTest(maxTicks = 1200)
	public void golemsOutsideZonesIdleWhenTheConfigSaysSo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		WbcgConfig before = WbcgConfig.get();
		WbcgConfig.override(before.withGolemsRequireZone(true));
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.IRON_INGOT, 16);
		BlockPos target = chest(helper, new BlockPos(6, 1, 3));
		label(level, target, ChestLabel.exact(id(Items.IRON_INGOT)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			WbcgConfig.override(before);
			golem.discard();
		});
		helper.runAfterDelay(600, () -> {
			helper.assertValueEqual(count(level, source, Items.IRON_INGOT), 16, "the copper chest is untouched outside every zone");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "the golem carries nothing");
			helper.succeed();
		});
	}

	// ---------------------------------------------------------------- tidy across sibling chests

	private static final Identifier STONE_AND_DIRT = Identifier.fromNamespaceAndPath("wbcg", "stone");

	/** Two chests labeled Stone & Dirt: A holds 26 stacks of stone and one of dirt, B holds dirt. */
	private static BlockPos[] stoneAndDirtSiblings(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Object[] a = new Object[54];
		for (int i = 0; i < 26; i++) {
			a[2 * i] = Items.STONE;
			a[2 * i + 1] = 64;
		}
		a[52] = Items.DIRT;
		a[53] = 64;
		BlockPos chestA = chest(helper, new BlockPos(6, 1, 2), a);
		BlockPos chestB = chest(helper, new BlockPos(6, 1, 5), Items.DIRT, 64, Items.DIRT, 30);
		ChestLabel label = ChestLabel.tag(id(Items.STONE), STONE_AND_DIRT);
		label(level, chestA, label);
		label(level, chestB, label);
		return new BlockPos[] {chestA, chestB};
	}

	/** The plan itself, and how the overview and the simulation report it. */
	@GameTest
	public void tidyPlanMovesTheMinorityDirtToTheChestHoldingMore(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withTidyInside(true)));
		BlockPos[] chests = stoneAndDirtSiblings(helper);
		List<io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.TidyMove> plan =
				io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.planTidy(level, java.util.Set.of(),
						Zones.toAABB(structureBox(helper)), 64);
		helper.assertValueEqual(plan.size(), 1, "one consolidation: the dirt");
		helper.assertValueEqual(plan.getFirst().source().pos(), chests[0], "out of the stone chest");
		helper.assertValueEqual(plan.getFirst().home().pos(), chests[1], "into the chest holding more dirt");
		helper.assertTrue(plan.getFirst().stack().is(Items.DIRT) && plan.getFirst().stack().getCount() == 64, "the whole dirt stack");

		Zones.ZoneRef ref = new Zones.ZoneRef(anchor, Zones.all(level).get(anchor));
		helper.assertTrue(ZoneOverview.build(level, ref).tidyMoves() >= 1, "the overview counts it");
		ZonePayloads.Simulation simulation = ZoneSimulation.build(level, ref);
		helper.assertValueEqual(simulation.moves().stream().filter(ZonePayloads.Move::tidy).count(), 1L, "the simulation lists it");
		ZonePayloads.Move move = simulation.moves().stream().filter(ZonePayloads.Move::tidy).findFirst().orElseThrow();
		helper.assertTrue(move.tidy() && move.item().equals(id(Items.DIRT)) && move.count() == 64
				&& move.to().equals(Optional.of(chests[1])), "as a tidy row: 64x dirt into the dirt chest");

		// A chest full of one item is never a source, and cannot be a destination.
		Object[] full = new Object[54];
		for (int i = 0; i < 27; i++) {
			full[2 * i] = Items.COBBLESTONE;
			full[2 * i + 1] = 64;
		}
		BlockPos fullChest = chest(helper, new BlockPos(2, 1, 6), full);
		BlockPos sibling = chest(helper, new BlockPos(4, 1, 6), Items.COBBLESTONE, 5, Items.GRAVEL, 3);
		label(level, fullChest, ChestLabel.exact(id(Items.COBBLESTONE)));
		label(level, sibling, ChestLabel.exact(id(Items.COBBLESTONE)));
		plan = io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.planTidy(level, java.util.Set.of(),
				Zones.toAABB(structureBox(helper)), 64);
		helper.assertTrue(plan.stream().noneMatch(m -> m.source().pos().equals(fullChest) || m.home().pos().equals(fullChest)),
				"the full cobblestone chest is left alone (no room, nothing minority)");
		helper.assertTrue(plan.stream().noneMatch(m -> m.stack().is(Items.GRAVEL)), "a misplaced stack is reorganize's job, not tidy's");
		Zones.remove(level, anchor);
		helper.succeed();
	}

	/** The example from the brief: a golem moves the dirt over, freeing the slot in the stone chest. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemTidiesTheDirtIntoTheSiblingChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withTidyInside(true)));
		BlockPos[] chests = stoneAndDirtSiblings(helper);
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, anchor);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, anchor);
			golem.discard();
		});
		helper.failIfEver(() -> {
			helper.assertValueEqual(count(level, chests[0], Items.STONE), 26 * 64, "stone never leaves the stone chest");
			helper.assertValueEqual(count(level, chests[1], Items.STONE), 0, "no stone arrives in the dirt chest");
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(count(level, chests[0], Items.DIRT), 0, "the dirt left the stone chest");
			helper.assertValueEqual(count(level, chests[1], Items.DIRT), 64 + 94, "all the dirt is in the dirt chest");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "nothing left in hand");
		});
	}

	/** In dry run the golem logs the move and touches nothing. */
	@GameTest(maxTicks = 1200)
	public void tidyInDryRunMovesNothing(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withTidyInside(true).withDryRun(true)));
		BlockPos[] chests = stoneAndDirtSiblings(helper);
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, anchor);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, anchor);
			golem.discard();
		});
		helper.runAfterDelay(800, () -> {
			helper.assertValueEqual(count(level, chests[0], Items.DIRT), 64, "the dirt stayed in dry run");
			helper.assertValueEqual(count(level, chests[1], Items.DIRT), 94, "the dirt chest is unchanged in dry run");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "the golem carries nothing in dry run");
			helper.succeed();
		});
	}

	/** A chest switched to "no golem frames" in the editor never gets one; the copper chest keeps its frames. */
	@GameTest(maxTicks = 1200)
	public void golemRespectsTheChestsFrameSwitch(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		golemWithFrames(helper, true);
		BlockPos source = helper.absolutePos(new BlockPos(1, 1, 3));
		BlockPos iron = helper.absolutePos(new BlockPos(6, 1, 3));
		ServerPlayer player = mockPlayer(helper);
		player.setPos(Vec3.atCenterOf(iron.above()));
		ChestEditor.setGolemFrames(player, iron, false);
		helper.assertFalse(ChestLabels.golemFramesAllowed(level, iron, level.getBlockState(iron)), "switched off");
		helper.assertFalse(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, iron), "no frame wanted");
		helper.runAfterDelay(800, () -> {
			helper.assertTrue(framesOnFront(level, iron).isEmpty(), "a frame was hung on a chest that forbids it");
			helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 26, "iron delivered, none framed");
			helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 2, "the copper chest's frames were not taken");
			ChestEditor.setGolemFrames(player, iron, true);
			helper.assertTrue(ChestLabels.golemFramesAllowed(level, iron, level.getBlockState(iron)), "switched back on");
			helper.succeed();
		});
	}

	/** A chest whose front is taken (a slab, a solid block, another frame) is never planned for a frame. */
	@GameTest
	public void frameNeedsAFreeFront(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chest = chest(helper, new BlockPos(3, 1, 3), Items.IRON_INGOT, 5);
		label(level, chest, ChestLabel.exact(id(Items.IRON_INGOT)));
		helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, chest), "air in front: wanted");
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE_SLAB);
		helper.assertFalse(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, chest), "a slab in front: not wanted");
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
		helper.assertFalse(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, chest), "a block in front: not wanted");
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.AIR);
		helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, chest), "free again: wanted");
		hangFrame(helper, chest, Items.DIAMOND);
		helper.assertFalse(io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.wantsFrame(level, chest), "a frame already there: not wanted");
		helper.succeed();
	}

	/** The front gets blocked while the golem is carrying the frame: it gives up and brings the frame back. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemGivesUpAndReturnsTheFrameWhenTheFrontGetsBlocked(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		CopperGolem golem = golemWithFrames(helper, true);
		BlockPos source = helper.absolutePos(new BlockPos(1, 1, 3));
		BlockPos iron = helper.absolutePos(new BlockPos(6, 1, 3));
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
						io.github.lnasser11.waybettercoppergolem.sorting.FrameHanger.isFrame(golem.getMainHandItem()), "golem not carrying a frame yet"))
				.thenExecute(() -> helper.setBlock(new BlockPos(6, 1, 2), Blocks.STONE_SLAB))
				.thenWaitUntil(() -> {
					helper.assertTrue(golem.getMainHandItem().isEmpty(), "golem still carrying the frame");
					helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 2, "the frame went back to the copper chest");
				})
				.thenExecute(() -> {
					helper.assertTrue(framesOnFront(level, iron).isEmpty(), "no frame hung on the blocked chest");
					helper.assertValueEqual(count(level, iron, Items.ITEM_FRAME), 0, "no frame stored in the iron chest");
					helper.assertValueEqual(count(level, iron, Items.IRON_INGOT), 26, "no ingot taken for a frame");
				})
				.thenSucceed();
	}

	// ---------------------------------------------------------------- idle golems perch

	/** With "idle golems perch" on, a golem with nothing to do climbs onto a chest and stays; new items bring it down. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void idleGolemPerchesOnAChestAndComesDownForWork(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 1));
		BlockPos target = chest(helper, new BlockPos(6, 1, 6));
		label(level, target, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withPerchIdle(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		aware.wbcg$joinZoneAt(level, source);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(aware.wbcg$isPerched(level), "golem not on a chest yet, at " + golem.blockPosition()))
				.thenExecuteFor(200, () -> helper.assertTrue(aware.wbcg$isPerched(level), "golem left its perch at " + golem.blockPosition()))
				.thenExecute(() -> fill(helper, source, Items.IRON_INGOT, 16))
				.thenWaitUntil(() -> helper.assertValueEqual(count(level, target, Items.IRON_INGOT), 16, "iron delivered after coming down"))
				.thenSucceed();
	}

	/** With the setting off, an idle golem is never sent onto a chest. */
	@GameTest(maxTicks = 1200)
	public void idleGolemStaysOnTheFloorWithPerchOff(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 1));
		chest(helper, new BlockPos(6, 1, 6));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		aware.wbcg$joinZoneAt(level, source);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.failIfEver(() -> {
			helper.assertFalse(aware.wbcg$isPerched(level), "perched with the setting off");
			helper.assertTrue(golem.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
					.map(t -> !level.getBlockState(t.getTarget().currentBlockPosition().below()).is(Blocks.CHEST)
							&& !level.getBlockState(t.getTarget().currentBlockPosition()).is(Blocks.CHEST)).orElse(true),
					"sent onto a chest with the setting off");
		});
		helper.runAfterDelay(800, helper::succeed);
	}

	// ---------------------------------------------------------------- reorganize: misplaced stacks go to the right chest

	/** A cake in the Stone & Dirt chest is moved to the Food chest, and the simulation predicts it. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemMovesAMisplacedStackToTheChestLabeledForIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT));
		BlockPos stone = chest(helper, new BlockPos(6, 1, 2), Items.STONE, 64, Items.STONE, 64, Items.CAKE, 1);
		BlockPos food = chest(helper, new BlockPos(6, 1, 5), Items.BREAD, 4);
		label(level, stone, ChestLabel.tag(id(Items.STONE), STONE_AND_DIRT));
		label(level, food, ChestLabel.tag(id(Items.BREAD), Identifier.fromNamespaceAndPath("wbcg", "food")));

		ZonePayloads.Simulation simulation = ZoneSimulation.build(level, new Zones.ZoneRef(anchor, Zones.all(level).get(anchor)));
		helper.assertValueEqual(simulation.moves().size(), 1, "one reorganize row");
		ZonePayloads.Move row = simulation.moves().getFirst();
		helper.assertTrue(row.reorganize() && row.item().equals(id(Items.CAKE)) && row.count() == 1
				&& row.from().equals(stone) && row.to().equals(Optional.of(food)),
				"reorganize: 1x cake from the stone chest " + stone + " to the food chest " + food + ", got " + row);

		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, anchor);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, anchor);
			golem.discard();
		});
		helper.failIfEver(() -> {
			helper.assertValueEqual(count(level, stone, Items.STONE), 128, "stone never leaves the stone chest");
			helper.assertValueEqual(count(level, food, Items.STONE), 0, "no stone arrives in the food chest");
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(count(level, stone, Items.CAKE), 0, "the cake left the stone chest");
			helper.assertValueEqual(count(level, food, Items.CAKE), 1, "the cake is in the food chest");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "nothing left in hand");
		});
	}

	// ---------------------------------------------------------------- tidy: stacks in order inside a chest

	/** Interleaved stacks end up grouped by item, the item with the most stacks first, partials merged. */
	@GameTest
	public void tidyLaysStacksOutByItemBiggestGroupFirst(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = chest(helper, new BlockPos(2, 1, 2),
				Items.DIRT, 64, Items.COBBLESTONE, 64, Items.SAND, 20, Items.COBBLESTONE, 30, Items.DIRT, 64,
				Items.COBBLESTONE, 64, Items.SAND, 10, Items.COBBLESTONE, 64, Items.COBBLESTONE, 10);
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(2, 1, 2), ChestBlockEntity.class);
		helper.assertFalse(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.isTidy(chest), "untidy to begin with");
		io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.tidyContainer(chest);
		// Cobblestone: 64+30+64+64+10 = 232 = 3 full stacks + 40; dirt: 2 stacks; sand: 30 in one stack.
		Item[] expected = {Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE, Items.DIRT, Items.DIRT, Items.SAND};
		int[] counts = {64, 64, 64, 40, 64, 64, 30};
		StringBuilder layout = new StringBuilder();
		for (int i = 0; i < chest.getContainerSize(); i++) {
			layout.append(i).append('=').append(chest.getItem(i)).append(' ');
		}
		for (int i = 0; i < expected.length; i++) {
			helper.assertTrue(chest.getItem(i).is(expected[i]) && chest.getItem(i).getCount() == counts[i],
					"slot " + i + " should be " + counts[i] + "x " + expected[i] + " but the layout is " + layout);
		}
		helper.assertTrue(chest.getItem(7).isEmpty(), "the rest is free");
		helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.isTidy(chest), "tidy afterwards");
		helper.assertValueEqual(count(level, pos, Items.COBBLESTONE) + count(level, pos, Items.DIRT) + count(level, pos, Items.SAND),
				232 + 128 + 30, "nothing created or lost");
		helper.succeed();
	}

	/** With tidy on, a golem visits an untidy labeled chest and puts its stacks in order without carrying anything. */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemVisitsAndSortsAnUntidyChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withTidyInside(true)));
		BlockPos messy = chest(helper, new BlockPos(6, 1, 4),
				Items.DIRT, 64, Items.COBBLESTONE, 64, Items.DIRT, 10, Items.COBBLESTONE, 64, Items.COBBLESTONE, 64);
		label(level, messy, ChestLabel.tag(id(Items.STONE), STONE_AND_DIRT));
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(6, 1, 4), ChestBlockEntity.class);
		Zones.ZoneRef ref = new Zones.ZoneRef(anchor, Zones.all(level).get(anchor));
		helper.assertTrue(ZoneSimulation.build(level, ref).moves().stream().anyMatch(ZonePayloads.Move::sort), "the simulation lists a sorting visit");
		helper.assertValueEqual(ZoneOverview.build(level, ref).tidyMoves(), 1, "the overview counts it");
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, anchor);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, anchor);
			golem.discard();
		});
		helper.failIfEver(() -> helper.assertTrue(golem.getMainHandItem().isEmpty(), "a sorting visit carries nothing"));
		helper.succeedWhen(() -> {
			helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.isTidy(chest), "chest sorted");
			helper.assertTrue(chest.getItem(0).is(Items.COBBLESTONE) && chest.getItem(3).is(Items.DIRT) && chest.getItem(4).getCount() == 10,
					"three cobblestone stacks, then dirt 64 and dirt 10");
			helper.assertValueEqual(count(level, messy, Items.DIRT) + count(level, messy, Items.COBBLESTONE), 266, "nothing created or lost");
		});
	}

	// ---------------------------------------------------------------- several golems: routing

	/**
	 * Golems do not shove each other. With the AI off a mob still runs
	 * {@code pushEntities()} every tick but never travels, so pushes pile up
	 * in its velocity and nothing else (a stroll can start on tick 2 with the
	 * AI on, which made the position-based version of this test flaky) can
	 * move it: two overlapping golems end with no sideways velocity at all,
	 * while a golem and a pig overlapping get pushed.
	 */
	@GameTest(maxTicks = 200)
	public void golemsDoNotPushEachOther(GameTestHelper helper) {
		buildRoom(helper);
		CopperGolem a = spawnGolem(helper, new BlockPos(2, 1, 2));
		CopperGolem b = spawnGolem(helper, new BlockPos(2, 1, 2));
		@SuppressWarnings("unchecked")
		EntityType<net.minecraft.world.entity.Mob> pigType = (EntityType<net.minecraft.world.entity.Mob>)
				BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("pig"));
		CopperGolem c = spawnGolem(helper, new BlockPos(5, 1, 5));
		net.minecraft.world.entity.Mob pig = helper.spawn(pigType, new BlockPos(5, 1, 5));
		for (net.minecraft.world.entity.Mob mob : List.of(a, b, c, pig)) {
			mob.setNoAi(true);
		}
		// Entities on exactly the same spot are not pushed at all (vanilla bails below 0.01 blocks), so offset the pairs.
		b.setPos(a.getX() + 0.3, a.getY(), a.getZ());
		pig.setPos(c.getX() + 0.3, c.getY(), c.getZ());
		helper.runBeforeTestEnd(() -> {
			a.discard();
			b.discard();
			c.discard();
			pig.discard();
		});
		helper.runAfterDelay(10, () -> {
			double golems = Math.max(a.getDeltaMovement().horizontalDistance(), b.getDeltaMovement().horizontalDistance());
			double mixed = Math.max(c.getDeltaMovement().horizontalDistance(), pig.getDeltaMovement().horizontalDistance());
			helper.assertTrue(mixed > 0.01, "a golem and a pig should push each other, sideways velocity " + mixed);
			helper.assertTrue(golems == 0.0, "two golems pushed each other, sideways velocity " + golems);
			helper.succeed();
		});
	}

	/** A chest another golem is heading to is passed over for the next best one, both as a source and as a destination. */
	@GameTest
	public void golemsPreferChestsNobodyElseIsHeadingTo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.clear();
		BlockPos near = copperChest(helper, new BlockPos(2, 1, 1));
		BlockPos far = copperChest(helper, new BlockPos(6, 1, 1));
		fill(helper, near, Items.IRON_INGOT, 8);
		fill(helper, far, Items.IRON_INGOT, 8);
		BlockPos nearIron = chest(helper, new BlockPos(2, 1, 6));
		BlockPos farIron = chest(helper, new BlockPos(6, 1, 6));
		label(level, nearIron, ChestLabel.exact(id(Items.IRON_INGOT)));
		label(level, farIron, ChestLabel.exact(id(Items.IRON_INGOT)));
		CopperGolem me = spawnGolem(helper, new BlockPos(1, 1, 3));
		CopperGolem other = spawnGolem(helper, new BlockPos(1, 1, 4));
		me.setNoAi(true);
		other.setNoAi(true);
		helper.runBeforeTestEnd(() -> {
			io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.clear();
			me.discard();
			other.discard();
		});
		Vec3 from = me.position();
		net.minecraft.world.phys.AABB area = Zones.toAABB(structureBox(helper));
		java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> copper = state -> state.is(net.minecraft.tags.BlockTags.COPPER_CHESTS);
		java.util.Set<BlockPos> none = java.util.Set.of();

		helper.assertValueEqual(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.findSource(level, from, copper,
				none(), none(), area, none, false).map(t -> t.pos()), Optional.of(near), "nobody heading anywhere: the nearest copper chest");
		io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claim(level, near, other);
		java.util.Set<BlockPos> claimed = io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claimedByOthers(level, me);
		helper.assertValueEqual(claimed, java.util.Set.of(near), "the other golem's claim is visible");
		helper.assertValueEqual(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.findSource(level, from, copper,
				none(), none(), area, claimed, false).map(t -> t.pos()), Optional.of(far), "the claimed chest is passed over");
		helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claimedByOthers(level, other).isEmpty(),
				"a golem's own claim does not count against it");

		ItemStack iron = new ItemStack(Items.IRON_INGOT, 8);
		helper.assertValueEqual(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.findDepositTarget(level, from, iron,
				ChestLabels::isLabelableChest, none(), none(), area, none).map(t -> t.pos()), Optional.of(nearIron), "nearest twin chest by default");
		io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claim(level, nearIron, other);
		helper.assertValueEqual(io.github.lnasser11.waybettercoppergolem.sorting.SortingEngine.findDepositTarget(level, from, iron,
				ChestLabels::isLabelableChest, none(), none(), area, io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claimedByOthers(level, me))
				.map(t -> t.pos()), Optional.of(farIron), "the twin chest another golem is heading to is passed over");
		io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claim(level, farIron, me);
		io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.release(other);
		helper.assertTrue(io.github.lnasser11.waybettercoppergolem.sorting.GolemClaims.claimedByOthers(level, me).isEmpty(), "released");
		helper.succeed();
	}

	private static java.util.Set<net.minecraft.core.GlobalPos> none() {
		return java.util.Set.of();
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
