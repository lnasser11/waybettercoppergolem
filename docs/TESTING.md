# In-game test plan (solo world, before the server)

Run these in order — each step builds on the last. Use a **new creative
world** (or a copy of an existing one, never the original). Singleplayer
runs an integrated server, so everything works without EnxadaHost.

The automated game tests (`./gradlew build` runs them) already cover the
label model, the learn inference, the tool's copy/paste, zones and the
picker's server side, and three of them let a **real copper golem** work
in a walled 8 × 8 room: items land in the chests whose labels match and
never in the other, a chest outside the zone box is ignored for a plain
chest inside it, and dry run moves nothing. A fourth sends three golems
through a 24 × 24 room with three copper chests of mixed loot and a
two-high wall of labeled chests on the far side. A second automated test
drives a **real client**
(`./gradlew runClientGameTest`; on a headless machine wrap it in
`xvfb-run -s "-screen 0 1920x1080x24"`): it builds a small chest room,
opens every screen at 480 × 270, 427 × 240 and 960 × 540, fails if any
widget is off-screen or any button clips its text, and walks through area
mode with real sneak-right-clicks on plain blocks. Its screenshots land in
`build/run/clientGameTest/screenshots`. This checklist is for what still
needs a person: golems in a real, messy chest room, how the HUD reads in
play, and how it all feels.

**Setup:** Fabric Loader 0.19.3 profile for 26.2, with `fabric-api` and
`waybettercoppergolem-1.1.0.jar` in the mods folder. Keep a **feather** in
your hotbar: it is the label tool.

**Watching logs:** dry-run lines go to the game log. Either enable "Open
output log when game starts" in the launcher, or watch
`.minecraft/logs/latest.log`. Lines look like:
`[DRY-RUN] would move 12x minecraft:iron_ingot from minecraft:copper_chest@... to minecraft:chest@...`

**Getting a golem:** `/summon minecraft:copper_golem`, or the survival
way (carved pumpkin on a copper block). Stay near the test area — golems
only work in loaded chunks, and note their rhythm: ~3 s at each chest,
7 s pause when they find nothing. That's vanilla, not a bug.

---

## 1. Zones and the settings screen

1. Place a copper chest. **Sneak-right-click it with an empty hand** → the
   zone screen opens (reorganize / tidy / dry-run toggles, area buttons,
   learn), showing `Zone anchored at x, y, z` and `Area: 65 × 17 × 65`,
   and a particle outline of that box appears around you for a moment.
2. Normal right-click still opens it as storage; sneak-right-click
   *holding a block* still places the block (vanilla).
3. Toggle **dry-run ON**, close, reopen → still ON (round-tripped).
4. Place a second copper chest 10 blocks away and open its settings → it
   shows the **same anchor** (the first chest) and the same toggles: one
   room, one zone.
5. Press **Set area with the tool…** → the screen closes and chat says to
   click the first corner. Holding the feather, sneak-right-click a block
   at one corner of your room (sparkles), then the opposite corner → chat
   says `Zone area set: W × H × D blocks` and the outline is drawn. Reopen
   the settings → the new size shows. `/wbcg zone` from inside the box
   reports it; from outside it says you are in no zone.
6. Press **Set area…** again and sneak-right-click the air → `Area mode
   cancelled` (and no picker opens). **Reset area** → back to 65 × 17 × 65.
7. Break the anchor copper chest → `/wbcg zone` no longer finds the zone;
   the second copper chest gets a fresh default zone when its settings
   are opened. Place the anchor back and set a small area around your
   test room for the rest of this plan.

## 1b. Ownership (needs a second account, or a friend)

1. You created the zone in step 1, so the zone screen's *Access* line
   reads `owner <you> · 0 trusted`. Press **Who may change this zone…** →
   the access screen shows the same, an empty trusted list and a name box.
2. Log in with the second account (no op). Open the same zone screen →
   everything is visible, the toggles, the reach stepper, *Set area*,
   *Reset area*, *Learn…*, *Save default* and *Apply to all* are greyed
   out, and hovering one says who may change the zone. Open a chest inside
   the zone, press **Golem** → the editor is read-only (`· read-only` in
   the header, every choice disabled). Sneak-right-click a chest inside the
   zone with the feather → `This chest is in a zone you may not change`.
   A chest *outside* the zone can still be labeled.
