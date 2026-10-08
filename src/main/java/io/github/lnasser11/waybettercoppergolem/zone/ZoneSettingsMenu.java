package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Settings panel for a sorting zone, opened from any copper chest inside
 * it. No slots; the values sync to the client through vanilla data slots
 * and edits come back through vanilla menu-button clicks, so no custom
 * networking is involved. Edits are written to the zone's anchor, which
 * may be a different copper chest than the one that was clicked.
 */
public class ZoneSettingsMenu extends AbstractContainerMenu {
	public static final int DATA_REORGANIZE = 0;
	public static final int DATA_TIDY = 1;
	public static final int DATA_DRY_RUN = 2;
	public static final int DATA_ANCHOR_X = 3;
	public static final int DATA_ANCHOR_Y = 4;
	public static final int DATA_ANCHOR_Z = 5;
	public static final int DATA_MIN_X = 6;
	public static final int DATA_MIN_Y = 7;
	public static final int DATA_MIN_Z = 8;
	public static final int DATA_MAX_X = 9;
	public static final int DATA_MAX_Y = 10;
	public static final int DATA_MAX_Z = 11;
	public static final int DATA_REACH = 12;
	public static final int DATA_COUNT = 13;

	public static final int BUTTON_TOGGLE_REORGANIZE = 0;
	public static final int BUTTON_TOGGLE_TIDY = 1;
	public static final int BUTTON_TOGGLE_DRY_RUN = 2;
	/** Run the learn pass over the zone's area. */
	public static final int BUTTON_LEARN = 3;
	/** Enter area mode: the next two tool clicks set the zone's corners. */
	public static final int BUTTON_SET_AREA = 4;
	/** Back to the default box around the anchor. */
	public static final int BUTTON_RESET_AREA = 5;
	/** Draw the area outline. */
	public static final int BUTTON_SHOW_AREA = 6;
	/** Send the zone overview (every chest, problems first). */
	public static final int BUTTON_OVERVIEW = 7;
	/** Send the simulation (what golems would do with the copper chests now). */
	public static final int BUTTON_SIMULATE = 8;
	/** Make this zone's settings the default for new zones in this world (operators). */
	public static final int BUTTON_SAVE_DEFAULTS = 9;
	/** Give every zone in this dimension these settings (operators). */
	public static final int BUTTON_APPLY_ALL = 10;
	/** Vertical reach one block lower / higher. */
	public static final int BUTTON_REACH_DOWN = 11;
	public static final int BUTTON_REACH_UP = 12;

	private final ContainerLevelAccess access;
	private final ContainerData data;
	private final BlockPos anchor;

	/** Client-side constructor; real values arrive via data-slot sync. */
	public ZoneSettingsMenu(int containerId, Inventory inventory) {
		this(containerId, ContainerLevelAccess.NULL, BlockPos.ZERO,
				dataFor(new Zones.ZoneRef(BlockPos.ZERO, Zone.defaultAround(BlockPos.ZERO))));
	}

	public ZoneSettingsMenu(int containerId, ContainerLevelAccess access, BlockPos anchor, ContainerData data) {
		super(WayBetterCopperGolem.ZONE_SETTINGS_MENU, containerId);
		this.access = access;
		this.anchor = anchor.immutable();
		this.data = data;
		this.addDataSlots(data);
	}

	public static ContainerData dataFor(Zones.ZoneRef ref) {
		SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
		write(data, ref);
		return data;
	}

	private static void write(ContainerData data, Zones.ZoneRef ref) {
		ZoneSettings settings = ref.settings();
		BoundingBox area = ref.area();
		data.set(DATA_REORGANIZE, settings.reorganize() ? 1 : 0);
		data.set(DATA_TIDY, settings.tidyInside() ? 1 : 0);
		data.set(DATA_DRY_RUN, settings.dryRun() ? 1 : 0);
		data.set(DATA_ANCHOR_X, ref.anchor().getX());
		data.set(DATA_ANCHOR_Y, ref.anchor().getY());
		data.set(DATA_ANCHOR_Z, ref.anchor().getZ());
		data.set(DATA_MIN_X, area.minX());
		data.set(DATA_MIN_Y, area.minY());
		data.set(DATA_MIN_Z, area.minZ());
		data.set(DATA_MAX_X, area.maxX());
		data.set(DATA_MAX_Y, area.maxY());
		data.set(DATA_MAX_Z, area.maxZ());
		data.set(DATA_REACH, settings.verticalReach());
	}

