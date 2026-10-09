package io.github.lnasser11.waybettercoppergolem.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.github.lnasser11.waybettercoppergolem.WayBetterCopperGolem;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side settings from {@code config/waybettercoppergolem.json}.
 * Missing file or missing keys fall back to defaults; the file is written
 * with defaults when absent so admins can find it.
 *
 * <ul>
 *   <li>{@code tool_item} — the vanilla item that acts as the label tool
 *       (copy/paste labels, open the picker). Default: a feather. Any
 *       registered item id works; an unknown id logs a warning and the
 *       feather is used.</li>
 *   <li>{@code golem_carry_size} — how many items a golem carries per
 *   trip (1–64; vanilla carries 16).</li>
 *   <li>{@code learn_radius} — default horizontal radius of
 *       {@code /wbcg learn} around the player.</li>
 *   <li>{@code learn_requires_op} — whether the learn pass needs
 *       permission level 2.</li>
 *   <li>{@code zones_require_op_to_create} — only operators may create
 *       zones (or claim ones without an owner).</li>
 *   <li>{@code labels_require_zone_ownership} — label edits inside a
 *       zone need the zone's owner, a trusted player or an operator;
 *       chests outside every zone follow the old rule (anyone).</li>
 *   <li>{@code golems_require_zone} — golems outside every zone do
 *       nothing instead of behaving like vanilla.</li>
 *   <li>{@code max_zones_per_player} — how many zones one player may own
 *       (operators are not limited).</li>
 * </ul>
 */
