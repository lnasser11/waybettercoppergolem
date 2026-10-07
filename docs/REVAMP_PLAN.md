# Usability revamp — implementation plan

Goal: configuring a storage room should take minutes, not an evening, and
must keep working with click-through mods (non-sneak clicks on an item
frame go to the block behind it; sneak-click is the only frame interaction
left). Everything that used to happen by clicking frames moves to the
chest itself, to a held tool, or to a bulk pass that reads the room.

The design discussion that led here, condensed:

- **Frames are never clicked.** The chest block entity owns its labels.
  A frame on an unlabeled chest still gives a free default label, but
  that default is derived, and anything the player sets explicitly wins.
- **Read the room instead of describing it.** A `learn` pass infers a
  label for every chest from what it already contains, previews the
  result, and applies it in one click. Per-chest editing is for the
  exceptions.
- **A held vanilla item is the labeling tool.** Default: a feather.
  Sneak-left-click copies a chest's labels, sneak-right-click pastes
  them, sneak-right-click the air opens a picker that fills the
  clipboard. The same tool copies and pastes copper-chest zone settings.
- **See labels without opening anything.** While holding the tool, the
  chest you look at shows its labels on the HUD, and the HUD shows the
  clipboard.

## Phase 1 — read the room, paint the rest

Deliverables, in build order. Each step leaves the mod shippable.

### 1. Chest-owned label model

Replace the `List<ChestLabel>` attachment with a record:

```
ChestLabelSet { List<ChestLabel> labels; boolean explicit }
```

- Same attachment id (`waybettercoppergolem:chest_labels`). Codec is
  `Codec.withAlternative(record, legacyList)`; a legacy list decodes as
  `explicit = false`.
- **Synced to clients** (`AttachmentRegistry.create(id, b -> b.persistent(codec)
  .syncWith(streamCodec, AttachmentSyncPredicate.all()))`) so the HUD can
  read it straight off the client block entity.
- `explicit = true` is set by paste, learn-apply, the picker, and
  commands. It is never overwritten by frames.
- `explicit = false` labels are **derived from frames** ("smart frame"):
  candidate stops are the frame item's exact stop plus
  `LabelResolver.orderedTags(item)`; the chosen stop is the narrowest one
  that covers every distinct item currently in the chest (empty chest →
  exact). Empty frame → catch-all, cobweb → off-limits, as today. Several
  frames → union, as today.
- Derived labels are recomputed when: a chest is right-clicked, a frame
  on a chest is used (vanilla handles the click, recompute next tick),
  learn scans the chest, or a golem evaluates it. Frame removal keeps
  the cached labels, as today.
- One-time migration: a frame carrying the legacy `frame_tag` attachment
  turns into an explicit label on its chest, then the attachment is
  removed from the frame.
- Double chests: explicit labels are written to **both halves** so the
  container never disagrees with itself; `effectiveLabels` keeps the
  union.

Removed: the sneak-click frame cycle, the sneak-click-with-item category
tuning gesture, and all writes to `FRAME_TAG`. Tuning stays reachable via
`/wbcg category add|remove|reset` until phase 2 gives it a screen.

Files: `label/ChestLabel.java` (unchanged), new `label/ChestLabelSet.java`,
`label/ChestLabels.java` (derivation, explicit set, migration),
`WayBetterCopperGolem.java` (attachment registration, handler removal).

### 2. Config file

`config/waybettercoppergolem.json`, read on server start, written with
defaults if missing:

```json
{
  "tool_item": "minecraft:feather",
  "learn_radius": 32,
  "learn_requires_op": true
}
```

- Unknown item id → log a warning and fall back to the feather.
- Sent to each client on join as a `wbcg:config` payload so the client
  knows which item is the tool. If the payload never arrives (vanilla
  server, old mod), the client assumes the feather.

Files: new `config/WbcgConfig.java`, new `net/ConfigPayload.java`,
`WayBetterCopperGolemClient.java` (receiver).

### 3. The tool (feather)

