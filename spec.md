# Catch (working name) — Spec v0.5

## 1. Goal
Run the stock Play Store Roblox on a hidden virtual display so it keeps rendering and stays connected while the user uses other apps. The user sets everything up manually, then hides the window. One small floating bubble shows status and gives control.

## 2. Non-goals
- No injection, no memory reading, no Roblox modification, no scripts or mod menus.
- No game-specific automation. The app only launches, mirrors, keeps alive, and sends taps.
- No iOS. No root.
- No cloud or remote instances.

## 3. Target environment
- Latest Android on OnePlus (OxygenOS). Exact version to be confirmed (assumed Android 16).
- Shizuku running via wireless debugging.
- Play Store Roblox, unmodified.
- Expect 6 GB+ RAM.

## 4. Architecture
| Component | Role |
|---|---|
| Foreground service | Owns the virtual display, the bubble, and the tap loop. Keeps our own process alive. |
| Shizuku bridge | Launches Roblox on the virtual display, injects input at the display ID, applies keep-alive tuning. |
| Virtual display manager | Creates the display (low res, low DPI), swaps its output Surface between mirror and sink. |
| Overlay UI | Draggable translucent bubble with collapsed and expanded states (SYSTEM_ALERT_WINDOW). |
| Tap loop | Optional timed taps at a calibrated coordinate. Doubles as anti-idle. |
| Watchdog | Detects Shizuku death, Roblox death, or display loss; notifies the user. |

## 5. UX
**Collapsed:** a small translucent dot or pill, draggable, snaps to an edge, remembers its position. Colour shows state: running, loop on, Shizuku down, Roblox dead.

**Menu (tap bubble):** a compact menu with:
- **Open Roblox:** brings Roblox back so the user can play or switch games. Two candidate implementations, decided in M0/M2: (a) expand the mirror window of the virtual display (safe, no state change); (b) move the Roblox task to the main display (full screen, but the move may recreate the activity and risks a disconnect). Default is (a); (b) is an experiment.
- **Hide:** collapse back to the bubble. Roblox keeps running on the virtual display.
- **Tools:** auto-clicker and anti-idle toggles (see section 9).
- **Stop:** kills Roblox completely (see section 6).

**Touch behaviour:** the overlay is non-focusable and only captures touches inside its own bounds. Opacity stays at or below 0.8 so Android's untrusted-touch rules do not block anything underneath.

**Notification:** persistent, with Show/Hide, Stop loop, and Stop all actions, so the user is never locked out if the bubble is lost.

## 6. Stop controls (independent)
- **Stop:** one action that ends everything, in this order: halt the auto-clicker and anti-idle, force-stop Roblox, release the virtual display, stop the service and exit Catch. Nothing keeps running afterwards. Reachable from the bubble menu and from the notification.
- **Loop off:** halts taps only. Roblox and the display keep running.

## 7. Virtual display
- Created by our app. Roblox is launched onto it via Shizuku (`am start --display <id>`).
- Starting resolution is a low value such as 1280x720 with low DPI. Tune for performance.
- **Hidden state:** the display's Surface is swapped to a tiny ImageReader sink at low FPS, never null. A null surface can pause the display and therefore Roblox.
- **Shown state:** the Surface is swapped back to the overlay's SurfaceView.

## 8. Input
- Mirror touches are translated to display coordinates and injected via Shizuku with the display ID set (InputManager injection, using hidden-API bypass). This supports taps, drags, and the walk joystick.
- Fallback: `input -d <id>` shell commands (taps and swipes only).
- Calibration: the user taps the in-game auto button once on the mirror; the app stores that coordinate.

## 9. Tools
Catch is a utility app: small tools that make idle play less tedious. Every tool is optional and off by default. Nothing here reads or modifies the game.

### 9.1 Auto-clicker
- **Position:** the user taps a point on the mirror to set where clicks land on the virtual display. Several named points can be saved; one is active at a time.
- **Rate:** a slider from 0.1 CPS up to a hard cap of 30 CPS (default 5). The cap is fixed in code and cannot be raised from the UI. It keeps the tool in the range of real human clicking.
- **Fixed interval, no tricks:** clicks are evenly spaced. No randomised timing or anything meant to hide that it is a tool.
- **Limits:** optional stop after N clicks or after N minutes.
- **Runs while hidden:** the clicker works whether the mirror is expanded or collapsed. The user never has to open Roblox for it to keep clicking.
- **Virtual display only:** every injected event carries the virtual display's ID, and the injector checks that the display still exists before each batch. If the display is gone or the ID does not match, nothing is sent. The clicker never injects to the real screen or any other display, so it cannot tap your other apps.
- **Tied to Roblox:** the clicker stops if the Roblox process dies, the display is released, or Shizuku disconnects. It does not restart by itself.
- **Safety:** pauses automatically while the user is touching the mirror, and stops immediately on Stop or when Shizuku dies. A visible indicator on the bubble shows when it is active.
- **Delivery:** shell `input tap` spawns a process per click and cannot reach 20+ CPS. Clicks are instead injected through InputManager from a Shizuku user service (in-process), with the display ID set. M0 check 3 decides whether this works.