public record WbcgConfig(Identifier toolItemId, int learnRadius, boolean learnRequiresOp, int golemCarrySize,
		boolean zonesRequireOpToCreate, boolean labelsRequireZoneOwnership, boolean golemsRequireZone,
		int maxZonesPerPlayer) {
	public static final Identifier DEFAULT_TOOL = BuiltInRegistries.ITEM.getKey(Items.FEATHER);
	public static final int DEFAULT_CARRY_SIZE = 64;
	public static final int DEFAULT_MAX_ZONES_PER_PLAYER = 16;
	public static final WbcgConfig DEFAULT = new WbcgConfig(DEFAULT_TOOL, 32, true, DEFAULT_CARRY_SIZE,
			false, true, false, DEFAULT_MAX_ZONES_PER_PLAYER);
	private static final String FILE_NAME = WayBetterCopperGolem.MOD_ID + ".json";
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

	public static final Codec<WbcgConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.optionalFieldOf("tool_item", DEFAULT.toolItemId()).forGetter(WbcgConfig::toolItemId),
			Codec.intRange(4, 64).optionalFieldOf("learn_radius", DEFAULT.learnRadius()).forGetter(WbcgConfig::learnRadius),
			Codec.BOOL.optionalFieldOf("learn_requires_op", DEFAULT.learnRequiresOp()).forGetter(WbcgConfig::learnRequiresOp),
			Codec.intRange(1, 64).optionalFieldOf("golem_carry_size", DEFAULT.golemCarrySize()).forGetter(WbcgConfig::golemCarrySize),
			Codec.BOOL.optionalFieldOf("zones_require_op_to_create", DEFAULT.zonesRequireOpToCreate()).forGetter(WbcgConfig::zonesRequireOpToCreate),
			Codec.BOOL.optionalFieldOf("labels_require_zone_ownership", DEFAULT.labelsRequireZoneOwnership()).forGetter(WbcgConfig::labelsRequireZoneOwnership),
			Codec.BOOL.optionalFieldOf("golems_require_zone", DEFAULT.golemsRequireZone()).forGetter(WbcgConfig::golemsRequireZone),
			Codec.intRange(1, 10000).optionalFieldOf("max_zones_per_player", DEFAULT.maxZonesPerPlayer()).forGetter(WbcgConfig::maxZonesPerPlayer)
	).apply(instance, WbcgConfig::new));

	private static volatile WbcgConfig current = DEFAULT;
	/** What the server we are connected to told us (client side only). */
	private static volatile Identifier remoteToolItemId = DEFAULT_TOOL;

	/** The active server configuration (defaults until {@link #load()} has run). */
	public static WbcgConfig get() {
		return current;
	}

	/**
	 * The item that acts as the label tool on this logical side: the file's
	 * choice on the server, the joined server's choice on the client.
	 */
	public static Item toolItem(Level level) {
		Identifier id = level.isClientSide() ? remoteToolItemId : current.toolItemId();
		return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.FEATHER);
	}

	public static boolean isTool(Level level, ItemStack stack) {
		return !stack.isEmpty() && stack.is(toolItem(level));
	}

	/** Client side: remember the tool item the server announced on join. */
	public static void applyRemote(Identifier toolItemId) {
		remoteToolItemId = toolItemId;
	}

	/** Client side: forget the previous server's choice. */
	public static void resetRemote() {
		remoteToolItemId = DEFAULT_TOOL;
	}

	/** Reads the config file, creating it with defaults when missing. */
	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		WbcgConfig loaded = DEFAULT;
		if (Files.exists(path)) {
			try {
				JsonObject json = GsonHelper.parse(Files.readString(path, StandardCharsets.UTF_8));
				loaded = CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(
						error -> WayBetterCopperGolem.LOGGER.warn("Config {}: {}", FILE_NAME, error)).orElse(DEFAULT);
			} catch (IOException | RuntimeException e) {
				WayBetterCopperGolem.LOGGER.warn("Could not read {}; using defaults", FILE_NAME, e);
			}
		} else {
			write(path, DEFAULT);
		}
		if (!BuiltInRegistries.ITEM.containsKey(loaded.toolItemId())) {
			WayBetterCopperGolem.LOGGER.warn("Config {}: unknown tool_item '{}', using {}",
					FILE_NAME, loaded.toolItemId(), DEFAULT_TOOL);
			loaded = loaded.withToolItem(DEFAULT_TOOL);
		}
		current = loaded;
		WayBetterCopperGolem.LOGGER.info("Label tool item: {}", current.toolItemId());
	}

	public WbcgConfig withToolItem(Identifier toolItemId) {
		return new WbcgConfig(toolItemId, learnRadius, learnRequiresOp, golemCarrySize,
				zonesRequireOpToCreate, labelsRequireZoneOwnership, golemsRequireZone, maxZonesPerPlayer);
	}

	public WbcgConfig withZonesRequireOpToCreate(boolean value) {
		return new WbcgConfig(toolItemId, learnRadius, learnRequiresOp, golemCarrySize,
				value, labelsRequireZoneOwnership, golemsRequireZone, maxZonesPerPlayer);
	}

	public WbcgConfig withLabelsRequireZoneOwnership(boolean value) {
		return new WbcgConfig(toolItemId, learnRadius, learnRequiresOp, golemCarrySize,
				zonesRequireOpToCreate, value, golemsRequireZone, maxZonesPerPlayer);
	}

	public WbcgConfig withGolemsRequireZone(boolean value) {
		return new WbcgConfig(toolItemId, learnRadius, learnRequiresOp, golemCarrySize,
				zonesRequireOpToCreate, labelsRequireZoneOwnership, value, maxZonesPerPlayer);
	}

	public WbcgConfig withMaxZonesPerPlayer(int value) {
		return new WbcgConfig(toolItemId, learnRadius, learnRequiresOp, golemCarrySize,
				zonesRequireOpToCreate, labelsRequireZoneOwnership, golemsRequireZone, value);
	}

	/**
	 * Replaces the active configuration without touching the file. For
	 * tests and tooling; the file is the source of truth on a real server.
	 */
	public static void override(WbcgConfig config) {
		current = config;
	}

	private static void write(Path path, WbcgConfig config) {
		try {
			Files.createDirectories(path.getParent());
			// Written by hand so every key shows up, defaults included; the
			// codec would omit anything equal to its default.
			JsonObject json = new JsonObject();
			json.addProperty("tool_item", config.toolItemId().toString());
			json.addProperty("learn_radius", config.learnRadius());
			json.addProperty("learn_requires_op", config.learnRequiresOp());
			json.addProperty("golem_carry_size", config.golemCarrySize());
			json.addProperty("zones_require_op_to_create", config.zonesRequireOpToCreate());
			json.addProperty("labels_require_zone_ownership", config.labelsRequireZoneOwnership());
			json.addProperty("golems_require_zone", config.golemsRequireZone());
			json.addProperty("max_zones_per_player", config.maxZonesPerPlayer());
			Files.writeString(path, PRETTY.toJson(json) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			WayBetterCopperGolem.LOGGER.warn("Could not write {}", FILE_NAME, e);
		}
	}
}
