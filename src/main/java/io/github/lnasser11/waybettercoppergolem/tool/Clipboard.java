package io.github.lnasser11.waybettercoppergolem.tool;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;
import io.github.lnasser11.waybettercoppergolem.label.ChestLabel;
import io.github.lnasser11.waybettercoppergolem.zone.ZoneSettings;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Optional;

/**
 * What a player's label tool is carrying. Two independent slots:
 *
 * <ul>
 *   <li>{@code labels} — a chest label set to paste onto regular chests.
 *       Absent means nothing to paste; an empty list means "clear":
 *       pasting it removes the chest's explicit labels so it falls back to
 *       its frames or to vanilla behavior. {@code noGolemFrames} rides
 *       along with the labels: it is the copied chest's "no golem frames"
 *       switch, pasted together with them (the picker always puts labels
 *       with frames allowed on the clipboard).</li>
 *   <li>{@code zone} — copper-chest zone settings to paste onto copper
 *       chests.</li>
 * </ul>
 *
 * <p>Lives in a persistent player attachment (survives death and relog),
 * synced to the owning client so the HUD can show it. Never stored on
 * the tool item itself: the feather stays an ordinary feather.
 */
public record Clipboard(Optional<List<ChestLabel>> labels, boolean noGolemFrames, Optional<ZoneSettings> zone) {
	public static final Clipboard EMPTY = new Clipboard(Optional.empty(), false, Optional.empty());

	public static final Codec<Clipboard> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ChestLabel.CODEC.listOf().optionalFieldOf("labels").forGetter(Clipboard::labels),
			Codec.BOOL.optionalFieldOf("no_golem_frames", false).forGetter(Clipboard::noGolemFrames),
			ZoneSettings.CODEC.optionalFieldOf("zone").forGetter(Clipboard::zone)
	).apply(instance, Clipboard::new));

	public static final StreamCodec<ByteBuf, Clipboard> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

	public Clipboard {
		labels = labels.map(List::copyOf);
		// The frame switch only means something next to labels to paste.
		noGolemFrames = noGolemFrames && labels.isPresent() && !labels.get().isEmpty();
	}

	public Clipboard(Optional<List<ChestLabel>> labels, Optional<ZoneSettings> zone) {
		this(labels, false, zone);
	}

	/** Puts labels on the clipboard with golem frames allowed (what the picker does). */
	public Clipboard withLabels(Optional<List<ChestLabel>> labels) {
		return withLabels(labels, false);
	}

	/** Puts labels on the clipboard together with the copied chest's frame switch. */
	public Clipboard withLabels(Optional<List<ChestLabel>> labels, boolean noGolemFrames) {
		return new Clipboard(labels, noGolemFrames, this.zone);
	}

	public Clipboard withZone(ZoneSettings zone) {
		return new Clipboard(this.labels, this.noGolemFrames, Optional.of(zone));
	}

	public boolean isEmpty() {
		return labels.isEmpty() && zone.isEmpty();
	}

	/** Whether the labels slot holds the "clear this chest" marker. */
	public boolean isClearMarker() {
		return labels.isPresent() && labels.get().isEmpty();
	}

	public static Clipboard of(Player player) {
		Clipboard clipboard = player.getAttached(WayBetterCopperGolem.CLIPBOARD);
		return clipboard == null ? EMPTY : clipboard;
	}

	public static void set(ServerPlayer player, Clipboard clipboard) {
		if (clipboard.isEmpty()) {
			player.removeAttached(WayBetterCopperGolem.CLIPBOARD);
		} else {
			player.setAttached(WayBetterCopperGolem.CLIPBOARD, clipboard);
		}
	}
}
