package io.github.lnasser11.waybettercoppergolem;

import com.mojang.serialization.Codec;

import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WayBetterCopperGolem implements ModInitializer {
	public static final String MOD_ID = "waybettercoppergolem";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Resolved labels cached on the chest block entity; survives frame loss. */
	public static final AttachmentType<List<ChestLabel>> CHEST_LABELS =
			AttachmentRegistry.createPersistent(id("chest_labels"), ChestLabel.CODEC.listOf());

	/** The chosen category tag id, stored on the label item frame itself; absent = exact item. */
	public static final AttachmentType<Identifier> FRAME_TAG =
			AttachmentRegistry.createPersistent(id("frame_tag"), Identifier.CODEC);

	/** World-wide category tuning (items added to / removed from tag categories). */
	public static final AttachmentType<java.util.Map<Identifier, io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning.TagOverride>> CATEGORY_OVERRIDES =
			AttachmentRegistry.createPersistent(id("category_overrides"),
					io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning.CODEC);

	/** Player-made categories: id to the name they typed for it. */
	public static final AttachmentType<java.util.Map<Identifier, String>> CUSTOM_CATEGORIES =
			AttachmentRegistry.createPersistent(id("custom_categories"),
					io.github.lnasser11.waybettercoppergolem.tuning.CustomCategories.CODEC);

	/** Sorting-zone settings, stored on a copper chest block entity. */
	public static final AttachmentType<io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings> ZONE_SETTINGS =
			AttachmentRegistry.createPersistent(id("zone_settings"),
					io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings.CODEC);

	public static final net.minecraft.world.inventory.MenuType<io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu> ZONE_SETTINGS_MENU =
			net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.MENU,
					id("zone_settings"),
					new net.minecraft.world.inventory.MenuType<>(
							io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu::new,
							net.minecraft.world.flag.FeatureFlags.VANILLA_SET));

	@Override
	public void onInitialize() {
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> LabelResolver.invalidateCaches());
		UseEntityCallback.EVENT.register(WayBetterCopperGolem::onUseEntity);
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register(WayBetterCopperGolem::onUseBlock);
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) ->
						io.github.lnasser11.waybettercoppergolem.command.WbcgCommand.register(dispatcher, registryAccess));
		registerCategoryNetworking();
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Force the lazily-loaded behavior class so a broken mixin fails
			// at startup in dev instead of when the first golem spawns.
			net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.class.getName();
		}
		LOGGER.info("Way Better Copper Golem initialized");
	}

	/** Payloads and server handlers for the category editor screen. */
	private static void registerCategoryNetworking() {
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Toggle.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Toggle.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Query.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Query.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListRequest.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListRequest.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Create.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Create.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Delete.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Delete.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListSync.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListSync.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Sync.TYPE,
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Sync.CODEC);

		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Toggle.TYPE, (payload, context) -> {
					ServerLevel level = context.player().level();
					net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getValue(payload.itemId());
					io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning
							.toggle(level, payload.tagId(), item);
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
							.send(context.player(), syncFor(level, payload.tagId()));
				});
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Query.TYPE, (payload, context) -> {
					ServerLevel level = context.player().level();
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
							.send(context.player(), syncFor(level, payload.tagId()));
				});
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListRequest.TYPE,
				(payload, context) -> net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
						.send(context.player(), categoryList(context.player().level())));
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Create.TYPE, (payload, context) -> {
					ServerLevel level = context.player().level();
					io.github.lnasser11.waybettercoppergolem.tuning.CustomCategories.create(level, payload.name());
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
							.send(context.player(), categoryList(level));
				});
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
				io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Delete.TYPE, (payload, context) -> {
					ServerLevel level = context.player().level();
					// Only player-made categories can be deleted; the shipped
					// presets are datapack data, not world data.
					if (io.github.lnasser11.waybettercoppergolem.tuning.CustomCategories
							.isCustom(payload.tagId())) {
						io.github.lnasser11.waybettercoppergolem.tuning.CustomCategories
								.delete(level, payload.tagId());
					}
					net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
							.send(context.player(), categoryList(level));
				});
	}

	/** Preset categories plus this world's player-made ones, for the editor. */
	private static io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListSync categoryList(
			ServerLevel level) {
		List<io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Entry> entries =
				new java.util.ArrayList<>();
		net.minecraft.core.registries.BuiltInRegistries.ITEM.getTags()
				.map(named -> named.key().location())
				.filter(id -> id.getNamespace().equals(LabelResolver.CATEGORY_NAMESPACE))
				.sorted(java.util.Comparator.comparing(Identifier::getPath))
				.forEach(id -> entries.add(new io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Entry(
						id, LabelResolver.tagName(level, id), false)));
		io.github.lnasser11.waybettercoppergolem.tuning.CustomCategories.all(level).keySet().stream()
				.sorted(java.util.Comparator.comparing(Identifier::getPath))
				.forEach(id -> entries.add(new io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Entry(
						id, LabelResolver.tagName(level, id), true)));
		return new io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.ListSync(List.copyOf(entries));
	}

	private static io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Sync syncFor(
			ServerLevel level, Identifier tagId) {
		var override = io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning.overridesFor(level, tagId);
		return new io.github.lnasser11.waybettercoppergolem.net.CategoryPayloads.Sync(tagId,
				List.copyOf(override.added()), List.copyOf(override.removed()));
	}

	/** Sneak-click on a label frame cycles its expansion level. */
	private static InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player,
			net.minecraft.world.level.Level level, InteractionHand hand,
			net.minecraft.world.entity.Entity entity, net.minecraft.world.phys.EntityHitResult hitResult) {
		if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(entity instanceof ItemFrame frame)) {
			return InteractionResult.PASS;
		}
		BlockPos supportPos = frame.getPos().relative(frame.getDirection().getOpposite());
		BlockState supportState = level.getBlockState(supportPos);
		if (!ChestLabels.isLabelableChest(supportState)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ServerLevel serverLevel = (ServerLevel) level;
		ChestLabel current = ChestLabels.labelFromFrame(frame);
		ItemStack heldItem = player.getMainHandItem();

		// Sneak-click while HOLDING an item on a category frame: toggle that
		// item's membership in the category, world-wide.
		if (!heldItem.isEmpty()) {
			net.minecraft.network.chat.Component message;
			if (current.isTagLabel()) {
				Identifier tagId = current.tagId().orElseThrow();
				var result = io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning
						.toggle(serverLevel, tagId, heldItem.getItem());
				String key = result == io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning.ToggleResult.ADDED
						? "waybettercoppergolem.tuning.added" : "waybettercoppergolem.tuning.removed";
				message = net.minecraft.network.chat.Component.translatable(key,
						heldItem.getItem().getName(heldItem), LabelResolver.tagName(serverLevel, tagId));
			} else {
				message = net.minecraft.network.chat.Component
						.translatable("waybettercoppergolem.tuning.needs_category");
			}
			if (player instanceof ServerPlayer serverPlayer) {
				serverPlayer.sendOverlayMessage(message);
			}
			return InteractionResult.SUCCESS;
		}

		// An empty frame that still carries a category (its icon was popped
		// out) resets to catch-all on sneak-click; cobweb frames and plain
		// empty frames have fixed meanings.
		ChestLabel label;
		if (frame.getItem().isEmpty() && current.isTagLabel()) {
			frame.removeAttached(FRAME_TAG);
			ChestLabels.labelsForHalf(serverLevel, supportPos);
			label = ChestLabel.catchAll();
		} else if (frame.getItem().isEmpty() || current.isOffLimits()) {
			label = current;
		} else {
			label = ChestLabels.cycleFrame(serverLevel, frame, supportPos);
		}
		if (label.isOffLimits()) {
			ChestLabels.labelsForHalf(serverLevel, supportPos);
		}
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendOverlayMessage(LabelResolver.describe(serverLevel, label));
		}
		return InteractionResult.SUCCESS;
	}

	/** Sneak-right-click with an empty hand on a copper chest opens the zone settings. */
	private static InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player,
			net.minecraft.world.level.Level level, InteractionHand hand,
			net.minecraft.world.phys.BlockHitResult hitResult) {
		if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		// Opening a labeled chest normally also shows its labels in the
		// actionbar; the chest still opens (PASS).
		if (!player.isShiftKeyDown()) {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				BlockPos chestPos = hitResult.getBlockPos();
				net.minecraft.world.level.block.state.BlockState chestState = level.getBlockState(chestPos);
				if (ChestLabels.isLabelableChest(chestState)) {
					java.util.List<ChestLabel> labels =
							ChestLabels.effectiveLabels(serverLevel, chestPos, chestState);
					if (!labels.isEmpty()) {
						net.minecraft.network.chat.MutableComponent summary =
								net.minecraft.network.chat.Component.empty();
						for (int i = 0; i < labels.size(); i++) {
							if (i > 0) {
								summary.append(", ");
							}
							summary.append(LabelResolver.shortName(serverLevel, labels.get(i)));
						}
						serverPlayer.sendOverlayMessage(net.minecraft.network.chat.Component
								.translatable("waybettercoppergolem.label.summary", summary));
					}
				}
			}
			return InteractionResult.PASS;
		}
		if (!player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		BlockPos pos = hitResult.getBlockPos();
		if (!level.getBlockState(pos).is(net.minecraft.tags.BlockTags.COPPER_CHESTS)) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings settings =
					io.github.lnasser11.waybettercoppergolem.zone.Zones.at(serverLevel, pos);
			serverPlayer.openMenu(new net.minecraft.world.SimpleMenuProvider(
					(containerId, inventory, p) -> new io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu(
							containerId,
							net.minecraft.world.inventory.ContainerLevelAccess.create(serverLevel, pos),
							io.github.lnasser11.waybettercoppergolem.zone.ZoneSettingsMenu.dataFor(settings)),
					net.minecraft.network.chat.Component.translatable("waybettercoppergolem.settings.title")));
		}
		return InteractionResult.SUCCESS;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
