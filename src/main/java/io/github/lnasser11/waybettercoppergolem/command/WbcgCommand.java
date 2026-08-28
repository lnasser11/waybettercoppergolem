package io.github.lnasser11.waybettercoppergolem.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.IdentifierArgument;
import com.mojang.brigadier.context.CommandContext;

import io.github.lnasser11.waybettercoppergolem.label.LabelResolver;
import io.github.lnasser11.waybettercoppergolem.tuning.CategoryTuning;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.Comparator;
import java.util.List;

/**
 * Inspection and admin tuning for label categories:
 *
 * <pre>
 * /wbcg categories                         list preset categories + tweak counts
 * /wbcg category list &lt;name&gt;              base size, added and removed items
 * /wbcg category add &lt;name&gt; &lt;item&gt;        include an item        (op)
 * /wbcg category remove &lt;name&gt; &lt;item&gt;     exclude an item        (op)
 * /wbcg category reset &lt;name&gt;             drop all tweaks        (op)
 * </pre>
 *
 * Names resolve in the {@code wbcg} namespace by default; any tag works
 * with an explicit namespace ({@code c:ingots}, {@code minecraft:planks}).
 */
public final class WbcgCommand {
	private WbcgCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("wbcg")
				.then(Commands.literal("categories").executes(WbcgCommand::listCategories))
				.then(Commands.literal("category")
						.then(Commands.literal("list")
								.then(Commands.argument("name", IdentifierArgument.id())
										.executes(WbcgCommand::listOne)))
						.then(Commands.literal("add")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("name", IdentifierArgument.id())
										.then(Commands.argument("item", ItemArgument.item(buildContext))
												.executes(ctx -> edit(ctx, true)))))
						.then(Commands.literal("remove")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("name", IdentifierArgument.id())
										.then(Commands.argument("item", ItemArgument.item(buildContext))
												.executes(ctx -> edit(ctx, false)))))
						.then(Commands.literal("reset")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("name", IdentifierArgument.id())
										.executes(WbcgCommand::reset)))
						.then(Commands.literal("test")
								.then(Commands.argument("name", IdentifierArgument.id())
										.then(Commands.argument("item", ItemArgument.item(buildContext))
												.executes(WbcgCommand::test))))));
	}

	private static Identifier tagId(CommandContext<CommandSourceStack> ctx) {
		Identifier raw = IdentifierArgument.getId(ctx, "name");
		// A bare name parses with the minecraft namespace; prefer the wbcg
		// category of that name when one exists ("redstone" -> wbcg:redstone).
		if (raw.getNamespace().equals("minecraft")) {
			Identifier category = Identifier.fromNamespaceAndPath(LabelResolver.CATEGORY_NAMESPACE, raw.getPath());
			if (BuiltInRegistries.ITEM.get(LabelResolver.itemTag(category)).isPresent()) {
				return category;
			}
		}
		return raw;
	}

	private static int listCategories(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		List<Identifier> categories = BuiltInRegistries.ITEM.getTags()
				.map(named -> named.key().location())
				.filter(id -> id.getNamespace().equals(LabelResolver.CATEGORY_NAMESPACE))
				.sorted(Comparator.comparing(Identifier::getPath))
				.toList();
		ctx.getSource().sendSuccess(
				() -> Component.translatable("waybettercoppergolem.command.categories", categories.size()), false);
		for (Identifier id : categories) {
			int size = BuiltInRegistries.ITEM.get(LabelResolver.itemTag(id)).map(t -> t.size()).orElse(0);
			CategoryTuning.TagOverride override = CategoryTuning.overridesFor(level, id);
			ctx.getSource().sendSuccess(() -> Component.literal(" - ")
					.append(LabelResolver.tagName(id))
					.append(Component.literal("  (" + id + ", " + size + " items, +"
							+ override.added().size() + "/-" + override.removed().size() + ")")), false);
		}
		return categories.size();
	}

	private static int listOne(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		Identifier id = tagId(ctx);
		int size = BuiltInRegistries.ITEM.get(LabelResolver.itemTag(id)).map(t -> t.size()).orElse(0);
		CategoryTuning.TagOverride override = CategoryTuning.overridesFor(level, id);
		ctx.getSource().sendSuccess(() -> Component.translatable("waybettercoppergolem.command.list_header",
				LabelResolver.tagName(id), id.toString(), size), false);
		ctx.getSource().sendSuccess(() -> Component.translatable("waybettercoppergolem.command.list_added",
				override.added().isEmpty() ? "-" : String.join(", ",
						override.added().stream().map(Identifier::toString).sorted().toList())), false);
		ctx.getSource().sendSuccess(() -> Component.translatable("waybettercoppergolem.command.list_removed",
				override.removed().isEmpty() ? "-" : String.join(", ",
						override.removed().stream().map(Identifier::toString).sorted().toList())), false);
		return 1;
	}

	private static int edit(CommandContext<CommandSourceStack> ctx, boolean include) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		Identifier id = tagId(ctx);
		Item item = ItemArgument.getItem(ctx, "item").createItemStack(1).getItem();
		CategoryTuning.setMembership(level, id, item, include);
		String key = include ? "waybettercoppergolem.tuning.added" : "waybettercoppergolem.tuning.removed";
		ctx.getSource().sendSuccess(() -> Component.translatable(key,
				item.getName(item.getDefaultInstance()), LabelResolver.tagName(id)), true);
		return 1;
	}

	private static int test(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		Identifier id = tagId(ctx);
		net.minecraft.world.item.ItemStack stack = ItemArgument.getItem(ctx, "item").createItemStack(1);
		boolean member = CategoryTuning.matches(level, id, stack);
		ctx.getSource().sendSuccess(() -> Component.translatable(
				member ? "waybettercoppergolem.command.test_yes" : "waybettercoppergolem.command.test_no",
				stack.getItem().getName(stack), LabelResolver.tagName(id)), false);
		return member ? 1 : 0;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		Identifier id = tagId(ctx);
		CategoryTuning.reset(level, id);
		ctx.getSource().sendSuccess(() -> Component.translatable("waybettercoppergolem.command.reset",
				LabelResolver.tagName(id)), true);
		return 1;
	}
}
