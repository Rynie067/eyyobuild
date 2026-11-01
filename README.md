# EYYOBUILD

EYYOBUILD is a Fabric client-side schematic/build assistant for Minecraft Java Edition 26.2.

## Current status — 0.1.0-alpha

- Fabric 26.2 / Java 25 project bootstrap.
- In-game EYYOBUILD screen, opened with a configurable key mapping.
- Scans the game's `schematics/` directory for `.litematic` files.
- Reads compressed Litematica NBT metadata and displays schematic dimensions, regions and palette counts.
- Configurable key mappings appear in **Options → Controls → Key Binds**.

This first repository commit is the foundation, not a finished automatic builder. It does not yet place blocks or connect to Litematica's live placement manager.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD | B |
| Refresh schematic list | R |
| Previous schematic | J |
| Next schematic | K |

All keys can be changed in Minecraft's Controls screen.

## Installing a schematic

1. Start Minecraft once with EYYOBUILD installed.
2. Put your `.litematic` files in the game's `schematics/` folder.
3. Press **B**, then use Refresh / Previous / Next.

## Build

Requirements: JDK 25 and Gradle 9.5.1 or newer.

```bash
gradle build
```

The mod JAR is written to `build/libs/`.

## Planned work

1. Parse all block-state palette entries and packed block-state indices from Litematica regions.
2. Add a schematic placement preview and per-block target highlighting.
3. Match required block states to inventory slots and show missing materials.
4. Add manual step-by-step placement assistance.
5. Add automatic placement only for single-player or explicitly authorized test worlds, with visible controls and no anti-cheat bypass or stealth behavior.
6. Add compatibility with Litematica's currently active placement when its API/version can be verified.

## Fair-play boundary

EYYOBUILD is not designed to hide automation, bypass server anti-cheat, spoof player input, or evade server rules. Online-server use must follow that server's rules.