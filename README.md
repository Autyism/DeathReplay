<p align="center"><img src="docs/icon.png" width="128" alt="icon"></p>
<h1 align="center">Death Replay</h1>
<p align="center">Find out how you died: rewatch the last seconds before your death, and look around freely on the death screen.</p>

**English** | [简体中文](README.zh-CN.md)

![Minecraft 1.21.11](https://img.shields.io/badge/Minecraft-1.21.11-62B47A) ![Fabric](https://img.shields.io/badge/Loader-Fabric-DBD0B4) ![Client-side](https://img.shields.io/badge/Side-Client-5B8DEF) ![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)

Death Replay is a client-side Fabric mod. While you play, it keeps a short rolling recording of what happens around you. When you die, you can watch those last seconds again from any angle, save them to a file, and drop markers, for example where your items fell. It only uses what the server already sends to your game, and it sends nothing back.

## Features

### Death replay

- **Always-on short recording.** While you are alive, the last 30 seconds are kept in memory (adjustable from 5 to 120 seconds). The mod records data, not video, and only what the server already sends to your game.
- **What you see in a replay.** You, with your skin and armor, plus the mobs, players, animals, dropped items and projectiles within 64 blocks of you. Blocks being placed and broken, particles, sounds, explosions, hurt flashes, critical hit sparks and burning entities appear at the moment they happened.
- **Three views.** Third Person follows you (the default), First Person shows what you saw at the time, and Free Camera lets you fly anywhere, through walls.
- **Playback controls.** Play, pause, restart, jump two seconds back or forward, or click and drag the timeline. A red line marks the moment of death, and a label tells you how many seconds before or after the death you are.
- **On the death screen or later.** Click Replay on the death screen, or press F6 after respawning. The latest replay stays available until your next death or until you leave the world or server, even if you have changed dimension since.
- **Your world is never touched.** A replay plays in a separate copy of the terrain. When you close it, you are back in the real world exactly as it is now.
- **Terrain along your whole route.** The copy covers the path you took during the recording, not only the place where you died. A replay that starts far away, after an elytra flight or a teleport, still has ground under it.
- **Save replays to files.** Use Save Replay on the death screen, Save in the replay, or turn on auto-save. Saved replays can be watched later from the Saved Replays list, even after restarting the game.

### Death screen camera

- **The camera leaves your body.** When you die, the camera moves behind and above your body, so you watch yourself fall instead of getting the tilted view through your body's eyes.
- **Look Around.** A new button on the death screen gives you a clear view: the red overlay, the buttons, the hotbar and the chat are hidden, and the mouse turns the camera. Press F5 to switch between Third Person (turn around your body), Orbit (the camera circles slowly by itself) and Free Camera (fly through walls, with no distance limit).
- **A frozen scene instead of an empty one.** About one second after you die, a vanilla server stops sending you the mobs and players around you, and the normal death screen goes empty. Death Replay shows the last recorded moment frozen in place instead: your body, the mob that killed you and the creatures nearby, until you respawn. If a server keeps sending them, you keep the live view.
- **Caves are visible from inside rock.** When the free camera is inside solid stone, the caves and hollow spaces around it are drawn, like in spectator mode.
- **Game menu without respawning.** Esc on the death screen opens the normal game menu, so you can reach Options or Mod Menu, or quit, without respawning. Closing the menu brings back the same death screen, death message included.

### Markers

- **Mark a spot.** Right-click while looking around to mark what the crosshair points at, or quickly right-click in a replay to mark the spot under the cursor. Useful for remembering where your items dropped or where an attacker came from.
- **Find it after respawning.** When a marked spot is on screen, a red dot with its name and distance appears there, even through walls. Markers also show up as dots on vanilla's locator bar above the hotbar, which helps you turn towards them.
- **Kept per world, only on your computer.** Markers are saved per server address or per single-player world and are still there after a restart. They are never sent to the server. Delete them from the Markers list.

### What it does not do

- **It sends nothing.** Death Replay itself sends no packets to the server and registers no network channels. It does not change gameplay or press any keys for you.
- **Nothing live after respawning.** The moment you respawn, the camera is back on your player. After that, all you can watch is the recording, which ends 0.75 seconds after your death (or at the moment you respawn, if that comes first).
- **Respawning works exactly as in vanilla.** The mod never delays or holds back your respawn.

## Screenshots

![The death screen with Death Replay](docs/images/death-screen.png)

The death screen: the zombie that killed you stays frozen in place, with the new Replay, Look Around and save buttons.

![A replay in third person](docs/images/replay-third-person.png)

Watching a replay in third person: placed blocks, particles and nearby mobs appear as they did at the time.

![The end of a replay](docs/images/replay-moment-of-death.png)

The end of a replay, just after the death. The red line on the timeline marks the moment you died.

![A replay in first person](docs/images/replay-first-person.png)

First person: what you saw at the time.

![A replay in free camera](docs/images/replay-free-camera.png)

Free camera: fly anywhere while the replay plays.

![Look Around with a marker](docs/images/look-around-marker.png)

Look Around in Free Camera on the death screen. A right click dropped a marker.

![A marker after respawning](docs/images/marker-after-respawn.png)

After respawning, the marker shows its name and distance. The bar above the hotbar is the locator bar, which points to your markers.

![The settings screen](docs/images/settings.png)

The settings screen with its default values.

## How to use

### Keys

| Action | Default key | Where to change it |
|---|---|---|
| Watch Death Replay (after respawning) | F6 | Options → Controls → Key Binds → Death Replay |
| Open Settings | Not bound | Options → Controls → Key Binds → Death Replay |
| Marker List | Not bound | Options → Controls → Key Binds → Death Replay |
| Switch view (Look Around and replays) | F5 (your Toggle Perspective key) | Options → Controls → Key Binds |
| Fly the free camera | W / A / S / D (your movement keys) | Options → Controls → Key Binds |
| Fly up / down | Space / Left Shift (your Jump / Sneak keys) | Options → Controls → Key Binds |
| Fly 2.5 times faster | Left Ctrl (your Sprint key) | Options → Controls → Key Binds |

The camera keys follow whatever you have bound in the vanilla controls. F6 only works when no screen is open; on the death screen, use the Replay button. If there is no death in memory, F6 opens the Saved Replays list instead.

**In a replay**

| Action | Key or mouse |
|---|---|
| Play / pause | Enter |
| Back / forward 2 seconds | Left / Right arrow |
| Restart | R |
| Switch view (Third Person → Free Camera → First Person) | F5 |
| Turn the camera | Hold the right mouse button and move the mouse |
| Drop a marker on the spot under the cursor | Quick right click, without dragging |
| Jump to a moment | Click or drag on the timeline |
| Camera distance (Third Person) / flight speed (Free Camera) | Mouse wheel |
| Close | Esc |

The buttons at the bottom do the same: Restart, Play / Pause, the current view (click to switch), Save and Close. Dragging the timeline pauses playback; if it was playing, it continues when you let go. Pressing Play at the end starts the replay again from the beginning.

**While looking around (death screen)**

| Action | Key or mouse |
|---|---|
| Turn the camera | Move the mouse |
| Switch view (Third Person → Orbit → Free Camera) | F5 |
| Camera distance (1.5 to 20 blocks) / flight speed (2 to 40 blocks per second) | Mouse wheel |
| Drop a marker where the crosshair points | Right click |
| Back to the death screen | Esc |

**On the death screen:** Esc opens the game menu.

### Commands

Death Replay has no commands.

### Settings screen

- **With Mod Menu:** Mods → Death Replay → the settings icon. From the death screen you get there through Esc → Mods.
- **Without Mod Menu:** bind a key to **Open Settings** under Options → Controls → Key Binds → Death Replay.

### Step by step

**See how you died**

1. Die. The camera moves behind your body, and a moment later the scene freezes in place.
2. Wait a moment until the **Replay** button lights up, then click it.
3. Drag the timeline, switch views with F5, and press Esc to go back to the death screen.

**Watch it again after respawning**

1. Respawn. A chat line reminds you which key plays the replay.
2. Press **F6**. Find a safe spot first: the game keeps running while you watch.

**Keep a replay**

1. Click **Save Replay** on the death screen, or **Save** in the replay. The chat shows the file name.
2. Later, join any world, open the settings, click **Saved Replays...** and pick the file. (If there is no death in memory, F6 opens the same list.)

**Mark where your items are**

1. On the death screen, click **Look Around** and press F5 until you are in Free Camera.
2. Fly to the spot, aim the crosshair at it and right-click.
3. Respawn and follow the label and the locator bar. When you are done, delete the marker in the Markers list.

### Good to know

- The game is not paused while you watch. If you watch after respawning, your character stays where it is and can be attacked; dying closes the replay.
- A replay ends when you respawn, change dimension or leave the world.
- Saved replays go to the `deathreplay` folder in your game directory (next to `mods` and `saves`), named after the date and time of the death, for example `death_2026-10-01_17-10-13.nbt`. The **Folder** button in the Saved Replays list opens it. Most of a file is terrain, so its size depends on where you were (about 50 KB on a superflat world, more on normal terrain).
- A saved file contains the terrain along your route, your coordinates and the names of players near you. Keep that in mind before sharing one.
- The Saved Replays list shows the latest death still in memory at the top, then your saved files, newest first.
- A marker lands on the surface of the block you aim at, up to 256 blocks away; if there is nothing in that direction, it is placed where the camera is. Markers are named automatically (Marker 1, Marker 2, ...) and only show in the dimension they belong to.
- Settings are stored in `config/deathreplay.json`, markers in `config/deathreplay-waypoints.json`.

## Settings

| Option (as shown in game) | Default | What it does |
|---|---|---|
| Recording Length | 30 s | How many seconds before your death are kept, from 5 to 120 in steps of 5. Longer uses more memory. |
| Auto-Save Replays | OFF | Saves every death replay to the `deathreplay` folder automatically. When off, use the Save Replay button. |
| Replay Starts In | Third Person | The view a replay opens with: First Person, Third Person or Free Camera. You can still switch while it plays. |
| Replay Hint After Respawn | ON | After you respawn, a chat line tells you which key plays the replay. |
| Death Screen Camera | ON | Moves the camera off your body on the death screen, adds the Look Around button and shows the frozen scene. When off, the death screen view is vanilla; the Replay and Save Replay buttons stay. |
| Death Screen Starts In | Third Person | The view the death screen opens with: Third Person, Orbit or Free Camera. |

The settings screen also has **Saved Replays...** and **Markers...** buttons. Hover over an option to see a short explanation. Changes apply immediately and are saved when you close the screen.

## Requirements

| | |
|---|---|
| Minecraft | Java Edition 1.21.11 |
| Mod loader | Fabric Loader 0.19.5 or newer |
| Fabric API | Required |
| Java | 21 or newer |
| Mod Menu | Optional: adds a settings button to the mod list |

Death Replay is client-side only. Install it in your own game; servers do not need it.

## Compatibility

- **Sodium:** works. Tested with Sodium 0.8.7.
- **Iris and shader packs:** not tested yet. Reports are welcome.
- **Voxy:** not tested yet.
- **Multiplayer servers:** nothing needs to be installed on the server, since the mod only uses what your game already receives. It has not been tested on a multiplayer server yet.
- **Server rules:** the Look Around free camera passes through blocks and has no distance limit, so it can show any terrain your game has loaded. It is only available while you are dead, and the mobs you see then are the frozen past. Whether a death screen camera is allowed is up to each server.
- **ERTZ Death Replay:** a different mod with the same name. Both change the death screen, so this mod declares it incompatible (its mod ID is `deathreplay`); install only one of the two.
- **Replays with modded content:** a saved replay that contains blocks or mobs from other mods may not open where those mods are missing. You get a message, not a crash.

## Installation

1. Install Fabric Loader 0.19.5 or newer for Minecraft 1.21.11.
2. Download Fabric API for 1.21.11 and put it in your `mods` folder.
3. Put the Death Replay jar in the same `mods` folder.
4. Optional: add Mod Menu to open the settings from the mod list.
5. Start the game with the Fabric profile.

## FAQ

**Does it send anything to the server? Is it allowed?**
Death Replay itself sends nothing to the server and registers no network channels; it only replays what your game already received. Whether a death screen free camera is allowed is decided by each server's rules.

**The Replay button is grey.**
It lights up a moment after you die, once the recording is finished.

**F6 does nothing.**
F6 only works when no screen is open. On the death screen, use the Replay button. Also check Key Binds for another mod using F6.

**F6 opens a list instead of a replay.**
There is no death in memory, for example right after starting the game or after joining another world. Pick a saved replay from the list.

**Parts of the replay are empty.**
A replay only contains the terrain along your route (8 chunks around the place of death and 6 around the rest of the route, never more than your render distance) and the entities within 64 blocks of you. Beyond that, the free camera shows empty space.

**The mobs on the death screen do not move.**
That is the frozen last moment. Click Replay to see what happened.

**My experience bar turned into a bar with a dot.**
That is vanilla's locator bar, showing your markers. Delete the markers to get the experience bar back. Your level number is still shown.

**I want the vanilla death screen back.**
Turn off **Death Screen Camera** in the settings.

**The chat hint after respawning bothers me.**
Turn off **Replay Hint After Respawn** in the settings.

**A saved replay says "Could not read".**
It was saved by another Minecraft version, the file is damaged, or it contains content from mods you do not have.

**Does it cost performance?**
It records data, not video. Measured on a flat world, recording took about 0.02 ms per game tick on average with about 30 entities nearby and about 0.07 ms with about 180 (a game tick lasts 50 ms). At the moment of death it copies the terrain once, which took about 3 ms on a flat world; busier terrain takes longer. Opening and closing a replay rebuilds the chunk display once, similar to pressing F3 + A.

## Known limitations

- Replays play at normal speed only; there is no slow motion or fast-forward.
- First-person replays do not show your hand or the item you were holding.
- Sounds of your own actions that your game plays by itself, such as your footsteps and swings, are not in the replay, because the server never sends them to you. Sounds from other players, mobs and blocks are.
- Fishing bobbers are not shown in replays.
- Signs and other blocks with stored content that were broken during the recording come back without their content, for example a sign without its text.
- The recording starts over when you change dimension: if you went through a portal shortly before dying, the replay starts when you arrived.
- Up to 512 entities are recorded per tick; in very crowded places, such as mob farms, the rest are left out.
- With the "Respawn immediately" game rule there is no death screen, so Look Around and the death screen buttons are not available. The death is still recorded: press F6 after respawning (the recording then ends at the moment you respawned).
- A quick right click in a replay always drops a marker, even if you only meant to turn the camera. Delete extra markers in the Markers list.
- Markers stay until you delete them, even after you reach them.
- While you have markers in your current dimension, the locator bar replaces the experience bar.
- The latest recording is kept in memory only until your next death or until you leave the world or server. Save it to keep it.
- Replays can only be watched while you are in a world, and saved files only open in the Minecraft version they were saved with.

## Credits

Death Replay is an original mod written from scratch by Autyism; it is not based on another mod. It is built on Fabric and Fabric API and has an optional Mod Menu integration.

Source code and issue tracker: [github.com/Autyism/DeathReplay](https://github.com/Autyism/DeathReplay) · [Issues](https://github.com/Autyism/DeathReplay/issues)

## License

`GPL-3.0-only`. Death Replay is licensed under the GNU General Public License v3.0: you may use, change and share it, and any version you distribute must also be released under the GPL. See [LICENSE](LICENSE).
