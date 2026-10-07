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
import net.minecraft.world.item.Items;

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
 *   <li>{@code learn_radius} — default horizontal radius of
 *       {@code /wbcg learn} around the player.</li>
 *   <li>{@code learn_requires_op} — whether the learn pass needs
 *       permission level 2.</li>
 * </ul>
 */
public record WbcgConfig(Identifier toolItemId, int learnRadius, boolean learnRequiresOp) {
	public static final Identifier DEFAULT_TOOL = BuiltInRegistries.ITEM.getKey(Items.FEATHER);
	public static final WbcgConfig DEFAULT = new WbcgConfig(DEFAULT_TOOL, 32, true);
	private static final String FILE_NAME = WayBetterCopperGolem.MOD_ID + ".json";
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

	public static final Codec<WbcgConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Identifier.CODEC.optionalFieldOf("tool_item", DEFAULT.toolItemId()).forGetter(WbcgConfig::toolItemId),
			Codec.intRange(4, 64).optionalFieldOf("learn_radius", DEFAULT.learnRadius()).forGetter(WbcgConfig::learnRadius),
			Codec.BOOL.optionalFieldOf("learn_requires_op", DEFAULT.learnRequiresOp()).forGetter(WbcgConfig::learnRequiresOp)
	).apply(instance, WbcgConfig::new));

	private static volatile WbcgConfig current = DEFAULT;

	/** The active configuration (defaults until {@link #load()} has run). */
	public static WbcgConfig get() {
		return current;
	}

	/** The item that acts as the label tool, resolved and validated. */
	public static Item toolItem() {
		return BuiltInRegistries.ITEM.getOptional(current.toolItemId()).orElse(Items.FEATHER);
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
			loaded = new WbcgConfig(DEFAULT_TOOL, loaded.learnRadius(), loaded.learnRequiresOp());
		}
		current = loaded;
		WayBetterCopperGolem.LOGGER.info("Label tool item: {}", current.toolItemId());
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
			Files.writeString(path, PRETTY.toJson(json) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			WayBetterCopperGolem.LOGGER.warn("Could not write {}", FILE_NAME, e);
		}
	}
}
