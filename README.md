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
itself** (any face, top included). The framed item is the example; how
broadly it counts is up to you:

- **Sneak-click the frame with an empty hand** to cycle the label through
  its stops, shown in the actionbar. For an iron ingot:
  `Iron Ingot (exact item only)` → `#c:ingots/iron` → `#c:ingots` →
  `Category: Ores & Minerals` → back to exact.
- Normal clicks still rotate the framed item, exactly like vanilla.
- **Empty frame** = the catch-all chest. Items matching no label anywhere
  go here.
- **Framed cobweb** = this chest is **off-limits**: golems never deposit
  into it, never claim it, never reorganize it.
- Several frames on one chest give it several categories (the union).
  A double chest is one container; a frame on either half labels all of it.
- An **unlabeled** chest keeps pure vanilla behavior (an empty one gets
  claimed by whatever the golem drops in it first). Labels never apply to
  chests you didn't label.

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
broader one. If *nothing* accepts an item (its chest is full and there's
no catch-all, or its category was tuned away mid-carry), the golem
returns it to a copper chest and retries half a minute later instead of
standing around holding it. Between equally-labeled chests, the golem prefers the one
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

They appear as the broadest cycle stops on any item they contain. And
because no preset will ever match your storage room exactly:

- **Sneak-click a category label frame while holding an item** to toggle
  that item in or out of the category — *"Added Glowstone to Redstone"*.
  The tweak applies server-wide to every chest labeled with that
  category, persists with the world, and never modifies the base tag.
- The same works on any tag label (`c:` and curated `minecraft:` tags
  included), so you can patch their gaps too.
- The settings screen's **Categories…** button opens a
  creative-inventory-style editor: pick a category, search the full item
  list, and click items to add or remove them — same tuning, visual.
- **Frame icons are yours to choose**: once a frame is set to a category,
  pop the displayed item out and put any item you like in — the category
  sticks to the frame, only the icon changes. (Sneak-click an emptied
  category frame to reset it to catch-all.)
- Whole categories can be replaced wholesale with a regular datapack
  (they're plain `data/wbcg/tags/item/*.json` files).

The `/wbcg` command inspects and edits the same data:

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
normal label-aware flow. It stays low-priority — it only runs when the
copper-chest dump queue is idle, with a short breather between moves —
but it reacts within seconds, not minutes. It only ever touches
**labeled** chests, and chests with a catch-all or cobweb label are never
considered misplaced. Toggleable per zone.

### Tall chest walls

Vanilla golems can only reach chests at their own height. This mod raises
their **vertical reach** (default 4 blocks, configurable 1–6), and fixes
the vanilla line-of-sight check that made any chest two or more blocks up
a chest wall count as "unreachable" — so a golem standing on the floor
serves a wall of chests four or five high. It still can't grab through
solid walls.

### Sorting-zone settings

Copper chests aren't bound to a golem, so settings configure a **zone**:
whatever golems work out of that copper chest obey its settings. A golem
binds to the last copper chest it worked from — or, before its first
pickup, simply to the nearest one — so settings (dry-run included) apply
the moment a golem is in the room.

**Sneak-right-click a copper chest with an empty hand** to open the
settings screen:

| Setting | Default | |
|---|---|---|
| Vanilla mode | off | one-click opt out: golems act 100% stock in this zone |
| Reorganize existing chests | on | background cleanup on/off |
| Tidy inside chests | off | merge partial stacks + close gaps in chests the golem visits |
| Dry run | off | log intended moves, touch nothing |
| Search radius | 32 | horizontal destination search distance (4–48) |
| Vertical reach | 4 | how high golems can reach into chest walls (1–6) |
| Carry amount | 16 | items per trip: 16 for immersion, up to 64 for efficiency |

**Vanilla mode** is the quick preset for players who don't want any of
this: with it on, golems working that zone behave exactly like stock
Minecraft — labels ignored, no reorganizing, vanilla reach and 16-item
carries, regardless of what the other settings say (they grey out to
show they don't apply) — while every label, category and setting stays
saved for the moment it's switched back off. Zones are independent, so one copper
chest can run vanilla while another runs fully tuned.

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
- **Frames are the source of truth, the chest is the backup.** Resolved
  labels are cached on the chest, so a creeper blowing up a frame doesn't
  scramble the room — the chest keeps sorting as labeled until you hang a
  new frame, which takes over immediately.
- **Mod-proof labels.** A frame stores the tag id it means, not a
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