3. Back on the owner: type the second account's name in the access
   screen, press **Trust** → chat confirms and the list shows the name
   (with a `✕` to untrust). The second account can now toggle settings
   and label chests inside the zone. Press `✕` → refused again.
4. As an operator, open someone else's zone → everything is enabled, and
   **Take over** (two clicks) makes you the owner. Check the server log:
   every change is an INFO line with the player's name.
5. **Confirmations.** Press **Reset area** once → the button turns into
   **Confirm** and nothing happens; wait six seconds → it reverts. Press
   it twice within five seconds → the area resets. Same for **Apply to
   all** and the editor's **Remove labels**.
6. **Limits.** `/wbcg learn` twice in a row → the second answers with
   `try again in N s`. Set `max_zones_per_player` to 1 in the config,
   restart, place a copper chest far outside your zone and sneak-right-
   click it → `You already own as many zones as this server allows`. Set
   `zones_require_op_to_create` to true → a non-op gets `Creating zones
   needs operator permission`. Set `golems_require_zone` to true → a golem
   outside every zone stands still, inside a zone it works.

## 2. The feather and the picker

1. Hold the feather, **sneak-right-click the air** → the picker opens:
   fourteen category buttons, Catch-all / Off-limits / Remove labels /
   Empty clipboard, and a search box listing the items you carry.
2. Type `iron`, click **Iron Ingot** → its stops appear: `Iron Ingot
   (exact item only)`, `Ingots › Iron · N items`, `Ingots · N items`, …
   (hover shows the raw tag id). Click the **exact item** → the screen
   closes and the actionbar says `Clipboard: Iron Ingot`.
3. Place a chest and sneak-**right**-click it → `Labeled chest: Iron
   Ingot` with green particles. Right-click it normally → `Golem labels:
   Iron Ingot` (no `(auto)`). This is the **iron chest** used below.
4. Sneak-**left**-click the iron chest → `Copied labels: Iron Ingot`,
   sparkles, the chest is **not** damaged and mining does not start.
   Sneak-right-click two more chests → both labeled Iron Ingot.
5. Picker → **Catch-all** → paste onto a fresh chest: the **catch-all
   chest**. Picker → **Off-limits** → paste onto another: golems must
   never use it.
6. Picker → **Remove labels** → paste onto one of the extra iron chests →
   `Cleared labels …`; right-click shows no label line.
7. Sneak-left-click an unlabeled chest → `Clipboard cleared …`; then
   sneak-right-click a chest → `Nothing to paste …`, nothing changes.
8. Without sneaking, right-clicking a chest with the feather opens it as
   usual; left-clicking mines it as usual. Sneak-right-click a *block*
   that is not a chest → no picker, just the clipboard line.
9. Die and respawn, or relog → the clipboard is still there.
10. Copper chests: sneak-left-click the zone's copper chest → `Copied
    zone settings: reorganize … tidy …`. Sneak-right-click a copper chest
    in a *different* zone → `Applied zone settings …`; open its screen →
    toggles match, its area is unchanged.

## 2b. The Golem button and the chest editor

1. Open the iron chest normally → a **Golem** button sits to the right of
   the inventory. Open a barrel or an ender chest → no button.
2. Press it → the editor opens: `Labels of the chest at x y z`,
   `Current: Iron Ingot`, a chip `✕ Iron Ingot`, `Mode: Replace`, the
   categories on the left, and `Suggested from what's inside:` on the
   right with coverage counts (fill the chest with mixed ingots first to
   see several).
3. Click a suggestion → the actionbar confirms, the screen **stays open**
   and the header now shows the new label. Hold the feather afterwards →
   the clipboard is left alone (only a sneak-left-click with the feather copies).
4. Switch to **Mode: Add**, click **Catch-all** → the chest now has two
   labels (two chips). Click the `✕ Catch-all` chip → back to one.
5. Click **Remove labels** → `No labels — vanilla behavior`, chest cleared.
6. Open a copper chest and press **Golem** → the zone screen opens
   instead.
7. Walk more than 10 blocks away with the editor open and click a
   category → nothing changes (the server ignores out-of-reach edits).

## 2c. Overview and simulate

