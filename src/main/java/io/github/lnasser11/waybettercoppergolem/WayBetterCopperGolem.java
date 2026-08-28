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

	/** Chosen expansion level, stored on the label item frame itself. */
	public static final AttachmentType<Integer> FRAME_EXPANSION =
			AttachmentRegistry.createPersistent(id("frame_expansion"), Codec.INT);

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
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Force the lazily-loaded behavior class so a broken mixin fails
			// at startup in dev instead of when the first golem spawns.
			net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers.class.getName();
		}
		LOGGER.info("Way Better Copper Golem initialized");
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
		// Cobweb frames and empty frames have fixed meanings; nothing to cycle.
		ChestLabel label = (frame.getItem().isEmpty() || current.isOffLimits())
				? current
				: ChestLabels.cycleFrame(serverLevel, frame, supportPos);
		if (label.isOffLimits()) {
			ChestLabels.labelsForHalf(serverLevel, supportPos);
		}
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendOverlayMessage(LabelResolver.describe(label));
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
							summary.append(LabelResolver.shortName(labels.get(i)));
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
