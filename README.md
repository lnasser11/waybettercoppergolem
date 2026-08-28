# waybettercoppergolem
Fabric mod: copper golems organize an existing storage room using item-frame chest labels.

## Target environment (pinned)

| Component | Version |
|---|---|
| Minecraft Java | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.152.1+26.2 |
| Mappings | Mojang official (Yarn was discontinued after snapshot 25w46a and does not exist for 26.x; Loom 1.17 uses mojmap by default) |
| Java | 25 (toolchain and runtime) |

## How it works

Install on the **server and every client** (the AI runs server-side; the
client part is only the settings screen).

**Label a chest** by putting an item frame on it with an example item
inside. Sneak-click the frame to cycle how broadly it matches, shown in
the actionbar: exact item -> narrowest conventional tag (e.g.
`#c:ingots/iron`) -> broader (`#c:ingots`) -> back to exact. An **empty
frame** marks the catch-all chest for items matching no label. Multiple
frames on one chest (or on both halves of a double chest) all apply.
Labels are cached on the chest itself, so a creeper blowing up the frame
does not scramble the room - put a new frame up whenever.

**Golems** then deposit copper-chest items into the labeled chest with
the narrowest matching label (full chests cascade to broader ones, then
the catch-all; unlabeled chests keep vanilla behavior and are never
claimed by labels). When the dump queue is idle they slowly relocate
misplaced stacks out of labeled chests. They can reach chests in walls
up to the configured vertical reach (default 4 blocks) from the floor.

### Categories and fine-tuning

Beyond exact items and tags, the mod ships **12 preset categories** as
plain datapack item tags (`wbcg:` namespace): Building Blocks, Wood,
Stone & Earth, Redstone, Food, Farming, Ores & Minerals, Tools & Gear,
Combat, Mob Drops, Nether & End, Decoration. They appear as the broadest
stops when cycling a label frame, with friendly names in the actionbar.

Every category is tunable in-game: **sneak-click a category label frame
while holding an item** to toggle that item in or out of the category,
server-wide ("Added Glowstone to Redstone"). Tweaks persist with the
world and never modify the base tag data. Admins can also use:

```
/wbcg categories                       list presets + tweak counts
/wbcg category list <name>             show a category's tweaks
/wbcg category test <name> <item>      is this item in the category?
/wbcg category add|remove <name> <item>   (op)
/wbcg category reset <name>               (op)
```

Names default to the `wbcg` namespace (`redstone` = `wbcg:redstone`);
any tag works with an explicit namespace (`c:ingots`,
`minecraft:planks`). Whole categories can also be replaced with a
regular datapack.

**Sneak-right-click a copper chest with an empty hand** to open that
sorting zone's settings: reorganize toggle, tidy-inside toggle
(merges partial stacks after visits, off by default), dry-run, search
radius, and vertical reach. Golems obey the settings of the copper chest
they last picked up from.

**Dry-run**: golems log every intended move
(`[DRY-RUN] would move 12x minecraft:iron_ingot from minecraft:copper_chest@0,-59,0 to minecraft:chest@8,-59,0`)
without touching any chest. Turn it on, watch the server log for a full
pass, then turn it off.

Removing the mod reverts golems to vanilla behavior; label/settings data
lives in Fabric attachments on vanilla block entities and is silently
dropped by vanilla, so nothing breaks without the mod.

## Building

```
./gradlew build          # mod jar in build/libs/
./gradlew runClient      # launch a dev client
```

Requires JDK 25 and network access to:
`maven.fabricmc.net`, `meta.fabricmc.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`, `libraries.minecraft.net`,
`resources.download.minecraft.net`, `services.gradle.org`, Maven Central.
