#!/usr/bin/env python3
"""S Pen / Wacom mouse emulator.

ADB mode is the canonical path:
    python spen_mouse_emulator.py --list
    python spen_mouse_emulator.py --device /dev/input/event3

The phone may not expose a reliable USB HID pen gadget on stock kernels. The
script therefore reads the rooted Wacom input stream through ADB and emits
absolute mouse input locally. Windows uses SendInput and has no third-party
runtime dependency. TCP mode remains available with ``--tcp --host`` for the
Compose tablet server.
"""
from __future__ import annotations

import argparse
import ctypes
import logging
import re
import socket
import subprocess
import sys
import threading
from dataclasses import dataclass
from typing import Optional, Protocol, Sequence, Tuple

LOG = logging.getLogger("spen_mouse_emulator")
DEFAULT_ADB = "adb"

ASCII_LOGO = r"""                                                                                          
                                                         B$% $@@@@@@                                
                                                       $@@@@ $@B@@$@@                               
                                                      B@@@$ $BB   %@@$                              
                                                     @@@B  $@@$  B@@@                               
                                                   $@@@%  @@@$  $@@@                                
                                                  $@@@$ $@@@$  @@@$$                                
                                                 B@@@  B@@@$ $$@@$                                  
                                               W@@@B  B@@B  B@@@$                                   
                                              @@@@$  @@@$  B@@@                                     
                                            $@@@@  $@@@@  B@@B                                      
                                           $B@@$ $@@@$  $$@@B                                       
                                          %@$@   %@@8  $@@@8                                        
                                         @@@B$        @@@@$                                         
                                        B@@$         @@@@                                           
                                       B@@B        %@@@B                                            
                                      $@@@       $@$@@                                              
                                     $@@@$      B@@@$                                               
                                     B@@$     $B@@B                                                 
                                     $@@    $@@$B                                                   
                                     $@@  $@@@@$                                                    
                                     B@@@@@@$$                                                      
                                    B@@@$$                                                          
                                    @@B               $B@@@@@$@@$                                   
                                 $B@B               $@@@@@@$$%@@@@B                                 
                                $B@@B              @@@@$       $$@@@                                
                                $@@$             B@@$@           @@@                                
                                @@$             B@$@$            @@@                                
                               $@$$            @@@B              $@@                                
                               $@@@          $@@@$              $@@$                                
                                @@@B$      B@@@@$              B@@$$                                
                                 $@$@@B%@@@@@$$         B$$$@@@@@B                                  
                                   $%@@@@@B$           $B@@@@@@                                     
""".strip("\n")

def print_ascii_logo() -> None:
    """Always print the original ASCII logo."""
    print(ASCII_LOGO)

DEFAULT_PORT = 7654

# Kernel event flags / tablet protocol flags.
TOUCH = 1
BUTTON = 2
ERASER = 4
IN_RANGE = 8
MIDDLE_BUTTON = 16
LEFT, RIGHT, MIDDLE = 0, 1, 2
ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270 = 0, 1, 2, 3
ORIENTATION_AUTO = "auto"
ORIENTATION_PORTRAIT = "portrait"
ORIENTATION_LANDSCAPE = "landscape"
ORIENTATION_LANDSCAPE_INVERTED = "landscape-inverted"


@dataclass(frozen=True)
class AxisRange:
    minimum: int
    maximum: int


@dataclass(frozen=True)
class DeviceCapabilities:
    x: AxisRange = AxisRange(0, 4095)
    y: AxisRange = AxisRange(0, 4095)
    pressure: AxisRange = AxisRange(0, 1024)


@dataclass
class PenState:
    x: int = 0
    y: int = 0
    pressure: int = 0
    touching: bool = False
    button: bool = False
    in_range: bool = False


class MouseBackend(Protocol):
    def move_absolute(self, x: int, y: int) -> None: ...
    def press(self, button: int) -> None: ...
    def release(self, button: int) -> None: ...
    def close(self) -> None: ...