All gestures require sneaking and the tool in the **main hand**. None of
them fire on frames, so the click-through mod and vanilla frame handling
are untouched.

| Gesture | On a chest / trapped chest | On a copper chest | In the air |
|---|---|---|---|
| sneak-**left**-click | copy labels; unlabeled → clear clipboard | copy zone settings | — |
| sneak-**right**-click | paste labels (replace, explicit); empty clipboard → hint message | paste zone settings | open the label picker |

- `AttackBlockCallback` returns `SUCCESS` so no block breaking starts
  (client and server both run the callback, so no crack animation).
- `UseBlockCallback` returns `SUCCESS`; vanilla does nothing for
  sneak-right-click with an item anyway, so nothing is lost.
- `UseItemCallback` on the client opens the picker screen directly; the
  server side ignores the event.
- Feedback: actionbar message naming what was copied/pasted, plus
  particles at the chest (`WAX_ON` for copy, `HAPPY_VILLAGER` for paste)
  via `ServerLevel.sendParticles`.
- Clipboard: a persistent, `copyOnDeath` **player attachment** holding
  `Optional<ChestLabelSet>` and `Optional<ZoneSettings>`, synced
  `targetOnly` so the owning client can show it on the HUD. Nothing is
  ever stored on the item stack.

Files: new `tool/LabelTool.java` (gesture handlers), new
`tool/Clipboard.java` (record + codecs), `WayBetterCopperGolem.java`
(registration).

### 4. Learn pass