	public ZoneSettings settings() {
		return new ZoneSettings(this.data.get(DATA_REACH),
				this.data.get(DATA_REORGANIZE) != 0,
				this.data.get(DATA_TIDY) != 0,
				this.data.get(DATA_DRY_RUN) != 0);
	}

	public BlockPos anchorPos() {
		return new BlockPos(this.data.get(DATA_ANCHOR_X), this.data.get(DATA_ANCHOR_Y), this.data.get(DATA_ANCHOR_Z));
	}

	public BoundingBox area() {
		return new BoundingBox(
				this.data.get(DATA_MIN_X), this.data.get(DATA_MIN_Y), this.data.get(DATA_MIN_Z),
				this.data.get(DATA_MAX_X), this.data.get(DATA_MAX_Y), this.data.get(DATA_MAX_Z));
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return false;
		}
		return this.access.evaluate((level, clicked) -> {
			if (!(level instanceof ServerLevel serverLevel)) {
				return false;
			}
			Zones.ZoneRef ref = Zones.zoneAt(serverLevel, clicked)
					.orElseGet(() -> Zones.zoneForCopperChest(serverLevel, clicked));
			Zone zone = ref.zone();
			ZoneSettings current = zone.settings();
			switch (buttonId) {
				case BUTTON_TOGGLE_REORGANIZE -> zone = zone.withSettings(current.withReorganize(!current.reorganize()));
				case BUTTON_TOGGLE_TIDY -> zone = zone.withSettings(current.withTidyInside(!current.tidyInside()));
				case BUTTON_TOGGLE_DRY_RUN -> zone = zone.withSettings(current.withDryRun(!current.dryRun()));
				case BUTTON_REACH_DOWN -> zone = zone.withSettings(current.withVerticalReach(current.verticalReach() - 1));
				case BUTTON_REACH_UP -> zone = zone.withSettings(current.withVerticalReach(current.verticalReach() + 1));
				case BUTTON_RESET_AREA -> zone = zone.withArea(Zone.defaultArea(ref.anchor()));
				case BUTTON_LEARN -> {
					if (!LearnSession.allowed(serverPlayer)) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.learn.not_allowed"));
					} else {
						LearnSession.preview(serverPlayer, serverLevel, zone.area(),
								Component.translatable("waybettercoppergolem.learn.scope.zone", Zones.describeArea(zone.area())),
								false);
					}
					return true;
				}
				case BUTTON_SET_AREA -> {
					LabelTool.beginAreaSelection(serverPlayer, serverLevel, ref.anchor());
					return true;
				}
				case BUTTON_SHOW_AREA -> {
					Zones.showOutline(serverPlayer, serverLevel, zone.area());
					return true;
				}
				case BUTTON_OVERVIEW -> {
					ZoneOverview.send(serverPlayer, serverLevel, ref);
					return true;
				}
				case BUTTON_SIMULATE -> {
					ZoneSimulation.send(serverPlayer, serverLevel, ref);
					return true;
				}
				case BUTTON_SAVE_DEFAULTS -> {
					if (!net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(serverPlayer.permissions())) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.not_allowed"));
						return true;
					}
					Zones.setDefaults(serverLevel, current);
					serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.saved",
							Zones.describe(current)));
					return true;
				}
				case BUTTON_APPLY_ALL -> {
					if (!net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(serverPlayer.permissions())) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.not_allowed"));
						return true;
					}
					int changed = Zones.applyToAll(serverLevel, current);
					serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.applied",
							changed, Zones.describe(current)));
					return true;
				}
				default -> {
					return false;
				}
			}
			Zones.put(serverLevel, ref.anchor(), zone);
			write(this.data, new Zones.ZoneRef(ref.anchor(), zone));
			if (buttonId == BUTTON_RESET_AREA) {
				Zones.showOutline(serverPlayer, serverLevel, zone.area());
			}
			return true;
		}, false);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.access.evaluate((level, pos) -> level.getBlockState(pos).is(BlockTags.COPPER_CHESTS)
				&& player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0, true);
	}

	/** The zone's anchor, as known when the menu was opened (server side). */
	public BlockPos anchor() {
		return this.anchor;
	}
}
