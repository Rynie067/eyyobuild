# EYYOBUILD

EYYOBUILD is a client-side Litematica auto-builder for Minecraft Java **26.2**. Its movement, route planning and schematic-building process are powered by the official Baritone 26.2 engine.

## Current status — 0.13.0-alpha.3

- Fabric 26.2 / Java 25.
- Exactly two configurable EYYOBUILD key mappings: **B** opens the UI, **H** starts/stops automatic construction.
- The Baritone Fabric 26.2 runtime is bundled inside the EYYOBUILD JAR as a separate nested mod. You do not need to install a second Baritone JAR.
- The schematic picker reads **enabled placements from Litematica's placement manager**. It no longer scans and lists every \`.litematic\` file in the \`schematics/\` folder.
- Disabled/closed Litematica placements are filtered out. While the EYYOBUILD menu is open, the list refreshes once per second, so a placement disappears after it is disabled or closed. It can appear again only if it is enabled in Litematica again.
- Repeated placements that refer to the same \`.litematic\` file are shown as one file entry.
- EYYOBUILD checks that the selected Litematica placement belongs to the same \`.litematic\` file chosen in its UI, then passes that selected placement to Baritone's own builder process.
- Litematica's selected placement, its enabled subregions, offsets, rotation and mirror are read by Baritone's Litematica integration instead of being reimplemented in EYYOBUILD.
- Baritone handles A* route planning and execution, obstacle mining, block placement, throwaway scaffolding/bridging, jumps, parkour movement, safe descents, automatic tool selection and moving inventory blocks to the hotbar, subject to Baritone settings and available materials.
- EYYOBUILD temporarily enables the automation settings needed for construction: breaking, placing, inventory handling, auto-tool, sprinting, parkour and the Baritone builder. Before building, it finds the schematic block types that exist in the player's inventory and temporarily tells Baritone to ignore unavailable types across the entire schematic. The inventory filter is refreshed every 10 ticks, so when one material stack runs out that block type is skipped and Baritone can path to other blocks of types still available, regardless of their Y layer. Newly acquired block types become eligible again while the build is running. Original Baritone settings are restored when the job ends or is stopped.
- The Baritone builder asks Baritone's own Litematica adapter for the transformed placement, including enabled subregions, rotation and mirror, then counts only positions whose current world block state still differs from the desired state. It refreshes this remaining-material estimate every 5 seconds while building.
- When a still-needed material reaches a low inventory reserve, EYYOBUILD searches nearby loaded chunks for placed chests (including trapped chests), barrels and shulker boxes (24-block horizontal radius, 12-block vertical radius). It records opened-container coordinates and observed item counts for the current build session, walks to accessible containers, takes one stack's worth through ordinary container clicks, returns to the saved build position and resumes the same Litematica placement.
- Material fetching is demand-driven: it aims to replenish a small reserve (up to one stack per block type), rather than trying to fit the whole schematic into the player's inventory. It can make additional supply trips as stacks run low. A QUICK_MOVE may transfer a full source stack, so a small amount of oversupply is possible.
- Container contents are read only after the server actually opens the container menu. Locked/inaccessible containers, unreachable positions and full-inventory transfers are skipped; this does not bypass permissions or server-side container rules. Storage memory is session-only and is refreshed from live container menus, not treated as permanent truth.
- The speed slider ranges from **1 to 25**. **25 is fastest** and maps to one game tick between Baritone block actions; 1 is slowest. The value is saved locally. Minecraft still runs on game ticks, so this is not a guarantee of 25 placed blocks per real-world second.
- The UI shows whether Baritone is walking/building, planning the next action, or paused because it could not find a valid next move.

## Setup

1. Use Minecraft **26.2**, Fabric Loader **0.19.5 or newer**, Fabric API, Litematica and its required dependency, MaLiLib.
2. Put the EYYOBUILD JAR in the game's \`mods/\` folder. The Baritone 26.2 engine is already nested inside it.
3. Load the \`.litematic\` file in Litematica and create a placement.
4. In Litematica's **Schematic Placements** list, enable the placement. EYYOBUILD only lists enabled placements.
5. Press **B**. Use **Önceki / Sonraki** to choose among the open schematic files. The list refreshes automatically while the menu remains open; **Yenile** forces an immediate refresh.
6. Select the matching placement in Litematica before starting, so Baritone builds the intended active placement.
7. Keep building blocks and some common full-block materials (such as dirt, cobblestone, netherrack or stone) in the inventory for the schematic and any support/scaffolding the route requires.
8. Click **Başlat** or press **H**. Click **Durdur** or press **H** again to stop.

When a required block type reaches its low-water reserve, EYYOBUILD checks nearby enabled storage containers, takes matching block-item stacks it can move into the inventory, returns to the saved build position and resumes the same Litematica placement. Remaining requirements are recalculated from the active transformed Litematica placement, not from the original full schematic file. If no accessible nearby container has the item, it keeps the existing inventory-aware behavior: only unavailable block types are skipped while other available types continue. EYYOBUILD does not craft missing materials.

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
- EYYOBUILD requires Litematica and at least one enabled placement that points to a readable \`.litematic\` file. Disabled placements and unrelated files in the schematics folder are not listed.

## Third-party component and licenses

- EYYOBUILD's own source is MIT-licensed (root \`LICENSE\`).
- The bundled Baritone 26.2 component is an unmodified nested Fabric mod from the official Baritone v1.19.0 release and is covered separately by its upstream LGPL-3.0 license.
- The Baritone license and source reference are included in \`LICENSES/Baritone-LICENSE.txt\` and \`THIRD_PARTY_NOTICES.md\`.

## Build

Requirements: JDK 25 and Gradle 9.5.1.

\`\`\`bash
gradle build
\`\`\`

The build downloads the official Baritone Fabric 26.2 JAR, verifies its SHA-256 checksum, compiles against its public API and embeds it as a nested Fabric mod. The EYYOBUILD JAR is written to \`build/libs/\`. GitHub Actions builds pushes and publishes version-matched pre-releases.

## Versioning

- Tiny bug fixes: increment the patch component.
- Feature additions: increment the minor component.
- Breaking or major milestones: increment the major component.
