
# SPenGestureFix


<img width="270" height="480" alt="spen_1787403401482" src="https://github.com/user-attachments/assets/18cd7b03-5857-4e33-9aed-1bca8537ee93" />

<img width="270" height="480" alt="Screenshot_20260822-145738_S Pen Gesture Fix" src="https://github.com/user-attachments/assets/796a8eca-d723-4d23-adf0-0bb53b26f33a" />

<img width="270" height="480" alt="spen_1787403373153" src="https://github.com/user-attachments/assets/e3c53420-9fd1-44e1-85a8-a94336f41a53" />

<img width="480" height="270" alt="Screenshot_20260822-150541_S Pen Gesture Fix" src="https://github.com/user-attachments/assets/fc026071-416f-4fe2-99df-0e4960987934" />


A focused, local-first Android utility for rooted phones with a compatible S Pen/Wacom input stack. It restores programmable S Pen gestures and exposes the device digitizer as a low-latency tablet input source on rooted AOSP/LineageOS ROMs.

> **Target platform:** rooted Android devices that expose a compatible pen input node and presence switch through `/dev/input` or sysfs. The official Samsung S Pen framework is not assumed to be present.

## Product capabilities

- **Programmable side button:** single click, double click, and long press can run independent actions.
- **Air Command wheel:** a staggered two-ring fan anchored in the lower-right corner like classic Note Air Command. Alternating inner/outer slot radii give every target 30 degrees of separation, labels render inside each slot in the device language, and a dedicated close slot keeps every spoke a real, labeled target. Selection follows the Samsung original: press and drag across the fan with a haptic tick on each slot, release to launch. A soft radial backdrop keeps the wheel readable over any app without blocking touches outside the window.
- **Quick Notes:** offline notes with editing, search, copy, sharing, character counting, phone-number dialing, and Maps lookup.
- **Screen tools:** annotate a screenshot or crop a rectangular region with coordinate-correct bitmap mapping.
- **Wacom Tablet Mode:** pressure curves, monitor-matched orientation, physical display resolution detection with a landscape-first default, a manual monitor override, haptics, smoothing, configurable right/middle/eraser/disabled pen-button behavior, a normalized TCP stream for a PC client, live session stats (frames sent), and an optional reverse PC-screen preview channel so the phone shows the Windows desktop while drawing.
- **Modern UI:** Jetpack Compose and Material 3 with a true AMOLED black mode, accent-tinted dashboard cards, and localization in 17 languages: English, Italian, Spanish, French, German, Portuguese, Dutch, Polish, Turkish, Russian, Ukrainian, Simplified Chinese, Japanese, Korean, Arabic, Hindi, and Indonesian.
- **Root actions:** optional screenshot, flashlight, system toggles, lock screen, freeform window, and custom root command actions.

## Hardware architecture

Many phone Wacom drivers are exposed as Linux input devices rather than as reliable USB HID pen gadgets. The Android service therefore reads `getevent -l` through a root shell:

- `sec_e-pen` carries absolute X/Y, pressure, hover, tip, and side-button events.
- `w1` reports physical S Pen insertion/removal through a device-specific switch code.
- Each device has its own `EPenInputReader` process. Stopping one reader never kills another process.
- Tablet Mode owns `sec_e-pen` exclusively. The normal gesture reader is stopped before tablet capture starts and restarted after the Activity exits, so two readers never compete for the same kernel stream.
- Device discovery uses `/proc/bus/input/devices` when available and falls back to `getevent -lp`; some ROMs deny the proc file even to root.
- Presence state is asynchronous and orthogonal to the normal digitizer stream. Inserting the pen updates state and the UI; outside Tablet Mode it never stops or gates Wacom input.
- Because some ROMs never report the presence switch live, presence also follows real pen activity: the first input event marks the pen as extracted, and 5 seconds without any pen input mark it as inserted again. This state machine is driven by real events and never blocks the digitizer pipeline.
- If the presence switch has no normal sysfs node, the initial slot state can also be read from a driver-specific connection node such as `epen_connection` (`OK`/`NG`).
- Compose pointer input is local to a screen or to the compact wheel window. The service never intercepts Android `MotionEvent` dispatch.
- Tablet Mode has an explicit process-local ownership state: the normal side-button analyzer, hover broadcasts, wheel, and shortcut actions are suspended until Tablet Mode exits. A back event cannot leave Tablet Mode; use its explicit Exit control.

