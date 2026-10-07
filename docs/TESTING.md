# In-game test plan (solo world, before the server)

Run these in order — each step builds on the last. Use a **new creative
world** (or a copy of an existing one, never the original). Singleplayer
runs an integrated server, so everything works without EnxadaHost.

**Setup:** Fabric Loader 0.19.3 profile for 26.2, with `fabric-api` and
`waybettercoppergolem-1.0.0.jar` in the mods folder.

**Watching logs:** dry-run lines go to the game log. Either enable "Open
output log when game starts" in the launcher, or watch
`.minecraft/logs/latest.log`. Lines look like:
`[DRY-RUN] would move 12x minecraft:iron_ingot from minecraft:copper_chest@... to minecraft:chest@...`

**Getting a golem:** `/summon minecraft:copper_golem`, or the survival
way (carved pumpkin on a copper block). Stay near the test area — golems
only work in loaded chunks, and note their rhythm: ~3 s at each chest,
7 s pause when they find nothing. That's vanilla, not a bug.

---

## 1. Labels from frames (never clicked)

1. Place a regular chest. Put an item frame on its front face with an
   **iron ingot** in it. Right-click the chest: the actionbar should show
   `Golem labels: Iron Ingot (auto)` and the chest opens normally.
2. Put copper ingots and gold ingots into the chest, close and reopen →
   `Golem labels: #c:ingots (auto)` (the smart-frame rule: narrowest tag
   of the framed item that covers everything inside). Take them out
   again → back to `Iron Ingot (auto)`.
3. Sneak-click the frame to rotate the item, swap the item for a diamond:
   pure vanilla behavior, no mod message. Right-click the chest →
   `Diamond (auto)`.
4. Put an **empty frame** on a second chest; right-click it →
   `Golem labels: catch-all (auto)`.
5. A frame on a block that isn't a chest never produces a label message.
6. If you have a click-through mod installed, right-clicking the frame
   opens the chest and shows the labels; sneak-clicking rotates. Nothing
   from this mod fires on the frame itself.

## 1b. The label tool (feather)

1. Hold a **feather** in your main hand. Sneak-**left**-click the
   iron-labeled chest from step 1 → `Copied labels: Iron Ingot`, sparkle
   particles, the chest is **not** damaged and mining does not start.
2. Sneak-**right**-click three unlabeled chests → each says
   `Labeled chest: Iron Ingot` with green particles; right-clicking one
   normally shows `Golem labels: Iron Ingot` **without** `(auto)`.
3. Hang a diamond frame on one of those chests → the label stays Iron
   Ingot (explicit labels ignore frames).
4. Sneak-left-click an unlabeled chest → `Clipboard cleared …`. Then
   sneak-right-click a chest → `Nothing to paste …` and nothing changes.
5. Sneak-right-click the air → the clipboard message. Die and respawn, or
   relog → the clipboard is still there.
6. Without sneaking, right-clicking a chest with the feather opens it as
   usual; left-clicking mines it as usual.
7. Copper chests: change a zone's settings in the GUI (step 2), then
   sneak-left-click it → `Copied zone settings: radius … reach …`.
   Sneak-right-click another copper chest → `Applied zone settings …`;
   open its GUI → values match.

## 1c. Learn pass

1. Build a small room: a chest of only diamonds, a chest of iron + copper
   + gold ingots, a chest of ingots plus one stack of dirt, a chest of
   dirt + a bow + a cake, and an empty chest. Stand in the middle.
2. `/wbcg learn 8` → chat shows a header like `Learn: 3 chests get a
   label, 2 skipped`, one line per chest (`Diamond`, `#c:ingots`,
   `#c:ingots misplaced: Dirt`), and `Skipped: … empty … mixed contents`.
3. Click a chest's `[x y z]` → white sparkles rise over that chest.
4. Click **[Apply]** → `Labeled 3 chests.` Right-click each → summary
   **without** `(auto)`. The dirt is still in its chest; with a golem and
   a catch-all chest nearby it gets moved later.
5. `/wbcg learn 8` again → the three are now `already labeled`; `/wbcg
   learn 8 overwrite` proposes them again.
6. Open a copper chest's settings and press **Learn this zone's
   chests…** → the screen closes and the same kind of preview appears,
   centered on the copper chest with the zone's search radius.
7. As a non-op (default config), `/wbcg learn` is not available and the
   zone-screen button says the pass needs operator permission.

## 2. Settings GUI

1. Place a copper chest. **Sneak-right-click it with an empty hand** →
   settings screen opens (reorganize / tidy / dry-run toggles, search
   radius, vertical reach).
2. Normal right-click still opens it as storage; sneak-right-click
   *holding a block* still places the block (vanilla).