1. In the zone screen press **Overview…** → a list of every chest in the
   zone: unlabeled ones first (yellow), then chests with `· misplaced
   stacks`, then `· same labels as another chest`, then the rest; a
   summary line counts them. **Find** makes a chest sparkle, **Edit**
   opens its editor, **Paste** (enabled when the clipboard has labels)
   gives it the clipboard's labels and the list refreshes.
2. Put Iron Ingot on the clipboard and press **Paste clipboard onto all
   unlabeled (N)** → every unlabeled chest in the zone is labeled, even
   those far from you (you are inside the zone).
3. Fill the copper chest with iron ingots, planks and a cake, with chests
   labeled for iron and planks and no catch-all. Press **Simulate…** →
   the cake row comes first in red (`nowhere to go`), then
   `64× Iron Ingot → Iron Ingot [x y z]` and the planks row; identical
   stacks are merged into one row. Click a row → the destination
   sparkles. Add a catch-all chest, **Refresh** → the cake now has a
   destination.

## 2d. Visual pass over the screens

Open each screen once at GUI scale "Auto" on a 1080p monitor (a 480 × 270
GUI, the smallest the layouts are designed for) and once at the largest
scale your monitor allows, and check:

1. Every screen sits on a dark panel with a thin border, a white title, a
   muted subtitle and a separator under the header; nothing overlaps the
   panel's edge, nothing is cut off at the bottom of the window, and the
   Done button (where there is one) is inside the panel. Widgets are
   drawn over the panel at full brightness, never dimmed under it.
1b. The zone screen is two columns: Behavior and Defaults on the left,
   Area and Tools on the right. The picker is three columns (Categories,
   search, Special/This chest) at 480 px and wider. On narrower GUIs (the
   default 854 × 480 window is 427 × 240) This chest/Special take the left
   column and the categories become a strip of fourteen icon chips under the
   search box, each with the category name in its tooltip. The result list
   grows with the window height (4 rows at 270 px, 3 at 240 px). The
   picker closes with Esc. No button anywhere clips its text.
2. Section labels (`Behavior`, `Area`, `Tools`, `Categories`, `Special`,
   `Any item`, …) are small, muted and sit just above their controls.
3. List rows (search results, stops, suggestions, category members,
   overview chests, simulation moves) are flat, highlight on hover, show
   the item icon on the left and the detail text on the right; a long
   name is cut with `…` instead of running under the detail.
4. State colors: in the overview an unlabeled chest is gold with a gold
   bar, an auto-labeled chest blue, an explicit one white; in the
   simulation a stuck move is red with a red bar; in tuning an added item
   has a green bar and an excluded one a gold bar with a muted name; a
   suggestion that covers every stack has a green bar.
5. Disabled rows and buttons (tuning as a non-op, Paste without a
   clipboard) look dimmed and do nothing when clicked.
6. The HUD panel at the top of the screen uses the same dark surface and border.

## 2e. Defaults, hints and the guide

1. On a fresh player: hang a frame on a chest → a gold `[WBCG]` chat line
   explains auto labels, ending in a clickable `[Read the guide]`. Hang
   another frame → no second hint. First feather gesture → the tool hint,
   once.
2. Click `[Read the guide]` (or `/wbcg guide`) → a written book
   "Copper Golem Sorting" lands in your inventory with 7 pages; the tool
   item's name appears in the text. With a full inventory it drops at
   your feet.
3. Hover each toggle in the zone screen → a tooltip explains it.
4. As an operator, set tidy ON in one zone and press **Save as world
   default** → chat confirms. Place a copper chest far away in no zone,
   open its settings → tidy is already ON. Press **Apply to all zones**
   → chat says how many zones changed; other zones' toggles now match,
   their areas unchanged. As a non-op both buttons answer with the
   permission message.

## 3. Frames: auto labels, never clicked

1. Place a chest with an item frame on its front face holding an **iron
   ingot**. Right-click the chest → `Golem labels: Iron Ingot (auto)`.
2. Put copper and gold ingots inside, close and reopen → `Golem labels:
   Ingots (auto)`. Take them out → back to `Iron Ingot (auto)`.
3. Sneak-click the frame to rotate the item, swap it for a diamond: pure
   vanilla behavior, no mod message. Right-click the chest →
   `Diamond (auto)`.
4. An **empty frame** on a chest → `catch-all (auto)`. A frame on a block
   that isn't a chest never produces a label message.
