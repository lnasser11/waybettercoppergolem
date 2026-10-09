package io.github.lnasser11.waybettercoppergolem.client;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.config.WbcgConfig;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabelSet;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabels;
import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.tool.Clipboard;
import io.github.lnasser11.waybettercoppergolem.tool.LabelTool;
import io.github.lnasser11.waybettercoppergolem.zone.Zones;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A few lines above the hotbar while the label tool is in either hand:
 * the labels of the chest you look at (or the zone of the copper chest),
 * then the clipboard, or the area-mode prompt. Looking at a copper chest
 * also draws its zone's outline with local particles.
 *
 * <p>Everything is read from data the server already syncs: chest label
 * attachments, the zone registry, and the player's clipboard attachment.
 */
public final class ToolHud implements HudElement {
	private static final Identifier ID = WayBetterCopperGolem.id("tool_hud");
	private static final int LINE_HEIGHT = 10;
	/** Below the top edge: clear of the crosshair and of whatever the player looks at. */
	private static final int TOP_MARGIN = 10;
	private static final int OUTLINE_EVERY_TICKS = 20;

	private long lastOutlineTick = Long.MIN_VALUE;

	private ToolHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, new ToolHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || player.isSpectator()) {
			return;
		}
		if (!WbcgConfig.isTool(level, player.getMainHandItem()) && !WbcgConfig.isTool(level, player.getOffhandItem())) {
			return;
		}
		List<Component> lines = new ArrayList<>();
		BlockPos target = lookedAtBlock(minecraft);
		if (target != null) {
			BlockState state = level.getBlockState(target);
			if (ChestLabels.isLabelableChest(state)) {
				lines.add(chestLine(level, target, state));
			} else if (state.is(BlockTags.COPPER_CHESTS)) {
				zoneLines(level, target, lines);
			}
		}
		int step = WayBetterCopperGolemClient.areaModeStep();
		if (step > 0) {
			lines.add(Component.translatable("waybettercoppergolem.hud.area_mode_" + step));
		} else {
			lines.add(LabelTool.describeClipboard(Clipboard.of(player)));
		}
		draw(graphics, minecraft.font, lines);
	}

	/** The block under the crosshair, looking through a frame to the chest it hangs on. */
	private static @Nullable BlockPos lookedAtBlock(Minecraft minecraft) {
		HitResult hit = minecraft.hitResult;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			return blockHit.getBlockPos();
		}
		if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof ItemFrame frame) {
			return ChestLabels.supportPos(frame);
		}
		return null;
	}

	private static Component chestLine(ClientLevel level, BlockPos pos, BlockState state) {
		ChestLabelSet labels = ChestLabels.cachedLabelSet(level, pos, state);
		if (labels.isEmpty()) {
			return Component.translatable("waybettercoppergolem.hud.unlabeled");
		}
		String key = labels.explicit() ? "waybettercoppergolem.hud.labels" : "waybettercoppergolem.hud.labels_auto";
		return Component.translatable(key, LabelResolver.listNames(labels.labels()));
	}

	private void zoneLines(ClientLevel level, BlockPos pos, List<Component> lines) {
		Optional<Zones.ZoneRef> zone = Zones.zoneAt(level, pos);
		if (zone.isEmpty()) {
			lines.add(Component.translatable("waybettercoppergolem.hud.no_zone"));
			return;
		}
		BlockPos anchor = zone.get().anchor();
		lines.add(Component.translatable("waybettercoppergolem.hud.zone",
				anchor.getX() + " " + anchor.getY() + " " + anchor.getZ(), Zones.describeArea(zone.get().area())));
		lines.add(Zones.describeBehavior(zone.get().settings()));
		lines.add(Zones.describeGolems(zone.get().settings()));
		long now = level.getGameTime();
		if (now - this.lastOutlineTick >= OUTLINE_EVERY_TICKS || now < this.lastOutlineTick) {
			this.lastOutlineTick = now;
			for (Vec3 point : Zones.outlinePoints(zone.get().area())) {
				level.addParticle(ParticleTypes.END_ROD, point.x, point.y, point.z, 0, 0, 0);
			}
		}
	}

	private static void draw(GuiGraphicsExtractor graphics, Font font, List<Component> lines) {
		if (lines.isEmpty()) {
			return;
		}
		int width = 0;
		for (Component line : lines) {
			width = Math.max(width, font.width(line));
		}
		int centerX = graphics.guiWidth() / 2;
		int top = TOP_MARGIN;
		int bottom = top + lines.size() * LINE_HEIGHT;
		int left = centerX - width / 2 - 5;
		int right = centerX + width / 2 + 5;
		graphics.fill(left, top - 4, right, bottom + 1, Ui.HUD_BACKGROUND);
		Panel.outline(graphics, left, top - 4, right - left, bottom + 1 - (top - 4), Ui.PANEL_BORDER);
		int y = top;
		for (int i = 0; i < lines.size(); i++) {
			boolean last = i == lines.size() - 1 && lines.size() > 1;
			graphics.centeredText(font, lines.get(i), centerX, y, last ? Ui.TEXT_MUTED : Ui.TEXT);
			y += LINE_HEIGHT;
		}
	}
}
