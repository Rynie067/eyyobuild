# EYYOBUILD

EYYOBUILD is a client-side schematic and manual build-assistance project for Minecraft Java 26.2.

## Current status — 0.1.2-alpha

- Fabric 26.2 / Java 25 project setup.
- Exactly two configurable EYYOBUILD key mappings.
- In-game schematic selection screen.
- Scans the game's `schematics/` folder for `.litematic` files.
- Reads compressed Litematica NBT metadata and displays schematic dimensions, regions and palette sizes.
- A visible manual-assistance session can be started or paused from either the UI or its key.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop manual-assistance session | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**. There are no separate refresh, previous, or next keybinds: those actions are all inside the UI.

## Schematic setup

1. Launch Minecraft once with EYYOBUILD installed.
2. Place your `.litematic` files into the game's `schematics/` folder.
3. Press **B** to open the interface.
4. Use Previous / Next / Refresh to select a schematic.
5. Press **H** or the UI's Start button to toggle the manual-assistance session.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

```bash
gradle build
```

The mod JAR is written to `build/libs/`. GitHub Actions builds the project after pushes and uploads the JAR as a workflow artifact.

## Important feature boundary

This alpha provides schematic discovery, metadata display, and a visible manual-assistance session state. It does **not** place blocks automatically yet, decode every region's packed block-state array for a placement preview, or integrate with Litematica's live placement manager. The H key does not run a macro.

EYYOBUILD is not designed to hide automation, bypass anti-cheat, spoof player input, or evade server rules. Future placement features must be tested only in single-player or explicitly authorized environments.