5. Hang a diamond frame on one of the pasted iron chests → the label
   stays Iron Ingot (explicit labels ignore frames).
6. With a click-through mod installed: right-clicking the frame opens the
   chest and shows the labels; sneak-clicking rotates. Nothing from this
   mod fires on the frame itself.

## 4. Learn pass

1. Build a small room inside the zone: a chest of only diamonds, a chest
   of iron + copper + gold ingots, a chest of ingots plus one stack of
   dirt, a chest of dirt + a bow + a cake, and an empty chest.
2. Open the zone screen and press **Learn this zone's chests…** → the
   screen closes; chat shows `Learn: N chests get a label, M skipped
   (zone area W × H × D)`, one line per chest (`Diamond`, `Ingots`,
   `Ingots misplaced: Dirt`, plus your explicit chests as `already
   labeled`), and `Skipped: … empty … mixed contents`.
3. Click a chest's `[x y z]` → white sparkles rise over that chest.
4. Click **[Apply]** → `Labeled N chests.` Right-click each → summary
   **without** `(auto)`. The dirt is still in its chest; once golems run
   it gets moved to the catch-all.
5. `/wbcg learn 8` standing in the room → the same chests are now
   `already labeled`; `/wbcg learn 8 overwrite` proposes them again;
   `[Cancel]` drops the proposal.
6. As a non-op (default config), `/wbcg learn` is not available and the
   zone-screen button says the pass needs operator permission.

## 5. The tool HUD

1. Hold the feather. A dark panel at the top of the screen shows the clipboard
   line. Switch to another item → the panel disappears. Holding the
   feather in the **off hand** also shows it.
2. Look at the frame chest → `Labels: Iron Ingot (auto, from the frame)`.
   Look at a pasted chest → `Labels: …` without `(auto)`. Look at an
   unlabeled chest → `Unlabeled chest — vanilla behavior`. Look at the
   **frame** itself → the same label line.
3. Look at a copper chest in a zone → `Zone at x y z · area W × H × D` and
   `reorganize on · tidy off · dry run off`, and the zone's outline keeps
   being drawn while you look. A copper chest in no zone → `No zone — …`.
4. Press **Set area…** in the zone screen → the panel's last line turns
   into `Area mode: sneak-right-click the first corner`, then `… the
   opposite corner` after the first click, then back to the clipboard
   line.

## 6. Dry-run pass (do this before anything touches real chests)

1. Room: copper chest + iron chest + catch-all chest + one unlabeled
   chest, all inside the zone.
2. Fill the copper chest with a mix: iron ingots, oak planks, dirt.
3. Confirm dry-run is ON. Summon a golem.
4. **Expect:** golem walks to the copper chest, opens it, takes nothing;
   log shows `would move` lines naming the right destinations
   (iron → iron chest, planks/dirt → catch-all). **No chest contents
   change.** It logs each source once, then again ~5 min later.

## 7. Real sorting

1. Turn dry-run OFF in the zone screen.
2. **Expect** within a minute or two: iron in the iron chest, planks and
   dirt in the catch-all, the unlabeled chest untouched, the off-limits
   chest untouched, copper chest empty.

## 8. Narrow beats broad, full cascades

1. Chest A labeled *Ingots › Iron* (picker → Iron Ingot → `Ingots › Iron`,
   paste); chest B labeled *Ingots* (picker → Iron Ingot → `Ingots`,
   paste).
2. Feed the copper chest iron ingots → they must land in **A**.
3. Feed it **copper ingots** → they match *Ingots* but not *Ingots › Iron*,
   so they must land in **B**.
4. Fill A completely full, feed more iron → it should cascade to **B**.

## 9. Golems respect the zone box

1. Make the zone small (just this room). Outside the box, a few blocks
   away, place another copper chest with items and a chest labeled for
   those items.
2. The golem inside the zone must only take from the copper chest inside
   and only deliver to chests inside; the outside copper chest stays full
   and the outside chest stays empty.
3. Walk the golem (or summon one) outside every zone → it behaves as
   vanilla there: nearest copper chest, label-aware deposits within the
   vanilla 32 × 8 volume.
