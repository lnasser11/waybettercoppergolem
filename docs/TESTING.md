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

## 1. Labels and the actionbar

1. Place a regular chest. Put an item frame on its front face with an
   **iron ingot** in it.
2. **Sneak-click the frame** repeatedly. The actionbar should cycle:
   `Label: Iron Ingot (exact item only)` → `Label: #c:ingots/iron` →
   `Label: #c:ingots` → back to exact.
3. Normal (non-sneak) click should still rotate the framed item.
4. Put an **empty frame** on a second chest; sneak-click →
   `Label: catch-all (unmatched items go here)`.
5. A frame on a block that isn't a chest: sneak-click should rotate as
   vanilla (no label message).

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
4. Toggle reorganize OFF in the GUI, plant more dirt → it should stay
   put. (Golems obey the nearest copper chest even before their first
   pickup, so the toggle applies to freshly spawned golems too.)

## 8. Tidy-inside (off by default)

1. Confirm the iron chest stays fragmented after deposits with tidy OFF.
2. Turn tidy ON. Spread partial iron stacks across scattered slots of the
   iron chest (e.g. 10 / 5 / 20 with gaps).
3. Feed iron into the copper chest. After the golem's deposit,
   **expect** the chest compacted: merged stacks from slot 0, no gaps,
   same total count.

## 9. Frame persistence (creeper insurance)

1. Break the iron chest's label frame (punch it).
2. Feed iron into the copper chest → it must **still** go to that chest
   (the label is cached on the chest itself).
3. Put a frame with a **diamond** on that chest → its category updates;
   iron now goes elsewhere.

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

1. Frame a **piston** on a chest; sneak-click with an empty hand until the
   actionbar shows `Label: Category: Redstone`. Feed redstone items into
   the copper chest → they land there.
2. Hold **glowstone** and sneak-click that frame → "Added Glowstone to
   Redstone". Feed glowstone → it now sorts into the Redstone chest.
   `/wbcg category list redstone` shows the tweak; it survives a restart.
3. Hold glowstone and sneak-click again → "Removed Glowstone from
   Redstone" (back to normal).
4. `/wbcg categories` lists 12 presets; `/wbcg category test redstone
   minecraft:piston` answers membership questions without a golem.
5. Remove an item's category **while a golem is carrying that item** (or
   fill/remove every chest that would take it) → the golem brings the
   stack back to the copper chest and retries ~30 s later. It must never
   freeze holding an item.

## 12. Carry amount and category editor

1. In the copper chest GUI set **Carry amount: 64**; put 40+ of one item
   in the copper chest → the golem moves it in a **single trip**. Set it
   back to 16 → back to small armfuls.
2. Open **Categories…** from the settings screen: pick a category, use
   the search box, click items to toggle membership (green = in category;
   corner marks = your tweaks). Changes behave exactly like frame tuning
   and show in `/wbcg category list`.
3. Icon swap: on a frame set to a category, pop the item out and put a
   different item in → the chest keeps the category (open it to confirm
   the actionbar), only the shown icon changes. Sneak-click the frame
   while it's empty to reset it to catch-all.

---

If all of that passes, the jar that goes on EnxadaHost is the same one
you just tested; server + every client, and you're done.
