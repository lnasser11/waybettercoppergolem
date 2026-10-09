# Way Better Copper Golem

A Fabric mod that turns vanilla copper golems into actual storage-room
organizers. You tell the mod what each chest is for — or let it read your
room and work that out itself — and golems deliver every item from your
copper chests to the right chest, then slowly clean up misplaced stacks
while they're at it.

Built for **Minecraft Java 26.2** · Fabric Loader 0.19.3 · Fabric API
0.152.1+26.2 · Java 25. Works in singleplayer and on dedicated servers.
Compatible with click-through mods: nothing here ever reacts to a click
on an item frame.

---

## Installation

Put two things in the `mods` folder of a Fabric 26.2 profile:

1. Fabric API
2. `waybettercoppergolem-<version>.jar` (from `build/libs/` after building,
   see below)

On a server, install the mod **both server-side and on every client**:
the golem AI, labels, zones and sorting run on the server; the client
part adds the screens and the HUD.

Removing the mod is always safe — see [Safety guarantees](#safety-guarantees).

---

## Quick start

1. **Place a copper chest** in your storage room. That is where you dump
   things for the golems to sort.
2. **Sneak-right-click it with an empty hand.** This opens its *zone*:
   the box golems work in and the settings they obey. Press **Set area
   with the tool…**, then, holding a **feather**, sneak-right-click two
   opposite corners of the room. (Skip this and the zone is a 65 × 17 × 65
   box around the copper chest.)
3. **Press "Learn this zone's chests…".** The mod reads every chest in
   the box and proposes a label for each from what it already holds. Read
   the preview in chat, click **[Apply]**.
4. **Fix the exceptions.** Open any chest and press the **Golem** button
   beside its inventory to edit that chest's labels, with suggestions
   from what's inside. Or hold the feather: it shows every chest's label
   on the HUD as you look around, sneak-left-click copies a label,
   sneak-right-click pastes it, and sneak-right-click on the air picks
   any label from a list.
5. **Summon or build golems.** Fill the copper chest and watch.

Everything below is the long version.

---

## Getting help in-game

The first time you hang a frame on a chest, and the first time you use
the feather, a one-line hint appears in chat with a link to **the guide**:
a written book with the basics, also available any time with
`/wbcg guide`. Every button on the mod's screens has a tooltip.

## Labels and zones, the two ideas

A **label** says what a chest is for. It is one of:

| Label | Means |
|---|---|
| an **exact item** (*Iron Ingot*) | only that item |
| a **tag** (*Ingots › Iron*, *Ingots*, *Wooden Slabs*) | everything in that item tag; narrower tags win over broader ones |
| a **preset category** (*Redstone*, *Food*, …) | one of the mod's 14 curated buckets, tunable per world; they overlap, and a fixed priority order settles it |
| **catch-all** | anything that matches no other label anywhere |
| **off-limits** | golems never deposit into, claim or reorganize this chest |

A chest can carry several labels (the union). A double chest is one
container with one label set. An **unlabeled** chest keeps pure vanilla
behavior: golems put items in it only if it is empty or already holds
that item.

Labels are either **explicit** (you set them: learn, feather, picker) or
**auto** (derived from an item frame hanging on the chest, see
[Frames](#frames-free-labels-you-never-click)). Explicit always wins and
never changes on its own.

A **zone** is a box in the world with the settings golems obey inside it,
anchored at a copper chest. Golems working for a zone only take from
copper chests inside the box and only deliver inside it. See
[Zones](#zones-a-room-its-area-its-settings).

---

## Labeling chests

### Learn: label a whole room from what it already holds

Most storage rooms are already sorted by hand, so the mod reads them
instead of asking you to describe them. Open a copper chest's zone and
press **Learn this zone's chests…** to scan the zone's area, or run
`/wbcg learn [radius]` where you stand. Every chest in range gets a
proposed label:

- one kind of item inside → that **exact item**;
- several kinds → the **narrowest tag or category covering all of them**
  (iron, copper and gold ingots → *Ingots*);
- everything but one kind fits → that label, and the odd kind is reported
  as **misplaced** (the golems' reorganize pass moves it later);
- nothing fits → **skipped** as mixed. Empty chests and chests you labeled
  explicitly are skipped too (`/wbcg learn <radius> overwrite` revisits
  the explicit ones).

The proposal appears in chat. Click a chest's coordinates to make it
sparkle so you can find it, then click **[Apply]** to label everything
in one go (or **[Cancel]**; proposals expire after two minutes). Running
learn again later only touches chests that are still unlabeled, so it is
safe to repeat as the room grows. Learn needs operator permission unless
`learn_requires_op` is turned off in the config.

### The Golem button: edit one chest from its inventory

Open any chest, trapped chest or copper chest as usual and a **Golem**
button sits beside the inventory. On a regular chest it opens the
**editor** for that chest:

- the header shows the chest's current labels as chips; click a chip to
  remove that one label. **Mode: Replace / Add** decides whether a new
  choice replaces the labels or joins them;
- the right column starts with **suggestions from what's inside**, each
  with its coverage (*Ingots · covers 4 of 5 stacks*). Type to search any
  item instead; pick one to see its stops;
- the left column has the preset categories, Catch-all, Off-limits and
  Remove labels.

Every choice applies to the chest immediately and the screen stays open,
so you can keep adjusting. The clipboard is left alone: only a
sneak-left-click with the feather copies. On a copper chest the button
opens the zone screen instead.

### The feather: copy and paste labels

The **label tool** is an ordinary vanilla item, a feather by default
(see [Configuration](#configuration)). Hold it in your main hand and
sneak:

| | chest / trapped chest | copper chest |
|---|---|---|
| **sneak-left-click** | copy its labels (an unlabeled chest clears the clipboard) | copy its zone's settings |
| **sneak-right-click** | paste the copied labels onto it, replacing what was there | paste the settings into its zone (the area is left alone) |
| **sneak-right-click the air** | open the [picker](#the-picker-choose-any-label-from-a-list) | |

The actionbar confirms each copy and paste and the chest sparkles. Pasted
labels are explicit. A chest's **no golem frames** switch (the chip in its
editor) is copied and pasted together with its labels, so a wall pasted
from a chest that forbids frames forbids them too; labels picked from the
picker paste with frames allowed, and the *Remove labels* marker leaves
the switch alone. The clipboard has two independent slots (labels and
zone settings), survives death and relogging, and lives on the player,
not on the item — the feather stays an ordinary feather. Labeling a wall
of chests is: copy once (or pick once), then sneak-right-click along the
wall.

### The picker: choose any label from a list

Sneak-right-click the air while holding the feather. Whatever you choose
goes onto the clipboard, ready to paste:

- the fourteen **preset categories** as buttons;
- **Catch-all**, **Off-limits**, **Remove labels** (pasting it unlabels a
  chest) and **Empty clipboard**;
- an **item search**, pre-filled with what you carry. Pick an item and
  its stops appear: the exact item, then its tags narrow to broad
  (*Ingots › Iron · 2 items*, *Ingots · 12 items*), with the raw tag id
  in the tooltip.

### Frames: free labels you never click

An item frame mounted on a chest (any face) gives it an **auto** label
without any clicks. The framed item is the sample; what the chest holds
decides how broadly it counts:

- an iron ingot frame on an empty chest, or on a chest of iron ingots,
  means *Iron Ingot*;
- the same frame on a chest holding iron, copper and gold ingots means
  *Ingots*: the narrowest `c:`/vanilla tag of the framed item that covers
  everything inside. Preset categories are never chosen automatically;
- an **empty frame** means catch-all, a **framed cobweb** means
  off-limits.

Auto labels follow the frame: swap the framed item and the label changes.
Once a chest has explicit labels, its frames are decoration. The mod never
intercepts a click on a frame, so rotating and swapping framed items works
exactly like vanilla, with or without a click-through mod.

### The HUD: see labels and zones without opening anything

While the feather is in either hand, a small panel above the hotbar shows
what you are looking at: a chest's labels (marked *auto, from the frame*
when derived) or *Unlabeled chest — vanilla behavior*; a copper chest's
zone and settings, while the zone's outline is drawn around you; and
always the clipboard, or the area-mode prompt while you pick corners.
Looking at a frame shows the chest it hangs on. Right-clicking a chest
normally also shows its labels in the actionbar.

---

## Zones: a room, its area, its settings

A zone is a box with settings, anchored at a copper chest. Every copper
chest inside the box belongs to the same zone, so one storage room is one
zone however many copper chests it has. Golems working for a zone **only
take from copper chests inside the box and only deposit or reorganize
inside it**. The first time a golem takes from a copper chest inside a
zone it **joins** that zone and keeps working for it wherever it stands,
until the zone is removed (the membership is saved with the golem). A
golem that has not joined any zone serves the room it is standing in; one
carrying items still delivers to the room it took them from. Outside every
zone, golems behave as in vanilla with default settings.

**Sneak-right-click any copper chest with an empty hand**, or press the
**Golem** button in its inventory, to open its zone, creating a default
65 × 17 × 65 zone around that chest if it is in none. The screen shows the anchor and the area size, and the area's
outline is drawn with particles for a moment.

| Setting | Default | |
|---|---|---|
| Reorganize existing chests | on | background cleanup on/off |
| Tidy sibling chests | off | consolidate chests that share a label set, one stack per trip; see [Tidy](#tidy-sibling-chests) |
| Dry run | off | log intended moves, touch nothing |
| Golems stay inside | on | the zone's golems never path out of its box; see [Golems stay inside](#golems-stay-inside-their-zone) |
| Golems hang frames | off | golems decorate bare labeled chests with an item frame; see [Golems hang frames](#golems-hang-frames) |
| Idle golems perch | off | a golem with nothing to do climbs onto a chest and stands there like a statue |

Buttons on the same screen (the ones that change the zone are disabled
unless you may change it, see [Who may change a zone](#who-may-change-a-zone)):

- **Set area with the tool…** closes the screen; the next two
  sneak-right-clicks with the feather on any blocks are the opposite
  corners of the zone (up to 128 blocks per side; the anchor chest is
  always included). Sneak-right-click the air to cancel.
- **Reset area** goes back to the default box; **Show area outline**
  draws it again; **Learn this zone's chests…** runs the learn pass over
  the area.
- **Overview…** lists every chest in the zone, problems first: unlabeled
  chests, chests holding stacks that match none of their labels, chests
  sharing a label set. Each row can be found (sparkles), edited, or given
  the clipboard's labels (and frame switch), and one button pastes the clipboard onto every
  unlabeled chest. Inside your own zone the editor reaches any chest, not
  just the ones near you.
- **Simulate…** shows where each stack in the zone's copper chests would
  go right now, one row per item and destination, with the ones that have
  nowhere to go first. No golem needed, nothing is moved. It ignores
  whether a golem could physically reach the chests.
- `/wbcg zone` tells you which zone you are standing in and draws it.

### Who may change a zone

On a shared server every zone has an **owner**: the player who created it
(opened its screen first, pasted settings into it, or set its area). The
owner can **trust** other players by name from the zone screen's *Who may
change this zone…* button, and operators (permission level 2) can change
or **take over** any zone. Settings, area, learn and label edits inside
the zone's box need the owner, a trusted player or an operator; everyone
else still opens the screens and sees everything, with the buttons that
would be refused disabled and a tooltip saying why, and the HUD works for
all. Labels on chests outside every zone follow the old rule (anyone), and
so do chests in a zone nobody has claimed yet (from before ownership
existed; the first player who opens its screen claims it). Every label and
zone change is logged with the player's name.

A few guard rails: *Reset area*, *Apply to all* and *Remove labels* want a
second click within five seconds (the button turns into **Confirm**), one
player can own at most 16 zones (`max_zones_per_player`), the learn pass
runs at most once per ten seconds per player, and the trusted list holds
at most 32 names.

The feather copies and pastes zone **settings** between zones; the area
stays with the place. Operators also get a **Defaults** section on the
zone screen: **Save as world default** makes the current three settings
the starting point for every new zone, and **Apply to all zones** gives
every zone in the dimension these settings at once, areas untouched. Normal right-click still opens the copper chest as
storage. All copper chest variants behave identically (exposed, weathered,
oxidized, waxed), and zones survive oxidation and waxing. Breaking the
anchor chest dissolves the zone.

---

## What the golems do

Golems carry a whole stack per trip (configurable) and notice new items at
once: whenever a copper chest's contents change, every golem nearby drops
its idle cooldown and forgets that chest's "already visited" mark, so it
searches again on the next tick. Vanilla would wait up to five minutes.

### Where an item goes

When a golem picks up items from a copper chest, it chooses the
destination by **narrowest matching label first**, inside its zone:

1. a chest labeled with the exact item;
2. a chest labeled with a matching tag, smaller tags first
   (*Ingots › Iron* beats *Ingots* beats the *Ores* category); between
   two preset categories, the earlier one in the preset list wins;
3. the catch-all chest;
4. an unlabeled chest, under the vanilla rule (empty, or already contains
   that item).

Full chests are skipped, so a full narrow chest **cascades** to the next
broader one. Between equally-labeled chests, the golem prefers the one
already holding that item (twin chests consolidate instead of scattering),
then the nearest. A labeled chest never accepts items that match none of
its labels: one stray stack can't redefine a chest.

### Reorganizing existing chests

When the copper-chest dump queue is idle, golems fix the room: they scan
labeled chests for stacks that match none of that chest's labels (a cake
in the *Stone & Dirt* chest, dirt in the *Iron Ingot* chest), pick up
exactly the misplaced stack, and deliver it through the normal flow to
the chest labeled for it. It is background work that only runs when
there is nothing to deliver (one stack per trip, about one every ten
seconds, a minute's pause when everything is in place), and a golem that
has just delivered into a chest and noticed a misplaced stack there goes
for it right away. It only ever touches **labeled** chests, and catch-all
and off-limits chests are never considered misplaced. Toggleable per
zone. **Simulate…** lists every pending move as *reorganize: 3× Cake →
[x y z]* and **Overview…** flags the chests.

### Tidy sibling chests

Off by default. When on, the same low-priority background job that
reorganizes the room also consolidates **sibling chests**: chests in the
zone that share exactly the same label set. For each item that sits in
more than one sibling, the sibling already holding the most of it is its
**home**, and the golem moves the stray stacks of that item from the other
siblings into the home while it has room, one stack per trip, like every
other move. So a chest labeled *Stone & Dirt* holding 26 stacks of stone
and one stack of dirt, next to a sibling that holds dirt, loses its dirt
to the sibling, and the next stone goes into the freed slot instead of
starting a new one: each chest trends toward one kind of content and
twin chests stop interleaving.

Inside each chest the stacks are kept **in order**: grouped by item,
the item with the most stacks first (five stacks of cobblestone, then
three of the next block, then two of the next), full stacks before
partial ones, partial stacks of the same item merged, free slots at the
end. A golem sorts a chest whenever it picks up from or delivers into
it, and between deliveries it visits chests whose stacks are out of
order to sort them in place (no carrying, one chest per trip).
Nothing ever leaves the group, nothing goes into a chest whose labels do
not match it (a stack that matches none of the group's labels is
reorganize's job), off-limits chests and copper chests take no part, and
dry run logs the intended move instead. **Simulate…** lists pending
consolidations as *tidy: 1× Dirt → [x y z]* rows and chests to sort as
*sort: 7 stacks out of order*, and **Overview…** counts both in its
summary.

### Golems stay inside their zone

On by default. Vanilla golems stroll a couple of blocks between trips and,
given an open door, eventually wander off and start serving some other
room. A golem that has joined a zone with this setting on never paths
outside the zone's box: its strolls are redrawn until they land inside,
any walk whose path would leave the box is cut at the last step still
inside (so a chest only reachable from outside counts as unreachable),
and a golem that is pushed, falls or is teleported out walks back to the
nearest spot inside. The box is the zone's area, not a radius, so an
L-shaped or long room works as drawn. Turn it off for golems that are
meant to roam.

### Golems hang frames

Off by default. When on, keep a stack of item frames (glow frames work
too) in any copper chest of the zone. After a golem delivers into an
**explicitly labeled** chest whose front face is bare, its next trip is a
frame trip: it fetches one frame from a copper chest, carries it in hand
like any item, and hangs it on the chest's front face showing one of the
chest's own items, which leaves the chest: the first stack matching an
exact-item label, otherwise the most common item inside. The labels you
set never follow frames, so the chest's labels do not change; the frame is
decoration. Frames already hanging are never touched, off-limits,
catch-all, unlabeled, auto-labeled and copper chests never get one, and a
chest with no content is skipped. The front face has to be free: a slab,
a solid block, a fluid or another hanging entity in front means the chest
is skipped even if golems can reach it, and a golem that arrives with a
frame to find the front taken gives up and carries the frame back to the
copper chest it came from. Any chest can be excluded with the **frame
switch** in its editor (the small item-frame chip beside *Mode*): red
means golems never hang a frame on that chest. While the setting is on,
frames in copper chests are supplies: golems do not sort them, and
Simulate leaves them out.

### Idle golems perch

Off by default. When on, a golem with nothing to do (empty hands, no
copper chest worth visiting) walks to the nearest chest top in its zone
that is free (two blocks of air above it, no other golem on it) and
stands there like a statue: no strolling, no wandering. The moment a
copper chest in the zone changes, the golem is woken as usual and climbs
down to work. Chests with a golem on top still open normally.

### Dry run

**Simulate…** in the zone screen answers "where would this go?" at once.
For a longer watch, turn on **Dry run** for a zone and its golems log
every intended move without touching a single chest:

```
[DRY-RUN] would move 12x minecraft:iron_ingot from minecraft:copper_chest@0,-59,0 to minecraft:chest@8,-59,0
```

Watch a full pass in the server log (or `.minecraft/logs/latest.log` in
singleplayer) before letting golems loose on a real storage room, then
switch it off. Each source chest is logged once per pass (~5 min cycle).

### Several golems in one room

Golems do not shove each other: vanilla mobs push every entity they
overlap, which is how three golems in one corridor end up wedged in each
other's way; here they simply pass through one another (players and
animals still push them and are pushed). And each golem claims the chest
it is heading to, so the others prefer a chest nobody is going to: two
golems with two copper chests take one each, twin labeled chests get one
golem each, and the queue in front of a single chest forms only when
there is really nothing else to do. Frame supplies in a copper chest are
never a reason to walk there.

### Tall chest walls

Vanilla golems can only reach chests at their own height. This mod gives
each zone a **vertical reach**, 6 blocks by default and up to 16, set
with the stepper in the zone screen, and fixes the vanilla line-of-sight
check that made any chest two or more blocks up a chest wall count as
"unreachable". A golem standing on the floor serves a wall of chests as
tall as the reach. It still can't grab through solid walls.

---

## Categories and tuning

Tags are precise but patchy for the categories players actually use, so
the mod ships **14 preset categories** as ordinary datapack item tags
(`wbcg:` namespace), and together they cover every item in the game:

> Brewing · Combat · Farming · Mob Drops · Nether & End · Food · Redstone ·
> Ores · Technical · Tools & Gear · Decoration · Wood · Stone & Dirt ·
> Building

That is also their priority order. Presets overlap on purpose (a wooden
slab is Wood and also Building), and when an item matches two preset
chests the one listed first wins, whatever their sizes. Technical holds
what only creative mode gives you: spawn eggs, command and structure
blocks, bedrock, the debug stick.

Because no preset will ever match your storage room exactly, every
category (and any `c:` or curated `minecraft:` tag) can be tuned per
world: add or remove single items — *"Added Glowstone to Redstone"*. The
tweak applies server-wide to every chest labeled with that category,
persists with the world, and never modifies the base tag. Whole
categories can be replaced wholesale with a regular datapack (they're
plain `data/wbcg/tags/item/*.json` files).

**Tuning in-game:** in the picker or the chest editor, press **Tune
categories…** and pick a preset, or press the **…** beside any tag in an
item's stop list. The tuning screen lists what the category contains
(click an item to exclude it; excluded items are listed last and click
to restore), lets you search any item to add it or add the item in your
hand, and has a **Reset tweaks** button. Everyone can look; changing
needs operator permission. The `/wbcg category` commands do the same
from chat.

Labels show friendly names everywhere: `c:ingots/iron` reads *Ingots ›
Iron*, `minecraft:wooden_slabs` reads *Wooden Slabs*, presets use their
names. The raw id is in tooltips and in `/wbcg category` output.

### Command reference

```
/wbcg learn [radius] [overwrite]        propose labels for the chests around you (op by default)
/wbcg learn apply | cancel              write or drop the pending proposal
/wbcg zone                              which zone you stand in, with its outline drawn
/wbcg guide                             a written book with the basics
/wbcg highlight <x> <y> <z>             make a chest sparkle so you can find it
/wbcg categories                        list presets with sizes and tweak counts
/wbcg category list <name>              a category's added/removed items
/wbcg category test <name> <item>       is this item currently in the category?
/wbcg category add <name> <item>        include an item            (op)
/wbcg category remove <name> <item>     exclude an item            (op)
/wbcg category reset <name>             drop all tweaks            (op)
```

Bare names resolve to presets (`redstone` → `wbcg:redstone`); explicit
namespaces address any tag (`c:ingots`, `minecraft:planks`).

---

## Configuration

The server writes `config/waybettercoppergolem.json` on first start:

```json
{
  "tool_item": "minecraft:feather",
  "learn_radius": 32,
  "learn_requires_op": true,
  "golem_carry_size": 64,
  "zones_require_op_to_create": false,
  "labels_require_zone_ownership": true,
  "golems_require_zone": false,
  "max_zones_per_player": 16
}
```

| Key | Default | |
|---|---|---|
| `tool_item` | `minecraft:feather` | the vanilla item that acts as the label tool. Any item id works; an unknown id logs a warning and the feather is used. Pick something without a right-click action of its own. |
| `learn_radius` | 32 | default radius of `/wbcg learn` around the player (4–64) |
| `learn_requires_op` | true | whether the learn pass needs permission level 2 |
| `golem_carry_size` | 64 | items a golem carries per trip (1–64). Vanilla carries 16. |
| `zones_require_op_to_create` | false | only operators may create zones (or claim ones without an owner) |
| `labels_require_zone_ownership` | true | label edits inside an owned zone need the owner, a trusted player or an operator; off = anyone, as before |
| `golems_require_zone` | false | golems outside every zone do nothing instead of behaving like vanilla |
| `max_zones_per_player` | 16 | how many zones one player may own (operators are not limited) |

Clients learn the tool item from the server on join, so only the server
file matters.

---

## Safety guarantees

- **No world-format changes.** Labels, zones, tweaks and clipboards live
  in Fabric data attachments on *vanilla* block entities, levels and
  players — no custom blocks, items or block entities. If the mod is
  removed, vanilla silently drops the attachment data and everything else
  (chests, contents, golems) is untouched; golems revert to stock
  behavior because all AI changes are runtime-only mixins.
- **No item loss or duplication.** Transfers use the vanilla hand-carry
  mechanism: items leave a chest only into the golem's hand, and the hand
  is flagged guaranteed-drop (a golem dying mid-carry drops the stack).
  Tidying only moves counts between existing stacks within one server
  tick.
- **The chest owns its labels.** A creeper blowing up a frame doesn't
  scramble the room; the chest keeps sorting as labeled. Explicit labels
  never change on their own.
- **Mod-proof labels.** A label stores the tag id it means, not a
  position in a list, so adding or removing mods never silently changes
  what an existing label matches.
- **No extra tick loops.** All logic rides the golem's own vanilla
  behavior cycle; tag lookups are cached per item and invalidated on
  datapack reload.
- **Upgrading from the frame-click version** is automatic: labels that
  were cycled on frames become explicit chest labels, and per-chest
  settings become zones, the first time each is seen.

## Vanilla behavior, for reference

Read from the decompiled 26.2 source (details in
[`docs/VANILLA_NOTES.md`](docs/VANILLA_NOTES.md)): golems take up to 16
items from the first occupied slot of the nearest copper chest (any
oxidation state, unowned), then walk to the nearest regular/trapped chest
and deposit only if it's empty or already contains that item — contents
are checked on arrival, not during the search. Search volume is 32 blocks
horizontal / 8 vertical; up to 10 chests are tried per cycle before a 7 s
cooldown. This mod keeps all of that machinery and replaces only the
destination choice, the acceptance rule, the reach, and, inside a zone,
the search volume.

---

## Building from source

```
./gradlew build        # jar lands in build/libs/; also runs the game tests
./gradlew runClient    # launch a dev client
./gradlew runServer    # launch a dev server
./gradlew runGameTest  # server-side game tests only (src/gametest)
./gradlew runClientGameTest   # drives a real client through the screens (needs a display;
                              # headless: xvfb-run -s "-screen 0 1920x1080x24" ./gradlew runClientGameTest)
```

Requires JDK 25 and network access to `maven.fabricmc.net`,
`meta.fabricmc.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`,
`libraries.minecraft.net`, `resources.download.minecraft.net`,
`services.gradle.org`, and Maven Central.

Mappings are **Mojang official** — Yarn was discontinued after snapshot
25w46a and does not exist for 26.x. Version pins live in
`gradle.properties`. The server game tests cover the label model, learn,
the tool, zones, the picker's server side, and real golems sorting in a
small room (labels, the zone box, dry run); the client game test opens
every screen at three GUI sizes, checks that nothing is off-screen or
clipped, walks through area mode with real clicks and leaves screenshots
in `build/run/clientGameTest/screenshots`. What still needs a person is
in the in-game checklist, [`docs/TESTING.md`](docs/TESTING.md) — run it
before deploying to a shared server. The design notes and roadmap are in
[`docs/REVAMP_PLAN.md`](docs/REVAMP_PLAN.md).

## License

CC0-1.0, same as the Fabric example mod this project was scaffolded from.