4. **Staying inside.** Leave the room's door open and **Golems stay
   inside** ON (the default). After the golem has taken from the copper
   chest once (it has joined the zone), watch it idle for a few minutes:
   it strolls inside the box and never steps through the door. Push it
   out of the door with your body, or `/tp` it two blocks outside → it
   turns around and walks back in. Toggle the setting OFF → sooner or
   later it wanders out and starts serving whatever room it ends up in.
   The HUD's zone line shows `stay inside on/off`.

## 9b. Several golems

1. Summon three golems in a one-block-wide corridor between the copper
   chest and the labeled chests → they walk through each other instead of
   jamming; you can still push a golem with your body.
2. Two copper chests with items and two golems → each golem takes a
   different copper chest; with two chests labeled the same, each golem
   delivers to a different one. One copper chest and three golems → they
   queue at it as in vanilla.

## 10. Vertical reach (the tall-wall feature)

1. Build a wall of chests 5 high standing on the floor. Label the **top**
   chest (4 above the golem's feet) with something distinctive, e.g.
   gold ingot.
2. Feed gold ingots into the copper chest.
3. **Expect:** the golem deposits into the top chest while standing on
   the floor — no climbing, no scaffolding. Reach is 6 blocks by default;
   a chest 7 above the floor is out of reach.
4. In the zone screen, press **+** next to *Reach* until it reads 10, and
   add chests up to 9 above the floor. The golem now serves those too.
   Press **−** back to 1 and watch it give up on anything above its head.
   The HUD's zone line shows "reach N" and follows the stepper.

## 11. Reorganize existing chests

1. Manually shove a stack of dirt into the iron chest. Open the zone
   screen → **Simulate…** → a `reorganize: 64× Dirt → [catch-all]` row.
2. Empty the copper chest and wait. This is background work that only
   runs when there is nothing to deliver: expect up to a minute of idling
   first, then about one move every ten seconds.
3. **Expect:** golem takes exactly the dirt (iron untouched) and moves it
   to the catch-all. One stray stack must never re-label a chest. Put a
   cake into a *Stone & Dirt* chest with a *Food* chest nearby → the cake
   ends up in the Food chest.
4. Toggle reorganize OFF in the zone screen, plant more dirt → it stays.

## 12. Tidy sibling chests (off by default)

1. Label two chests *Stone & Dirt*. Fill chest A with 26 stacks of stone
   and one stack of dirt, and chest B with two stacks of dirt. Empty the
   copper chest so the golem is idle.
2. With tidy OFF nothing happens (reorganize leaves dirt alone: it matches
   the label). Open the zone screen → **Overview…** → the summary already
   says `1 tidy move pending`; **Simulate…** shows `tidy: 64× Dirt →
   [B]` as the last row.
3. Turn tidy ON and wait (background work, up to a minute or two): the
   golem walks to A, takes exactly the dirt, and puts it into B. Chest A
   now has a free slot; feed stone into the copper chest → it lands in A.
4. Interleave stacks in a labeled chest (dirt, cobble, sand, cobble, dirt,
   cobble, sand…) and leave the golem idle → **Simulate…** shows `sort: N
   stacks out of order`; within a minute the golem walks to the chest,
   opens it without taking anything, and afterwards the stacks are grouped
   by item with the item that has the most stacks first, full stacks before
   partial ones, no gaps. The same happens to any chest it picks up from
   or delivers into.
5. A chest full of one item is never touched: fill a chest with 27 stacks
   of cobblestone and label a sibling the same with a few cobblestone →
   nothing moves either way (no room). Dry run ON → the log shows
   `[DRY-RUN] would tidy 64x minecraft:dirt from … to …` and nothing
   moves.

## 12b. Golems hang frames (off by default)

1. Turn **Golems hang frames** ON and drop a stack of item frames into the
   copper chest along with some iron. The iron chest must have air in
   front of its face and no frame there yet.
2. The golem delivers the iron first (it never carries the frames as
   cargo), then goes back for one frame, walks to the iron chest and hangs
   it on the front face showing an iron ingot; the chest has one ingot
   less. Right-click the chest → the label summary is unchanged (no
   `(auto)`).
3. Repeat with a chest labeled *Ingots* holding mostly copper ingots and a
   few iron → the frame shows a copper ingot. A chest that already has a
   frame, a catch-all chest, an off-limits chest, an unlabeled chest and a
   chest whose front face is blocked never get one.
4. Toggle the setting OFF → the frames are ordinary cargo again (they go
   wherever frames are labeled to go) and no frame is hung.
5. Put a slab (or any block) in front of a labeled chest and feed it →
   the golem delivers but never fetches a frame. Remove the slab after
   the golem has picked up a frame and is walking over, then put it back
   before it arrives → the golem turns around and drops the frame back
   into the copper chest; nothing is hung and no item is taken from the
   chest.
6. Open a chest's editor and click the small item-frame chip beside
   *Mode* → it turns red (`Golem frames: not on this chest`) and the chest
   never gets a frame even with the zone setting on; click again to allow
   it. The switch needs the same permission as a label edit.
7. With the chip red, sneak-left-click that chest → `Copied labels: Iron
   Ingot · no golem frames`; the HUD shows the same. Sneak-right-click
   another chest → `Labeled chest: Iron Ingot · no golem frames` and its
   editor chip is red too. The overview's **Paste** does the same. Copy a
   chest whose chip is green and paste again → the chip turns green.
   Picker → Iron Ingot → the clipboard line loses `· no golem frames`.

## 12c. Idle golems perch (off by default)

1. Turn **Idle golems perch** ON and empty the copper chests. Within a
   minute the golem walks to a chest, climbs on top and stays there, no
   strolling. Two golems pick two different chests. A chest under a golem
   still opens.
2. Drop items into a copper chest → the golem climbs down at once and
   sorts them, then goes back to a perch. Toggle the setting OFF → it
   stays on the floor and strolls like before.

## 13. Persistence and upgrading

1. Break the frame of the auto-labeled chest → feed matching items → they
   must **still** go there (the label lives on the chest). Hang a frame
   with a different item → the label follows it.
2. Explicit labels, zones and the clipboard survive a save/quit/reload.
3. **Migration, on a copy of a world from the frame-click version:**
   right-click a chest whose frame was cycled to a tag → the summary shows
   that tag **without** `(auto)`; open a copper chest that had settings →
   the zone screen shows those toggles with a default area; golems keep
   sorting as before.

## 14. Categories and tuning

1. `/wbcg categories` lists 14 presets; `/wbcg category test redstone
   minecraft:piston` answers membership questions without a golem.
2. Label a chest **Redstone** (picker → Redstone → paste). Feed redstone
   items into the copper chest → they land there.
3. Open the picker → **Tune categories…** → **Redstone** → the tuning
   screen: `Contents of Redstone`, a paged list of its items with icons,
   a search box. Type `glow`, click **+ Glowstone** → the actionbar says
   `Added Glowstone to Redstone`, the list now shows `+ Glowstone`. Feed
   glowstone → it sorts into the Redstone chest. `/wbcg category list
   redstone` shows the tweak; it survives a restart.
4. Click **Piston** in the list → `Removed Piston from Redstone`; it moves
   to the end as `excluded: Piston`; click it again → restored.
5. Hold a redstone torch, press **Add held item** → no change (already a
   member); hold a bell, press it → added. **Reset tweaks** → all gone.
6. Open the picker, search `iron`, click Iron Ingot, press the **…** next
   to `Ingots` → the tuning screen for `c:ingots` opens.
7. As a non-op, the tuning screen opens but every item button and the
   add/reset buttons are disabled, with a red hint.

## 15. Safety checks

1. Kill a golem mid-carry (`/kill @e[type=copper_golem]` while it holds
   items) → the held stack drops on the ground. Count items: nothing
   duplicated, nothing voided.
2. Double chests: paste a label onto one half, confirm the HUD shows it on
   both halves and deposits work into either half.
3. Wax/oxidize the copper chest (honeycomb / waiting) → zone and golem
   behavior unchanged (all 8 variants are covered).
4. Change `tool_item` in `config/waybettercoppergolem.json` to
   `minecraft:stick`, restart → the stick is the tool (HUD, copy/paste,
   picker), the feather does nothing special.
5. **Uninstall test, on a copy of the world:** remove the mod, load the
   copy → world opens fine, chests intact with contents, golems behave
   pure vanilla. (Attachments are silently dropped by vanilla; re-adding
   the mod later re-derives frame labels, explicit ones are gone.)

---

If all of that passes, the jar that goes on EnxadaHost is the same one
you just tested; server + every client, and you're done.
