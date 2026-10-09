package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Map;

/**
 * Tests written during the bug hunt (docs/BUG_HUNT.md). Each one encodes
 * the behavior the README promises; they failed on the tree the hunt
 * audited and pass since the fixes. Helpers are copies of the private ones
 * in {@link WbcgGameTests}.
 */
public final class WbcgBugHuntTests {
	private static final int GOLEM_TIMEOUT = 3000;

	/**
	 * README, Configuration: with {@code golems_require_zone} "golems outside
	 * every zone do nothing". The copper-chest pickup is blocked, but the
	 * reorganize pass still runs with the default settings: the golem takes
	 * the misplaced dirt out of the iron chest, and because deposits are
	 * blocked too, it then holds the stack for good.
	 */
	@GameTest(maxTicks = 1200)
	public void golemsRequireZoneStillLetsAZonelessGolemReorganize(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		WbcgConfig before = WbcgConfig.get();
		WbcgConfig.override(before.withGolemsRequireZone(true));
		BlockPos iron = chest(helper, new BlockPos(1, 1, 3), Items.IRON_INGOT, 10, Items.DIRT, 7);
		label(level, iron, ChestLabel.exact(id(Items.IRON_INGOT)));
		BlockPos dirt = chest(helper, new BlockPos(6, 1, 3));
		label(level, dirt, ChestLabel.exact(id(Items.DIRT)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		helper.runBeforeTestEnd(() -> {
			WbcgConfig.override(before);
			golem.discard();
		});
		helper.runAfterDelay(600, () -> {
			helper.assertValueEqual(count(level, iron, Items.DIRT), 7, "dirt left in the iron chest (outside every zone the golem must do nothing)");
			helper.assertTrue(golem.getMainHandItem().isEmpty(), "the golem carries nothing, but holds " + golem.getMainHandItem());
			helper.succeed();
		});
	}

	/**
	 * README, Learn: "Learn needs operator permission unless
	 * learn_requires_op is turned off". The zone screen's Learn button only
	 * checks that the player may edit the zone, so a non-operator owner gets
	 * a proposal (and cannot apply it: the command behind [Apply] is op-only).
	 */
	@GameTest
	public void zoneScreenLearnButtonIgnoresLearnRequiresOp(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		WbcgConfig before = WbcgConfig.get();
		WbcgConfig.override(new WbcgConfig(before.toolItemId(), before.learnRadius(), true, before.golemCarrySize(),
				before.zonesRequireOpToCreate(), before.labelsRequireZoneOwnership(), before.golemsRequireZone(),
				before.maxZonesPerPlayer()));
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1));
		chest(helper, new BlockPos(3, 1, 3), Items.DIAMOND, 5);
		ServerPlayer owner = mockPlayer(helper);
		helper.runBeforeTestEnd(() -> {
			WbcgConfig.override(before);
			LearnSession.cancel(owner);
			Zones.remove(level, anchor);
		});
		helper.assertFalse(LearnSession.allowed(owner), "the mock player is not an operator, so the learn pass needs op");
		Zones.ZoneRef created = Zones.zoneForCopperChest(level, anchor, owner).orElseThrow();
		Zones.put(level, anchor, created.zone().withArea(structureBox(helper)));
		Zones.ZoneRef ref = Zones.zoneAt(level, anchor).orElseThrow();
		helper.assertTrue(ref.zone().access().isOwner(owner.getUUID()), "the player owns the zone");
		ZoneSettingsMenu menu = new ZoneSettingsMenu(1, ContainerLevelAccess.create(level, anchor), anchor,
				ZoneSettingsMenu.dataFor(ref, ZoneSettingsMenu.flagsFor(ref, owner)));
		LearnSession.resetRateLimit(owner);
		menu.clickMenuButton(owner, ZoneSettingsMenu.BUTTON_LEARN);
		boolean pending = LearnSession.cancel(owner);
		helper.assertFalse(pending, "a non-operator got a learn proposal from the zone screen with learn_requires_op on");
		helper.succeed();
	}