class WindowsSendInputBackend:
    """DPI-aware absolute mouse backend using only Win32 SendInput."""

    INPUT_MOUSE = 0
    MOVE = 0x0001
    ABSOLUTE = 0x8000
    VIRTUAL_DESK = 0x4000
    LEFT_DOWN = 0x0002
    LEFT_UP = 0x0004
    RIGHT_DOWN = 0x0008
    RIGHT_UP = 0x0010
    MIDDLE_DOWN = 0x0020
    MIDDLE_UP = 0x0040

    def __init__(self, width: int, height: int) -> None:
        if sys.platform != "win32":
            raise RuntimeError("WindowsSendInputBackend is available only on Windows")
        self._ctypes = ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass
        self._user32 = ctypes.windll.user32
        self._width = max(1, width)
        self._height = max(1, height)
        self._left = int(self._user32.GetSystemMetrics(76))  # SM_XVIRTUALSCREEN
        self._top = int(self._user32.GetSystemMetrics(77))   # SM_YVIRTUALSCREEN
        self._virtual_width = max(1, int(self._user32.GetSystemMetrics(78)))
        self._virtual_height = max(1, int(self._user32.GetSystemMetrics(79)))
        self._pressed: set[int] = set()

        ULONG_PTR = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [
                ("dx", ctypes.c_long),
                ("dy", ctypes.c_long),
                ("mouseData", ctypes.c_ulong),
                ("dwFlags", ctypes.c_ulong),
                ("time", ctypes.c_ulong),
                ("dwExtraInfo", ULONG_PTR),
            ]

        class INPUTUNION(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("data",)
            _fields_ = [("type", ctypes.c_ulong), ("data", INPUTUNION)]

        self._INPUT = INPUT
        self._MOUSEINPUT = MOUSEINPUT
        self._ULONG_PTR = ULONG_PTR

    def _send(self, flags: int, x: int = 0, y: int = 0) -> None:
        inp = self._INPUT()
        inp.type = self.INPUT_MOUSE
        inp.mi = self._MOUSEINPUT(
            dx=x, dy=y, mouseData=0, dwFlags=flags, time=0, dwExtraInfo=0
        )
        sent = self._user32.SendInput(1, self._ctypes.byref(inp), self._ctypes.sizeof(inp))
        if sent != 1:
            raise OSError(f"SendInput failed with return code {sent}")

    def move_absolute(self, x: int, y: int) -> None:
        x = max(0, min(self._width - 1, x))
        y = max(0, min(self._height - 1, y))
        screen_x = self._left + int(
            x * max(0, self._virtual_width - 1) / max(1, self._width - 1)
        )
        screen_y = self._top + int(
            y * max(0, self._virtual_height - 1) / max(1, self._height - 1)
        )
        nx = int(
            (screen_x - self._left) * 65535 / max(1, self._virtual_width - 1)
        )
        ny = int(
            (screen_y - self._top) * 65535 / max(1, self._virtual_height - 1)
        )
        self._send(
            self.MOVE | self.ABSOLUTE | self.VIRTUAL_DESK,
            max(0, min(65535, nx)),
            max(0, min(65535, ny)),
        )

    def press(self, button: int) -> None:
        if button in self._pressed:
            return
        flag = {LEFT: self.LEFT_DOWN, RIGHT: self.RIGHT_DOWN, MIDDLE: self.MIDDLE_DOWN}[button]
        self._send(flag)
        self._pressed.add(button)

    def release(self, button: int) -> None:
        if button not in self._pressed:
            return
        flag = {LEFT: self.LEFT_UP, RIGHT: self.RIGHT_UP, MIDDLE: self.MIDDLE_UP}[button]
        self._send(flag)
        self._pressed.discard(button)

    def close(self) -> None:
        for button in list(self._pressed):
            try:
                self.release(button)
            except Exception:
                LOG.debug("Could not release button %s", button, exc_info=True)
        self._pressed.clear()


class OptionalMouseBackend:
    """pynput/pyautogui fallback for non-Windows development machines."""

    def __init__(self, width: int, height: int) -> None:
        self._pressed: set[int] = set()
        self._buttons = None
        try:
            from pynput.mouse import Button, Controller  # type: ignore
            self._mouse = Controller()
            self._buttons = {LEFT: Button.left, RIGHT: Button.right, MIDDLE: Button.middle}
            self._kind = "pynput"
        except ImportError:
            import pyautogui  # type: ignore
            self._mouse = pyautogui
            self._buttons = {LEFT: "left", RIGHT: "right", MIDDLE: "middle"}
            self._kind = "pyautogui"
        LOG.info("Mouse backend: %s", self._kind)

    def move_absolute(self, x: int, y: int) -> None:
        if self._kind == "pynput":
            self._mouse.position = (x, y)
        else:
            self._mouse.moveTo(x, y, _pause=False)

    def press(self, button: int) -> None:
        if button in self._pressed:
            return
        value = self._buttons[button]
        if self._kind == "pynput":
            self._mouse.press(value)
        else:
            self._mouse.mouseDown(button=value, _pause=False)
        self._pressed.add(button)

    def release(self, button: int) -> None:
        if button not in self._pressed:
            return
        value = self._buttons[button]
        if self._kind == "pynput":
            self._mouse.release(value)
        else:
            self._mouse.mouseUp(button=value, _pause=False)
        self._pressed.discard(button)

    def close(self) -> None:
        for button in list(self._pressed):
            try:
                self.release(button)
            except Exception:
                LOG.debug("Could not release button %s", button, exc_info=True)


def parse_event_line(line: str) -> Optional[Tuple[str, str, str]]:
    """Parse all known getevent -l layouts, including a path ending in ':'."""
    tokens = line.strip().replace(":", " ").split()
    try:
        event_index = next(i for i, token in enumerate(tokens) if token.startswith("EV_"))
    except StopIteration:
        return None
    if len(tokens) < event_index + 3:
        return None
    return tokens[event_index], tokens[event_index + 1], tokens[event_index + 2]


def parse_value(value: str) -> Optional[int]:
    if value.upper() == "DOWN":
        return 1
    if value.upper() == "UP":
        return 0
    try:
        if value.lower().startswith("0x"):
            return int(value, 16)
        # getevent emits fixed-width hexadecimal values; short values from
        # hand-written fixtures are accepted as decimal for convenience.
        return int(value, 16) if len(value) > 2 else int(value, 10)
    except ValueError:
        return None


def normalize_resolution(width: int, height: int) -> Optional[Tuple[int, int]]:
    """Return valid display dimensions in landscape order."""
    if width <= 0 or height <= 0:
        return None
    return (max(width, height), min(width, height))


def parse_wm_size(output: str) -> Optional[Tuple[int, int]]:
    """Parse ``adb shell wm size`` and prefer the physical display size."""
    candidates: list[Tuple[bool, int, int]] = []
    pattern = re.compile(r"^\s*(?:(Physical|Override)\s*)?size:\s*(\d+)x(\d+)\s*$", re.IGNORECASE)
    for line in output.splitlines():
        match = pattern.search(line)
        if match:
            candidates.append(((match.group(1) or "").lower() == "physical", int(match.group(2)), int(match.group(3))))
    for physical, width, height in candidates:
        if physical:
            return normalize_resolution(width, height)
    if candidates:
        _, width, height = candidates[0]
        return normalize_resolution(width, height)
    return None


def parse_display_rotation(output: str) -> Optional[int]:
    """Parse Android rotation values reported as indices or degrees."""
    patterns = (
        re.compile(r"mCurrentRotation\s*[=:]\s*(?:ROTATION_)?(\d+)", re.IGNORECASE),
        re.compile(r"mDisplayRotation\s*[=:]\s*(?:ROTATION_)?(\d+)", re.IGNORECASE),
        re.compile(r"\borientation\s*[=:]\s*(?:ROTATION_)?(\d+)", re.IGNORECASE),
        re.compile(r"\brotation\s*[=:]\s*(?:ROTATION_)?(\d+)", re.IGNORECASE),
    )
    for pattern in patterns:
        match = pattern.search(output)
        if not match:
            continue
        value = int(match.group(1))
        if value in (0, 1, 2, 3):
            return value
        if value in (90, 180, 270):
            return value // 90
    return None


def parse_tablet_metadata(line: str) -> Optional[dict[str, object]]:
    """Parse the optional comment-prefixed tablet handshake line."""
    parts = line.strip().split()
    if len(parts) < 6 or parts[0] != "#SPEN_TABLET":
        return None
    try:
        version = int(parts[1])
        width = int(parts[2])
        height = int(parts[3])
        rotation = int(parts[4])
    except (ValueError, IndexError):
        return None
    if version != 1 or width < 0 or height < 0 or rotation not in (0, 1, 2, 3):
        return None
    return {
        "version": version,
        "source_width": width,
        "source_height": height,
        "source_rotation": rotation,
        "orientation": parts[5],
    }


def resolve_auto_rotation(
    resolution: Optional[Tuple[int, int]],
    display_rotation: Optional[int],
) -> int:
    """Resolve natural portrait pen axes into a horizontal desktop target."""
    if display_rotation in (ROTATION_90, ROTATION_270):
        return int(display_rotation)
    # parse_wm_size normalizes the physical panel to landscape order. A
    # portrait-native digitizer therefore needs one clockwise quarter-turn
    # even when dumpsys reports 0.
    if resolution and resolution[0] > resolution[1]:
        return ROTATION_90
    return ROTATION_0


def orient_normalized(x: float, y: float, rotation: int) -> Tuple[float, float]:
    """Rotate natural portrait Wacom axes into the Android display axes."""
    x = max(0.0, min(1.0, x))
    y = max(0.0, min(1.0, y))
    if rotation == ROTATION_90:
        return y, 1.0 - x
    if rotation == ROTATION_180:
        return 1.0 - x, 1.0 - y
    if rotation == ROTATION_270:
        return 1.0 - y, x
    return x, y


def orientation_rotation(orientation: str, display_rotation: int = ROTATION_0) -> int:
    """Resolve a CLI orientation into a quarter-turn transform."""
    if orientation == ORIENTATION_AUTO:
        return display_rotation if display_rotation in (0, 1, 2, 3) else ROTATION_0
    if orientation == ORIENTATION_LANDSCAPE:
        return ROTATION_90
    if orientation == ORIENTATION_LANDSCAPE_INVERTED:
        return ROTATION_270
    return ROTATION_0


def map_frame_orientation(
    x: float,
    y: float,
    source_orientation: str,
    target_width: int,
    target_height: int,
) -> Tuple[float, float]:
    """Map a normalized wire frame to the physical monitor orientation."""
    source_is_landscape = not source_orientation.lower().startswith("portrait")
    target_is_landscape = target_width >= target_height
    if source_is_landscape == target_is_landscape:
        return x, y
    if source_is_landscape:
        return 1.0 - y, x
    return y, 1.0 - x


def parse_capabilities(output: str) -> DeviceCapabilities:
    ranges: dict[str, AxisRange] = {}
    pattern = re.compile(r"(ABS_[A-Z0-9_]+).*?min\s+(-?\d+),\s*max\s+(-?\d+)")
    for line in output.splitlines():
        match = pattern.search(line)
        if match:
            ranges[match.group(1)] = AxisRange(int(match.group(2)), int(match.group(3)))
    return DeviceCapabilities(
        x=ranges.get("ABS_X", AxisRange(0, 4095)),
        y=ranges.get("ABS_Y", AxisRange(0, 4095)),
        pressure=ranges.get("ABS_PRESSURE", AxisRange(0, 1024)),
    )


def find_epen_device(output: str) -> Optional[str]:
    """Find sec_e-pen from getevent -lp blocks without relying on event number."""
    current_path: Optional[str] = None
    current_block: list[str] = []
    device_path = re.compile(r"(/dev/input/event\d+)")

    def inspect(block: Sequence[str], path: Optional[str]) -> Optional[str]:
        text = "\n".join(block).lower()
        return path if path and ("sec_e-pen" in text or "e-pen" in text) else None

    for line in output.splitlines():
        match = device_path.search(line.strip())
        if match:
            found = inspect(current_block, current_path)
            if found:
                return found
            current_path = match.group(1)
            current_block = [line]
        else:
            current_block.append(line)
    return inspect(current_block, current_path)


def normalize(value: int, axis: AxisRange) -> float:
    if axis.maximum <= axis.minimum:
        return 0.0
    return max(0.0, min(1.0, (value - axis.minimum) / (axis.maximum - axis.minimum)))


def query_device_resolution(adb: Sequence[str]) -> Optional[Tuple[int, int]]:
    try:
        return parse_wm_size(adb_output(list(adb) + ["shell", "wm", "size"]))
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        return None


def query_device_rotation(adb: Sequence[str]) -> Optional[int]:
    try:
        return parse_display_rotation(adb_output(list(adb) + ["shell", "dumpsys", "display"]))
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        return None


def screen_size() -> Tuple[int, int]:
    if sys.platform == "win32":
        try:
            user32 = ctypes.windll.user32
            return int(user32.GetSystemMetrics(0)), int(user32.GetSystemMetrics(1))
        except Exception:
            pass
    return 1920, 1080


def create_backend(width: int, height: int) -> MouseBackend:
    if sys.platform == "win32":
        return WindowsSendInputBackend(width, height)
    try:
        return OptionalMouseBackend(width, height)
    except ImportError as error:
        raise RuntimeError("Install pynput or pyautogui on non-Windows hosts") from error


class AdbPenEmulator:
    def __init__(
        self,
        adb: Sequence[str],
        device_path: str,
        capabilities: DeviceCapabilities,
        backend: MouseBackend,
        screen_width: int,
        screen_height: int,
        reconnect: bool = True,
        orientation: str = ORIENTATION_PORTRAIT,
        display_rotation: int = ROTATION_0,
    ) -> None:
        self.adb = list(adb)
        self.device_path = device_path
        self.capabilities = capabilities
        self.backend = backend
        self.screen_width = screen_width
        self.screen_height = max(1, screen_height)
        self.reconnect = reconnect
        self.orientation = orientation
        self.display_rotation = display_rotation
        self.rotation = orientation_rotation(orientation, display_rotation)
        self.stop_event = threading.Event()
        self.state = PenState()
        self.process: Optional[subprocess.Popen[str]] = None

    def command(self) -> list[str]:
        # device_path is validated by discovery or the CLI before it reaches
        # this command, so it does not need shell interpolation.
        return self.adb + ["shell", "su", "-c", f"exec getevent -l {self.device_path}"]

    def handle_event(self, event: Tuple[str, str, str]) -> None:
        event_type, code, raw_value = event
        if event_type == "EV_ABS":
            value = parse_value(raw_value)
            if value is None:
                return
            if code == "ABS_X":
                self.state.x = value
                self._move()
            elif code == "ABS_Y":
                self.state.y = value
                self._move()
            elif code == "ABS_PRESSURE":
                self.state.pressure = value
        elif event_type == "EV_KEY":
            value = parse_value(raw_value)
            if value is None:
                return
            down = value != 0
            if code == "BTN_TOUCH":
                self._button_transition(LEFT, down)
                self.state.touching = down
            elif code in ("BTN_STYLUS", "BTN_STYLUS2"):
                self._button_transition(RIGHT, down)
                self.state.button = down
            elif code in ("BTN_DIGI", "BTN_TOOL_PEN", "BTN_TOOL_RUBBER"):
                self.state.in_range = down

    def _move(self) -> None:
        raw_x = normalize(self.state.x, self.capabilities.x)
        raw_y = normalize(self.state.y, self.capabilities.y)
        x, y = orient_normalized(raw_x, raw_y, self.rotation)
        self.backend.move_absolute(
            int(x * (self.screen_width - 1)),
            int(y * (self.screen_height - 1)),
        )

    def _button_transition(self, button: int, down: bool) -> None:
        if down:
            self.backend.press(button)
        else:
            self.backend.release(button)

    def release_buttons(self) -> None:
        self.backend.release(LEFT)
        self.backend.release(RIGHT)
        self.state.touching = False
        self.state.button = False

    def stop(self) -> None:
        self.stop_event.set()
        process = self.process
        if process is not None:
            try:
                process.terminate()
            except OSError:
                pass

    def run(self) -> None:
        backoff = 1.0
        while not self.stop_event.is_set():
            try:
                LOG.info("Reading %s through ADB", self.device_path)
                self.process = subprocess.Popen(
                    self.command(),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    bufsize=1,
                    creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0),
                )
                backoff = 1.0
                assert self.process.stdout is not None
                for line in self.process.stdout:
                    if self.stop_event.is_set():
                        break
                    event = parse_event_line(line)
                    if event:
                        self.handle_event(event)
                if self.stop_event.is_set() or not self.reconnect:
                    break
                LOG.warning("ADB event stream closed")
            except FileNotFoundError:
                LOG.error("ADB was not found in PATH: %s", self.adb[0])
                break
            except OSError as error:
                LOG.warning("ADB input error: %s", error)
            finally:
                process = self.process
                self.process = None
                if process is not None:
                    try:
                        if process.poll() is None:
                            process.terminate()
                        process.wait(timeout=2)
                    except Exception:
                        try:
                            process.kill()
                        except Exception:
                            pass
                self.release_buttons()
            if self.stop_event.wait(backoff):
                break
            backoff = min(15.0, backoff * 2)


