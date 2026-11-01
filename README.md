# EYYOBUILD

EYYOBUILD is a client-side Litematica schematic reader and auto-builder for Minecraft Java 26.2.

## Current status — 0.2.1-alpha.2

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings: **B** opens the UI, **H** starts/stops auto-build.
- Reads Litematica region positions/sizes, palettes, block properties and packed BlockStates.
- Keeps region coordinates relative to the actual Litematica schematic origin, including negative region offsets.
- Reads the selected Litematica placement's world origin, rotation and mirror settings.
- Builds at the selected Litematica placement, not at the player's feet.
- **Automatic movement is enabled only while H/Auto-build is active.** When the build is stopped, EYYOBUILD releases only the movement keys it pressed and leaves normal player controls alone.
- On ground, it searches for a bounded path using only positions with solid floor and two clear blocks; it won't deliberately walk over unsupported air.
- If the player is already flying, it steers forward and vertically toward the next build target while preserving the existing flight state.
- It never enables flight permission by itself. In multiplayer, server permissions still apply.
- Selects matching block items from the hotbar, skips blocks already correct, protects block entities, and removes incorrect non-container blocks when permitted.

## Setup

1. Install **Litematica** and its required dependency, **MaLiLib**, for Minecraft 26.2.
2. Put the same `.litematic` file into EYYOBUILD's game `schematics/` folder.
3. In Litematica, load the schematic and create a placement. Select that placement in the Schematic Placements list.
4. Press **B** in EYYOBUILD and select the matching `.litematic` file.
5. Keep the required blocks in your hotbar.
6. Press **H** to start automatic building and movement. Press **H** again to stop both.
7. If you are building high in the air, enable creative flight first. EYYOBUILD will steer while flight is already active; it will not switch flight permissions on.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop auto-build and auto-movement | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**.

## Safety and limitations

- Ground movement only follows positions with a solid floor; the pathfinder will stop instead of intentionally walking across a void or unsupported edge.
- In an existing flight state, the builder uses forward plus jump/descend inputs to approach the target in 3D. It does not grant flight abilities; survival players must use a permitted flight method.
- If the player is airborne without flight, automatic movement waits instead of steering them into a fall.
- The first block of a completely floating build still needs a normal adjacent support block. Vanilla placement cannot place a block into completely empty air.
- The player must be within normal interaction reach to place or break the target block.
- In multiplayer, the server remains authoritative and may reject placement, breaking or movement. EYYOBUILD does not bypass anti-cheat, permissions, region protection, cooldowns or server rules. Use it only where automation is permitted.
- Required block items must be in the hotbar. Missing blocks pause progress.
- Existing block entities (chests, barrels, furnaces, etc.) are protected.
- Per-subregion Litematica offsets/rotation/mirror settings and complex block state orientation (for example every rail/redstone shape) are not fully reproduced yet. Review those parts manually.
- Large schematics are limited to 8 million decoded positions.
- This alpha has not been tested in every modpack/server. Test on a backup world first.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

```bash
gradle build
```

The mod JAR is written to `build/libs/`. GitHub Actions builds pushes and creates a GitHub pre-release.

## Versioning

- Tiny bug fixes: increment the patch component.
- Feature additions: increment the minor component.
- Breaking or major milestones: increment the major component.
