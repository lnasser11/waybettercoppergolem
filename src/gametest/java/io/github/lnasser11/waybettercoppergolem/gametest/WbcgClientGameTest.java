package io.github.lnasser11.waybettercoppergolem.gametest;

import io.github.lnasser11.waybettercoppergolem.client.CategoryListScreen;
import io.github.lnasser11.waybettercoppergolem.client.CategoryTuningScreen;
import io.github.lnasser11.waybettercoppergolem.client.LabelPickerScreen;
import io.github.lnasser11.waybettercoppergolem.client.ListRow;
import io.github.lnasser11.waybettercoppergolem.client.SimulationScreen;
import io.github.lnasser11.waybettercoppergolem.client.WayBetterCopperGolemClient;
import io.github.lnasser11.waybettercoppergolem.client.ZoneAccessScreen;
import io.github.lnasser11.waybettercoppergolem.client.ZoneOverviewScreen;
import io.github.lnasser11.waybettercoppergolem.client.ZoneSettingsScreen;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.tool.ChestEditor;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;
import io.github.lnasser11.waybettercoppergolem.tuning.TuningNet;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneOverview;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSimulation;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a real client through the mod: builds a small chest room in a
 * fresh singleplayer world, opens every screen at the smallest GUI the
 * layouts are designed for (1080p at "Auto" scale, 480×270) and at the
 * default window (854×480, which is 427×240), checks that no widget is
 * off-screen, and walks through area mode with real sneak-right-clicks on
 * the floor. Screenshots land in {@code build/run/clientGameTest/screenshots}.
 *
 * <p>Run with {@code ./gradlew runClientGameTest} (under {@code xvfb-run}
 * on a headless machine).
 */
public final class WbcgClientGameTest implements FabricClientGameTest {
	private static final Identifier C_INGOTS = Identifier.fromNamespaceAndPath("c", "ingots");

	private BlockPos anchor = BlockPos.ZERO;
	private BlockPos ingotChest = BlockPos.ZERO;
	private BlockPos cobbleChest = BlockPos.ZERO;
	private BlockPos cornerA = BlockPos.ZERO;
	private BlockPos cornerB = BlockPos.ZERO;

