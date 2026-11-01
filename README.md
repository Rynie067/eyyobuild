# EYYOBUILD

EYYOBUILD is a client-side Litematica schematic reader and visible auto-builder for Minecraft Java 26.2.

## Current status — 0.2.0-alpha.2

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings.
- In-game schematic selection screen.
- Scans the game's `schematics/` folder for `.litematic` files.
- Reads Litematica region Position/Size compounds, palettes, block properties and packed BlockStates.
- Decodes schematic block coordinates (X-fastest, then Z, then Y) and normalizes multi-region layouts.
- Shows schematic dimensions, region count and the number of non-air blocks to place.
- Includes a visible schematic build executor with walking, jump-step handling, sneak placement, hotbar block selection and obstruction breaking.
- Stops when a required block is missing, a block entity would be damaged, the world closes, or no safe path can be found.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop auto-build | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**. There are no extra refresh or navigation keybinds; those actions are inside the UI.

## Schematic setup

1. Launch Minecraft once with EYYOBUILD installed.
2. Put your `.litematic` files into the game's `schematics/` folder.
3. Press **B** and choose a schematic.
4. Stand at the intended starting corner and face the direction in which the schematic should extend.
5. Keep the required block items in your hotbar.
6. Press **H** or the UI's **İnşaatı başlat** button.
7. Press **H** again to stop at any time.

The schematic origin is placed one block in front of the player. Build order is bottom-up. Movement uses normal client movement keys and interactions use the normal block interaction API.

## Important limitations

- Auto-build can run in single-player and multiplayer. Multiplayer interactions are sent through the normal client game interaction path; the server remains authoritative and may reject placement, breaking or movement.
- It does not bypass anti-cheat, permissions, region protection, cooldowns or server rules. Use it only on servers where automation is permitted.
- The navigation system handles simple, walkable ground and one-block steps. It is not a full parkour, scaffold, swimming, ladder or flying pathfinder.
- Required blocks must be in the hotbar. The mod pauses and identifies a missing item instead of silently substituting another block.
- Block IDs and palette properties are decoded. Vanilla placement determines some directional/shape properties; complex blocks such as redstone, rails, doors, beds, waterlogged blocks and tile-entity contents may need manual correction.
- Existing block entities (chests, barrels, furnaces, etc.) are protected from automatic breaking.
- Large schematics are limited to 8 million decoded positions to avoid excessive memory usage.
- This alpha has not been integration-tested in a live Minecraft world. Test on a backup copy of a world first. On multiplayer, test only where you have permission to automate building.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

```bash
gradle build
```

The mod JAR is written to `build/libs/`. GitHub Actions builds pushes and creates a GitHub Release when a `v*` tag is pushed.

## Versioning

- Tiny bug fixes: increment the patch component.
- Feature additions: increment the minor component.
- Breaking or major milestones: increment the major component.