	/**
	 * README, Safety guarantees: "No item loss or duplication". A golem
	 * delivering a stack of item frames into the chest labeled for them is
	 * left with a remainder when the chest fills up; that chest is then
	 * remembered as wanting a frame, the remainder in hand counts as "the
	 * frame", and hanging it empties the hand: every frame but one is gone.
	 */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void hangingAFrameOutOfAStackInHandLosesTheRest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos anchor = copperChest(helper, new BlockPos(1, 1, 1)); // empty; the zone needs a copper chest at its anchor
		Object[] contents = new Object[27 * 2];
		for (int i = 0; i < 26; i++) {
			contents[2 * i] = Items.ITEM_FRAME;
			contents[2 * i + 1] = 64;
		}
		contents[52] = Items.ITEM_FRAME;
		contents[53] = 10;
		BlockPos frameChest = chest(helper, new BlockPos(6, 1, 3), contents);
		label(level, frameChest, ChestLabel.exact(id(Items.ITEM_FRAME)));
		Zones.put(level, anchor, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withHangFrames(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		golem.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.ITEM_FRAME, 64));
		golem.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		int total = 26 * 64 + 10 + 64;
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, anchor);
			framesInRoom(helper).forEach(ItemFrame::discard);
			golem.discard();
		});
		helper.failIfEver(() -> helper.assertValueEqual(framesEverywhere(helper, golem, frameChest), total,
				"item frames in the chest + in hand + hung (frame and shown item) + dropped"));
		helper.succeedWhen(() -> {
			// The chest fills up (27 full stacks) and the ten left over stay in hand as ordinary cargo: with nothing
			// else accepting frames the golem keeps them, and a remainder is never mistaken for a frame to hang.
			helper.assertValueEqual(count(level, frameChest, Items.ITEM_FRAME), 27 * 64, "frames in the chest after the delivery");
			helper.assertValueEqual(golem.getMainHandItem().getCount(), 10, "frames left in hand");
			helper.assertTrue(framesOnFront(level, frameChest).isEmpty(), "no frame may be hung out of a stack of cargo");
			helper.assertValueEqual(framesEverywhere(helper, golem, frameChest), total, "no item frame was lost");
		});
	}

	/**
	 * A golem carrying a frame for the chest it last delivered into keeps
	 * targeting that chest without consulting its unreachable memory. When
	 * the way to the chest is closed after the frame was fetched (here: the
	 * chest is walled in, front face still free), it must give up like it
	 * does when the front is taken (README: "gives up and carries the frame
	 * back"), instead of re-targeting the same chest every tick for good.
	 */
	@GameTest(maxTicks = 1200)
	public void golemGivesUpOnAnUnreachableFrameChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.ITEM_FRAME, 3);
		BlockPos iron = chest(helper, new BlockPos(6, 1, 3), Items.IRON_INGOT, 10);
		label(level, iron, ChestLabel.exact(id(Items.IRON_INGOT)));
		// Seal the chest in a pocket (x=5 column, the ends of the x=6 pocket, three high, and a glass lid), so no
		// line of sight reaches it from anywhere a golem can stand; its front face at (6,1,2) stays air.
		for (int y = 1; y <= 3; y++) {
			for (int z = 1; z <= 5; z++) {
				helper.setBlock(new BlockPos(5, y, z), Blocks.GLASS);
			}
			helper.setBlock(new BlockPos(6, y, 1), Blocks.GLASS);
			helper.setBlock(new BlockPos(6, y, 5), Blocks.GLASS);
		}
		for (int z = 2; z <= 4; z++) {
			helper.setBlock(new BlockPos(6, 3, z), Blocks.GLASS);
		}
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withHangFrames(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		aware.wbcg$joinZoneAt(level, source);
		// The state right after a frame trip: one frame in hand, fetched from the copper chest, meant for the iron chest.
		golem.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.ITEM_FRAME, 1));
		golem.setGuaranteedDrop(EquipmentSlot.MAINHAND);
		aware.wbcg$setPendingFrameChest(iron);
		aware.wbcg$setFrameReturnChest(source);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			framesInRoom(helper).forEach(ItemFrame::discard);
			golem.discard();
		});
		helper.runAfterDelay(600, () -> {
			boolean stillTrying = iron.equals(aware.wbcg$pendingFrameChest()) && golem.getMainHandItem().is(Items.ITEM_FRAME);
			helper.assertFalse(stillTrying, "after 600 ticks the golem is still holding the frame for the walled-in chest");
			String where = "hand=" + golem.getMainHandItem() + " pending=" + aware.wbcg$pendingFrameChest() + " return=" + aware.wbcg$frameReturnChest()
					+ " golemAt=" + golem.position() + " framesHung=" + framesInRoom(helper).size() + " framesOnIronFront=" + framesOnFront(level, iron).size()
					+ " framesInIron=" + count(level, iron, Items.ITEM_FRAME) + " ironIngots=" + count(level, iron, Items.IRON_INGOT)
					+ " dropped=" + level.getEntitiesOfClass(ItemEntity.class, Zones.toAABB(structureBox(helper)).inflate(1)).size();
			helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 4, "the frame went back into the copper chest (" + where + ")");
			helper.succeed();
		});
	}

	/**
	 * README, Golems stay inside: a golem "pushed, falls or is teleported
	 * out walks back to the nearest spot inside". With "Idle golems perch"
	 * on, a golem that lands on top of a chest outside its box is treated as
	 * perched: the walk-back target set once a second is erased every tick,
	 * so it stands on that chest for good.
	 */
	@GameTest(maxTicks = 1200)
	public void confinedGolemPerchedOnAChestOutsideItsZoneNeverWalksBack(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		for (int z = 1; z <= 6; z++) {
			if (z == 3 || z == 4) {
				continue; // a doorway in the inner wall at x=5
			}
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(new BlockPos(5, y, z), Blocks.GLASS);
			}
		}
		// The zone's only copper chest, empty and sealed in glass (a storage room behind a wall the golem cannot
		// pass): the transport behavior finds it unreachable and idles, so only the walk-back rule can bring the
		// golem home. (With a reachable copper chest the transport behavior itself walks the golem back to it.)
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 1));
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(new BlockPos(2, y, 1), Blocks.GLASS);
			helper.setBlock(new BlockPos(2, y, 2), Blocks.GLASS);
			helper.setBlock(new BlockPos(1, y, 2), Blocks.GLASS);
		}
		helper.setBlock(new BlockPos(1, 3, 1), Blocks.GLASS);
		BoundingBox west = BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(4, 7, 7)));
		Zones.put(level, source, new Zone(west, ZoneSettings.DEFAULT.withStayInside(true).withPerchIdle(true)));
		BlockPos outsideChest = chest(helper, new BlockPos(6, 1, 3));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		ZoneAwareGolem aware = (ZoneAwareGolem) golem;
		aware.wbcg$joinZoneAt(level, source);
		helper.assertTrue(source.equals(aware.wbcg$homeZoneAnchor()), "the golem belongs to the west zone");
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		net.minecraft.world.phys.Vec3 onTop = helper.absoluteVec(new net.minecraft.world.phys.Vec3(6.5, 1.875, 3.5));
		helper.runAfterDelay(5, () -> {
			golem.teleportTo(onTop.x, onTop.y, onTop.z);
			helper.assertTrue(outsideChest.equals(golem.blockPosition()), "the golem stands on the chest outside the zone, at " + golem.blockPosition());
		});
		helper.runAfterDelay(600, () -> {
			helper.assertTrue(west.isInside(golem.blockPosition()),
					"600 ticks later the golem is still outside its zone at " + golem.blockPosition() + " (perched=" + aware.wbcg$isPerched(level)
							+ ", cooldown=" + golem.getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS) + ")");
			helper.succeed();
		});
	}

	/**
	 * README, Idle golems perch: a perched golem "stands there like a statue:
	 * no strolling, no wandering" until a copper chest changes. Vanilla
	 * re-checks every copper chest after each 7 s cooldown, empty or not, so
	 * without a rule of its own the golem climbs down every few seconds to
	 * look into the empty copper chest and comes back.
	 */
	@GameTest(maxTicks = 1600)
	public void perchedGolemStaysPutNextToAnEmptyCopperChest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3)); // empty, in plain reach
		BlockPos target = chest(helper, new BlockPos(6, 1, 3));
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
				.thenExecuteFor(700, () -> helper.assertTrue(aware.wbcg$isPerched(level), "golem left its perch, at " + golem.blockPosition()))
				.thenSucceed();
	}

	/** A perch the golem cannot climb onto (a chest two blocks up) must not be chosen, or the golem paces under it for good. */
	@GameTest(maxTicks = 1200)
	public void idleGolemIsNotSentTowardAPerchItCannotReach(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 1));
		helper.setBlock(new BlockPos(6, 1, 6), Blocks.GLASS);
		helper.setBlock(new BlockPos(6, 2, 6), Blocks.GLASS);
		BlockPos high = chest(helper, new BlockPos(6, 3, 6)); // free top, two blocks of air above, no way up
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withPerchIdle(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 3));
		((ZoneAwareGolem) golem).wbcg$joinZoneAt(level, source);
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			golem.discard();
		});
		helper.failIfEver(() -> helper.assertFalse(golem.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
				.map(walk -> walk.getTarget().currentBlockPosition().equals(high.above())).orElse(false),
				"the golem was sent toward the perch it cannot reach"));
		helper.runAfterDelay(800, helper::succeed);
	}

	/**
	 * Frames in a lived-in room: a double chest facing east, labeled by the
	 * learn pass (explicit), frames mixed with ordinary items in the copper
	 * chest, perch on. The golem delivers, fetches a frame and hangs it on
	 * the half it delivered into.
	 */
	@GameTest(maxTicks = GOLEM_TIMEOUT)
	public void golemHangsAFrameOnADoubleChestInARealisticRoom(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		clearZonesAround(helper);
		buildRoom(helper);
		BlockPos source = copperChest(helper, new BlockPos(1, 1, 3));
		fill(helper, source, Items.ITEM_FRAME, 4, Items.IRON_INGOT, 16);
		net.minecraft.world.level.block.state.BlockState east = Blocks.CHEST.defaultBlockState()
				.setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.EAST);
		helper.setBlock(new BlockPos(5, 1, 2), east.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.LEFT));
		helper.setBlock(new BlockPos(5, 1, 3), east.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
				net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
		BlockPos lower = helper.absolutePos(new BlockPos(5, 1, 2));
		BlockPos upper = helper.absolutePos(new BlockPos(5, 1, 3));
		helper.getBlockEntity(new BlockPos(5, 1, 2), ChestBlockEntity.class).setItem(0, new ItemStack(Items.IRON_INGOT, 10));
		LearnSession.resetRateLimit(mockPlayer(helper));
		label(level, lower, ChestLabel.exact(id(Items.IRON_INGOT)));
		Zones.put(level, source, new Zone(structureBox(helper), ZoneSettings.DEFAULT.withHangFrames(true).withPerchIdle(true)));
		CopperGolem golem = spawnGolem(helper, new BlockPos(3, 1, 5));
		helper.runBeforeTestEnd(() -> {
			Zones.remove(level, source);
			framesInRoom(helper).forEach(ItemFrame::discard);
			golem.discard();
		});
		helper.succeedWhen(() -> {
			List<ItemFrame> frames = framesInRoom(helper).stream().filter(frame -> frame.getDirection() == Direction.EAST).toList();
			helper.assertValueEqual(frames.size(), 1, "frames hung on the double chest's front");
			helper.assertTrue(frames.getFirst().getItem().is(Items.IRON_INGOT), "the frame shows an iron ingot");
			BlockPos support = ChestLabels.supportPos(frames.getFirst());
			helper.assertTrue(support.equals(lower) || support.equals(upper), "the frame hangs on the chest, not at " + support);
			helper.assertValueEqual(count(level, source, Items.ITEM_FRAME), 3, "one frame taken from the copper chest");
		});
	}

	// ---------------------------------------------------------------- helpers (copies of WbcgGameTests')

	private static int framesEverywhere(GameTestHelper helper, CopperGolem golem, BlockPos chest) {
		ServerLevel level = helper.getLevel();
		int inChest = count(level, chest, Items.ITEM_FRAME);
		int hung = 0;
		for (ItemFrame frame : framesInRoom(helper)) {
			hung += 1 + (frame.getItem().is(Items.ITEM_FRAME) ? frame.getItem().getCount() : 0);
		}
		ItemStack held = golem.getMainHandItem();
		int inHand = held.is(Items.ITEM_FRAME) ? held.getCount() : 0;
		int dropped = 0;
		for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, Zones.toAABB(structureBox(helper)).inflate(1))) {
			if (entity.getItem().is(Items.ITEM_FRAME)) {
				dropped += entity.getItem().getCount();
			}
		}
		return inChest + hung + inHand + dropped;
	}

	private static List<ItemFrame> framesOnFront(ServerLevel level, BlockPos chestAbs) {
		return ChestLabels.labelFrames(level, chestAbs).stream().filter(frame -> frame.getDirection() == Direction.NORTH).toList();
	}

	private static List<ItemFrame> framesInRoom(GameTestHelper helper) {
		return helper.getLevel().getEntitiesOfClass(ItemFrame.class, Zones.toAABB(structureBox(helper)).inflate(1));
	}

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

	private static int count(ServerLevel level, BlockPos abs, Item item) {
		if (!(level.getBlockEntity(abs) instanceof BaseContainerBlockEntity container)) {
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

	/** Fills a container's slots from 0 at the absolute position. */
	private static void fill(GameTestHelper helper, BlockPos abs, Object... itemsAndCounts) {
		if (helper.getLevel().getBlockEntity(abs) instanceof BaseContainerBlockEntity container) {
			int slot = 0;
			for (int i = 0; i < itemsAndCounts.length; i += 2) {
				container.setItem(slot++, new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
			}
			container.setChanged();
		}
	}

	private static BlockPos copperChest(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.COPPER_CHEST.asList().getFirst());
		return helper.absolutePos(relative);
	}

	private static BoundingBox structureBox(GameTestHelper helper) {
		return BoundingBox.fromCorners(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(7, 7, 7)));
	}

	private static void clearZonesAround(GameTestHelper helper) {
		BoundingBox mine = structureBox(helper);
		for (Map.Entry<BlockPos, Zone> entry : Zones.all(helper.getLevel()).entrySet()) {
			if (entry.getValue().area().intersects(mine)) {
				Zones.remove(helper.getLevel(), entry.getKey());
			}
		}
	}

	private static BlockPos chest(GameTestHelper helper, BlockPos relative, Object... itemsAndCounts) {
		helper.setBlock(relative, Blocks.CHEST);
		ChestBlockEntity blockEntity = helper.getBlockEntity(relative, ChestBlockEntity.class);
		int slot = 0;
		for (int i = 0; i < itemsAndCounts.length; i += 2) {
			blockEntity.setItem(slot++, new ItemStack((Item) itemsAndCounts[i], (Integer) itemsAndCounts[i + 1]));
		}
		return helper.absolutePos(relative);
	}

	@SuppressWarnings("removal")
	private static ServerPlayer mockPlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	private static Identifier id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item);
	}
}
