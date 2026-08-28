package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Settings panel for a sorting zone (a copper chest). No slots; the five
 * values sync to the client through vanilla data slots, and edits come back
 * through vanilla menu-button clicks, so no custom networking is involved.
 */
public class ZoneSettingsMenu extends AbstractContainerMenu {
	public static final int DATA_RADIUS = 0;
	public static final int DATA_REACH = 1;
	public static final int DATA_REORGANIZE = 2;
	public static final int DATA_TIDY = 3;
	public static final int DATA_DRY_RUN = 4;
	public static final int DATA_CARRY = 5;
	public static final int DATA_COUNT = 6;

	public static final int BUTTON_TOGGLE_REORGANIZE = 0;
	public static final int BUTTON_TOGGLE_TIDY = 1;
	public static final int BUTTON_TOGGLE_DRY_RUN = 2;
	/** buttonId = base + value, for slider-style settings. */
	public static final int BUTTON_RADIUS_BASE = 100;
	public static final int BUTTON_REACH_BASE = 200;
	public static final int BUTTON_CARRY_BASE = 300;

	private final ContainerLevelAccess access;
	private final ContainerData data;

	/** Client-side constructor; real values arrive via data-slot sync. */
	public ZoneSettingsMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
		this(containerId, ContainerLevelAccess.NULL, dataFor(ZoneSettings.DEFAULT));
	}

	public ZoneSettingsMenu(int containerId, ContainerLevelAccess access, ContainerData data) {
		super(WayBetterCopperGolem.ZONE_SETTINGS_MENU, containerId);
		this.access = access;
		this.data = data;
		this.addDataSlots(data);
	}

	public static ContainerData dataFor(ZoneSettings settings) {
		SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
		data.set(DATA_RADIUS, settings.searchRadius());
		data.set(DATA_REACH, settings.verticalReach());
		data.set(DATA_REORGANIZE, settings.reorganize() ? 1 : 0);
		data.set(DATA_TIDY, settings.tidyInside() ? 1 : 0);
		data.set(DATA_DRY_RUN, settings.dryRun() ? 1 : 0);
		data.set(DATA_CARRY, settings.carryAmount());
		return data;
	}

	public ZoneSettings settings() {
		return new ZoneSettings(
				this.data.get(DATA_RADIUS),
				this.data.get(DATA_REACH),
				this.data.get(DATA_REORGANIZE) != 0,
				this.data.get(DATA_TIDY) != 0,
				this.data.get(DATA_DRY_RUN) != 0,
				this.data.get(DATA_CARRY));
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		ZoneSettings current = settings();
		ZoneSettings updated;
		if (buttonId == BUTTON_TOGGLE_REORGANIZE) {
			updated = new ZoneSettings(current.searchRadius(), current.verticalReach(),
					!current.reorganize(), current.tidyInside(), current.dryRun(), current.carryAmount());
		} else if (buttonId == BUTTON_TOGGLE_TIDY) {
			updated = new ZoneSettings(current.searchRadius(), current.verticalReach(),
					current.reorganize(), !current.tidyInside(), current.dryRun(), current.carryAmount());
		} else if (buttonId == BUTTON_TOGGLE_DRY_RUN) {
			updated = new ZoneSettings(current.searchRadius(), current.verticalReach(),
					current.reorganize(), current.tidyInside(), !current.dryRun(), current.carryAmount());
		} else if (buttonId >= BUTTON_RADIUS_BASE && buttonId < BUTTON_RADIUS_BASE + 100) {
			updated = new ZoneSettings(buttonId - BUTTON_RADIUS_BASE, current.verticalReach(),
					current.reorganize(), current.tidyInside(), current.dryRun(), current.carryAmount());
		} else if (buttonId >= BUTTON_REACH_BASE && buttonId < BUTTON_REACH_BASE + 100) {
			updated = new ZoneSettings(current.searchRadius(), buttonId - BUTTON_REACH_BASE,
					current.reorganize(), current.tidyInside(), current.dryRun(), current.carryAmount());
		} else if (buttonId >= BUTTON_CARRY_BASE && buttonId < BUTTON_CARRY_BASE + 100) {
			updated = new ZoneSettings(current.searchRadius(), current.verticalReach(),
					current.reorganize(), current.tidyInside(), current.dryRun(), buttonId - BUTTON_CARRY_BASE);
		} else {
			return false;
		}
		this.data.set(DATA_RADIUS, updated.searchRadius());
		this.data.set(DATA_REACH, updated.verticalReach());
		this.data.set(DATA_REORGANIZE, updated.reorganize() ? 1 : 0);
		this.data.set(DATA_TIDY, updated.tidyInside() ? 1 : 0);
		this.data.set(DATA_DRY_RUN, updated.dryRun() ? 1 : 0);
		this.data.set(DATA_CARRY, updated.carryAmount());
		this.access.execute((level, pos) -> {
			if (level.getBlockState(pos).is(BlockTags.COPPER_CHESTS)) {
				BlockEntity blockEntity = level.getBlockEntity(pos);
				if (blockEntity != null) {
					Zones.store(blockEntity, updated);
				}
			}
		});
		return true;
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
}
