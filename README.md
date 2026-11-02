# EYYOBUILD

EYYOBUILD is a client-side Litematica auto-builder for Minecraft Java **26.2**. Its movement, route planning and schematic-building process are powered by the official Baritone 26.2 engine.

## Current status — 0.7.0-alpha.1

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings: **B** opens the UI, **H** starts/stops automatic construction.
- The Baritone Fabric 26.2 runtime is bundled inside the EYYOBUILD JAR as a separate nested mod. You do not need to install a second Baritone JAR.
- EYYOBUILD checks that the selected Litematica placement belongs to the same `.litematic` file chosen in its UI, then passes that selected placement to Baritone's own builder process.
- Baritone handles A* route planning and execution, obstacle mining, block placement, throwaway scaffolding/bridging, jumps, parkour movement, safe descents, automatic tool selection and moving inventory blocks to the hotbar, subject to Baritone settings and available materials.
- Litematica's selected placement, its enabled subregions, offsets, rotation and mirror are read by Baritone's Litematica integration instead of being reimplemented in EYYOBUILD.
- EYYOBUILD temporarily enables the automation settings needed for construction: breaking, placing, inventory handling, auto-tool, sprinting, parkour and the Baritone builder. Original Baritone settings are restored when the job ends or is stopped.
- The EYYOBUILD speed slider controls Baritone's right-click and block-break delays. It is a pacing setting, not an anti-cheat bypass.
- The UI shows whether Baritone is walking/building, planning the next action, or paused because it could not find a valid next move.
- **New:** the B menu can start a second Minecraft client by nickname only. It reuses the current Fabric launch, Minecraft version, Java runtime, assets and installed mod JARs; it does not create a Modrinth launcher profile.
- The second client uses an isolated per-nickname game directory under `eyyobuild-accounts/`. Mod JARs are hard-linked when possible (copied if the filesystem does not support hard links), and the current mod config is copied on first launch. Saves and logs remain separate to avoid two clients writing to the same world files.
- Nickname-only accounts use Minecraft's offline identity. They work only on servers that allow offline-mode accounts; authenticated online-mode servers require a real Microsoft account and will reject them.
- The second client is capped at up to 3 GB of Java heap to reduce memory pressure when two game windows are open. The first client is not changed.
- **B** and **H** are the only EYYOBUILD key mappings; both can be rebound in Minecraft Controls.

## Setup

1. Use Minecraft **26.2**, Fabric Loader **0.19.5 or newer**, Fabric API, Litematica and its required dependency, MaLiLib.
2. Put the EYYOBUILD JAR in the game's `mods/` folder. The Baritone 26.2 engine is already nested inside it.
3. Put the `.litematic` file into the game's `schematics/` folder.
4. Load that same file in Litematica and create/enable a placement. Select the placement in Litematica's Schematic Placements list.
5. Press **B** in EYYOBUILD and select the matching `.litematic` file.
6. Keep building blocks and some common full-block materials (such as dirt, cobblestone, netherrack or stone) in the inventory for the schematic and any support/scaffolding the route requires.
7. Click **Başlat** or press **H**. Click **Durdur** or press **H** again to stop.

## Second Minecraft client

1. Press **B** to open EYYOBUILD.
2. Enter a nickname of 3–16 characters using A–Z, 0–9 or underscore.
3. Click **İkinci Oyunu Başlat**. EYYOBUILD starts another Fabric client using the same installed mods and Minecraft version, but in a separate game folder.
4. Join an offline-mode server from the second window. Nickname-only identities cannot authenticate to online-mode premium servers.

The second client has its own saves, logs and per-user config folder. Its launch log is written to `eyyobuild-accounts/<nickname>/logs/eyyobuild-secondary.log`. If the current launcher does not expose a standard Fabric `KnotClient` command/classpath, EYYOBUILD reports that it cannot safely duplicate that launch instead of guessing a different profile.

## Controls

| Action | Default key |
|---|---|
| Open EYYOBUILD UI | B |
| Start / stop automatic building and movement | H |

Both keys can be changed in **Options → Controls → Key Binds → EYYOBUILD**.

## Multiplayer and safety

- EYYOBUILD performs ordinary client-side Baritone movement and block interactions. The server remains authoritative and may reject movement, mining or placement.
- EYYOBUILD does **not** bypass anti-cheat, server cooldowns, permissions, region protection, server rules or moderation. Donut SMP, Chicken SMP and any other public server may prohibit automation; use it only where the server permits it. No ban-free or server-compatibility guarantee is made.
- Advanced Baritone movement such as parkour, scaffold placement and water-bucket descents can be risky in some terrain. Test in a backup/single-player world first and keep valuable items safe.
- If Baritone cannot find a valid route or construction action, its builder may pause rather than continue blindly. The UI reports the paused state; press **H** to stop.
- The speed slider cannot force the server to accept an action. Increase the delay when the server is rejecting or throttling interactions.
- EYYOBUILD requires an active, enabled Litematica placement that matches the selected file. If the file, placement or Litematica world is not available, the build will not start.

## Third-party component and licenses

- EYYOBUILD's own source is MIT-licensed (root `LICENSE`).
- The bundled Baritone 26.2 component is an unmodified nested Fabric mod from the official Baritone v1.19.0 release and is covered separately by its upstream LGPL-3.0 license.
- The Baritone license and source reference are included in `LICENSES/Baritone-LICENSE.txt` and `THIRD_PARTY_NOTICES.md`.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

```bash
gradle build
```

The build downloads the official Baritone Fabric 26.2 JAR, verifies its SHA-256 checksum, compiles against its public API and embeds it as a nested Fabric mod. The EYYOBUILD JAR is written to `build/libs/`. GitHub Actions builds pushes and publishes version-matched pre-releases.

## Versioning

- Tiny bug fixes: increment the patch component.
- Feature additions: increment the minor component.
- Breaking or major milestones: increment the major component.
