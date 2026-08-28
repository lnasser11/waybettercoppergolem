# Vanilla 26.2 copper golem — decompiled-source notes

Read from Loom `genSources` output (Mojang official mappings, Vineflower
decompile of Minecraft 26.2). Class references below are mojmap names.

## Key classes

| Class | Role |
|---|---|
| `net.minecraft.world.entity.animal.golem.CopperGolem` | The entity. Brain-based (no goal selector). |
| `net.minecraft.world.entity.animal.golem.CopperGolemAi` | Builds the brain: CORE activity (panic, move/look sinks, cooldown countdowns) + IDLE activity. |
| `net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers` | The entire sorting behavior. Generic; in vanilla only the copper golem instantiates it. |
| `net.minecraft.world.level.block.CopperChestBlock` / `WeatheringCopperChestBlock` | Copper chest blocks, tag `#minecraft:copper_chests` (all oxidation stages). |

## The transport behavior (what the golem actually does)

Instantiated in `CopperGolemAi.initIdleActivity()` as IDLE priority 0:

```java
new TransportItemsBetweenContainers(
    1.0F,
    state -> state.is(BlockTags.COPPER_CHESTS),          // source
    state -> state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST), // destination
    32, 8,                                                // search radius: horizontal, vertical
    ...)
```

State machine per tick: `TRAVELLING` → (`QUEUING`) → `INTERACTING`.

- **Target search** (`getTransportTarget`): iterates loaded chunks in range,
  looks at every `ChestBlockEntity`, keeps the **nearest** one that passes
  `isTargetValidToPick`: inside the 32/8 AABB, right block type for the mode
  (hand empty → copper chest; holding → chest/trapped chest), not already
  visited/unreachable, not `isLocked()`. **Contents are NOT examined during
  the search** — match/mismatch is only discovered on arrival.
- **Pickup** (`pickupItemFromContainer`): first non-empty slot,
  `container.removeItem(slot, min(count, 16))` — max 16 items. Hand item is
  set as `setGuaranteedDrop(MAINHAND)` (drops on death, never voided).
- **Deposit check on arrival** (`matchesLeavingItemsRequirement`): container
  is empty OR contains a stack where `ItemStack.isSameItem(hand)` — item id
  only, **components/NBT ignored**. On failure: shrug animation
  (`PLACE_NO_ITEM`), chest marked visited, walk to next nearest.
- **Deposit** (`addItemsToContainer`): merge into stacks that are
  `isSameItemSameComponents` and not full, else first empty slot. If a
  remainder is left in hand, the chest is marked visited and the golem moves
  on to another destination with the remainder.
- **Interaction**: 60 ticks at the chest (opens lid, sound at tick 9).
- **Memory caps**: `VISITED_BLOCK_POSITIONS` max **10** (expiry 6000 ticks =
  5 min); `UNREACHABLE_TRANSPORT_BLOCK_POSITIONS` max 50. Exceeding either,
  or finding no target, sets `TRANSPORT_ITEMS_COOLDOWN_TICKS` = **140**
  (7 s) and clears both memories. A successful pickup/full deposit also
  clears both.
- **Queuing**: if another mob has the target chest open, stop ~3 blocks away
  and wait. Double chests handled via `getConnectedTargets` (both halves
  share visited-marking; container is the combined `CompoundContainer`).
- Interrupted by panic and by being leashed. Won't path while a passenger
  (search radius drops to 1).

## User-assumption scorecard

| Assumption | Verdict |
|---|---|
| Empty hands → nearest copper chest, takes up to 16 of first item | ✅ Confirmed (also skips locked/in-use/visited chests) |
| Holding → checks up to 10 chests, deposits if empty or same item | ✅ Roughly. The "10" is the visited-positions cap (shared across the whole cycle); candidate chests are tried nearest-first, contents checked only on arrival; item match ignores NBT/components |
| Search 32 horizontal / 8 vertical | ✅ Confirmed exactly |
| Copper chests not bound to a golem | ✅ Confirmed — plain block-tag predicate, no ownership |

## Other verified facts relevant to the mod

- **Item frames attach directly to chest faces.** `ItemFrame.survives()`
  requires the support block `state.isSolid()`; chests are legacy-solid
  (collision box ≥ 0.729 avg size). No adjacency hack needed.
- **Sneak-click on an item frame is not special-cased** — vanilla rotates
  the framed item whether sneaking or not. Claiming sneak-click for label
  cycling overrides rotation-while-sneaking on label frames only.
- **Yarn does not exist for 26.x** (last Yarn: snapshot 25w46a). Toolchain
  and this repo use Mojang official mappings (Loom default).
- Golem oxidizes over time (weathering ticks in `tick()`); wax/axe
  interactions as with copper blocks; fully-oxidized golems eventually
  become statues. Shears drop the held item... (see `mobInteract`:
  empty-hand click makes the golem throw you its held item).
- `fabric-data-attachment-api-v1` and `fabric-convention-tags-v2`
  (`#c:ingots`, `#c:ingots/iron`, …) are both present in Fabric API
  0.152.1+26.2.

## Injection points chosen (all memory-only, no world-format changes)

1. Mixins into `TransportItemsBetweenContainers` guarded by
   `body instanceof CopperGolem`:
   - `getTransportTarget` — label-aware destination ranking while holding an
     item (narrowest matching label → broader → catch-all → vanilla rule for
     unlabeled chests).
   - `matchesLeavingItemsRequirement` call site — labels are authoritative:
     a labeled chest only accepts matching items.
   - `pickUpItems` / `putDownItem` — dry-run interception + logging.
2. Mixin into `CopperGolemAi` idle-activity construction to append the
   low-priority "reorganize existing chests" behavior (reuses the same
   scan primitives; no independent tick loop).
3. Chest categories cached via Fabric attachment on the vanilla
   `ChestBlockEntity` (no custom block entity types); item frame is the
   source of truth when present, cache survives frame destruction.