	@Override
	public void runTest(ClientGameTestContext context) {
		TestInput input = context.getInput();
		context.runOnClient(mc -> mc.options.guiScale().set(0)); // Auto
		input.resizeWindow(1920, 1080); // → 480 × 270 at scale 4
		context.waitTicks(2);

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(true)
				.adjustSettings(settings -> {
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					settings.setAllowCommands(true);
				})
				.create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			buildRoom(server);
			context.waitTicks(10);
			input.lookAt(this.anchor);
			context.waitTicks(5);
			context.takeScreenshot("00_room");

			assertGuiSize(context, 480, 270);
			zoneScreenAndAreaMode(context, server, "480x270");
			pickers(context, server, "480x270");
			otherScreens(context, server, "480x270");

			// ---- the default window: 854 × 480 at Auto is 427 × 240
			input.resizeWindow(854, 480);
			context.waitTicks(2);
			assertGuiSize(context, 427, 240);
			openZoneScreen(context, server, "427x240");
			accessScreen(context, "427x240");
			pressEscape(context);
			openChestPicker(context, server, "427x240");
			pressEscape(context);
			openOverview(context, server, "427x240");
			pressEscape(context);
			context.setScreen(CategoryListScreen::new);
			context.waitTicks(5);
			context.takeScreenshot("categories_427x240");
			assertWidgetsOnScreen(context, "categories 427x240");
			pressEscape(context);

			// ---- a roomy GUI: 1080p at scale 2 is 960 × 540
			input.resizeWindow(1920, 1080);
			context.runOnClient(mc -> mc.options.guiScale().set(2));
			context.waitTicks(2);
			assertGuiSize(context, 960, 540);
			openChestPicker(context, server, "960x540");
			pressEscape(context);
			openZoneScreen(context, server, "960x540");
			pressEscape(context);
			context.runOnClient(mc -> mc.options.guiScale().set(0));
		}
	}

	// ---------------------------------------------------------------- the room

	/** A copper chest (the zone anchor) and two chests next to the player, on a stone floor. */
	private void buildRoom(TestServerContext server) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			ServerLevel level = player.level();
			BlockPos base = player.blockPosition();
			for (int dx = -2; dx <= 8; dx++) {
				for (int dz = -5; dz <= 5; dz++) {
					level.setBlock(base.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
					for (int dy = 0; dy <= 3; dy++) {
						level.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			this.anchor = base.offset(4, 0, 0);
			this.ingotChest = base.offset(4, 0, 2);
			this.cobbleChest = base.offset(4, 0, -2);
			// Corner markers at eye level and within reach, so the crosshair lands on them exactly.
			this.cornerA = base.offset(2, 1, 3);
			this.cornerB = base.offset(3, 1, -3);
			level.setBlock(this.cornerA, Blocks.STONE_BRICKS.defaultBlockState(), 3);
			level.setBlock(this.cornerB, Blocks.STONE_BRICKS.defaultBlockState(), 3);
			level.setBlock(this.anchor, Blocks.COPPER_CHEST.asList().getFirst().defaultBlockState(), 3);
			level.setBlock(this.ingotChest, Blocks.CHEST.defaultBlockState(), 3);
			level.setBlock(this.cobbleChest, Blocks.CHEST.defaultBlockState(), 3);
			fill(level, this.anchor, new ItemStack(Items.IRON_INGOT, 20), new ItemStack(Items.COBBLESTONE, 30),
					new ItemStack(Items.GOLD_INGOT, 5));
			fill(level, this.ingotChest, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 12));
			fill(level, this.cobbleChest, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.IRON_INGOT, 3));
			BlockState cobbleState = level.getBlockState(this.cobbleChest);
			ChestLabels.setExplicit(level, this.cobbleChest, cobbleState,
					List.of(ChestLabel.exact(BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE))));
			player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(Items.FEATHER));
		});
	}

	private static void fill(ServerLevel level, BlockPos pos, ItemStack... stacks) {
		if (level.getBlockEntity(pos) instanceof BaseContainerBlockEntity container) {
			for (int i = 0; i < stacks.length; i++) {
				container.setItem(i, stacks[i]);
			}
			container.setChanged();
		}
	}

	private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	// ---------------------------------------------------------------- zone screen + area mode

	private void zoneScreenAndAreaMode(ClientGameTestContext context, TestServerContext server, String size) {
		openZoneScreen(context, server, size);

		// The reach stepper: one press of "+" must show up in the label and on the server.
		context.clickScreenButton("waybettercoppergolem.settings.reach_up");
		context.waitTicks(5);
		int reach = server.computeOnServer(s -> Zones.settingsAt(player(s).level(), this.anchor).verticalReach());
		assertEquals(ZoneSettings.DEFAULT_VERTICAL_REACH + 1, reach, "zone reach after one press of +");
		assertTrue(context.computeOnClient(mc -> mc.gui.screen() != null && mc.gui.screen().children().stream()
						.anyMatch(w -> w instanceof AbstractWidget widget
								&& widget.getMessage().getString().equals("Reach: " + (ZoneSettings.DEFAULT_VERTICAL_REACH + 1)))),
				"the reach label shows the new value");
		context.clickScreenButton("waybettercoppergolem.settings.reach_down");
		context.waitTicks(5);

		// "Set area with the tool…" closes the screen and starts area mode on the server.
		context.clickScreenButton("waybettercoppergolem.settings.set_area");
		context.waitFor(mc -> mc.gui.screen() == null);
		context.waitTicks(5);
		assertEquals(1, WayBetterCopperGolemClient.areaModeStep(), "client area mode step after Set area");
		assertTrue(server.computeOnServer(s -> LabelTool.inAreaMode(player(s))), "server in area mode");
		context.takeScreenshot("area_1_hud_" + size);

		// First corner: a plain block, not a chest. Before the fix this cancelled area mode.
		BlockPos first = sneakRightClick(context, this.cornerA);
		assertTrue(server.computeOnServer(s -> LabelTool.inAreaMode(player(s))),
				"still in area mode after the first corner (a non-chest block)");
		assertEquals(2, WayBetterCopperGolemClient.areaModeStep(), "client area mode step after corner 1");
		context.takeScreenshot("area_2_first_corner_" + size);

		// Second corner → the zone's area is the box spanned by both corners (plus the anchor).
		BlockPos second = sneakRightClick(context, this.cornerB);
		assertEquals(0, WayBetterCopperGolemClient.areaModeStep(), "client area mode step after corner 2");
		assertTrue(server.computeOnServer(s -> !LabelTool.inAreaMode(player(s))), "area mode over");
		BoundingBox expected = Zone.areaFromCorners(first, second).encapsulate(this.anchor);
		BoundingBox actual = server.computeOnServer(s -> {
			Map<BlockPos, Zone> zones = Zones.all(player(s).level());
			Zone zone = zones.get(this.anchor);
			if (zone == null) {
				throw new AssertionError("no zone at the anchor after area mode; zones: " + zones.keySet());
			}
			return zone.area();
		});
		assertTrue(sameBox(expected, actual), "zone area " + describe(actual) + " should be " + describe(expected));
		context.waitTicks(10);
		context.takeScreenshot("area_3_done_" + size);

		// Reset area asks for a second click: the first only arms the button.
		openZoneScreen(context, server, size + "_before_reset");
		accessScreen(context, size);
		context.clickScreenButton("waybettercoppergolem.settings.reset_area");
		context.waitTicks(5);
		assertTrue(sameBox(actual, server.computeOnServer(s -> Zones.all(player(s).level()).get(this.anchor).area())),
				"one click on Reset area must not reset the area");
		assertTrue(context.computeOnClient(mc -> mc.gui.screen() != null && mc.gui.screen().children().stream()
						.anyMatch(w -> w instanceof AbstractWidget widget && widget.getMessage().getString().equals("Confirm"))),
				"the button reads Confirm after the first click");
		context.takeScreenshot("area_4_confirm_" + size);
		context.clickScreenButton("waybettercoppergolem.confirm");
		context.waitTicks(5);
		assertTrue(sameBox(Zone.defaultArea(this.anchor), server.computeOnServer(s -> Zones.all(player(s).level()).get(this.anchor).area())),
				"the second click resets the area");
		pressEscape(context);

		// Sneak-right-clicking the air outside area mode shows the clipboard, and does not open a screen
		// on a block. Clicking the air with nothing selected opens the picker (tested in pickers()).
	}

	private void openZoneScreen(ClientGameTestContext context, TestServerContext server, String size) {
		server.runOnServer(s -> ChestEditor.openZoneScreen(player(s), player(s).level(), this.anchor));
		context.waitForScreen(ZoneSettingsScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("zone_settings_" + size);
		assertWidgetsOnScreen(context, "zone settings " + size);
	}

	/** The access screen opens over the zone screen and Back returns to it. */
	private void accessScreen(ClientGameTestContext context, String size) {
		context.clickScreenButton("waybettercoppergolem.settings.access");
		context.waitForScreen(ZoneAccessScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("access_" + size);
		assertWidgetsOnScreen(context, "access " + size);
		context.clickScreenButton("gui.back");
		context.waitForScreen(ZoneSettingsScreen.class);
		context.waitTicks(2);
	}

	/**
	 * Looks at the block, holds sneak, right-clicks through the real input
	 * path, releases. Returns the block under the crosshair at the click.
	 */
	private static BlockPos sneakRightClick(ClientGameTestContext context, BlockPos pos) {
		TestInput input = context.getInput();
		input.lookAt(pos);
		context.waitTicks(2);
		BlockPos aimed = context.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult hit
				? hit.getBlockPos() : null);
		assertTrue(aimed != null, "the crosshair should be on a block when aiming at " + pos);
		assertTrue(pos.equals(aimed), "aimed at " + aimed + ", expected " + pos);
		input.holdShift();
		context.waitTicks(2);
		input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitTicks(10);
		input.releaseShift();
		context.waitTicks(5);
		return aimed;
	}

	// ---------------------------------------------------------------- pickers

	private void pickers(ClientGameTestContext context, TestServerContext server, String size) {
		openChestPicker(context, server, size);
		pressEscape(context);

		// The golem button inside the chest's own screen: open the chest for real, press the button,
		// and afterwards neither side may still think the chest menu is open.
		TestInput keys = context.getInput();
		keys.lookAt(this.ingotChest);
		context.waitTicks(2);
		keys.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitForScreen(ContainerScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("chest_with_golem_button_" + size);
		context.clickScreenButton("waybettercoppergolem.chest_button");
		context.waitForScreen(LabelPickerScreen.class);
		context.waitTicks(5);
		assertTrue(context.computeOnClient(mc -> mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu),
				"the client still has the chest menu open after the golem button");
		assertTrue(server.computeOnServer(s -> player(s).containerMenu == player(s).inventoryMenu),
				"the server still has the chest menu open after the golem button");
		pressEscape(context);

		// Sneak-right-click the sky with the tool → the clipboard picker (client side only).
		TestInput input = context.getInput();
		input.lookAt(0, -90);
		context.waitTicks(2);
		input.holdShift();
		context.waitTicks(2);
		input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		context.waitForScreen(LabelPickerScreen.class);
		input.releaseShift();
		context.waitTicks(5);
		context.takeScreenshot("picker_clipboard_" + size);
		assertWidgetsOnScreen(context, "clipboard picker " + size);
		pressEscape(context);
	}

	private void openChestPicker(ClientGameTestContext context, TestServerContext server, String size) {
		server.runOnServer(s -> ChestEditor.open(player(s), this.ingotChest));
		context.waitForScreen(LabelPickerScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("picker_chest_" + size);
		assertWidgetsOnScreen(context, "chest picker " + size);
	}

	// ---------------------------------------------------------------- the rest

	private void otherScreens(ClientGameTestContext context, TestServerContext server, String size) {
		server.runOnServer(s -> TuningNet.open(player(s), C_INGOTS));
		context.waitForScreen(CategoryTuningScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("tuning_" + size);
		assertWidgetsOnScreen(context, "tuning " + size);
		pressEscape(context);

		openOverview(context, server, size);
		pressEscape(context);

		server.runOnServer(s -> {
			ServerLevel level = player(s).level();
			ZoneSimulation.send(player(s), level, Zones.zoneForCopperChest(level, this.anchor));
		});
		context.waitForScreen(SimulationScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("simulation_" + size);
		assertWidgetsOnScreen(context, "simulation " + size);
		pressEscape(context);

		context.setScreen(CategoryListScreen::new);
		context.waitTicks(5);
		context.takeScreenshot("categories_" + size);
		assertWidgetsOnScreen(context, "categories " + size);
		pressEscape(context);
	}

	private void openOverview(ClientGameTestContext context, TestServerContext server, String size) {
		server.runOnServer(s -> {
			ServerLevel level = player(s).level();
			ZoneOverview.send(player(s), level, Zones.zoneForCopperChest(level, this.anchor));
		});
		context.waitForScreen(ZoneOverviewScreen.class);
		context.waitTicks(5);
		context.takeScreenshot("overview_" + size);
		assertWidgetsOnScreen(context, "overview " + size);
	}

	// ---------------------------------------------------------------- checks

	private static void pressEscape(ClientGameTestContext context) {
		context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
		context.waitFor(mc -> mc.gui.screen() == null);
		context.waitTicks(2);
	}

	private static void assertGuiSize(ClientGameTestContext context, int width, int height) {
		int[] actual = context.computeOnClient(mc -> new int[] {
				mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
		assertTrue(actual[0] == width && actual[1] == height,
				"GUI size " + actual[0] + "x" + actual[1] + ", expected " + width + "x" + height);
	}

	/** Every visible widget of the current screen lies inside the screen, and no button clips its text. */
	private static void assertWidgetsOnScreen(ClientGameTestContext context, String what) {
		List<String> problems = context.computeOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<String> out = new ArrayList<>();
			if (screen == null) {
				out.add("no screen open");
				return out;
			}
			int count = 0;
			for (GuiEventListener child : screen.children()) {
				if (child instanceof AbstractWidget widget && widget.visible) {
					count++;
					if (widget.getX() < 0 || widget.getY() < 0 || widget.getRight() > screen.width
							|| widget.getBottom() > screen.height) {
						out.add(widget.getMessage().getString() + " at [" + widget.getX() + "," + widget.getY()
								+ " " + widget.getRight() + "," + widget.getBottom() + "] outside "
								+ screen.width + "x" + screen.height);
					}
					// Vanilla buttons scroll text wider than the button; list rows cut with an ellipsis instead.
					if (!(widget instanceof ListRow) && !(widget instanceof EditBox)
							&& mc.font.width(widget.getMessage()) > widget.getWidth() - 6) {
						out.add("'" + widget.getMessage().getString() + "' ("
								+ mc.font.width(widget.getMessage()) + "px) clipped in a " + widget.getWidth() + "px button");
					}
				}
			}
			if (count == 0) {
				out.add("no widgets on " + screen.getClass().getSimpleName());
			}
			return out;
		});
		assertTrue(problems.isEmpty(), what + ": " + problems);
	}

	private static boolean sameBox(BoundingBox a, BoundingBox b) {
		return a.minX() == b.minX() && a.minY() == b.minY() && a.minZ() == b.minZ()
				&& a.maxX() == b.maxX() && a.maxY() == b.maxY() && a.maxZ() == b.maxZ();
	}

	private static String describe(BoundingBox box) {
		return "[" + box.minX() + " " + box.minY() + " " + box.minZ() + " → "
				+ box.maxX() + " " + box.maxY() + " " + box.maxZ() + "]";
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void assertEquals(int expected, int actual, String what) {
		assertTrue(expected == actual, what + ": expected " + expected + ", got " + actual);
	}
}