class TcpPenEmulator:
    """Optional client for the app's normalized TCP tablet stream."""
    def __init__(self, host: str, port: int, backend: MouseBackend, width: int, height: int) -> None:
        self.host, self.port = host, port
        self.backend = backend
        self.width, self.height = width, height
        self.stop_event = threading.Event()
        self.touching = False
        self.button = False
        self.middle_button = False
        self.eraser = False
        self.right_output = False
        self.metadata: Optional[dict[str, object]] = None
        self.source_orientation = ORIENTATION_LANDSCAPE

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        backoff = 1.0
        while not self.stop_event.is_set():
            sock: Optional[socket.socket] = None
            try:
                sock = socket.create_connection((self.host, self.port), timeout=5)
                sock.settimeout(None)
                LOG.info("Connected to tablet server %s:%d", self.host, self.port)
                buffer = ""
                while not self.stop_event.is_set():
                    data = sock.recv(4096)
                    if not data:
                        break
                    buffer += data.decode("ascii", errors="ignore")
                    while "\n" in buffer:
                        line, buffer = buffer.split("\n", 1)
                        if line.startswith("#SPEN_TABLET"):
                            self.metadata = parse_tablet_metadata(line)
                            if self.metadata:
                                self.source_orientation = str(self.metadata["orientation"])
                                LOG.info(
                                    "Tablet source: %sx%s, rotation %s°, orientation=%s; monitor: %sx%s",
                                    self.metadata["source_width"],
                                    self.metadata["source_height"],
                                    int(self.metadata["source_rotation"]) * 90,
                                    self.source_orientation,
                                    self.width,
                                    self.height,
                                )
                        else:
                            self.handle_frame(line)
                backoff = 1.0
            except (OSError, ValueError) as error:
                LOG.warning("TCP error: %s", error)
            finally:
                if sock:
                    try:
                        sock.close()
                    except OSError:
                        pass
                self.backend.release(LEFT)
                self.backend.release(RIGHT)
                self.backend.release(MIDDLE)
                self.touching = self.button = self.middle_button = self.eraser = False
                self.right_output = False
            if self.stop_event.wait(backoff):
                break
            backoff = min(15.0, backoff * 2)

    def handle_frame(self, line: str) -> None:
        parts = line.strip().split(",")
        if len(parts) != 4:
            return
        try:
            x, y = float(parts[0]), float(parts[1])
            flags = int(parts[3])
        except ValueError:
            return
        x, y = map_frame_orientation(
            max(0.0, min(1.0, x)),
            max(0.0, min(1.0, y)),
            self.source_orientation,
            self.width,
            self.height,
        )
        self.backend.move_absolute(
            int(max(0.0, min(1.0, x)) * (self.width - 1)),
            int(max(0.0, min(1.0, y)) * (self.height - 1)),
        )
        touching = bool(flags & TOUCH)
        button = bool(flags & BUTTON)
        middle_button = bool(flags & MIDDLE_BUTTON)
        eraser = bool(flags & ERASER)
        if touching != self.touching:
            (self.backend.press if touching else self.backend.release)(LEFT)
            self.touching = touching
        # The optional middle flag is independent from the right-button flag.
        if middle_button != self.middle_button:
            (self.backend.press if middle_button else self.backend.release)(MIDDLE)
            self.middle_button = middle_button
        # SendInput has no generic eraser primitive. Keep eraser distinct on
        # the wire, but expose it as a right-button hold to common desktop apps.
        right_output = button or eraser
        if right_output != self.right_output:
            (self.backend.press if right_output else self.backend.release)(RIGHT)
            self.right_output = right_output
        self.button = button
        self.eraser = eraser


