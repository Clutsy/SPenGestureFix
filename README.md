<img width="270" height="480" alt="spen_1787403401482" src="https://github.com/user-attachments/assets/18cd7b03-5857-4e33-9aed-1bca8537ee93" />

<img width="270" height="480" alt="Screenshot_20260822-145738_S Pen Gesture Fix" src="https://github.com/user-attachments/assets/796a8eca-d723-4d23-adf0-0bb53b26f33a" />

<img width="270" height="480" alt="spen_1787403373153" src="https://github.com/user-attachments/assets/e3c53420-9fd1-44e1-85a8-a94336f41a53" />

<img width="480" height="270" alt="Screenshot_20260822-150541_S Pen Gesture Fix" src="https://github.com/user-attachments/assets/fc026071-416f-4fe2-99df-0e4960987934" />




# SPenGestureFix

A focused, local-first Android utility for rooted phones with a compatible S Pen/Wacom input stack. It restores programmable S Pen gestures and exposes the device digitizer as a low-latency tablet input source on rooted AOSP/LineageOS ROMs.

> **Target platform:** rooted Android devices that expose a compatible pen input node and presence switch through `/dev/input` or sysfs. The official Samsung S Pen framework is not assumed to be present.

## Product capabilities

- **Programmable side button:** single click, double click, and long press can run independent actions.
- **Air Command wheel:** six configurable radial actions, a user-selectable accent color, and a lightweight single-window fan anchored in the lower-right corner like classic Note Air Command. The six targets are deliberately spaced; tapping an action runs it, tapping the center closes the wheel, and tapping outside the compact window closes it without a full-screen touch blocker.
- **Quick Notes:** offline notes with editing, search, copy, sharing, character counting, phone-number dialing, and Maps lookup.
- **Screen tools:** annotate a screenshot or crop a rectangular region with coordinate-correct bitmap mapping.
- **Wacom Tablet Mode:** pressure curves, monitor-matched orientation, physical display resolution detection with a landscape-first default, a manual monitor override, haptics, smoothing, configurable right/middle/eraser/disabled pen-button behavior, and a normalized TCP stream for a PC client.
- **Modern UI:** Jetpack Compose and Material 3, localized in 17 languages: English, Italian, Spanish, French, German, Portuguese, Dutch, Polish, Turkish, Russian, Ukrainian, Simplified Chinese, Japanese, Korean, Arabic, Hindi, and Indonesian.
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

The in-app footer links to the project author at `https://github.com/clutsy`.

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

The canonical script is `scripts/spen_mouse_emulator.py`. It uses ADB and root `getevent` by default and emits absolute mouse input locally.

### ADB mode

```powershell
python scripts\spen_mouse_emulator.py --list
python scripts\spen_mouse_emulator.py --screen-w 1920 --screen-h 1080
python scripts\spen_mouse_emulator.py --serial R58MXXXX --orientation auto
python scripts\spen_mouse_emulator.py --serial R58MXXXX --device /dev/input/event3
python scripts\spen_mouse_emulator.py --debug
```

The script automatically discovers the device named `sec_e-pen`, reads the phone’s physical display resolution with `adb shell wm size`, reads the active display rotation, reads the real `ABS_X`, `ABS_Y`, and `ABS_PRESSURE` limits from `getevent -lp`, reconnects with bounded backoff, and releases held buttons on disconnect or Ctrl-C. The phone resolution is reported as the input source; mouse coordinates target the actual Windows desktop by default. Use `--screen-w/--screen-h` to override the desktop target explicitly.

On Windows it uses `ctypes` and `SendInput`; `pynput` and `pyautogui` are not required. DPI awareness and the virtual desktop are handled by the backend. The `tools/spen_mouse_emulation.py` path remains as a compatibility wrapper.

### Optional TCP mode

Tablet Mode can stream normalized frames over port `7654`:

```powershell
python scripts\spen_mouse_emulator.py --tcp --host 192.168.1.42 --port 7654
```

The TCP stream starts with an optional newline-terminated metadata record, for example `#SPEN_TABLET 1 1920 1080 1 landscape`, followed by `X,Y,P,FLAGS` records. Legacy clients can ignore the comment line. Flags are tip `1`, right/barrel button `2`, eraser `4`, in-range `8`, and middle button `16`. Tablet Mode maps the source axes once; the Windows client only rotates again when its monitor aspect orientation differs from the source metadata.

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
- `WheelOverlay.kt`: lower-right, single-window Canvas Air Command overlay with short transitions, spaced hit targets, outside-tap dismissal, and a configurable accent color.
- `MainComposeScreen.kt`, `ComposeNotesEditors.kt`, `ComposeTabletUi.kt`: Material 3 screens.
- `TabletInputCapture.kt`: capability-aware normalized frames.
- `TabletNetworkServer.kt`: latest-frame TCP transport.
- `scripts/spen_mouse_emulator.py`: ADB-first PC emulator.
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
