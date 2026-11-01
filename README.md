# EYYOBUILD

EYYOBUILD is a client-side Litematica schematic reader and assisted auto-builder for Minecraft Java 26.2.

## Current status — 0.2.1-alpha.1

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings: **B** opens the UI, **H** starts/stops building.
- Reads Litematica region positions/sizes, palettes, block properties and packed BlockStates.
- Keeps each region's coordinates relative to the actual Litematica schematic origin, including negative region offsets.
- Reads the currently selected Litematica placement's world origin, rotation and mirror settings.
- Places decoded blocks at the selected Litematica placement, not at the player's feet.
- Does not write to WASD, jump, sneak or other movement key mappings. The player can walk, jump and fly normally while the builder runs.
- Does not move or rotate the player. If a target is outside interaction reach, it waits for the player to move/fly closer.
- Selects matching block items from the hotbar, skips blocks already correct, protects block entities, and removes incorrect non-container blocks when permitted.

## Setup

1. Install **Litematica** and its required dependency, **MaLiLib**, for your Minecraft 26.2 environment.
2. Put the same `.litematic` file into EYYOBUILD's game `schematics/` folder.
3. In Litematica, load the schematic and create a placement. Select that placement in the Schematic Placements list.
4. Press **B** in EYYOBUILD and select the matching `.litematic` file.
5. Keep the required blocks in your hotbar.
6. Press **H** to start and **H** again to stop.
7. Fly/walk normally around the build. EYYOBUILD never takes control of your movement; it places blocks only when you are close enough.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop auto-build | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**.

## Important limitations

- EYYOBUILD uses the selected Litematica placement's origin/rotation/mirror. The selected placement must reference the exact same file selected in EYYOBUILD.
- The first block of a completely floating build still needs a normal solid adjacent support block. Vanilla block placement cannot place a block into completely empty air. Place a temporary starting support block yourself, or use a server/world that explicitly allows such placement.
- The player must fly/walk within normal interaction reach of each target. EYYOBUILD deliberately does not auto-walk or auto-fly, so it cannot push the player off a ledge or interrupt creative flight.
- In multiplayer, the server remains authoritative and may reject block placement or breaking. EYYOBUILD does not bypass anti-cheat, permissions, region protection, cooldowns or server rules. Use it only where automation is permitted.
- Required block items must be in the hotbar. Missing blocks pause progress.
- Existing block entities (chests, barrels, furnaces, etc.) are protected.
- Per-subregion Litematica offsets/rotation/mirror settings and complex block state orientation (for example every rail/redstone shape) are not fully reproduced yet. Review those parts manually.
- Large schematics are limited to 8 million decoded positions to avoid excessive memory use.
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
