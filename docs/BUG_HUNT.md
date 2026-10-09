# Bug hunt — Way Better Copper Golem 1.1.0

Tree audited: `ebc4a55` (Merge pull request #22), unmodified except for the
test file this hunt added, `src/gametest/java/.../gametest/WbcgBugHuntTests.java`
(registered in `src/gametest/resources/fabric.mod.json`). Nothing else was
changed and nothing was committed. Vanilla claims below were read from the
Loom `genSources` output for 26.2 (Mojang mappings); line numbers refer to
the decompiled files.

Severity order: item loss, permission bypass, wrong behavior, UI, cosmetic.

**Status (follow-up commit):** findings 1–8 are fixed and the flaky
`golemsDoNotPushEachOther` test now judges by velocity with the AI off. The
tests in `WbcgBugHuntTests` that demonstrated 1, 2, 4 and 5 pass with the
fixes and stay as regression tests. What changed, in short: a frame trip
takes exactly one frame out of the hand (1); the zone screen's Learn button
is refused server-side and disabled client-side for non-operators under
`learn_requires_op` (2); unknown names in *Trust* are resolved off the
server thread (3); the remembered chests (pending frame chest, frame return
chest, tidy home) consult the golem's unreachable memory (4); only a chest
inside the zone's box counts as a perch (5); the static maps are cleared on
server stop, the wake throttle tolerates a game time from another world,
and area mode expires after 6000 game ticks instead of 60 s of wall clock
(6); the trusted list is paged (7); the editor shows as many label chips as
fit and pages the rest (8).

---

### 1. Hanging a frame out of a stack held in hand voids the rest of the stack  [data loss / item loss]
**Where:** `src/main/java/.../mixin/TransportItemsBetweenContainersMixin.java:587-603` (`wbcg$rememberBareChest`, `:602` sets the pending chest), `:179-187` (the pending-chest branch of `wbcg$labelAwareDestination`), `:539-563` (`wbcg$hangFrameInsteadOfStoring`); `src/main/java/.../sorting/FrameHanger.java:256-261` (`hang`).
**What happens:** After any deposit, `wbcg$rememberBareChest` marks the chest as the golem's pending frame chest whenever the zone hangs frames and the chest wants one. It does not check that the golem's hand is empty. When the golem was delivering *item frames* into a chest labeled for them (exact *Item Frame*, or *Decoration*, or a chest already holding frames) and the chest filled up, vanilla `putDownItem` leaves the remainder in hand and marks the chest visited; the mod then sets `pending = that chest`. On the next search the pending branch fires because the hand holds "a frame", targets the same chest again (visited memory is ignored on purpose), `wbcg$labelAwareAccept` says yes (the chest still wants a frame), and `FrameHanger.hang` hangs **one** frame and then does `golem.setItemSlot(MAINHAND, ItemStack.EMPTY)`: every other frame in the hand is gone.
**How to reproduce:** `WbcgBugHuntTests.hangingAFrameOutOfAStackInHandLosesTheRest` (fails: `Expected item frames in the chest + in hand + hung (frame and shown item) + dropped to be 1738: was 1729`). In game: zone with *Golems hang frames* on, a chest labeled *Item Frame* with 26 full stacks and one stack of 10, a golem carrying 64 frames (e.g. reorganize picked a misplaced stack of frames out of another labeled chest, or the frames were cargo before the setting was switched on): it deposits 54, keeps 10, walks back, hangs one, and 9 vanish.
**Why it is a bug:** README, Safety guarantees: "No item loss or duplication." README, Golems hang frames: the golem "fetches one frame from a copper chest" — the design assumes the hand holds exactly one frame, the code does not enforce it.
**Suggested fix:** In `FrameHanger.hang` shrink the held stack by one instead of clearing it (`held.shrink(1)`), and in `wbcg$rememberBareChest` only remember the chest when the hand is empty after the deposit.

### 2. The zone screen's "Learn this zone's chests…" ignores `learn_requires_op`  [permission bypass]
**Where:** `src/main/java/.../zone/ZoneSettingsMenu.java:268-273` (`BUTTON_LEARN`); `src/main/java/.../learn/LearnSession.java:55-57` (`allowed`, never consulted on this path); `src/client/java/.../client/ZoneSettingsScreen.java:171-178` (button gated on `canEdit` only); `src/main/java/.../command/WbcgCommand.java:56-58` (the command, including `learn apply`, is op-only).
**What happens:** The button handler only checks that the player may edit the zone (owner, trusted or op) and then runs `LearnSession.preview`, a full scan of the zone's area, for a non-operator. The chat preview ends in `[Apply]`, which runs `/wbcg learn apply`; that command tree requires op, so the non-op owner gets "Unknown or incomplete command". The client enables the button for any editor, so this needs no modified client.
**How to reproduce:** `WbcgBugHuntTests.zoneScreenLearnButtonIgnoresLearnRequiresOp` (fails: `a non-operator got a learn proposal from the zone screen with learn_requires_op on`). In game (default config): as a non-op, create a zone, press *Learn this zone's chests…*: the proposal appears; click *[Apply]*: command error.
**Why it is a bug:** README, Learn: "Learn needs operator permission unless `learn_requires_op` is turned off in the config." `docs/TESTING.md` §4.6: "As a non-op (default config), `/wbcg learn` is not available and the zone-screen button says the pass needs operator permission."
**Suggested fix:** In `BUTTON_LEARN` check `LearnSession.allowed(serverPlayer)` and answer with the permission message; on the client disable the button (reason tooltip) unless `operator || !learnRequiresOp` (the flag would need to travel in `DATA_FLAGS`).

### 3. Trusting an unknown name does a blocking Mojang API lookup on the server thread, for any zone owner  [wrong behavior]
**Where:** `src/main/java/.../zone/ZoneSettingsMenu.java:299-323` (`trust(player, anchor, name, add)`), `:356-366` (`resolve`, line 362: `level.getServer().services().nameToIdCache().get(name)`); handler wired at `WayBetterCopperGolem.java:178-179` from the `ZonePayloads.Trust` packet. Vanilla: `net/minecraft/server/players/CachedUserNameToIdResolver.java:102-123` — on a cache miss `get(String)` calls `lookupGameProfile` → `profileRepository.findProfileByName(name)`, a synchronous HTTP request.
**What happens:** The payload handler runs on the server thread. For a name the server has never seen, the lookup blocks the tick loop until Mojang's session service answers or times out (seconds, longer when the service is slow or the server's outbound network is down). The only gate is `ZoneAccess.canEdit`, so every zone owner or trusted player, no op needed, can stall the whole server repeatedly by typing made-up names (16 chars, `[A-Za-z0-9_]+`, so the regex does not help). Vanilla keeps this kind of lookup behind op-only commands.
**How to reproduce:** Trace above. In game, on an online-mode server as a non-op zone owner: open *Who may change this zone…*, type `zzqxv_nobody_1` and press *Trust* while watching the tick time (`/tps`-style metrics or a second player moving): the server freezes for the duration of the HTTP round trip; repeat with different names at will.
**Why it is a bug:** Audit item 8 (threading and timing: nothing touched from a network handler should block the server thread); a non-op player should not be able to stall the server.
**Suggested fix:** Resolve only online players and names already in the cache (e.g. `nameToIdCache().get(name)` only after checking the cache's own `profilesByName` or by using the async variant with the result applied back on the server thread), or restrict unknown-name lookups to operators.

### 4. A golem carrying a frame (or a tidy stack) for an unreachable chest re-targets it every tick, forever  [wrong behavior]
**Where:** `src/main/java/.../mixin/TransportItemsBetweenContainersMixin.java:179-187` (pending frame chest), `:169-177` (tidy home), `:188-196` (frame return chest): all three `tryCreatePossibleTarget(pos)` and return the chest without consulting `VISITED_BLOCK_POSITIONS` / `UNREACHABLE_TRANSPORT_BLOCK_POSITIONS`. Vanilla: `TransportItemsBetweenContainers.java:334-347` (`hasValidTarget` → `markVisitedBlockPosAsUnreachable` + `stopTargetingCurrentTarget`), `:411-419` (`isPositionAlreadyVisited`, the check the vanilla search applies and these branches skip), `:351-358` (`hasValidTravellingPath`, the per-tick `createPath`), `:436-447` (the unreachable set only triggers a cooldown above 50 entries).
**What happens:** When the chest the golem is heading to with its frame becomes unreachable (door closed, block placed, the golem fell into a pit) vanilla marks it unreachable and drops the target; the mod's next `getTransportTarget` hands the same chest back because the pending branch ignores the unreachable memory. The result is a loop with a path computation on every tick (`hasValidTravellingPath` → `createPath`), the golem standing still holding the frame, never returning it and never doing anything else. The unreachable set holds one position so the 50-entry cooldown never fires. The same holds for the tidy home chest while carrying a tidy stack and for the frame-return copper chest.
**How to reproduce:** `WbcgBugHuntTests.golemGivesUpOnAnUnreachableFrameChest` (fails: after 600 ticks the golem still holds the frame and `pendingFrameChest` is still the walled-in chest; the first version of this test, with a 2-high wall, showed the golem climbing the wall and hanging the frame from above, which is correct behavior with reach 6).
**Why it is a bug:** README, Golems hang frames: a golem that cannot hang the frame "gives up and carries the frame back to the copper chest it came from"; README, Vanilla behavior: unreachable chests are remembered and skipped. A golem that stands still pathfinding every tick is also a server-performance problem in a big room.
**Suggested fix:** In the three branches, skip the remembered chest when its `GlobalPos` is in the unreachable (or visited) memory and clear the remembered position, so the frame-return / normal-delivery flow takes over.

### 5. With "Idle golems perch" on, a confined golem standing on a chest outside its zone never walks back  [wrong behavior]
**Where:** `src/main/java/.../mixin/CopperGolemMixin.java:231-254` (`wbcg$walkBackInside`: `holdThePose` every tick, the walk-back `WALK_TARGET` only every 20 ticks), `:264-275` (`wbcg$holdThePose` erases any `WALK_TARGET` and stops navigation while `wbcg$isPerched`), `:204-213` (`wbcg$isPerched`: any chest under the feet counts, inside or outside the box).
**What happens:** The brain ticks first (`CopperGolem.customServerAiStep`), then the mixin's TAIL code. Tick N (N % 20 == 0): the walk-back target is set. Tick N+1: `MoveToTargetSink` starts walking; then `holdThePose` sees an idle golem (transport cooldown present) standing on a chest and erases the target and stops navigation. The golem stays on the outside chest indefinitely. It gets there by being pushed off a zone-edge chest wall, by falling, or by a teleport.
**How to reproduce:** `WbcgBugHuntTests.confinedGolemPerchedOnAChestOutsideItsZoneNeverWalksBack` (fails: `600 ticks later the golem is still outside its zone … (perched=true, cooldown=true)`). The test seals the zone's only copper chest in glass: when a copper chest is reachable, the transport behavior itself walks the golem to it (and so back inside) every cooldown cycle, which hid the problem in a first version of the test. So the stuck state needs an idle golem (no reachable copper chest with a valid target), *Idle golems perch* on, and a chest top outside the box to land on. The sibling test `confinedGolemTeleportedOutsideWalksBack` in `WbcgGameTests` passes because there the golem lands on the floor.
**Why it is a bug:** README, Golems stay inside: "a golem that is pushed, falls or is teleported out walks back to the nearest spot inside."
**Suggested fix:** Make `wbcg$isPerched` (or `holdThePose`) require the perch to be inside the zone's box (`zone.area().isInside(feet)`), so the walk-back target survives outside.

### 6. Wall-clock timers survive singleplayer pauses and world switches  [wrong behavior]
**Where:**
- `src/main/java/.../sorting/GolemWake.java:35-51`: `LAST_WAKE` is a static map keyed by `GlobalPos` with values in *game time*; the throttle at line 45 is `now - last < 10`. `GolemClaims.java:25-51` has the same shape (`expiresAt` in game time, static map).
- `src/main/java/.../tool/LabelTool.java:66-92, 141-146`: area mode expires 60 s of `System.currentTimeMillis()` after the last click.
**What happens:**
- Static maps outlive the world: in singleplayer, leaving a world and opening another one in the same session keeps every entry. A copper chest at the same coordinates in the new world (same dimension key) compares the new world's game time against the old world's: if the old world was older (larger game time), `now - last` is negative, so `< 10` holds and the wake is swallowed, for every change of that chest, until the new world's game time passes the old one's (the cleanup at >4096 entries only removes entries with `now - value > 10`, so these never go). The golems then fall back to vanilla's 5-minute revisit. `GolemClaims` has the mirror image: stale claims from the old world read as valid in the new one (a preference only, so golems merely avoid those chests).
- Area mode: the README says "the next two sneak-right-clicks with the feather on any blocks are the opposite corners"; the code forgets the selection 60 s after the last click, measured in wall-clock time, so the pause menu (singleplayer) and a walk across a 128-block room both count. The player learns it only at the next click ("Area mode expired") while the HUD still shows the area-mode prompt until then.
**How to reproduce:** Traces above; both are single-process state. World switch: open world A, let it run past the game time of world B, drop an item into a copper chest at a position that holds a copper chest in B too, switch to B, drop items into that chest: no golem wakes.
**Why it is a bug:** Audit item 8 (wall clock vs. game ticks); README, What the golems do: "whenever a copper chest's contents change, every golem nearby drops its idle cooldown … Vanilla would wait up to five minutes."
**Suggested fix:** Clear `GolemWake.LAST_WAKE` and `GolemClaims.CLAIMS` on `ServerLifecycleEvents.SERVER_STOPPING` (or key them by `MinecraftServer` identity); make the area-mode and learn timers tick-based, or at least drop the area-mode timeout and sync `AreaModePayload(0)` when it expires.

### 7. The access screen shows at most 6 trusted players, but the list holds 32  [UI]
**Where:** `src/client/java/.../client/ZoneAccessScreen.java:32` (`MAX_ROWS = 6`), `:63` (`rows = clamp(size, 1, 6)`), `:95` (`for i < min(size, 6)`); `src/main/java/.../zone/ZoneAccess.java:38` (`MAX_TRUSTED = 32`).
**What happens:** With seven or more trusted players the screen shows the first six, without a pager or a scroll. The header still says "7 trusted", and the only way to untrust number seven is… none: the ✕ buttons exist only for the rows shown.
**How to reproduce:** Trust seven names (online players or names the server has seen), open *Who may change this zone…*.
**Why it is a bug:** README, Who may change a zone: "the trusted list holds at most 32 names" and "The owner can trust other players by name from the zone screen's Who may change this zone… button" (with a ✕ to untrust, TESTING §1b.3).
**Suggested fix:** Page the list like the overview does (`<`/`>` buttons), or size `rows` to what fits (`Ui.rowsThatFit`) and page the rest.

### 8. The chest editor shows only two of a chest's labels as removable chips  [UI]
**Where:** `src/client/java/.../client/LabelPickerScreen.java:70` (`MAX_CHIPS = 2`), `:227-238` (chip loop); `src/main/java/.../tool/ChestEditor.java:138` (up to 8 labels accepted).
**What happens:** *Mode: Add* lets a chest accumulate up to eight labels; the "This chest" column renders chips for the first two only. Labels three to eight are listed in the header line but cannot be removed one at a time; the only ways out are *Remove labels* (all of them) or a Replace-mode choice.
**How to reproduce:** Editor on a chest, *Mode: Add*, click three categories, look at the chips.
**Why it is a bug:** README, The Golem button: "the header shows the chest's current labels as chips; click a chip to remove that one label."
**Suggested fix:** Render the chips in a scrolling/paged strip, or at least size `MAX_CHIPS` to the available rows and show a "+N more" chip that opens the full list.

---

## Unconfirmed, needs a human check

- **`golemsDoNotPushEachOther` is flaky, not a mod bug.** It failed once in three runs of the unmodified tree (`two golems pushed each other apart, distance 0.4018 on tick 5`) and passed twice. The vanilla idle activity (`CopperGolemAi.java:84`) lets `RandomStroll` run as soon as the transport behavior sets its cooldown, which happens on the first tick when no copper chest is around, so one golem can start a 2-block stroll before tick 5 and carry the pair past the 0.4 threshold. The `doPush` override itself works: `WbcgBugHuntTests.motionlessGolemsDoNotPushEachOther` (AI off, so `pushEntities()` still runs each tick but nothing else can move the mob) passes, with zero sideways velocity for the golem pair and a push on the golem–pig pair. Suggest raising `maxTicks` and either asserting with AI off as in the new test, or giving the golems a `WALK_TARGET` to their own position for the duration.
- **Reorganize/tidy on a stale `TidyMove`.** `takeTidyStack` and the `tidyContainer` calls in `wbcg$onPickup` (mixin `:442-444`) use the `Container` objects captured when the move was planned, not the one vanilla hands in. Vanilla's `targetHasNotChanged` (`:373`) drops the *source* target if its block entity was replaced, so the source side is safe, but the *home* side (`move.home().container()`) can be a dead block entity if the home chest was broken and re-placed mid-trip: `room()` is then computed on the dead container and the stack is picked up anyway, then delivered through the normal flow. I could not construct a loss or duplication from it (a broken chest's stacks are emptied by `Containers.dropContents`), so this is a robustness note only.
- **`ChestLabels.labelFrames` and `effectiveLabelSet` run on the client for the HUD via `cachedLabelSet` only** — fine; but `WayBetterCopperGolem.onUseBlock` re-derives on every right-click on the server, which is `getEntitiesOfClass` per click. Not a bug, noted for cost.

