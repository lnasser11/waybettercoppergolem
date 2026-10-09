package io.github.lnasser11.waybettercoppergolem.zone;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.learn.LearnSession;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
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

import java.util.Optional;

/**
 * Settings panel for a sorting zone, opened from any copper chest inside
 * it. No slots; the values sync to the client through vanilla data slots
 * and edits come back through vanilla menu-button clicks, so no custom
 * networking is involved. Edits are written to the zone's anchor, which
 * may be a different copper chest than the one that was clicked.
 *
 * <p>Every edit is checked against the zone's {@link ZoneAccess} on the
 * server; the {@link #DATA_FLAGS} slot tells the client whether this
 * player may edit (and whether they are an operator) so the screen can
 * disable what would be refused.
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
	public static final int DATA_STAY_INSIDE = 13;
	public static final int DATA_HANG_FRAMES = 14;
	/** Bit field: {@link #FLAG_CAN_EDIT}, {@link #FLAG_OPERATOR}. */
	public static final int DATA_FLAGS = 15;
	public static final int DATA_PERCH_IDLE = 16;
	public static final int DATA_COUNT = 17;

	public static final int FLAG_CAN_EDIT = 1;
	public static final int FLAG_OPERATOR = 2;

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
	public static final int BUTTON_TOGGLE_STAY_INSIDE = 13;
	public static final int BUTTON_TOGGLE_HANG_FRAMES = 14;
	/** Become the zone's owner (operators). */
	public static final int BUTTON_TAKE_OVER = 15;
	public static final int BUTTON_TOGGLE_PERCH_IDLE = 16;

	private final ContainerLevelAccess access;
	private final ContainerData data;
	private final BlockPos anchor;

	/** Client-side constructor; real values arrive via data-slot sync. */
	public ZoneSettingsMenu(int containerId, Inventory inventory) {
		this(containerId, ContainerLevelAccess.NULL, BlockPos.ZERO,
				dataFor(new Zones.ZoneRef(BlockPos.ZERO, Zone.defaultAround(BlockPos.ZERO)), 0));
	}

	public ZoneSettingsMenu(int containerId, ContainerLevelAccess access, BlockPos anchor, ContainerData data) {
		super(WayBetterCopperGolem.ZONE_SETTINGS_MENU, containerId);
		this.access = access;
		this.anchor = anchor.immutable();
		this.data = data;
		this.addDataSlots(data);
	}

	/** Data for a player who may edit everything (tests and tools). */
	public static ContainerData dataFor(Zones.ZoneRef ref) {
		return dataFor(ref, FLAG_CAN_EDIT | FLAG_OPERATOR);
	}

	public static ContainerData dataFor(Zones.ZoneRef ref, int flags) {
		SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
		write(data, ref);
		data.set(DATA_FLAGS, flags);
		return data;
	}

	/** The flags for this player on this zone. */
	public static int flagsFor(Zones.ZoneRef ref, ServerPlayer player) {
		int flags = 0;
		if (ZoneAccess.canEdit(ref, player)) {
			flags |= FLAG_CAN_EDIT;
		}
		if (ZoneAccess.isOperator(player)) {
			flags |= FLAG_OPERATOR;
		}
		return flags;
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
		data.set(DATA_STAY_INSIDE, settings.stayInside() ? 1 : 0);
		data.set(DATA_HANG_FRAMES, settings.hangFrames() ? 1 : 0);
		data.set(DATA_PERCH_IDLE, settings.perchIdle() ? 1 : 0);
	}

	public ZoneSettings settings() {
		return new ZoneSettings(this.data.get(DATA_REACH),
				this.data.get(DATA_REORGANIZE) != 0,
				this.data.get(DATA_TIDY) != 0,
				this.data.get(DATA_DRY_RUN) != 0,
				this.data.get(DATA_STAY_INSIDE) != 0,
				this.data.get(DATA_HANG_FRAMES) != 0,
				this.data.get(DATA_PERCH_IDLE) != 0);
	}

	public BlockPos anchorPos() {
		return new BlockPos(this.data.get(DATA_ANCHOR_X), this.data.get(DATA_ANCHOR_Y), this.data.get(DATA_ANCHOR_Z));
	}

	public BoundingBox area() {
		return new BoundingBox(
				this.data.get(DATA_MIN_X), this.data.get(DATA_MIN_Y), this.data.get(DATA_MIN_Z),
				this.data.get(DATA_MAX_X), this.data.get(DATA_MAX_Y), this.data.get(DATA_MAX_Z));
	}

	/** Whether the viewing player may change this zone (as the server decided when the menu opened). */
	public boolean canEdit() {
		return (this.data.get(DATA_FLAGS) & FLAG_CAN_EDIT) != 0;
	}

	public boolean isOperator() {
		return (this.data.get(DATA_FLAGS) & FLAG_OPERATOR) != 0;
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
			// Creates the zone if the chest has none, and claims a zone nobody owns yet.
			Optional<Zones.ZoneRef> found = Zones.zoneForCopperChest(serverLevel, clicked, serverPlayer);
			if (found.isEmpty()) {
				return true;
			}
			Zones.ZoneRef ref = found.get();
			Zone zone = ref.zone();
			ZoneSettings current = zone.settings();
			boolean editor = ZoneAccess.canEdit(ref, serverPlayer);
			boolean operator = ZoneAccess.isOperator(serverPlayer);
			switch (buttonId) {
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
					if (!operator) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.not_allowed"));
						return true;
					}
					Zones.setDefaults(serverLevel, current);
					serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.saved",
							Zones.describe(current)));
					WayBetterCopperGolem.LOGGER.info("[zone] {} saved the world default zone settings: {}",
							ZoneAccess.nameOf(serverPlayer), Zones.describe(current).getString());
					return true;
				}
				case BUTTON_APPLY_ALL -> {
					if (!operator) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.not_allowed"));
						return true;
					}
					int changed = Zones.applyToAll(serverLevel, current);
					serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.defaults.applied",
							changed, Zones.describe(current)));
					WayBetterCopperGolem.LOGGER.info("[zone] {} applied {} to all {} zones of {}",
							ZoneAccess.nameOf(serverPlayer), Zones.describe(current).getString(), changed,
							serverLevel.dimension().identifier());
					return true;
				}
				case BUTTON_TAKE_OVER -> {
					if (!operator) {
						serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.access.denied"));
						return true;
					}
					zone = zone.withAccess(zone.access().withOwner(new NameAndId(serverPlayer.getGameProfile())));
					Zones.put(serverLevel, ref.anchor(), zone);
					write(this.data, new Zones.ZoneRef(ref.anchor(), zone));
					this.data.set(DATA_FLAGS, flagsFor(new Zones.ZoneRef(ref.anchor(), zone), serverPlayer));
					serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.access.took_over"));
					WayBetterCopperGolem.LOGGER.info("[zone] {} took over the zone at {}", ZoneAccess.nameOf(serverPlayer), ref.anchor());
					return true;
				}
				default -> {
				}
			}
			if (!editor) {
				serverPlayer.sendSystemMessage(Component.translatable("waybettercoppergolem.access.denied"));
				return true;
			}
			switch (buttonId) {
				case BUTTON_TOGGLE_REORGANIZE -> zone = zone.withSettings(current.withReorganize(!current.reorganize()));
				case BUTTON_TOGGLE_TIDY -> zone = zone.withSettings(current.withTidyInside(!current.tidyInside()));
				case BUTTON_TOGGLE_DRY_RUN -> zone = zone.withSettings(current.withDryRun(!current.dryRun()));
				case BUTTON_TOGGLE_STAY_INSIDE -> zone = zone.withSettings(current.withStayInside(!current.stayInside()));
				case BUTTON_TOGGLE_HANG_FRAMES -> zone = zone.withSettings(current.withHangFrames(!current.hangFrames()));
				case BUTTON_TOGGLE_PERCH_IDLE -> zone = zone.withSettings(current.withPerchIdle(!current.perchIdle()));
				case BUTTON_REACH_DOWN -> zone = zone.withSettings(current.withVerticalReach(current.verticalReach() - 1));
				case BUTTON_REACH_UP -> zone = zone.withSettings(current.withVerticalReach(current.verticalReach() + 1));
				case BUTTON_RESET_AREA -> zone = zone.withArea(Zone.defaultArea(ref.anchor()));
				case BUTTON_LEARN -> {
					LearnSession.preview(serverPlayer, serverLevel, zone.area(),
							Component.translatable("waybettercoppergolem.learn.scope.zone", Zones.describeArea(zone.area())),
							false);
					return true;
				}
				case BUTTON_SET_AREA -> {
					LabelTool.beginAreaSelection(serverPlayer, serverLevel, ref.anchor());
					return true;
				}
				default -> {
					return false;
				}
			}
			Zones.put(serverLevel, ref.anchor(), zone);
			write(this.data, new Zones.ZoneRef(ref.anchor(), zone));
			WayBetterCopperGolem.LOGGER.info("[zone] {} changed the zone at {}: {} · area {}",
					ZoneAccess.nameOf(serverPlayer), ref.anchor(), Zones.describe(zone.settings()).getString(),
					Zones.describeArea(zone.area()).getString());
			if (buttonId == BUTTON_RESET_AREA) {
				Zones.showOutline(serverPlayer, serverLevel, zone.area());
			}
			return true;
		}, false);
	}

	/**
	 * The "Trust…" payload: adds or removes a trusted player by name. Only
	 * the zone's editors may; the name must be an online player or one the
	 * server has seen before.
	 */
	public static void trust(ServerPlayer player, BlockPos anchor, String name, boolean add) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		Zone zone = Zones.all(level).get(anchor);
		if (zone == null) {
			return;
		}
		Zones.ZoneRef ref = new Zones.ZoneRef(anchor, zone);
		if (!ZoneAccess.canEdit(ref, player)) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.denied"));
			return;
		}
		String trimmed = name.trim();
		if (trimmed.isEmpty() || trimmed.length() > 16 || !trimmed.matches("[A-Za-z0-9_]+")) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.unknown_player", name));
			return;
		}
		Optional<NameAndId> target = resolve(level, trimmed);
		if (target.isEmpty()) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.unknown_player", trimmed));
			return;
		}
		trust(player, anchor, target.get(), add);
	}

	/** Trusts or untrusts a resolved player; the permission check is here too (tests call this directly). */
	public static void trust(ServerPlayer player, BlockPos anchor, NameAndId target, boolean add) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		Zone zone = Zones.all(level).get(anchor);
		if (zone == null || !ZoneAccess.canEdit(new Zones.ZoneRef(anchor, zone), player)) {
			player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.denied"));
			return;
		}
		ZoneAccess access = zone.access();
		if (add) {
			if (access.isOwner(target.id())) {
				return;
			}
			if (access.trusted().size() >= ZoneAccess.MAX_TRUSTED) {
				player.sendSystemMessage(Component.translatable("waybettercoppergolem.access.trust_full", ZoneAccess.MAX_TRUSTED));
				return;
			}
			access = access.withTrusted(target);
		} else {
			access = access.withoutTrusted(target.id());
		}
		Zones.put(level, anchor, zone.withAccess(access));
		player.sendSystemMessage(Component.translatable(add
				? "waybettercoppergolem.access.trusted" : "waybettercoppergolem.access.untrusted", target.name()));
		WayBetterCopperGolem.LOGGER.info("[zone] {} {} {} on the zone at {}", ZoneAccess.nameOf(player),
				add ? "trusted" : "no longer trusts", target.name(), anchor);
	}

	/** An online player by name, else the server's name cache (players it has seen). */
	private static Optional<NameAndId> resolve(ServerLevel level, String name) {
		ServerPlayer online = level.getServer().getPlayerList().getPlayerByName(name);
		if (online != null) {
			return Optional.of(new NameAndId(online.getGameProfile()));
		}
		try {
			return level.getServer().services().nameToIdCache().get(name);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
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