`/wbcg learn [radius]` (default `learn_radius`, centered on the player)
and a **"Learn this zone"** button on the zone settings screen (centered
on the copper chest, radius = that zone's search radius).

Algorithm, per chest (double chests counted once, explicit chests skipped
unless `/wbcg learn <radius> overwrite`):

1. `D` = distinct items in the chest. Empty → skip ("empty").
2. `|D| == 1` → exact label.
3. Otherwise candidates = every stop of every item in `D`; keep those
   that match **all** of `D` (honoring category overrides via
   `CategoryTuning.matches`); pick the one with the fewest members.
4. No candidate and `|D| >= 3` → retry once with each single item left
   out; keep the narrowest result and report the left-out item as
   **misplaced** (reorganize will move it later).
5. Still nothing → skip ("mixed contents").
6. Catch-all and off-limits are never inferred.

Flow: the proposal is stored per player (expires after 2 minutes) and
printed as a summary line plus up to 15 entries with hover coordinates
and a click action that highlights the chest with particles
(`/wbcg highlight <pos>`), then an **[Apply]** chat button that runs
`/wbcg learn apply`. Skipped chests are listed with their reason. Apply
re-checks each block is still a chest, writes explicit labels, and
reports the count. Requires permission level 2 when `learn_requires_op`
is true.

Files: new `learn/RoomLearner.java` (scan + inference), new
`learn/LearnSession.java` (pending proposals), `command/WbcgCommand.java`
(`learn`, `learn apply`, `highlight`), `zone/ZoneSettingsMenu.java` +
`client/ZoneSettingsScreen.java` (button).

### 5. Label picker screen

Opened by sneak-right-clicking the air with the tool. Phase 1 fills the
**clipboard only**; phase 2 reuses it as the per-chest editor.

Layout (vanilla widgets, no textures):

- **Categories**: the 12 presets as buttons, friendly names from lang.
- **Special**: Catch-all, Off-limits, Clear clipboard.
- **Item**: an `EditBox` search over the client item registry; picking
  an item lists its stops (exact, then `orderedTags`) with friendly
  names and member counts; pick one.
- Current clipboard shown at the top.

Selection sends one `wbcg:set_clipboard` payload carrying a
`ChestLabel`; the server validates that the item/tag exists and stores
it in the player's clipboard. The synced attachment updates the HUD.

Friendly tag names: `wbcg:` → lang entry; `c:ingots/iron` →
"Ingots › Iron"; `minecraft:wooden_slabs` → "Wooden Slabs"; raw id in the
tooltip. Stops whose only member is the exact item are dropped.

Files: new `client/LabelPickerScreen.java`, new `net/SetClipboardPayload.java`,
`label/LabelResolver.java` (friendly names, stop filtering).

### 6. HUD while holding the tool

Fabric `HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, …)`.
Renders only when the tool is in either hand:

- Looking at a chest within reach: its labels, suffixed "(auto)" when
  derived, or "Unlabeled — vanilla behavior".
- Looking at a copper chest: "Sorting zone · radius 32 · reach 4 ·
  reorganize on". `ZONE_SETTINGS` becomes a synced attachment for this.
- Second line: "Clipboard: …" or "Clipboard empty — sneak-left-click a
  chest to copy, sneak-right-click the air to pick".

Files: new `client/ToolHud.java`, `WayBetterCopperGolemClient.java`,
`WayBetterCopperGolem.java` (zone settings sync).

### 7. Docs and strings

- README "How to use it" rewritten around: hang frames or fill chests →
  run learn → fix exceptions with the feather. Command table updated.
- `docs/TESTING.md` replaced for the new flow (frame click sections
  removed; tool, learn, HUD, picker, migration tests added).
- `en_us.json` / `pt_br.json`: tool messages, learn output, picker,
  HUD, friendly names.

### Networking summary (phase 1)

| Payload | Direction | Content |
|---|---|---|
| `wbcg:config` | S2C on join | tool item id |
| `wbcg:set_clipboard` | C2S | one `ChestLabel`, or empty = clear |
| chest labels, zone settings, clipboard | Fabric attachment sync | automatic |

### Acceptance tests (solo world)

1. Old world with frame-cycled labels loads; labels still apply; HUD
   shows them without "(auto)"; `frame_tag` is gone from frames.
2. Hang an oak-planks frame on a chest of mixed planks → HUD shows
   "Planks (auto)". Empty the chest → "Oak Planks (auto)".
3. With a click-through mod installed, normal clicks on a frame open the
   chest; sneak-clicks rotate the item; nothing from this mod fires.
4. Feather: copy from a labeled chest, paste onto three others, HUD and
   actionbar agree, particles show, labels are explicit. Copy an
   unlabeled chest → clipboard cleared. Paste with empty clipboard →
   hint, no change.
5. Feather on copper chests: copy settings from one, paste to another,
   reopen its settings screen → values match.
6. Picker: choose "Food" from the air → clipboard shows Food → paste.
   Search "iron", pick iron ingot, pick "Ingots › Iron" → paste.
7. Learn: room of 10 chests (6 single-item, 2 same-category mixed, 1
   with one stray stack, 1 random mix). Preview lists 9 proposals and 1
   skipped; the stray is reported misplaced; clicking an entry
   highlights the chest; Apply labels 9 chests; golems sort accordingly
   and later move the stray.
8. Learn from the zone screen button produces the same result centered
   on the copper chest.
9. Server with mod on both sides: config payload arrives (change
   `tool_item` to a stick, restart, HUD reacts to the stick).
10. Uninstall on a world copy: loads clean, no errors about attachments.

## Phase 2 — screens

- A golem button in every chest inventory screen (mixin on the generic
  container screen, position announced by a small S2C payload when the
  chest opens) → label editor on regular chests, zone settings on copper
  chests. This replaces the sneak-right-click-empty-hand entry.
- Per-chest editor = the picker in "chest mode", with **suggestions from
  contents** at the top ("Ores & Minerals covers 25 of 27 stacks").
- Category tuning UI inside the editor (grid of members, click to
  exclude, add held item).
- Zone overview from the copper chest: every chest in range, problems
  first (unlabeled, duplicate labels, items with no destination), click
  to highlight, bulk apply/clear.
- "Simulate" in the zone screen: where each stack in the copper chest
  would go right now, no golem needed.

## Phase 3 — polish

- World-default zone settings with per-chest overrides; sliders and
  tooltips in the zone screen.
- Onboarding: one chat hint the first time a player places a frame on a
  chest or picks up the tool item; `/wbcg guide` hands out a written
  book.
- Optional: sign text as a label hint for the learn pass.
