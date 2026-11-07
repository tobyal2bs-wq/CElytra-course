# CircleCheckpoints 1.2.1

Checkpoint races for **Minecraft Java 26.2 on Paper, Paper-compatible forks, and Folia**, using Java 25. There is no server-brand whitelist: a fork must retain the Paper APIs and scheduler behavior this plugin uses. This is not a claim that every modified fork has been runtime-tested.

## Install

1. Install **PacketEvents 2.14.0 or newer**, using its **Spigot** JAR on either Paper or Folia: https://github.com/retrooper/packetevents/releases/tag/v2.14.0
2. Put `CircleCheckpoints-1.2.1.jar` in your server's `plugins/` folder.
3. For hologram/scoreboard placeholders, also install a server-compatible **PlaceholderAPI** release: https://www.spigotmc.org/resources/placeholderapi.6245/
4. Restart the server. Do not use the server `/reload` command.

PacketEvents is required. PlaceholderAPI and a hologram plugin are optional. The plugin itself does not create a hologram; it provides the text placeholders for your chosen hologram plugin. That hologram plugin must support per-viewer PlaceholderAPI parsing and Folia if you use Folia.

### Upgrade from 1.0.0, 1.1.0 or 1.2.0

Stop the server, back up `plugins/CircleCheckpoints/`, replace the old plugin JAR, and keep that folder. Do not install both JAR versions. Existing course positions, yaw, layout IDs and times load without resetting. On the next save, `state.yml` upgrades from schema 1 to schema 2 to store full quaternion rotations; the preceding file is retained as `state.yml.bak`. If downgrading, restore a matching schema-1 backup as well as the old JAR.

Existing config files are preserved. Legacy default orb counts of 128/640 are upgraded to 960 in memory, and the old default band width of 0.7 becomes 0.45. Custom values remain unchanged. For explicit control, set `config-version: 3`, `rings.orbs-per-ring: 960` and `rings.band-width: 0.45`. New installs display at most 8 nearby rings; an existing configured visibility cap is retained.

The timer now appears in the action bar by default, including when upgrading. Set `race.timer-location: both` to also show the existing sidebar. Axiom center editing, course positions, rotations and saved times remain available.

## Make the example course

Run these as an operator, or give yourself `circles.admin`:

```text
/circles create spawn
```

Stand **at the bottom-middle of the first ring**, facing toward the route. Then run:

```text
/circles set spawn 1
```

Move to the second checkpoint, face along the route, and run:

```text
/circles set spawn 2
```

Move to the finish and run:

```text
/circles set spawn 3
/circles enable spawn
```

`set` places an initially vertical ring with a **7-block outer diameter**. Its bottom is at your feet; its center is 3.5 blocks above you. Use `setcenter` to place the center at your feet and take both your yaw and pitch, or move and rotate a center handle afterward. Place rings in clear space. Operators can see disabled courses while editing; other players see enabled courses.

## Move and rotate rings with Axiom

```text
/circles edit spawn 2
```

This pauses `spawn` and creates a small emerald **ItemDisplay entity at checkpoint 2's center**. Enable Axiom's display-entity gizmos, select the handle, and use its move arrows or rotation rings. The XP band follows the display entity's location, display translation, and all three rotation axes. Tilt it sideways, pitch it flat, or use decimal angles; detection follows the same plane. The display's own scale is only the size of the handle, not the radius of the checkpoint.

To create handles for every checkpoint in the course:

```text
/circles edit spawn
```

When finished:

```text
/circles enable spawn
```

The plugin captures the last handle positions on their owning entity schedulers, saves the course and removes its handles. Changes are also saved after a short pause while editing. Checkpoints remain stored as ordinary plugin data; you do not have to keep the handle entities in the world. A clean server restart retains the latest saved geometry, leaves an editing course disabled, and discards the temporary handles. Use `edit` again to recreate them.

Moving or rotating a handle starts a fresh leaderboard layout once per edit session. Opening the editor and closing it without changing the geometry preserves the existing layout/times. Copying a handle in Axiom does **not** create another checkpoint; use the numbered `set`/`setcenter` commands for additional checkpoints. Handles cannot move a checkpoint into another world; make another course for another world.

Axiom is a separate client/server setup. The plugin uses standard display entities and does not patch Axiom or make an unsupported Axiom server build work on Folia. Use a compatible Axiom installation for your actual server/version, or use the commands below. Axiom's official server plugin lists Paper support; Folia support of Axiom itself is separate from CircleCheckpoints' Folia support.