## Checked and fine

- **Item loss elsewhere.** Reorganize pickup (`stack == misplaced` identity on the same container object vanilla passes), tidy pickup (`takeTidyStack` re-checks the home's labels and room), sort visits (`tidyContainer` only moves counts between live stacks and reassigns every slot exactly once), dry run (every pickup path cancels before any `removeItem`; `maybeTidy` is guarded by `!dryRun`), frame trips (`takeFrame` removes one), frame return (`canAcceptAny` then vanilla store), death mid-carry (`setGuaranteedDrop` on every path that fills the hand). Double chests: both halves get the explicit set; `isVisited`/`isClaimed` check both halves. Vanilla `addItemsToContainer`'s odd `setCount(count - countThatCanBeAdded)` cannot go negative in a way that loses items (a negative count reads as empty only when everything fit).
- **`golems_require_zone` and reorganize.** I suspected the RETURN injector `wbcg$reorganizeSource` would still run after the HEAD injector returned `Optional.empty()` for a zone-less golem; it does not (a cancelling HEAD inject skips the method body, so RETURN injectors never fire). `WbcgBugHuntTests.golemsRequireZoneStillLetsAZonelessGolemReorganize` passes and stays as a regression test.
- **Permissions.** Every serverbound payload re-checks on the server: `SetChestLabels`/`SetChestFrames`/`PasteClipboard`/`OpenEditor` go through `ChestEditor.inReach` (10 blocks or inside the zone the player stands in, loaded chunk only) and `ZoneAccess.canEditLabelsAt`; labels validated (`≤ 8`, `LabelResolver.isValid` rejects tag-without-item); `SetClipboard` validated; `Trust` requires `canEdit`, regex and length checks (see finding 3 for the lookup); `OpenOverview`/`RunSimulation` are read-only; `TuneCategory` requires op; `ZoneSettingsMenu.clickMenuButton` checks op for defaults/apply-all/take-over and `canEdit` for everything else; `stillValid` keeps the menu within 8 blocks of a copper chest. `zones_require_op_to_create`, `max_zones_per_player` and `labels_require_zone_ownership` hold on every creation path I found (screen, feather paste, area mode via `claimIfUnowned`, overview paste via `pasteClipboard`); the two-argument `Zones.zoneForCopperChest` (no player, no checks) has no caller outside tests. Confirm buttons (Reset area, Apply to all, Remove labels, Take over) are UI-only guard rails against misclicks, which matches the README's wording; the server has no second-click requirement and does not need one.
- **Persistence.** All attachments have codecs with optional fields and defaults: `Zone.access` and `Zone.settings` default, `ZoneSettings` every field optional, `Clipboard.no_golem_frames` defaults to false, `ChestLabelSet` accepts the legacy bare list, legacy `FRAME_TAG` and `ZONE_SETTINGS` migrate once. The golem's `GOLEM_ZONE` is persistent; in another dimension the anchor is not found and the attachment is dropped with a log line. Breaking the anchor prunes the zone lazily on the next lookup, and golems leave it through `wbcg$homeZone`.
- **Confinement.** `PathNavigationMixin` clips only while inside the box and keeps full paths outside; `LandRandomPosMixin` redraws with a re-entrancy guard; walk-back is a plain `WALK_TARGET` (except finding 5). `wbcg$nearestFreePerch` stays inside the box. The vertical reach `@ModifyConstant` targets the single `0.5` in `isWithinTargetDistance` (verified in the decompiled source; `defaultRequire: 1` would have failed the mixin otherwise). `doPush(Entity)` still exists with that signature in `LivingEntity` and the override takes effect.
- **Brain flags.** `wbcg$reorganizeActive`, `tidyActive`, `sortActive`, `frameTripActive` are cleared in the TAIL of `stopTargetingCurrentTarget`, which vanilla calls on every exit path (`updateInvalidTarget`, `clearMemoriesAfterMatchingTargetFound`, `enterCooldownAfterNoMatchingTargetFound`, the failure callbacks of `doReachedTargetInteraction`). `updateInvalidTarget` calls `stopTargetingCurrentTarget` *before* `getTransportTarget`, so a flag set during a search cannot leak into a different target. `tidyPlan` is only read under `tidyActive`.
- **Threading.** Fabric play-phase payload handlers run on the server thread; the client-side receivers only touch client state. `ConcurrentHashMap`s are used for the static maps. Only the name lookup (finding 3) blocks.
- **Lang.** `en_us.json` and `pt_br.json` have identical key sets (290), identical `%s` argument counts on every shared key, no literal `\n`; every key built dynamically in code (`category.*`, `count.*.one/many`, `guide.page.1..7`, `hud.area_mode_1/2`, `learn.skip.*`, `overview.problem.*`) exists in both files.
- **Screens.** The client game test (three GUI sizes, every screen, widget-on-screen and text-clipping assertions, area mode with real clicks) passes on this tree (see Test results). Read-only mode disables every editing control in the editor (`lockForReadOnly`, `add()`), the zone screen (`editable`), the tuning screen (`enabled(canEdit)`), the overview (`canPaste`), the access screen (`trust.active`, `remove.active`). `ConfirmButton` arms on the first click, fires only on a second click within 5 s, and `getMessage()` falls back to the label after that.
- **Docs drift (not code bugs).** `docs/TESTING.md` §2b.3 still says a suggestion click puts the label on the clipboard; the code and README say the editor leaves the clipboard alone (commit `a5a8ad9`). README/TESTING say the HUD sits "above the hotbar"; since commit `6692ede` it is drawn 10 px from the top of the screen.

## Test results

Commands as in the task description, with `-Dorg.gradle.java.home=/opt/jdk25`
(Temurin 25), `--no-daemon --max-workers=1`.

**Unmodified tree, `runGameTest` (71 tests), three runs:**

```
[14:15:48] [Server thread/ERROR] (Minecraft) waybettercoppergolem_tests:wbcg_game_tests_golems_do_not_push_each_other failed at 6332962, -59, -4366466! two golems pushed each other apart, distance 0.40184000508827267 on tick 5
[14:15:51] [Server thread/INFO] (Minecraft) 1 required tests failed :(
> Task :runGameTest FAILED
[14:24:45] [Server thread/INFO] (Minecraft) All 71 required tests passed :)
[14:25:21] [Server thread/INFO] (Minecraft) All 71 required tests passed :)
```

**Unmodified tree, `runClientGameTest`** (software GL under `xvfb-run -a -s "-screen 0 1280x720x24"`): `BUILD SUCCESSFUL in 3m 26s`, 22 screenshots in `build/run/clientGameTest/screenshots` (`0000_00_room.png` … `0021_zone_settings_960x540.png`), no assertion failures. The client test does not touch the test file added here, so this result stands for both trees.

**With `WbcgBugHuntTests` (76 tests), `runGameTest`, last run:**

```
[14:39:33] [Server thread/ERROR] (Minecraft) waybettercoppergolem_tests:wbcg_bug_hunt_tests_zone_screen_learn_button_ignores_learn_requires_op failed at -10175702, -59, 1450483! a non-operator got a learn proposal from the zone screen with learn_requires_op on on tick 0
[14:39:34] [Server thread/ERROR] (Minecraft) waybettercoppergolem_tests:wbcg_bug_hunt_tests_hanging_aframe_out_of_astack_in_hand_loses_the_rest failed at -10175687, -59, 1450483! Expected item frames in the chest + in hand + hung (frame and shown item) + dropped to be 1738: was 1729 on tick 146
[14:39:35] [Server thread/ERROR] (Minecraft) waybettercoppergolem_tests:wbcg_bug_hunt_tests_golem_gives_up_on_an_unreachable_frame_chest failed at -10175657, -59, 1450483! after 600 ticks the golem is still holding the frame for the walled-in chest on tick 600
[14:39:35] [Server thread/ERROR] (Minecraft) waybettercoppergolem_tests:wbcg_bug_hunt_tests_confined_golem_perched_on_achest_outside_its_zone_never_walks_back failed at -10175642, -59, 1450483! 600 ticks later the golem is still outside its zone at BlockPos{x=-10175635, y=-56, z=1450488} (perched=true, cooldown=true) on tick 600
[14:39:35] [Server thread/INFO] (Minecraft) 4 required tests failed :(
```

The other two new tests pass: `motionlessGolemsDoNotPushEachOther` (the `doPush`
override works) and `golemsRequireZoneStillLetsAZonelessGolemReorganize`
(the suspicion it was written for turned out wrong; kept as a regression test).
The 71 pre-existing tests passed in every run that included the new file.

**After the fixes (follow-up commit), `runGameTest` (76 tests):**

```
[14:52:15] [Server thread/INFO] (Minecraft) All 76 required tests passed :)
```

**After the fixes, `runClientGameTest`:** `BUILD SUCCESSFUL in 1m 46s`, same 22 screenshots, no assertion failures.