### Why the digitizer stopped responding

The previous implementation used `pkill -f 'getevent -l'` when the pen was inserted. That command was global: it could terminate the `w1` reader while it was processing the insertion event, and it could also terminate the `sec_e-pen` reader or a second diagnostic process. The service then lost both presence notifications and Wacom events. UI overlay operations were also started from a reader thread instead of the main looper.

The current architecture uses `exec getevent` per reader, idempotent process ownership, background parsing, main-thread-only overlay operations, and a continuously available normal digitizer reader. Tablet Mode explicitly pauses that reader before taking ownership of the device.

## Release notes

### 2.1 — refined desktop panel, credits in the app

- **Clutsy is credited inside the app, not just the docs.** The sidebar carries a footer with the app mark, an "by Clutsy" link to `https://github.com/Clutsy` and the version; the Guide page ends with a Credits card (app & engine → Clutsy on GitHub, device photography → Wikimedia Commons contributors, both clickable) and the about card mentions the author next to the version. `--doctor` prints the project link too.
- **Quieter, more finished dashboard.** The mirror area shows a branded empty state (the app mark plus a hint) instead of a dead black rectangle, and its frame border flashes green while streaming and amber while paused; the live/paused badge and the header device chip gained status icons (the chip's phone turns green when a device is detected). The window titlebar is painted dark on Windows so the chrome matches the theme.
- **Selectable gallery.** The generation shown in the hero panel is ringed in accent blue inside the photo database grid, so the selection is visible at a glance; input fields get an accent focus ring. Guide cards are addressable (`guide_cards`) for tests and tooling.

### 2.0 — rebuilt desktop panel, real device photos, truly portable exe

- **The Windows panel was rewritten around five pages** (Dashboard, Phone, Options, Log, Guide) with a graphite theme, an accent sidebar with an active indicator, drawn vector icons, live stat tiles, keyboard shortcuts, a filterable log and a device chip in the header. Every control that used to need a command line is now one click away.
- **Device photos actually look like the phones.** `fetch_device_photos.py` now keeps a curated Wikimedia Commons file per Note generation, scores search results (`portrait`, `transparent PNG`, model match, penalising boxes/back shots/tablets), knives out a uniform background where possible, and renders two assets per device: a transparent hero render and a ready-made card with a soft glow, drop shadow and rounded corners. Cards are generated at 2x for DPI-scaled displays. Eleven generations: Note (2011), Note II, Note 3, Note 4, Note Edge, Note 5, Note 7, Note 8, Note 9, Note 10/10+ and Note 20/Ultra, each with author and licence in `photo_credits.json`.
- **The device pages show the phones at their real proportions.** The Phone page renders the selected generation into the exact box the layout gives it (and re-renders when the window changes), so a render is never stretched or clipped by its panel; the gallery lays every generation out in one wrapping grid at a single uniform scale, and the app mark (`gui_assets/logo.png`) drives the sidebar, the window icon and the exe icon.
- **The exe is genuinely portable.** The PyInstaller spec produces one self-contained `SPGF_WacomGUI.exe` (about 20 MB) that carries the UI, the streaming engine, the customtkinter theme assets, the photo database and the app icon; it needs neither Python nor any package. Settings live in `SPGF_WacomGUI.json` next to the exe, so the folder can be copied to a USB stick as-is. If that folder is read-only it falls back to the user profile instead of failing.
- **It survives other machines:** fonts are resolved from what is installed (no Segoe UI assumption), the window is clamped to the screen so a 1366x768 laptop does not lose its controls, the classic console opens through a platform-appropriate terminal, and unicode glyphs were replaced by drawn icons because Segoe UI renders ▶, ⟳ and ⚙ as empty boxes.
- **Built-in diagnostics:** `SPGF_WacomGUI.exe --doctor` prints a one-screen report (bindings, photo database, adb, settings path, engine) and `--write-icon` regenerates the app icon.
- **Fixed a startup crash:** the previous panel called a `main()` that did not exist, so it only ever ran in smoke-test mode.

### 1.7 — hub dashboard, classic wheel up to 7 slots, real color picker, portable GUI

- **Hub dashboard:** the home screen is now a grid of section tiles (Tablet Mode, Wheel look, Wheel slots, Wheel sounds, Gestures, Notes, Settings) plus the status card; tapping a tile opens that section as its own page with a back arrow in the top bar.
- **Wheel sounds fixed:** open/close sounds now play for BOTH wheel styles (they were silently gated to the classic style before); the first play prepares the player synchronously so MP3 files are never swallowed, and starting one sound stops the other.
- **Classic wheel supports 4/5/6/7 slots:** bitmap discs beyond the chosen count are erased pixel-perfectly from the extracted SpenCommand artwork; slot 7 continues the original spiral on a slightly wider (194dp) window. The classic style always shows exactly the slots configured in the dashboard.
- **Real color picker:** the wheel color opens a proper picker dialog with an HSV plane, hue slider and a hex/RGB text field kept in sync; presets and recents remain one tap away.
- **`SPGF_WacomGUI.exe` rebuilt around pages:** a sidebar opens Dashboard (live mirror, fps/quality chips, start/pause/resume), Telefono (adb detection with a REAL photo of the detected Galaxy Note generation — photos fetched from Wikimedia Commons with credits, line-art fallback), Opzioni (every streaming option) and Log (live event feed). Photo database refresh: `python scripts/fetch_device_photos.py`.

### 1.6 — Desktop Duplication capture, app-gated input, runtime channels

- **DXGI Desktop Duplication capture** (via the small `dxcam` package) is now **opt-in** with `--capture dxgi`: it can exceed 60 fps on machines with a reliable GPU duplicator, but on some driver stacks it degrades the stream, so the **stable default is GDI BitBlt** (`--capture gdi`). When DXGI fails twice in a row the session permanently falls back to GDI automatically.
- **Adaptive preview quality:** when WiFi cannot sustain the fps target, JPEG quality and scale step down (to 30 / 0.5×) and recover when headroom returns — the stream stays smooth instead of stuttering. A quiet screen is no longer mistaken for congestion.
- **Connection keep-alive:** the phone pings the PC (and paces idle pen sessions) once per second so the Wi-Fi radio never drops into power save during quiet stretches — no more stutters or resets when motion resumes after an idle period. The PC sender uses timeouts instead of blocking forever on a half-open link.
- **Zombie connections eliminated:** both phone servers now serve the latest connection — a PC reconnect after a WiFi hiccup evicts the dead socket instantly instead of queueing behind it forever.
- **Decode off the socket thread:** the preview server decodes JPEGs on a dedicated thread with a newest-wins queue, so a slow decode can no longer backpressure the stream.
- **Input only while Tablet Mode is on in the app:** the PC now opens pen-input sessions on demand; mouse injection starts and stops with the app's Tablet Mode, held buttons are released the instant the session drops, and reconnects are capped at 5 s.
- **Runtime channels from the already-running script:** type `preview` (screen stream) or `tcp` (pen input) in the console at any time — no `.bat`, no restarts. `status` shows both channels; the phone IP is remembered after the first launch.
- **Phone-side reader rebuilt:** the preview server now reads the socket in bulk (up to 64 KB) instead of one byte at a time, so decoding no longer caps the stream well below the network rate.

### 1.5 — fast fullscreen preview, clean pie wheel, battery saver

- **PC preview rebuilt for motion:** three parallel threads (GDI capture → Pillow JPEG encode → send, with an event-driven handoff so the sender wakes the instant a frame is ready), the raw BGRA buffer fed straight to Pillow at C speed, and a 60 Hz frame-sequence recompose loop on the phone replacing the old 66 ms poll. Measured **~30 fps end-to-end** (was 16). The phone shows the stream in a **true fullscreen view** with a live FPS counter.
- **No more retyping the command:** the client remembers your phone IP in `scripts\SPGF_Wacom.ini` after the first run — a plain `python scripts\SPGF_Wacom.py` reconnects to the same phone, and the interactive console creates channels at runtime.
- **Interactive console:** on an interactive terminal you can now type `preview` (start or resume streaming — works even when the script was started without `--preview`, as long as `--host` was given), `stop` (pause capture), `fps` (actual stream rate), `status`, and `quit` at runtime — no script restart needed.
- **`--preview-fps`** (default 30) sets the stream rate target; the adaptive encoder degrades gracefully to whatever the network sustains. `fps`/`status` report the true send rate.
- **Wheel redesigned as a clean pie menu:** one even circle of uniform discs, real **vector icons** instead of emoji, short labels, and a dedicated center close disc. Adjacent targets can no longer overlap at any slot count.
- **Fixed the phantom wheel opens:** digitizer touch events were being promoted to "pen extracted", auto-opening the wheel while writing. Presence now comes only from the physical slot switch.
- **Battery saver (5-second rule):** only when the physical slot switch confirms the pen is stored AND no input arrived for five seconds, the root digitizer reader process is parked completely (toggleable in Settings, on by default); pulling the pen out wakes it instantly. If the switch is unavailable the reader simply stays always-on, so gestures keep working.
- Removed the experimental Screen-off memo.

### 1.4 — translate, ordered wheel, richer notes

- Added the **Translate** Air Command action: sends the clipboard text to Google Translate.
- The wheel now has **seven ordered slots** with move up/down controls in the dashboard, so actions appear in the fan exactly in the configured order.
- Notes: **pin** (pinned notes sort first), six **accent colors** rendered as a card stripe, quick **open link** / **send email** detection alongside phone numbers, note **export all** (share every note as one text block), and **delete all** with confirmation.
- `SPGF_Wacom.py --preview` now works **standalone** (stream the PC screen without pen streaming): `python scripts\SPGF_Wacom.py --preview --host <phone-ip>`.

### 1.3 — wheel refresh, PC preview, and UI polish

- Rebuilt the Air Command wheel as a staggered two-ring fan: 30 degrees of effective spacing between targets, in-slot localized labels, a dedicated close slot, press-and-drag selection with haptics, and a soft radial backdrop.
- Added the Tablet Mode **PC screen preview**: a reverse TCP channel (port 7655) streams small JPEG frames of the Windows desktop to the phone, with a live pen-position marker. `SPGF_Wacom.py --preview` captures the desktop with GDI and requires no third-party packages (Pillow optional, higher quality).
- Tablet Mode now shows live session stats (frames sent, connection state) and a fullscreen PC preview dialog.
- The dashboard follows the AMOLED setting correctly (the flag previously had no effect on Compose screens) and gains accent-tinted section icons.
- Restored the Python test suite after the script rename (`test_spen_mouse_emulator.py` now imports `SPGF_Wacom`) and added framing/pen-state tests for the preview protocol.

### 1.2 — interaction and reliability refresh

- Replaced the rough centered wheel with a compact lower-right radial fan that is easier to reach and much lighter on the GPU.
- Removed the meaningless wheel background image and replaced it with a configurable accent palette.
- Fixed adaptive launcher icons using an opaque artwork layer; the artwork is now visible on Android 8+ launchers instead of rendering as a black square.
- Added physical display metrics detection, a one-tap resolution reset, monitor-aware orientation selection, and a forced landscape default for the normal horizontal-monitor workflow.
- Added note search, copy-to-clipboard, stable timestamp-based editing/deletion, and a 4,000-character guard.
- Kept presence inference independent from digitizer input: a pen with no input for strictly more than five seconds is considered inserted.
- Added automated checks for all 17 resource locales and the Android locale configuration.

## Hardware compatibility

The only hardware tested end to end is the Samsung Galaxy Note 3 SM-N9005 on the connected rooted ROM used by this repository. Compatibility with another device is not implied by the presence of an S Pen alone.

Potential, unverified targets include Galaxy Note 4/5 devices, Galaxy Note 8/9/10 devices, Galaxy Tab models with a Wacom digitizer, and other rooted Android hardware that exposes a compatible Linux input node with `ABS_X`, `ABS_Y`, pressure, `BTN_TOUCH`, and `BTN_STYLUS`. Each target needs its own device-name, axis-range, switch-polarity, kernel-permission, and orientation validation.

## Requirements

- Samsung Galaxy Note 3 SM-N9005 for the tested configuration, or a compatible Wacom kernel device for experimental use.
- Root access through Magisk, SuperSU, or an equivalent `su` implementation.
- AOSP/LineageOS or another ROM exposing the input devices.
- Android SDK with the project compile SDK installed.
- Python 3.9+ on the PC for the optional emulator.

The application needs overlay permission for Air Command and notification permission on Android 13+ for the foreground service. Root is required for the kernel input stream and root actions.

The in-app footer links to the project author at `https://github.com/Clutsy` (the desktop panel also credits the author in its sidebar and Guide page).

## Build and install

```bash
# From the repository root (system Gradle 8.x or the local .tools/gradle-8.13)
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the Windows checkout used by this project, the local Gradle distribution (kept outside the repository) can be invoked as:

```powershell
.tools\gradle-8.13\bin\gradle.bat :app:assembleDebug
```

The build uses Kotlin, AndroidX, Jetpack Compose, and Material 3. No NDK or JNI code is required.

The optional Windows panel builds from the same tree:

```bash
# From the repository root (needs Python 3.9+ with pillow and customtkinter)
python scripts\SPGF_WacomGUI.py --write-icon    # refresh gui_assets\app.ico
pyinstaller --noconfirm SPGF_WacomGUI.spec      # -> dist\SPGF_WacomGUI.exe

# Check a fresh copy (source or exe)
python scripts\SPGF_WacomGUI.py --doctor
dist\SPGF_WacomGUI.exe --doctor
```

The spec produces a single portable exe: copy `dist\SPGF_WacomGUI.exe` anywhere (it writes `SPGF_WacomGUI.json` beside itself). `SPGF_Wacom.py` is only needed next to the exe if you want the **Classic console** button to open the terminal client.

## First launch

1. Grant root access when requested.
2. Grant **Display over other apps** permission for the wheel.
3. Grant notifications on Android 13+.
4. Start the S Pen service from the dashboard; it must remain running because extraction is a Linux input event, not a system broadcast.
5. Enable **Open wheel on S Pen extraction** if the listener should show Air Command automatically.
6. Configure the side-button gestures, the six wheel slots, and the wheel accent color.
7. In Tablet Mode, use **Detect device resolution** to reset the target to the phone’s real display metrics after changing orientation or a manual monitor override.
8. Extract and insert the pen while watching the live state card.

The service notification reports whether the digitizer reader is active. If the kernel device is missing, the app remains usable as a configuration tool and reports the failure rather than blocking the UI.

## Windows Wacom emulator

The canonical script is `scripts/SPGF_Wacom.py`. It uses ADB and root `getevent` by default and emits absolute mouse input locally.

### ADB mode

```powershell
python scripts\SPGF_Wacom.py --list
python scripts\SPGF_Wacom.py --screen-w 1920 --screen-h 1080
python scripts\SPGF_Wacom.py --serial R58MXXXX --orientation auto
python scripts\SPGF_Wacom.py --serial R58MXXXX --device /dev/input/event3
python scripts\SPGF_Wacom.py --debug
```

The script automatically discovers the device named `sec_e-pen`, reads the phone’s physical display resolution with `adb shell wm size`, reads the active display rotation, reads the real `ABS_X`, `ABS_Y`, and `ABS_PRESSURE` limits from `getevent -lp`, reconnects with bounded backoff, and releases held buttons on disconnect or Ctrl-C. The phone resolution is reported as the input source; mouse coordinates target the actual Windows desktop by default. Use `--screen-w/--screen-h` to override the desktop target explicitly.

On Windows it uses `ctypes` and `SendInput`; `pynput` and `pyautogui` are not required. DPI awareness and the virtual desktop are handled by the backend. The `tools/spen_mouse_emulation.py` path remains as a compatibility wrapper.

### Optional TCP mode

Tablet Mode can stream normalized frames over port `7654`:

```powershell
python scripts\SPGF_Wacom.py --tcp --host 192.168.1.42 --port 7654
```

The TCP stream starts with an optional newline-terminated metadata record, for example `#SPEN_TABLET 1 1920 1080 1 landscape`, followed by `X,Y,P,FLAGS` records. Legacy clients can ignore the comment line. Flags are tip `1`, right/barrel button `2`, eraser `4`, in-range `8`, and middle button `16`. Tablet Mode maps the source axes once; the Windows client only rotates again when its monitor aspect orientation differs from the source metadata.

### PC screen preview (reverse channel)
<arg_value><b88a6f17>While a Tablet Mode session runs, the phone listens on port `7655` for a screen preview and shows it in a true fullscreen view with a live FPS counter. **Streaming starts automatically the moment the script launches** — no flags, no console commands:

```powershell
python scripts\SPGF_Wacom.py
```

The phone IP is remembered after the first launch (`python scripts\SPGF_Wacom.py --host 192.168.1.42` once, or just edit `scripts\SPGF_Wacom.ini`). The same launch also arms pen input, which injects only while Tablet Mode is on in the app.

The client captures the whole virtual desktop with GDI `BitBlt`, draws a live pen-position marker (blue hovering, red while touching, white ring when the barrel button is held), scales the image to `--preview-width` (default 960 px), and streams JPEG frames framed as `#PV` + 8 hex digits of payload length. Pillow produces the JPEG when installed (C-speed path); without it the script falls back to GDI+ encoding, so no third-party package is required. `--preview-quality` (15-95, default 55), `--preview-fps` (default 30), `--capture gdi|dxgi` and `--preview-port` tune the stream; capture → encode → send run on parallel threads with an event-driven handoff, so the sender never waits for the next poll tick. In the app, tap **PC screen** in the Tablet Mode status bar to open the fullscreen preview.

On an interactive terminal the client also accepts runtime commands (never required): `stop` pauses the screen stream, `preview` resumes it, `fps` prints the actual stream rate, `status` reports both channels, `tcp` re-arms pen input, and `quit` exits.

## Diagnostics

On a rooted device, inspect the available devices without changing state:

```bash
adb shell su -c "getevent -lp"
adb logcat -s SPenGestureService EPenInputReader SPenDebug
```

The expected digitizer capabilities include `BTN_TOUCH`, `BTN_STYLUS`, and `BTN_DIGI`. Presence switch codes and polarity are ROM-specific; the service accepts `001a`, `SW_001A`, and common pen-switch aliases. If the switch never fires, presence falls back to input activity: 5 seconds without pen input counts as inserted, and the next input event counts as extracted.

## Actions

| Action | Description |
|---|---|
| Open app | Launches a selected launcher application. |
| Open S Pen Wheel | Opens or closes Air Command. |
| Screenshot | Captures a screenshot into `Pictures/SPenScreenshots`. |
| Screen Write | Captures and annotates a screenshot. |
| Smart Select | Crops a selected rectangle from a screenshot. |
| Quick Note | Opens the offline note editor. |
| App search | Searches launcher applications and opens one. |
| Pen Window | Starts a selected app in an experimental freeform window. |
| Flashlight | Toggles the available camera torch. |
| Wi-Fi / Bluetooth | Runs the corresponding rooted `svc` command. |
| Mute / Lock screen | Sends the relevant rooted key event. |
| Custom root command | Runs an explicitly configured root command. |

Custom root commands are intentionally powerful. Only assign commands you understand and keep the device protected.

## Project map

- `SPenGestureService.kt`: foreground orchestration and runtime state.
- `EPenInputReader.kt`: isolated root input reader and event parser.
- `EventDeviceFinder.kt`: stable device discovery and capability limits.
- `PenGestureAnalyzer.kt`: non-blocking button and hover state machine.
- `WheelOverlay.kt`: lower-right staggered two-ring Air Command fan with press-drag selection, haptics, and a configurable accent color.
- `MainComposeScreen.kt`, `ComposeNotesEditors.kt`, `ComposeTabletUi.kt`: Material 3 screens.
- `TabletInputCapture.kt`: capability-aware normalized frames.
- `TabletNetworkServer.kt`: latest-frame TCP transport.
- `TabletPreviewServer.kt`: reverse JPEG screen-preview transport (port 7655).
- `scripts/SPGF_Wacom.py`: ADB-first PC emulator with optional screen preview.
- `scripts/SPGF_WacomGUI.py`: portable desktop panel (Dashboard, Phone, Options, Log, Guide); `--doctor`, `--smoke`, `--write-icon`.
- `scripts/fetch_device_photos.py`: builds `scripts/gui_assets` (device cards, hero renders, `photo_credits.json`) from Wikimedia Commons.
- `scripts/gui_assets/logo.png`: the app artwork; `--write-icon` turns it into `app.ico`/`app.png`.
- `docs/TRANSLATION.md`: documentation and localization workflow.

## Testing

The repository includes JVM tests for input parsing, presence decoding, the five-second idle rule, coordinate normalization, and pressure curves. The Python parser and locale checks are hardware-free:

```bash
gradle :app:testDebugUnitTest
python scripts/test_i18n.py
python scripts/test_spen_mouse_emulator.py
```

A connected phone is recommended for final acceptance testing because the exact `w1` switch code and polarity are kernel-specific.

## Limitations

- Text `getevent` parsing is robust for gesture and tablet workflows but has more latency than a native binary input reader.
- Screen Write and Smart Select are deliberately simple utilities; they do not provide Samsung handwriting recognition.
- Pen Window and root toggles depend on the ROM and are not available on every build.
- Android overlay policy and the device kernel can limit touch passthrough or background execution.
- The wheel is intentionally a compact lower-right overlay without a blurred backdrop: Android does not allow a generic overlay to capture and blur another application’s private pixels, and keeping the window small prevents it from blocking unrelated touches.
- Tablet Mode follows the configured monitor aspect orientation and defaults to landscape for a horizontal monitor. The app sends source orientation metadata so the Windows client can avoid a second accidental axis swap. If a particular ROM mounts the panel in reverse, choose Landscape (inverted) or use `--orientation landscape-inverted` in the Windows emulator.

## Documentation and contributions

The root README is the commercial English source of truth. Follow `docs/TRANSLATION.md` when adding a language, changing terminology, or translating another document. Keep source comments and user-facing resource keys clear and consistent.

The Android package is `com.spengesturefix`. Keep it unchanged: it is part of the app identity and the manifest, and renaming it again requires a full resource and manifest pass.

## License

Open source.