def adb_command(serial: Optional[str]) -> list[str]:
    return [DEFAULT_ADB] + (["-s", serial] if serial else [])


def adb_output(command: Sequence[str], timeout: float = 15) -> str:
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"command exited with {result.returncode}")
    return result.stdout


def discover_device(adb: Sequence[str]) -> Tuple[str, DeviceCapabilities]:
    output = adb_output(list(adb) + ["shell", "su", "-c", "getevent -lp"])
    path = find_epen_device(output)
    if not path:
        raise RuntimeError("sec_e-pen was not found; use --device after checking --list")
    try:
        # Query the selected node again so another input device cannot overwrite
        # its axis limits while the complete capability listing is parsed.
        selected = adb_output(
            list(adb) + ["shell", "su", "-c", f"getevent -lp {path}"]
        )
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        selected = output
    return path, parse_capabilities(selected)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ADB-first S Pen Wacom mouse emulator")
    parser.add_argument("--device", "-d", help="/dev/input/eventN; auto-detected when omitted")
    parser.add_argument("--serial", help="ADB device serial when more than one device is connected")
    parser.add_argument("--screen-w", type=int, help="Target screen width")
    parser.add_argument("--screen-h", type=int, help="Target screen height")
    parser.add_argument("--max-x", type=int, help="Override ABS_X maximum")
    parser.add_argument("--max-y", type=int, help="Override ABS_Y maximum")
    parser.add_argument("--max-pressure", type=int, help="Override ABS_PRESSURE maximum")
    parser.add_argument(
        "--orientation",
        choices=(ORIENTATION_AUTO, ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE, ORIENTATION_LANDSCAPE_INVERTED),
        default=ORIENTATION_AUTO,
        help="Map natural pen axes to the display (default: auto)",
    )
    parser.add_argument("--list", action="store_true", help="Print rooted input devices and exit")
    parser.add_argument("--debug", action="store_true", help="Enable verbose logging")
    parser.add_argument("--tcp", action="store_true", help="Use the optional TCP tablet protocol")
    parser.add_argument("--host", help="Phone IP address for --tcp")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    print_ascii_logo()
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="[%(asctime)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    adb = adb_command(args.serial)
    device_resolution: Optional[Tuple[int, int]] = None
    device_rotation: Optional[int] = None
    if not args.tcp:
        device_resolution = query_device_resolution(adb)
        device_rotation = query_device_rotation(adb)
        if device_resolution:
            print(f"Phone display resolution: {device_resolution[0]}x{device_resolution[1]}")
        else:
            LOG.warning("Could not read the phone resolution; using the local Windows display")

    local_width, local_height = screen_size()
    # The phone resolution describes the input source. Mouse coordinates must
    # target the actual Windows desktop unless the user explicitly overrides it.
    width = args.screen_w or local_width
    height = args.screen_h or local_height
    if width <= 0 or height <= 0:
        parser.error("screen dimensions must be positive")
    if args.list and not args.tcp:
        if device_rotation is not None:
            print(f"Phone display rotation: {device_rotation * 90} degrees")
        try:
            print(adb_output(list(adb) + ["shell", "su", "-c", "getevent -lp"]))
            return 0
        except (OSError, RuntimeError) as error:
            LOG.error("%s", error)
            return 1

    if args.device and not re.fullmatch(r"/dev/input/event\d+", args.device):
        parser.error("--device must match /dev/input/eventN")
    auto_rotation = resolve_auto_rotation(device_resolution, device_rotation)
    resolved_rotation = orientation_rotation(args.orientation, auto_rotation)
    LOG.info(
        "Input mapping: %s (rotation %d); phone source: %s; desktop target: %dx%d",
        args.orientation,
        resolved_rotation * 90,
        f"{device_resolution[0]}x{device_resolution[1]}" if device_resolution else "unknown",
        width,
        height,
    )

    backend = create_backend(width, height)
    worker = None
    try:
        if args.tcp:
            if not args.host:
                parser.error("--tcp requires --host <phone-ip>")
            worker = TcpPenEmulator(args.host, args.port, backend, width, height)
        else:
            device, capabilities = discover_device(adb) if not args.device else (args.device, DeviceCapabilities())
            if args.device:
                try:
                    capabilities = parse_capabilities(
                        adb_output(
                            list(adb)
                            + ["shell", "su", "-c", f"getevent -lp {args.device}"]
                        )
                    )
                except Exception as error:
                    LOG.warning("Could not read device capabilities: %s", error)
            if args.max_x:
                capabilities = DeviceCapabilities(
                    AxisRange(capabilities.x.minimum, args.max_x), capabilities.y, capabilities.pressure
                )
            if args.max_y:
                capabilities = DeviceCapabilities(
                    capabilities.x, AxisRange(capabilities.y.minimum, args.max_y), capabilities.pressure
                )
            if args.max_pressure:
                capabilities = DeviceCapabilities(
                    capabilities.x, capabilities.y, AxisRange(capabilities.pressure.minimum, args.max_pressure)
                )
            worker = AdbPenEmulator(
                adb,
                device,
                capabilities,
                backend,
                width,
                height,
                orientation=args.orientation,
                display_rotation=auto_rotation,
            )
        worker.run()
        return 0
    except KeyboardInterrupt:
        LOG.info("Stopping")
        return 0
    except (OSError, RuntimeError) as error:
        LOG.error("%s", error)
        return 1
    finally:
        if worker is not None:
            worker.stop()
        backend.close()


if __name__ == "__main__":
    raise SystemExit(main())