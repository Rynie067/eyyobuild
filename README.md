# EYYOBUILD

EYYOBUILD is a client-side Litematica schematic reader and auto-builder for Minecraft Java 26.2.

## Current status — 0.3.0-alpha.1

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings: **B** opens the UI, **H** starts/stops auto-build.
- The UI **Başlat** button explicitly starts the builder; it no longer relies on a toggle call. It changes to **Durdur** once active.
- The UI now keeps the status message clear of the button row so missing requirements and start errors are visible.
- Reads Litematica region positions/sizes, palettes, block properties and packed BlockStates.
- Keeps region coordinates relative to the actual Litematica schematic origin, including negative region offsets.
- Reads the selected Litematica placement's world origin, rotation and mirror settings.
- Builds at the selected Litematica placement, not at the player's feet.
- **Creative:** if the player is allowed to fly, EYYOBUILD enables flight for the active build session and automatically restores the previous non-flying state when stopped.
- **Survival/adventure:** uses safe ground-based pathfinding, jumping up one-block steps and walking through passable decorations. It does not grant flight.
- Ground path search is bounded and uses collision shapes, solid support and clear head/feet space. It will not deliberately walk into unsupported air.
- When the build is stopped, EYYOBUILD releases only the movement keys and flight state it changed.
- Searches all 36 main-inventory slots for required blocks. If a block is in the main inventory, it is swapped into an empty hotbar slot (or the selected hotbar slot if full) and selected automatically.
- If a required block is nowhere in the inventory, that schematic position is skipped and the builder continues; missing materials no longer freeze the build at one row.
- Starts each horizontal layer near the player's starting X/Z position instead of always beginning at the same schematic corner.
- Adds a persistent UI speed slider for block placement and breaking (0.1–5.0 blocks/second, default 1.0). Movement speed is unchanged; the delay is saved in `config/eyyobuild.properties`.
- Skips blocks already correct, protects block entities, and removes incorrect non-container blocks when permitted.

## Setup

1. Install **Litematica** and its required dependency, **MaLiLib**, for Minecraft 26.2.
2. Put the same `.litematic` file into EYYOBUILD's game `schematics/` folder.
3. In Litematica, load the schematic and create a placement. Select that placement in the Schematic Placements list.
4. Press **B** in EYYOBUILD and select the matching `.litematic` file.
5. Keep the required blocks in your hotbar.
6. Click **Başlat** in the UI or press **H**. Click **Durdur** or press **H** again to stop.
7. In creative, EYYOBUILD turns flight on for the build session. In survival/adventure it walks only where a safe route exists.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop auto-build and auto-movement | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**.

## Safety and limitations

- Survival/adventure mode cannot fly in vanilla. EYYOBUILD walks and jumps on supported ground; it will stop rather than walk across a void or unsupported edge.
- In creative mode, flight is enabled only while an auto-build session is active, and is restored when the session stops. Servers remain authoritative and can reject flight or movement.
- If the player is airborne without flight, automatic movement waits instead of steering them into a fall.
- The first block of a completely floating build still needs a normal adjacent support block. Vanilla placement cannot place a block into completely empty air.
- The player must be within normal interaction reach to place or break the target block.
- In multiplayer, the server remains authoritative and may reject placement, breaking or movement. The speed slider only adds a client-side delay; it does not bypass anti-cheat, permissions, region protection, cooldowns or server rules, and cannot guarantee that a server will allow automation. Use it only where automation is permitted.
- Required block items can be anywhere in the player's main inventory; EYYOBUILD moves them to the hotbar automatically. Positions whose blocks are not in the inventory are skipped and reported in the final summary.
- Existing block entities (chests, barrels, furnaces, etc.) are protected.
- Per-subregion Litematica offsets/rotation/mirror settings and complex block state orientation (for example every rail/redstone shape) are not fully reproduced yet. Review those parts manually.
- Large schematics are limited to 8 million decoded positions.
- This alpha has not been tested in every modpack/server. Test on a backup world first.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

```bash
gradle build
```

The mod JAR is written to `build/libs/`. GitHub Actions builds pushes and creates a version-matched GitHub pre-release.

## Versioning

- Tiny bug fixes: increment the patch component.
- Feature additions: increment the minor component.
- Breaking or major milestones: increment the major component.