3. Toggle **dry-run ON**, close, reopen → the toggle must still be ON
   (it round-tripped through the server).
4. This screen is the one part never tested during development — report
   anything visually broken.

## 3. Dry-run pass (do this before anything touches real chests)

1. Room: copper chest + iron-labeled chest (exact) + catch-all chest +
   one unlabeled chest, all within a few blocks.
2. Fill the copper chest with a mix: iron ingots, oak planks, dirt.
3. Confirm dry-run is ON. Summon a golem.
4. **Expect:** golem walks to the copper chest, opens it, takes nothing;
   log shows `would move` lines naming the right destinations
   (iron → iron chest, planks/dirt → catch-all). **No chest contents
   change.** It logs each source once, then again ~5 min later.

## 4. Real sorting

1. Turn dry-run OFF in the GUI.
2. **Expect** within a minute or two: iron in the iron chest, planks and
   dirt in the catch-all, the unlabeled chest untouched, copper chest
   empty.

## 5. Narrow beats broad, full cascades

1. Chest A labeled `#c:ingots/iron` (iron ingot frame, cycled once);
   chest B labeled `#c:ingots` (iron ingot frame, cycled twice).
2. Feed the copper chest iron ingots → they must land in **A**.
3. Feed it **copper ingots** → they match `#c:ingots` but not
   `#c:ingots/iron`, so they must land in **B**.
4. Fill A completely full, feed more iron → it should cascade to **B**.

## 6. Vertical reach (the tall-wall feature)

1. Build a wall of chests 5 high standing on the floor. Label the **top**
   chest (4 above the golem's feet) with something distinctive, e.g. a
   gold ingot frame.
2. Feed gold ingots into the copper chest.
3. **Expect:** the golem deposits into the top chest while standing on
   the floor — no climbing, no scaffolding.
4. In the GUI, drop vertical reach to 1 and feed more gold → it should
   now NOT reach it (goes to catch-all or shrugs). Set it back to 4.

## 7. Reorganize existing chests

1. Manually shove a stack of dirt into the iron-labeled chest.
2. Empty the copper chest and wait. This is deliberately slow,
   low-priority background work: expect up to a minute or two of idling
   first.
3. **Expect:** golem takes exactly the dirt (iron untouched) and moves it
   to the catch-all. One stray stack must never re-label a chest.
4. Toggle reorganize OFF in the GUI, plant more dirt → it should stay put.

## 8. Tidy-inside (off by default)

1. Confirm the iron chest stays fragmented after deposits with tidy OFF.
2. Turn tidy ON. Spread partial iron stacks across scattered slots of the
   iron chest (e.g. 10 / 5 / 20 with gaps).
3. Feed iron into the copper chest. After the golem's deposit,
   **expect** the chest compacted: merged stacks from slot 0, no gaps,
   same total count.

## 9. Frame persistence (creeper insurance)

1. Break the iron chest's label frame (punch it twice).
2. Feed iron into the copper chest → it must **still** go to that chest
   (the label lives on the chest itself).
3. Put a frame with a **diamond** on that chest → its label updates to
   diamond; iron now goes elsewhere.
4. **Migration:** on a world from before this version with frames that
   were cycled to a tag, right-click the chest → the summary shows the
   old tag **without** `(auto)` (it was converted to an explicit label),
   and golems keep sorting as before.

## 10. Safety checks

1. Kill a golem mid-carry (`/kill @e[type=copper_golem]` while it holds
   items) → the held stack drops on the ground. Count items: nothing
   duplicated, nothing voided.
2. Double chests: label one half, confirm deposits work into either half
   and the label applies to the whole inventory.
3. Wax/oxidize the copper chest (honeycomb / waiting) → settings and
   golem behavior unchanged (all 8 variants are covered).
4. **Uninstall test, on a copy of the world:** remove the mod, load the
   copy → world opens fine, chests intact with contents, golems behave
   pure vanilla. (Label attachments are silently dropped by vanilla;
   re-adding the mod later re-reads frames.)

## 11. Categories and tuning

1. `/wbcg categories` lists 12 presets; `/wbcg category test redstone
   minecraft:piston` answers membership questions without a golem.
2. Label a chest **Redstone** explicitly (learn pass, label tool or
   picker; until those ship, use a frame on a chest whose contents only
   share the preset — presets are never auto-derived, so expect an exact
   label there). Feed redstone items into the copper chest → they land
   in the Redstone chest.
3. `/wbcg category add redstone minecraft:glowstone` → feed glowstone → it
   now sorts into the Redstone chest. `/wbcg category list redstone`
   shows the tweak; it survives a restart.
4. `/wbcg category remove redstone minecraft:glowstone` → back to normal.

---

If all of that passes, the jar that goes on EnxadaHost is the same one
you just tested; server + every client, and you're done.
