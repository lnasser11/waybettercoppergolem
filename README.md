# Way Better Copper Golem

A Fabric mod that turns vanilla copper golems into actual storage-room
organizers. Instead of dumping copper-chest items into whichever chest
happens to contain a match, golems deliver every item to the chest you
labeled for it — using the item frames you already hang on chests — and
slowly clean up misplaced stacks while they're at it.

Built for **Minecraft Java 26.2** · Fabric Loader 0.19.3 · Fabric API
0.152.1+26.2 · Java 25. Works in singleplayer and on dedicated servers.

---

## Installation

Put three things in the `mods` folder of a Fabric 26.2 profile:

1. Fabric API
2. `waybettercoppergolem-<version>.jar` (from `build/libs/` after building,
   see below)

…plus Fabric Loader itself as the profile. On a server, install the mod
**both server-side and on every client**: the golem AI, labels, and
sorting all run on the server; the client part adds the settings screen.

Removing the mod is always safe — see [Safety guarantees](#safety-guarantees).

---

## How to use it

### Label chests with item frames

A chest's category is declared by an **item frame mounted on the chest
itself** (any face, top included). The chest owns the label; the frame is
the sample it is read from, and **nothing in this mod ever intercepts a
click on a frame** — rotating or swapping the framed item works exactly
like vanilla, and click-through mods are fully compatible.

How broadly a framed item counts is decided by what the chest already
holds (the "smart frame" rule):

- An iron ingot frame on an empty chest, or on a chest holding only iron
  ingots, means **Iron Ingot (exact item only)**.
- The same frame on a chest holding iron, copper and gold ingots means
  **#c:ingots**: the narrowest `c:`/vanilla tag of the framed item that
  covers everything in the chest. Preset categories are never chosen
  automatically; pick those on purpose (see below).
- **Empty frame** = the catch-all chest. Items matching no label anywhere
  go here.
- **Framed cobweb** = this chest is **off-limits**: golems never deposit
  into it, never claim it, never reorganize it.
- Several frames on one chest give it several categories (the union).
  A double chest is one container; a frame on either half labels all of it.
- An **unlabeled** chest keeps pure vanilla behavior (an empty one gets
  claimed by whatever the golem drops in it first). Labels never apply to
  chests you didn't label.

Frame-derived labels are marked **(auto)** and follow the frame: change
the framed item and the label changes with it. Labels you set explicitly
(the learn pass, the label tool, the picker — see the following sections)
are never changed by frames; the frame then becomes decoration.

Opening a labeled chest shows its full label set in the actionbar.

### How golems decide where things go

When a golem picks up items from a copper chest, it chooses the
destination by **narrowest matching label first**:

1. a chest labeled with the exact item;
2. a chest labeled with a matching tag, smaller tags first
   (`#c:ingots/iron` beats `#c:ingots` beats `Category: Ores & Minerals`);
3. the catch-all chest;
4. an unlabeled chest, under the vanilla rule (empty, or already contains
   that item).

Full chests are skipped, so a full narrow chest **cascades** to the next
broader one. Between equally-labeled chests, the golem prefers the one
already holding that item (twin chests consolidate instead of scattering),
then the nearest. A labeled chest never accepts items that match none of
its labels — one stray stack can't redefine a chest.

### Categories: presets you can tune in-game

Tags are precise but patchy for the categories players actually use, so
the mod ships **12 preset categories** as ordinary datapack item tags
(`wbcg:` namespace):

> Building Blocks · Wood · Stone & Earth · Redstone · Food · Farming ·
> Ores & Minerals · Tools & Gear · Combat · Mob Drops · Nether & End ·
> Decoration

Because no preset will ever match your storage room exactly, every
category (and any `c:` or curated `minecraft:` tag) can be tuned per
world: add or remove single items — *"Added Glowstone to Redstone"*. The
tweak applies server-wide to every chest labeled with that category,
persists with the world, and never modifies the base tag. Whole
categories can be replaced wholesale with a regular datapack (they're
plain `data/wbcg/tags/item/*.json` files).

Tuning is done with the `/wbcg` command:

```
/wbcg categories                        list presets with sizes and tweak counts
/wbcg category list <name>              a category's added/removed items
/wbcg category test <name> <item>       is this item currently in the category?
/wbcg category add <name> <item>        include an item            (op)
/wbcg category remove <name> <item>     exclude an item            (op)
/wbcg category reset <name>             drop all tweaks            (op)
```

Bare names resolve to presets (`redstone` → `wbcg:redstone`); explicit
namespaces address any tag (`c:ingots`, `minecraft:planks`).

### Reorganizing existing chests

When the copper-chest dump queue is idle, golems slowly fix the storage
room: they scan labeled chests for stacks that match none of that chest's
labels, pick up exactly the misplaced stack, and deliver it through the
normal label-aware flow. It's deliberately low-priority background work
(one move per ~30 s, a minute's pause when everything is tidy), it only
ever touches **labeled** chests, and chests with a catch-all or cobweb
label are never considered misplaced. Toggleable per zone.

### Tall chest walls

Vanilla golems can only reach chests at their own height. This mod raises
their **vertical reach** (default 4 blocks, configurable 1–6), and fixes
the vanilla line-of-sight check that made any chest two or more blocks up
a chest wall count as "unreachable" — so a golem standing on the floor
serves a wall of chests four or five high. It still can't grab through
solid walls.

### Sorting-zone settings

Copper chests aren't bound to a golem, so settings configure a **zone**:
whatever golems work out of that copper chest obey its settings (a golem
remembers the last copper chest it picked up from).

**Sneak-right-click a copper chest with an empty hand** to open the
settings screen:

| Setting | Default | |
|---|---|---|
| Reorganize existing chests | on | background cleanup on/off |
| Tidy inside chests | off | merge partial stacks + close gaps in chests the golem visits |
| Dry run | off | log intended moves, touch nothing |
| Search radius | 32 | horizontal destination search distance (4–48) |
| Vertical reach | 4 | how high golems can reach into chest walls (1–6) |

Normal right-click still opens the copper chest as storage. All copper
chest variants behave identically (exposed/weathered/oxidized and all
waxed versions), and settings survive oxidation and waxing.

### Dry-run mode

Turn on **Dry run** for a zone and its golems log every intended move
without touching a single chest:

```
[DRY-RUN] would move 12x minecraft:iron_ingot from minecraft:copper_chest@0,-59,0 to minecraft:chest@8,-59,0
```

Watch a full pass in the server log (or `.minecraft/logs/latest.log` in
singleplayer) before letting golems loose on a real storage room, then
switch it off. Each source chest is logged once per pass (~5 min cycle).

---

## Configuration

The server writes `config/waybettercoppergolem.json` on first start:

```json
{
  "tool_item": "minecraft:feather",
  "learn_radius": 32,
  "learn_requires_op": true
}
```

| Key | Default | |
|---|---|---|
| `tool_item` | `minecraft:feather` | the vanilla item that acts as the label tool (copy/paste labels, open the picker). Any item id works; an unknown id logs a warning and the feather is used. Pick something without a right-click action of its own. |
| `learn_radius` | 32 | default horizontal radius of `/wbcg learn` around the player (4–64) |
| `learn_requires_op` | true | whether the learn pass needs permission level 2 |

Clients learn the tool item from the server on join, so only the server
file matters.

---

## Safety guarantees

- **No world-format changes.** Labels, tweaks, and settings live in
  Fabric data attachments on *vanilla* block entities and the world —
  no custom blocks, no custom block entities, no chest subclasses. If
  the mod is removed, vanilla silently drops the attachment data and
  everything else (chests, contents, golems) is untouched; golems revert
  to stock behavior because all AI changes are runtime-only mixins.
- **No item loss or duplication.** Transfers use the vanilla hand-carry
  mechanism: items leave a chest only into the golem's hand, and the hand
  is flagged guaranteed-drop (a golem dying mid-carry drops the stack).
  Tidying only moves counts between existing stacks within one server
  tick.
- **The chest owns its labels.** Labels live on the chest, so a creeper
  blowing up a frame doesn't scramble the room — the chest keeps sorting
  as labeled. Explicit labels never change on their own; frame-derived
  ones follow the frame the moment a new one is hung.
- **Mod-proof labels.** A label stores the tag id it means, not a
  position in a list, so adding or removing mods never silently changes
  what an existing label matches.
- **No extra tick loops.** All logic rides the golem's own vanilla
  behavior cycle; tag lookups are cached per item and invalidated on
  datapack reload.

## Vanilla behavior, for reference

Read from the decompiled 26.2 source (details in
[`docs/VANILLA_NOTES.md`](docs/VANILLA_NOTES.md)): golems take up to 16
items from the first occupied slot of the nearest copper chest (any
oxidation state, unowned), then walk to the nearest regular/trapped chest
and deposit only if it's empty or already contains that item — contents
are checked on arrival, not during the search. Search volume is 32 blocks
horizontal / 8 vertical; up to 10 chests are tried per cycle before a 7 s
cooldown. This mod keeps all of that machinery and replaces only the
destination choice, the acceptance rule, and the reach.

---

## Building from source

```
./gradlew build        # jar lands in build/libs/
./gradlew runClient    # launch a dev client
./gradlew runServer    # launch a dev server
```

Requires JDK 25 and network access to `maven.fabricmc.net`,
`meta.fabricmc.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`,
`libraries.minecraft.net`, `resources.download.minecraft.net`,
`services.gradle.org`, and Maven Central.

Mappings are **Mojang official** — Yarn was discontinued after snapshot
25w46a and does not exist for 26.x. Version pins live in
`gradle.properties`.

Before deploying to a shared server, run through the in-game checklist in
[`docs/TESTING.md`](docs/TESTING.md).

## License

CC0-1.0, same as the Fabric example mod this project was scaffolded from.
