# In-game test plan (solo world, before the server)

Run these in order — each step builds on the last. Use a **new creative
world** (or a copy of an existing one, never the original). Singleplayer
runs an integrated server, so everything works without EnxadaHost.

The automated game tests (`./gradlew build` runs them) already cover the
label model, the learn inference, the tool's copy/paste, zones and the
picker's server side. This checklist is for what needs a real client:
screens, the HUD, click handling, golems moving, and how it all feels.

**Setup:** Fabric Loader 0.19.3 profile for 26.2, with `fabric-api` and
`waybettercoppergolem-1.0.0.jar` in the mods folder. Keep a **feather** in
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

## 2. The feather and the picker

1. Hold the feather, **sneak-right-click the air** → the picker opens:
   twelve category buttons, Catch-all / Off-limits / Remove labels /
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

1. Hold the feather. A dark panel above the hotbar shows the clipboard
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

## 10. Vertical reach (the tall-wall feature)

1. Build a wall of chests 5 high standing on the floor. Label the **top**
   chest (4 above the golem's feet) with something distinctive, e.g.
   gold ingot.
2. Feed gold ingots into the copper chest.
3. **Expect:** the golem deposits into the top chest while standing on
   the floor — no climbing, no scaffolding. Reach is fixed at 6 blocks; a
   chest 7 above the floor is out of reach.

## 11. Reorganize existing chests

1. Manually shove a stack of dirt into the iron chest.
2. Empty the copper chest and wait. This is deliberately slow,
   low-priority background work: expect up to a minute or two of idling
   first.
3. **Expect:** golem takes exactly the dirt (iron untouched) and moves it
   to the catch-all. One stray stack must never re-label a chest.
4. Toggle reorganize OFF in the zone screen, plant more dirt → it stays.

## 12. Tidy inside (off by default)

1. Confirm the iron chest stays fragmented after deposits with tidy OFF.
2. Turn tidy ON. Spread partial iron stacks across scattered slots of the
   iron chest (e.g. 10 / 5 / 20 with gaps).
3. Feed iron into the copper chest. After the golem's deposit,
   **expect** the chest compacted: merged stacks from slot 0, no gaps,
   same total count.

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

1. `/wbcg categories` lists 12 presets; `/wbcg category test redstone
   minecraft:piston` answers membership questions without a golem.
2. Label a chest **Redstone** (picker → Redstone → paste). Feed redstone
   items into the copper chest → they land there.
3. `/wbcg category add redstone minecraft:glowstone` → feed glowstone → it
   now sorts into the Redstone chest. `/wbcg category list redstone`
   shows the tweak; it survives a restart.
4. `/wbcg category remove redstone minecraft:glowstone` → back to normal.

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