Official references: [Axiom display editing](https://axiomdocs.moulberry.com/contextmenu/displayentities.html), [Axiom gizmos](https://axiomdocs.moulberry.com/editor/gizmos.html), [Axiom server plugin](https://modrinth.com/plugin/axiom-paper-plugin).

### Exact angles without Axiom

```text
/circles rotate spawn 2 37.5 -25 12
/circles enable spawn
```

Angles are **yaw, pitch, roll**, in degrees. Any finite decimal degree is accepted; a full turn wraps. `rotate` keeps the center and radius, disables the course for editing, and changes the leaderboard layout. A pitch of `90` makes the ring horizontal:

```text
/circles rotate spawn 2 0 90 0
```

Approach the first ring from one side and move through its opening. Standing near a ring or walking around its outside does not count. Crossing works in either direction.

For another independent course, repeat the same steps using a different name:

```text
/circles create parkour
/circles set parkour 1
# Move before setting the next ring!
/circles set parkour 2
/circles enable parkour
```

`spawn` and `parkour` have separate progress, best times, last times and top-10 lists. A player runs one course at a time; multiple players can run courses independently. Course IDs use lowercase letters, numbers or hyphens, with a maximum of 32 characters.

## What players see

- Passing ring 1 starts timing and shows `Checkpoint 1/3 | 00:00.000` in the action bar.
- Passing ring 2 changes it to `Checkpoint 2/3 | 00:12.345`, with the live timer continuing to update.
- Every accepted ring, including start and finish, plays a soft XP pickup sound for that player (volume 0.25, pitch 1.3). Old checkpoints and skipped checkpoints do not play the success sound.
- Skipping ring 2 and crossing ring 3 shows `Checkpoint skipped! Pass 2 first. | 00:12.345`. Progress stays at 1, the timer remains visible and keeps running, and the player must return to ring 2 and then cross ring 3 again.
- Passing all rings in order shows `Done! 01:21.011` in the action bar for 10 seconds and a finish title. The optional sidebar also shows the final result when enabled.
- Finishing launches one short green-and-gold firework above the racer. Plugin-created finish fireworks have their damage cancelled for racers and nearby entities. The finish effect replaces the previous level-up sound.
- The default timer is above the hotbar. Optionally set `race.timer-location: both` to also show the sidebar with course, checkpoint count, current time and personal best.
- With 100 rings, the action bar can read `Checkpoint 10/100 | 01:21.011`.
- Re-crossing ring 1 restarts the current course (with a one-second debounce).

The first ring is included in the checkpoint total. A three-ring course is start 1, checkpoint 2, finish 3. Add as many numbered rings as you need, without gaps. The highest-numbered ring is always the finish.

Teleporting, dying, changing worlds, logging out or restarting the server cancels the active run. Flying, elytra and vehicles are disabled for timed runs by default; you can change those settings. Runs expire after 120 minutes by default. This plugin does not implement automatic checkpoint respawning or act as an anti-cheat system.

## Time format

`MM:SS.mmm` means minutes, seconds, milliseconds:

| Display | Meaning |
| --- | --- |
| `00:05.007` | 5 seconds and 7 milliseconds |
| `01:21.011` | 1 minute, 21 seconds and 11 milliseconds |
| `02:03.456` | 2 minutes, 3 seconds and 456 milliseconds |
| `120:00.000` | 120 minutes |
| `--:--.---` | No time recorded / not running |

The clock uses monotonic real time rather than counting server ticks. Movement events still determine when crossing is observed, so three displayed millisecond digits do not imply 1 ms measurement accuracy. `1.21.11` in the original example was interpreted as a time example; this build targets Minecraft **26.2**, not the older Minecraft 1.21.11 release.

## Commands

| Command | Purpose |
| --- | --- |
| `/circles help` | Show help |
| `/circles list` | List courses |
| `/circles info spawn` | Show ring count/status; admins also see positions |
| `/circles best spawn` | Your best time, last time and rank |
| `/circles leaderboard spawn` | Choose which course the short `%elytra%` / `%elytra_1%`–`%elytra_100%` placeholders use (admin) |
| `/circles top spawn` | Top 10 personal bests, fastest first |
| `/circles leave` | Cancel the current run; ring 1 starts a new one |
| `/circles create spawn` | Create an empty disabled course (admin) |
| `/circles set spawn 1` | Add or replace ring 1 at your position (admin) |
| `/circles set spawn 1 3.5` | Same, with explicit radius; valid radius 1–16 (admin) |
| `/circles setcenter spawn 1` | Place the center at your feet; use your yaw and pitch (admin) |
| `/circles edit spawn [number]` | Pause and create Axiom-editable center display handles (admin) |
| `/circles rotate spawn 2 37.5 -25 12` | Set yaw, pitch and roll, in decimal degrees (admin) |
| `/circles enable spawn` | Enable a course with at least two rings (admin) |
| `/circles disable spawn` | Disable the course (admin) |
| `/circles removelast spawn` | Remove the highest-numbered ring (admin) |
| `/circles delete spawn` | Delete the course definition (admin) |
| `/circles tp spawn 2` | Teleport in front of the oriented opening, with your torso at its center (admin) |

Aliases: `/circle`, `/checkpoints`. `circles.use` is granted by default; `circles.admin` defaults to operators. Admin teleports use the position you configured; place rings over suitable terrain.

Adding, moving, rotating, replacing or removing a ring disables the course and creates a new layout ID. Re-enable it when editing is complete. Leaderboards start fresh for the new layout, while historical results remain archived in `state.yml`. Simply disabling and re-enabling a course retains its leaderboard. Course rings must all be in one world.

## PlaceholderAPI examples

No eCloud expansion download is required: the plugin registers its own `circles` and `elytra` expansions when PlaceholderAPI is installed. Restart after installing PlaceholderAPI.

### Short Elytra placeholders, ranks 1–100

Choose an existing course, without renaming it or losing its results:

```text
/circles leaderboard spawn
```

The choice is saved to `placeholders.elytra-course` in config and takes effect immediately.

| Placeholder | Example / meaning |
| --- | --- |
| `%elytra%` | `01:21.011` — the **viewing player's best completed time** on the selected course |
| `%elytra_1%` | `Alex - 01:10.000` — first-place player's name and best time |
| `%elytra_10%` | Tenth-place player's name and best time |
| `%elytra_100%` | Hundredth-place player's name and best time |
| `%elytra_10_name%` | Only the tenth-place name |
| `%elytra_10_time%` | Only the tenth-place time |
| `%elytra_last%` | The viewing player's last completed time |
| `%elytra_live%` | The viewing player's live timer on the selected course |
| `%elytra_course%` | The selected course ID |

Use one numbered placeholder per leaderboard line. `%elytra_1-100%` is a description of the supported range, not a literal placeholder. Ranking uses each player's **lowest** completed time; later slower runs do not move their personal best down. No-result viewers see `--:--.---`; empty ranks show `-`. Invalid ranks such as 0 or 101 remain unresolved.

Until you explicitly choose a course, automatic selection uses a course named `elytra`, otherwise `spawn`, otherwise the only configured course. If there are several courses and neither preferred name exists, choose one with `/circles leaderboard <course>`. The board never switches courses based on which race the viewer is currently running.

Hologram example (enable per-viewer PlaceholderAPI parsing in your hologram plugin):

```text
ELYTRA - FASTEST TIMES
1. %elytra_1%
2. %elytra_2%
10. %elytra_10%
100. %elytra_100%
Your best: %elytra%
```

Quick check:

```text
/papi parse me %elytra%
/papi parse me %elytra_1%
/papi parse me %elytra_100%
```

If another installed expansion already owns the identifier `elytra`, this plugin logs a registration warning and does not replace it. Existing course-specific `circles` placeholders remain available below.

### Course-specific placeholders

| Placeholder | Output |
| --- | --- |
| `%circles_spawn_best%` | **The viewing player's** best completed time on spawn |
| `%circles_spawn_last%` | The viewing player's last completed spawn time |
| `%circles_spawn_rank%` | The viewing player's rank, or `-` |
| `%circles_spawn_time%` | The viewing player's live spawn timer, or `--:--.---` |
| `%circles_spawn_best_ms%` | The viewing player's best spawn time as integer milliseconds |
| `%circles_spawn_last_ms%` | The viewing player's last spawn time as integer milliseconds |
| `%circles_spawn_time_ms%` | The viewing player's live spawn time as integer milliseconds |
| `%circles_spawn_checkpoint%` | Current spawn checkpoint, or `0` |
| `%circles_spawn_total%` | Number of rings in spawn |
| `%circles_spawn_top_1_name%` | Rank 1 player name |
| `%circles_spawn_top_1_time%` | Rank 1 best time |
| `%circles_spawn_top_10_name%` | Rank 10 player name |
| `%circles_spawn_top_10_time%` | Rank 10 best time |
| `%circles_course%` | Active course, or `-` |
| `%circles_time%` | Active course timer, or `--:--.---` |
| `%circles_time_ms%` | Active course timer as integer milliseconds |
| `%circles_checkpoint%` | Active course checkpoint, or `0` |
| `%circles_total%` | Active course ring count, or `0` |

Replace `spawn` with `parkour` or another course ID. Top ranks now support **1–100**, including `%circles_spawn_top_100_name%` and `%circles_spawn_top_100_time%`. Empty leaderboard entries return `-` for the name and `--:--.---` for time. Completed results persist; active runs are not restored after logout/restart.

Numeric millisecond placeholders return `-1` when there is no result/run. Filter that sentinel when integrating with an external leaderboard. Formatted `01:21.011` corresponds to numeric `81011`.

Test personal parsing in-game:

```text
/papi parse me %circles_spawn_best%
/papi parse me %circles_spawn_top_1_time%
```

Paste these lines into a **per-viewer** PlaceholderAPI-enabled hologram. Refresh it periodically so times update. Each player must receive their own parsed line; a globally cached hologram cannot show different personal times to different viewers.

```text
SPAWN — TOP 10
1. %circles_spawn_top_1_name% — %circles_spawn_top_1_time%
2. %circles_spawn_top_2_name% — %circles_spawn_top_2_time%
3. %circles_spawn_top_3_name% — %circles_spawn_top_3_time%
4. %circles_spawn_top_4_name% — %circles_spawn_top_4_time%
5. %circles_spawn_top_5_name% — %circles_spawn_top_5_time%
6. %circles_spawn_top_6_name% — %circles_spawn_top_6_time%
7. %circles_spawn_top_7_name% — %circles_spawn_top_7_time%
8. %circles_spawn_top_8_name% — %circles_spawn_top_8_time%
9. %circles_spawn_top_9_name% — %circles_spawn_top_9_time%
10. %circles_spawn_top_10_name% — %circles_spawn_top_10_time%

Your best: %circles_spawn_best%
Your last: %circles_spawn_last%
Your rank: #%circles_spawn_rank%
```

## Configuration and saved data

- `plugins/CircleCheckpoints/config.yml`: radius, orb density, view distance, visibility cap, sidebar and race settings. Restart to apply edits.
- `plugins/CircleCheckpoints/state.yml`: courses and per-layout personal best/last results, keyed by UUID.
- `state.yml.bak`: previous successful saved state. A malformed state file causes startup to fail while preserving the file.
- State is saved on a dedicated writer with atomic replacement where supported, and flushed during clean shutdown. Back up the whole plugin folder before manual data edits.

Each ring uses **960 overlapping virtual XP orbs** packed into one compact band. The default width is 0.45 blocks, extending inward from the 3.5-block outer radius and leaving a 3.05-block-radius opening. The depth spread is only 0.06 blocks. This packs over twice as many orbs per unit of band area as version 1.1.0, without fixed radial rows or separate concentric lines. The outer diameter remains 7 blocks. Checkpoints detect passage through the opening. For small rings, width is capped at 45% of the radius.

The orbs cannot award XP, merge or create server-side XP entities. They are only sent to nearby players. By default, only the nearest 8 rings within 32 blocks are displayed to any one player; this limits display load, not the number of saved checkpoints. Checkpoint detection remains active for all enabled course rings. Reduce density, range or visible rings if needed on a large server. Only editor handles are real entities, and they are temporary and non-persistent.

The action bar always shows the live timer during a run. The optional sidebar is only used when `race.timer-location: both` and `sidebar.enabled: true`. It uses packets instead of the globally shared Bukkit scoreboard, temporarily takes the normal sidebar slot while racing and attempts to restore the previously displayed objective afterward. If another plugin manages your sidebar, keep the default actionbar mode and use the live placeholders in that plugin's scoreboard instead.

## Build and validation

Use Java 25 and Maven 3.9+:

```text
mvn verify
```

The JAR is produced in `target/CircleCheckpoints-1.2.1.jar`. Paper, PacketEvents and PlaceholderAPI are provided dependencies and are not bundled into the plugin.

Local verification: 39 JUnit tests passed, covering the soft XP sound call, rocket appearance/lifetime settings, finish-firework damage cancellation, the real PlaceholderAPI percent-token parser with bare `%elytra%` and ranks 1/10/100, fastest-time sorting, per-viewer results, course isolation, action-bar timing during skip warnings, compact band density, ring geometry, 500 varied 3D orientations, horizontal passage, display-transform decoding, edit-layout behavior, schema-1 migration, ordered progress, independent courses/players, 1,500 checkpoints, time formatting, persistence, concurrent finishes, modern orb packet construction, orb relocation without respawning, and sidebar lifecycle. The plugin layout validator passed. The bundled testing-layout checker assumes Gradle and cannot validate this Maven project; Maven Surefire executed the actual tests successfully.

**Not yet verified on a live Paper/Folia server with a Minecraft client or Axiom.** Packet tests check construction and version mappings, not network serialization or visual rendering. Pure editor tests cover pose decoding and edit-layout behavior; they do not emulate Folia region scheduling or Axiom's packet handling. Before public use, check rings from both sides, move/rotate a handle in Axiom, enable the course immediately after the last movement, pass a horizontal ring, skip a checkpoint, finish, restart to verify saved positions/times, and test two players viewing personal hologram times on your actual server setup.

Reference documentation: https://docs.papermc.io/paper/dev/folia-support/ and https://wiki.placeholderapi.com/developers/creating-a-placeholderexpansion/