### 9.2 Anti-idle
- Roblox kicks after about 20 minutes without input. Optional single tap or tiny movement every N minutes (default 5, always well under 20).
- Can run on its own or alongside the auto-clicker.

## 10. Keep-alive tuning (one-time, via Shizuku)
- Battery whitelist: `dumpsys deviceidle whitelist +com.roblox.client`
- `appops set com.roblox.client RUN_ANY_IN_BACKGROUND allow`
- Disable the phantom process killer where it still applies (`settings put global settings_enable_monitor_phantom_procs false` and the `device_config` max_phantom_processes override). Verify on the actual Android version, since behaviour has changed across releases.
- Our service runs in the foreground.
- OxygenOS manual steps (shown in an in-app checklist): allow background activity and auto-launch for Roblox and for our app, and lock both in recents.

## 11. Failure handling
- **Shizuku dies:** notify "Restart Shizuku". Roblox keeps running; only the tap loop and display control are affected.
- **Roblox dies:** notify and offer a one-tap relaunch onto the virtual display.
- **Display lost:** same as above.
- **Disconnected from the game:** detect by Roblox process death only. No screen scraping in v1.

## 12. Milestones
- **M0, feasibility prototype:** see section 12a. If checks 1 to 6 do not pass, stop and rethink before building anything else.
- **M1:** overlay bubble with mirror and hide, with the ImageReader sink swap.
- **M2:** full touch injection through the mirror (joystick and drags).
- **M3:** calibration plus tap loop plus Stop controls.
- **M4:** keep-alive tuning, watchdog, notification actions.
- **M5:** polish: position memory, OxygenOS checklist, battery and RAM measurements.

## 12a. M0 checklist
All checks run with Roblox on the virtual display and a different app (WhatsApp, Instagram, etc.) on the real screen. The prototype only needs a throwaway activity that creates the display, shows it in a SurfaceView, and has buttons for the Shizuku calls. No bubble, no loop.

| # | Check | Pass |
|---|---|---|
| 1 | Create the virtual display and launch Roblox on it via Shizuku (`am start --display <id>`) | Roblox appears on the display, not on the real screen |
| 2 | Render: watch the mirror | Live, moving frames, not frozen |
| 3 | Touch: inject a Shizuku tap at the display ID on a Roblox UI button | The button responds |
| 4 | Play: log in, join a game, walk with a joystick drag | Character moves normally |
| 5 | Focus test: switch to another app for 2, 10, then 30 minutes, then return | No disconnect message, and the game kept progressing (e.g. an in-game timer or auto-farm counter advanced) |
| 6 | Lifecycle: while another app is on the real screen, run `dumpsys activity activities` | Roblox shows as RESUMED on the virtual display, not PAUSED or STOPPED |
| 7 | Hidden surface: swap the display output to the ImageReader sink for 10 minutes | Still progressing, and the sink keeps receiving frames |
| 8 | Memory pressure: open Chrome, Instagram, and a heavy app, run for 30 to 60 minutes | Roblox process survives (watch logcat for kills) |
| 9 | Kill: force-stop Roblox from the prototype | Process dies and the display releases cleanly |

**Record for each run:** Android and OxygenOS version, free RAM, and logcat excerpts for any failure.

**If 5 or 6 fail:** the main fallback is a tiny freeform window on the real screen. If 3 fails: try the `input -d <id>` shell fallback, then reconsider the whole approach.

## 13. Risks
- Roblox may misrender or reject input on a secondary display (M0 answers this).
- Android 16 behaviour changes around virtual displays, overlays, and phantom process limits.
- OxygenOS aggressive memory management may still kill Roblox under pressure.
- Hidden-API access can break between Android versions.
- Roblox ToS (accepted by the user).

## 14. Open questions
- Exact Android and OxygenOS version, and RAM.
- Whether Roblox accepts touch input on a non-default display.
- Mirror window size and default position.
- Project name.
