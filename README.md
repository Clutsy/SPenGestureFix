# S Pen Gesture Fix

A focused, local-first Android utility for the Samsung Galaxy Note 3 (SM-N9005). It restores programmable S Pen gestures and exposes the Note 3 Wacom digitizer as a low-latency tablet input source on rooted AOSP/LineageOS ROMs.

> **Target platform:** rooted Galaxy Note 3 / Android ROMs that expose `sec_e-pen` and the `w1` presence switch through `/dev/input`. The official Samsung S Pen framework is not assumed to be present.

## Product capabilities

- **Programmable side button:** single click, double click, and long press can run independent actions.
- **Air Command wheel:** six configurable radial actions, custom background, animated Compose overlay, touch isolation, and AMOLED-friendly styling.
- **Quick Notes:** offline notes with editing, sharing, phone-number dialing, and Maps lookup.
- **Screen tools:** annotate a screenshot or crop a rectangular region with coordinate-correct bitmap mapping.
- **Wacom Tablet Mode:** pressure curves, orientation and aspect-ratio controls, haptics, smoothing, and a normalized TCP stream for a PC client.
- **Modern UI:** Jetpack Compose and Material 3 with English, Italian, and Spanish resources.
- **Root actions:** optional screenshot, flashlight, system toggles, lock screen, freeform window, and custom root command actions.

## Hardware architecture

The Note 3 Wacom driver is exposed as Linux input devices rather than as a reliable USB HID pen gadget. The Android service therefore reads `getevent -l` through a root shell:

- `sec_e-pen` carries absolute X/Y, pressure, hover, tip, and side-button events.
- `w1` reports physical S Pen insertion/removal through a device-specific switch code.
- Each device has its own `EPenInputReader` process. Stopping one reader never kills another process.
- Device discovery uses `/proc/bus/input/devices` when available and falls back to `getevent -lp`; some Note 3 ROMs deny the proc file even to root.
- Presence state is asynchronous and orthogonal to the digitizer stream. Inserting the pen updates state and the UI; it does not stop or gate Wacom input.
- On the Note 3, the initial slot state is also read from `sec_epen/epen_connection` (`OK`/`NG`) because `w1` does not expose a normal switch sysfs node.
- Compose pointer input is local to a screen or to the compact wheel window. The service never intercepts Android `MotionEvent` dispatch.

### Why the digitizer stopped responding

The previous implementation used `pkill -f 'getevent -l'` when the pen was inserted. That command was global: it could terminate the `w1` reader while it was processing the insertion event, and it could also terminate the `sec_e-pen` reader or a second diagnostic process. The service then lost both presence notifications and Wacom events. UI overlay operations were also started from a reader thread instead of the main looper.

The current architecture uses `exec getevent` per reader, idempotent process ownership, background parsing, main-thread-only overlay operations, and a continuously available digitizer reader.

## Requirements

- Samsung Galaxy Note 3 SM-N9005 or a compatible Wacom kernel device.
- Root access through Magisk, SuperSU, or an equivalent `su` implementation.
- AOSP/LineageOS or another ROM exposing the input devices.
- Android SDK with the project compile SDK installed.
- Python 3.9+ on the PC for the optional emulator.

The application needs overlay permission for Air Command and notification permission on Android 13+ for the foreground service. Root is required for the kernel input stream and root actions.

## Build and install

```bash
# From the repository root
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the Windows checkout used by this project, the bundled Gradle distribution can be invoked as:

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
6. Configure the side-button gestures and six wheel slots.
7. Extract and insert the pen while watching the live state card.

The service notification reports whether the digitizer reader is active. If the kernel device is missing, the app remains usable as a configuration tool and reports the failure rather than blocking the UI.

## Windows Wacom emulator

The canonical script is `scripts/spen_mouse_emulator.py`. It uses ADB and root `getevent` by default and emits absolute mouse input locally.

### ADB mode

```powershell
python scripts\spen_mouse_emulator.py --list
python scripts\spen_mouse_emulator.py --screen-w 1920 --screen-h 1080
python scripts\spen_mouse_emulator.py --serial R58MXXXX --device /dev/input/event3
python scripts\spen_mouse_emulator.py --debug
```

The script automatically discovers the device named `sec_e-pen`, reads the real `ABS_X`, `ABS_Y`, and `ABS_PRESSURE` limits from `getevent -lp`, reconnects with bounded backoff, and releases held buttons on disconnect or Ctrl-C.

On Windows it uses `ctypes` and `SendInput`; `pynput` and `pyautogui` are not required. DPI awareness and the virtual desktop are handled by the backend. The `tools/spen_mouse_emulation.py` path remains as a compatibility wrapper.

### Optional TCP mode

Tablet Mode can stream normalized frames over port `7654`:

```powershell
python scripts\spen_mouse_emulator.py --tcp --host 192.168.1.42 --port 7654
```

The TCP frame format is `X,Y,P,FLAGS` where flags are tip `1`, barrel button `2`, eraser `4`, and in-range `8`.

## Diagnostics

On a rooted device, inspect the available devices without changing state:

```bash
adb shell su -c "getevent -lp"
adb logcat -s SPenGestureService EPenInputReader SPenDebug
```

The expected digitizer capabilities include `BTN_TOUCH`, `BTN_STYLUS`, and `BTN_DIGI`. The Note 3 presence switch is ROM-specific; the service accepts `001a`, `SW_001A`, and common pen-switch aliases. On the stock Note 3 driver, `epen_connection=NG` means extracted and `OK` means inserted. A different kernel may report the inverse polarity, in which case the diagnostic decoder should be adjusted.

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
- `WheelOverlay.kt`: dual-window Compose Air Command overlay.
- `MainComposeScreen.kt`, `ComposeNotesEditors.kt`, `ComposeTabletUi.kt`: Material 3 screens.
- `TabletInputCapture.kt`: capability-aware normalized frames.
- `TabletNetworkServer.kt`: latest-frame TCP transport.
- `scripts/spen_mouse_emulator.py`: ADB-first PC emulator.
- `docs/TRANSLATION.md`: documentation and localization workflow.

## Testing

The repository includes JVM tests for input parsing, presence decoding, coordinate normalization, and pressure curves. The Python parser tests are hardware-free:

```bash
./gradlew :app:testDebugUnitTest
python scripts/test_spen_mouse_emulator.py
```

A connected phone is recommended for final acceptance testing because the exact `w1` switch code and polarity are kernel-specific.

## Limitations

- Text `getevent` parsing is robust for gesture and tablet workflows but has more latency than a native binary input reader.
- Screen Write and Smart Select are deliberately simple utilities; they do not provide Samsung handwriting recognition.
- Pen Window and root toggles depend on the ROM and are not available on every build.
- Android overlay policy and the device kernel can limit touch passthrough or background execution.
- `RenderEffect` blur is applied to the app-owned overlay layer. Android does not allow a generic overlay to capture and blur another application’s private pixels.

## Documentation and contributions

The root README is the commercial English source of truth. Follow `docs/TRANSLATION.md` when adding a language, changing terminology, or translating another document. Keep source comments and user-facing resource keys clear and consistent.

## License

Open source.
