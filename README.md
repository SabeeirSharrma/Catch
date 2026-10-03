# Catch

Run the stock Play Store Roblox on a **hidden virtual display** so it keeps rendering and stays
connected while you use other apps. You set Roblox up yourself, then hide the window; a small
floating bubble is the only thing left on screen.

Catch is a utility app: it launches, mirrors, keeps alive, and sends taps. It does **not** inject
code, read memory, modify Roblox, or automate gameplay. Everything is driven by
[spec.md](spec.md) (Spec v0.5).

> Status: implemented and covered by unit tests + CI builds, but **not yet verified on a device**.
> The M0 feasibility checks in `spec.md` §12a still have to run on real hardware before this can be
> considered working — see [Verification status](#verification-status).

## How it works

| Piece | What it does |
|---|---|
| Foreground service (`service/CatchService`) | Owns the virtual display, the bubble and the tap loop; keeps the process alive. |
| Virtual display (`display/VirtualDisplayManager`) | MediaProjection-backed display (default 1280×720, low DPI). Its Surface is swapped between the mirror view and a small `ImageReader` sink — never null, so Roblox never pauses. |
| Shizuku bridge (`shell/`) | Runs as the shell uid in a user service: launches Roblox on the display (`am start --display`), injects input, applies keep-alive tuning. |
| Input (`shell/InputEngine`) | Primary path is `InputManager.injectInputEvent` with the display id set (hidden-API bypass inside the Shizuku process); fallback is a persistent shell writing `input -d <id> tap/swipe`. |
| Overlay (`overlay/BubbleOverlay`) | Draggable translucent bubble (opacity ≤ 0.8, non-focusable), edge-snapping, position memory, plus the mirror window. |
| Clicker / anti-idle (`core/ClickerEngine`, `core/AntiIdleEngine`) | Fixed-interval taps at a calibrated point, hard-capped at **30 CPS**, optional stop after N clicks/minutes; anti-idle taps every N minutes (default 5, always well under Roblox's ~20 min kick). |
| Watchdog (`service/WatchdogLogic`) | Notices Shizuku death, Roblox death or display loss and tells you; the loop stops and never restarts by itself. |
| Stop (`core/StopSequence`) | One action: halt the tools → force-stop Roblox → release the display → stop the service. |

**Safety rail:** every injected event carries the virtual display id and passes through
`core/InjectorGuard`, which refuses `displayId <= 0` and any id that is not the live virtual
display. Catch cannot tap your real screen or another app.

## Requirements

- Android 11+ (minSdk 30), built against SDK 35.
- **[Shizuku](https://shizuku.rikka.app/)** running via wireless debugging — this is how Catch
  works on a **non-rooted** phone. Grant Catch the Shizuku permission on first launch.
- MediaProjection consent (the system dialog appears when you start a session).
- OnePlus/OxygenOS: the in-app checklist covers the extra steps (background activity + auto-launch
  for both apps, unrestricted battery, lock both apps in recents).

## Build

Requires **JDK 17+ (21 recommended)** and an Android SDK with `platforms;android-35`.

```bash
./gradlew :app:testDebugUnitTest   # 92 unit tests
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease     # minified release APK (signed only if a keystore is configured)
```

Release signing is environment-driven, so no keystore is ever committed to git:

```bash
export CATCH_KEYSTORE_FILE=/path/to/release.jks
export CATCH_KEYSTORE_PASSWORD=… CATCH_KEY_ALIAS=… CATCH_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

## Releases (CI)

Two workflows live in `.github/workflows/`:

- **CI** (`ci.yml`) — on every push to `main` and on pull requests: unit tests, lint, debug APK,
  and uploads the reports as an artifact.
- **Release** (`release.yml`) — on a tag push matching `v*`:

  ```bash
  git tag v0.1.0
  git push origin v0.1.0
  ```

  It builds `assembleRelease`, verifies the signature with `apksigner` (an unsigned
  APK can never be published), and creates a GitHub Release with `Catch-vX.Y.Z.apk`
  and `SHA256SUMS.txt`.

  Signing requires these repository secrets — without them the job fails on purpose:

  | Secret | Value |
  |---|---|
  | `KEYSTORE_BASE64` | `base64 -w0 keystore/catch-release.jks` |
  | `KEYSTORE_PASSWORD` | contents of `keystore/catch-release.password.txt` |
  | `KEY_ALIAS` | `catch` |
  | `KEY_PASSWORD` | same as `KEYSTORE_PASSWORD` |

  Back up `keystore/` somewhere outside the repo: without it, no future update can
  be signed, and Android will refuse to install an update signed with a different key.

## Verification status

Verified locally and in CI:

- 104 JVM unit tests covering clicker rate/limits/pause, anti-idle, stop ordering, coordinate
  mapping, edge snapping, injector guard (including real-screen refusal), exec framing, keep-alive
  command text, watchdog transitions, surface routing, config validation, overlay window-metric
  fallbacks and error-report rendering.
- `lintDebug` clean of errors; `assembleDebug` and R8 `assembleRelease` both build, and the
  mapping file confirms the Shizuku user-service class survives shrinking unrenamed.
- No path fails silently: the bubble's window metrics fall back to the display metrics when the
  window manager refuses a non-UI context (a Service), every service command is guarded, and the
  last failure is written to disk and offered from Diagnostics as **Share last error report** —
  the only way a stack can leave a non-debuggable release build.

**Still unverified (needs a device, M0 §12a):** that the display is accepted with
`FLAG_PUBLIC`, that `InputManager.injectInputEvent` works through the Shizuku user service on the
target Android build, and Roblox's behaviour on a secondary display (focus, rendering, input).

## Project layout

```
app/src/main/aidl/      IShellService.aidl — user-service interface
app/src/main/java/com/sabeeir/catchapp/
  CrashLog.kt            last error report (survives a non-debuggable release build)
  core/                 pure logic: clicker, anti-idle, mapping, guard, stop, routing
  shell/                Shizuku session, user service, input engine, keep-alive, self-test
  display/              virtual display + ImageReader mirror sink
  service/              foreground service, notification, watchdog, broadcast actions
  overlay/              bubble + mirror overlay window, window-metric fallbacks
  ui/                   setup screen (permissions, checklist, session controls)
app/src/test/           unit tests
```

## License

See [LICENSE](LICENSE).
