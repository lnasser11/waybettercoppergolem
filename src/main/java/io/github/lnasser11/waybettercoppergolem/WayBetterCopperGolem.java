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

	@Override
	public void onInitialize() {
		CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> LabelResolver.invalidateCaches());
		UseEntityCallback.EVENT.register(WayBetterCopperGolem::onUseEntity);
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
		ChestLabel label = frame.getItem().isEmpty()
				? ChestLabels.labelFromFrame(frame)
				: ChestLabels.cycleFrame(serverLevel, frame, supportPos);
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendOverlayMessage(LabelResolver.describe(label));
		}
		return InteractionResult.SUCCESS;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
