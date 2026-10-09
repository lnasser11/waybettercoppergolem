package io.github.lnasser11.waybettercoppergolem;

import io.github.lnasser11.waybettercoppergolem.command.WbcgCommand;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.net.AreaModePayload;
import io.github.lnasser11.waybettercoppergolem.net.ConfigPayload;
import io.github.lnasser11.waybettercoppergolem.net.EditorPayloads;
import io.github.lnasser11.waybettercoppergolem.net.SetClipboardPayload;
import io.github.lnasser11.waybettercoppergolem.net.TuningPayloads;
import io.github.lnasser11.waybettercoppergolem.net.ZonePayloads;
import io.github.lnasser11.waybettercoppergolem.tool.ChestEditor;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;
import io.github.lnasser11.waybettercoppergolem.tool.Onboarding;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;
import io.github.lnasser11.waybettercoppergolem.tuning.TuningNet;
import io.github.lnasser11.waybettercoppergolem.zone.Zone;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneOverview;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSimulation;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class WayBetterCopperGolem implements ModInitializer {
	public static final String MOD_ID = "waybettercoppergolem";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * The chest's labels, owned by the chest block entity and synced to
	 * clients. Explicit sets are the player's choice; derived sets are the
	 * frame-based default. See {@link ChestLabelSet}.
	 */
	public static final AttachmentType<ChestLabelSet> CHEST_LABELS = AttachmentRegistry.create(
			id("chest_labels"),
			builder -> builder.persistent(ChestLabelSet.CODEC)
					.syncWith(ChestLabelSet.STREAM_CODEC, AttachmentSyncPredicate.all()));

	/**
	 * Legacy: the tag an old-style cycled frame meant. No longer written;
	 * read once to migrate the chest to an explicit label set, then removed.
	 */
	public static final AttachmentType<Identifier> FRAME_TAG =
			AttachmentRegistry.createPersistent(id("frame_tag"), Identifier.CODEC);

	/** World-wide category tuning (items added to / removed from tag categories). */
	public static final AttachmentType<Map<Identifier, CategoryTuning.TagOverride>> CATEGORY_OVERRIDES =
			AttachmentRegistry.createPersistent(id("category_overrides"), CategoryTuning.CODEC);

	/**
	 * What a player's label tool carries. Survives death and relog; synced
	 * only to its owner, for the HUD.
	 */
	public static final AttachmentType<Clipboard> CLIPBOARD = AttachmentRegistry.create(
			id("clipboard"),
			builder -> builder.persistent(Clipboard.CODEC).copyOnDeath()
					.syncWith(Clipboard.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));

	/**
	 * The dimension's sorting zones, anchor copper chest → zone (area +
	 * settings). Synced to clients for the HUD. See {@link Zones}.
	 */
	public static final AttachmentType<Map<BlockPos, Zone>> ZONES = AttachmentRegistry.create(
			id("zones"),
			builder -> builder.persistent(Zones.CODEC)
					.syncWith(Zones.STREAM_CODEC, AttachmentSyncPredicate.all()));

	/**
	 * Legacy: settings stored on each copper chest before zones had areas.
	 * Read once to migrate the chest into a zone, then removed.
	 */
	public static final AttachmentType<ZoneSettings> ZONE_SETTINGS =
			AttachmentRegistry.createPersistent(id("zone_settings"), ZoneSettings.CODEC);

	/**
	 * The anchor of the zone a golem belongs to, set the first time it takes
	 * from a copper chest inside a zone and kept until that zone is removed.
	 * Persisted on the golem; see {@link io.github.lnasser11.waybettercoppergolem.sorting.ZoneAwareGolem}.
	 */
	public static final AttachmentType<BlockPos> GOLEM_ZONE =
			AttachmentRegistry.createPersistent(id("golem_zone"), BlockPos.CODEC);

	/** Settings new zones start from in this world (overworld attachment; absent = built-in defaults). */
	public static final AttachmentType<ZoneSettings> ZONE_DEFAULTS =
			AttachmentRegistry.createPersistent(id("zone_defaults"), ZoneSettings.CODEC);

	/** Which one-time hints a player has already seen. */
	public static final AttachmentType<Onboarding.State> ONBOARDING = AttachmentRegistry.create(
			id("onboarding"), builder -> builder.persistent(Onboarding.State.CODEC).copyOnDeath());

	public static final MenuType<ZoneSettingsMenu> ZONE_SETTINGS_MENU =
			Registry.register(BuiltInRegistries.MENU, id("zone_settings"),
					new MenuType<>(ZoneSettingsMenu::new, FeatureFlags.VANILLA_SET));

	@Override
	public void onInitialize() {
		WbcgConfig.load();
		PayloadTypeRegistry.clientboundPlay().register(ConfigPayload.TYPE, ConfigPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AreaModePayload.TYPE, AreaModePayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetClipboardPayload.TYPE, SetClipboardPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SetClipboardPayload.TYPE,
				(payload, context) -> LabelTool.applyPickerChoice(context.player(), payload.labels()));
		PayloadTypeRegistry.clientboundPlay().register(EditorPayloads.EditorContext.TYPE, EditorPayloads.EditorContext.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.OpenEditor.TYPE, EditorPayloads.OpenEditor.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditorPayloads.SetChestLabels.TYPE, EditorPayloads.SetChestLabels.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.OpenEditor.TYPE,
				(payload, context) -> ChestEditor.open(context.player(), payload.pos()));
		ServerPlayNetworking.registerGlobalReceiver(EditorPayloads.SetChestLabels.TYPE,
				(payload, context) -> ChestEditor.apply(context.player(), payload.pos(), payload.labels()));
		PayloadTypeRegistry.clientboundPlay().register(TuningPayloads.TuningContext.TYPE, TuningPayloads.TuningContext.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TuningPayloads.OpenTuning.TYPE, TuningPayloads.OpenTuning.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TuningPayloads.TuneCategory.TYPE, TuningPayloads.TuneCategory.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TuningPayloads.OpenTuning.TYPE,
				(payload, context) -> TuningNet.open(context.player(), payload.tagId()));
		ServerPlayNetworking.registerGlobalReceiver(TuningPayloads.TuneCategory.TYPE,
				(payload, context) -> TuningNet.change(context.player(), payload.tagId(), payload.itemId(), payload.include()));
		PayloadTypeRegistry.clientboundPlay().register(ZonePayloads.Overview.TYPE, ZonePayloads.Overview.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ZonePayloads.Simulation.TYPE, ZonePayloads.Simulation.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ZonePayloads.OpenOverview.TYPE, ZonePayloads.OpenOverview.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ZonePayloads.RunSimulation.TYPE, ZonePayloads.RunSimulation.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ZonePayloads.OpenOverview.TYPE, (payload, context) -> {
			if (context.player().level() instanceof ServerLevel level) {
				Zones.zoneAt(level, payload.anchor()).ifPresent(zone -> ZoneOverview.send(context.player(), level, zone));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ZonePayloads.RunSimulation.TYPE, (payload, context) -> {
			if (context.player().level() instanceof ServerLevel level) {
				Zones.zoneAt(level, payload.anchor()).ifPresent(zone -> ZoneSimulation.send(context.player(), level, zone));
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler, ConfigPayload.TYPE)) {
				sender.sendPacket(new ConfigPayload(WbcgConfig.get().toolItemId()));
			}
		});
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> LabelResolver.invalidateCaches());
		LabelTool.register();
		UseBlockCallback.EVENT.register(WayBetterCopperGolem::onUseBlock);
		ServerEntityEvents.ENTITY_LOAD.register(WayBetterCopperGolem::onEntityLoad);
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> WbcgCommand.register(dispatcher, registryAccess));
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Force the lazily-loaded behavior class so a broken mixin fails
			// at startup in dev instead of when the first golem spawns.
			TransportItemsBetweenContainers.class.getName();
		}
		LOGGER.info("Way Better Copper Golem initialized");
	}

	/** A frame placed on (or loaded next to) a chest gives it a derived label right away. */
	private static void onEntityLoad(Entity entity, ServerLevel level) {
		if (entity instanceof ItemFrame frame) {
			ChestLabels.onFrameChanged(level, frame);
		}
	}

	/**
	 * Right-clicking a chest refreshes its derived labels and shows the
	 * label set in the actionbar (the chest still opens). Sneak-right-click
	 * with an empty hand on a copper chest opens the zone settings.
	 */
	private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand,
			BlockHitResult hitResult) {
		if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!player.isShiftKeyDown()) {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				BlockPos chestPos = hitResult.getBlockPos();
				BlockState chestState = level.getBlockState(chestPos);
				if (ChestLabels.isLabelableChest(chestState)
						&& (player.getMainHandItem().is(net.minecraft.world.item.Items.ITEM_FRAME)
								|| player.getMainHandItem().is(net.minecraft.world.item.Items.GLOW_ITEM_FRAME))) {
					// Placing a frame on a chest: the one-time hint about auto labels.
					Onboarding.hintFrame(serverPlayer);
				}
				if (ChestLabels.isLabelableChest(chestState)) {
					ChestLabelSet labels = ChestLabels.effectiveLabelSet(serverLevel, chestPos, chestState);
					if (!labels.isEmpty()) {
						serverPlayer.sendOverlayMessage(describeSummary(labels));
					}
				}
			}
			return InteractionResult.PASS;
		}
		if (!player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hitResult.getBlockPos();
		if (!level.getBlockState(pos).is(BlockTags.COPPER_CHESTS)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			ChestEditor.openZoneScreen(serverPlayer, serverLevel, pos);
		}
		return InteractionResult.SUCCESS;
	}

	/** "Golem labels: Iron Ingot, catch-all", marked "(auto)" when frame-derived. */
	public static Component describeSummary(ChestLabelSet labels) {
		String key = labels.explicit()
				? "waybettercoppergolem.label.summary" : "waybettercoppergolem.label.summary.auto";
		return Component.translatable(key, LabelResolver.listNames(labels.labels()));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
