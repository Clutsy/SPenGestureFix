#!/usr/bin/env python3
"""S Pen / Wacom emulator for the Galaxy Note 3.

ADB mode is the canonical path:
    python spen_mouse_emulator.py --list
    python spen_mouse_emulator.py --device /dev/input/event3

The phone has no reliable USB HID pen gadget on stock Note 3 kernels. The
script therefore reads the rooted Wacom input stream through ADB and emits
absolute mouse input locally. Windows uses SendInput and has no third-party
runtime dependency. TCP mode remains available with ``--tcp --host`` for the
Compose tablet server.
"""
from __future__ import annotations

import argparse
import ctypes
import logging
import os
import re
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional, Protocol, Sequence, Tuple

LOG = logging.getLogger("spen_mouse_emulator")
DEFAULT_ADB = "adb"
DEFAULT_PORT = 7654

# Kernel event flags / tablet protocol flags.
TOUCH = 1
BUTTON = 2
ERASER = 4
IN_RANGE = 8
LEFT, RIGHT, MIDDLE = 0, 1, 2


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
        screen_x = self._left + int(x * self._virtual_width / max(1, self._width - 1))
        screen_y = self._top + int(y * self._virtual_height / max(1, self._height - 1))
        nx = int((screen_x - self._left) * 65535 / max(1, self._virtual_width - 1))
        ny = int((screen_y - self._top) * 65535 / max(1, self._virtual_height - 1))
        self._send(self.MOVE | self.ABSOLUTE | self.VIRTUAL_DESK, nx, ny)

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
    ) -> None:
        self.adb = list(adb)
        self.device_path = device_path
        self.capabilities = capabilities
        self.backend = backend
        self.screen_width = screen_width
        self.screen_height = screen_height
        self.reconnect = reconnect
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
            elif code == "BTN_STYLUS":
                self._button_transition(RIGHT, down)
                self.state.button = down
            elif code in ("BTN_DIGI", "BTN_TOOL_PEN"):
                self.state.in_range = down

    def _move(self) -> None:
        x = int(normalize(self.state.x, self.capabilities.x) * (self.screen_width - 1))
        y = int(normalize(self.state.y, self.capabilities.y) * (self.screen_height - 1))
        self.backend.move_absolute(x, y)

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
                self.touching = self.button = False
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
        self.backend.move_absolute(
            int(max(0.0, min(1.0, x)) * (self.width - 1)),
            int(max(0.0, min(1.0, y)) * (self.height - 1)),
        )
        touching = bool(flags & TOUCH)
        button = bool(flags & BUTTON)
        if touching != self.touching:
            (self.backend.press if touching else self.backend.release)(LEFT)
            self.touching = touching
        if button != self.button:
            (self.backend.press if button else self.backend.release)(RIGHT)
            self.button = button


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
    return path, parse_capabilities(output)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="ADB-first S Pen Wacom mouse emulator")
    parser.add_argument("--device", "-d", help="/dev/input/eventN; auto-detected when omitted")
    parser.add_argument("--serial", help="ADB device serial when more than one device is connected")
    parser.add_argument("--screen-w", type=int, help="Target screen width")
    parser.add_argument("--screen-h", type=int, help="Target screen height")
    parser.add_argument("--max-x", type=int, help="Override ABS_X maximum")
    parser.add_argument("--max-y", type=int, help="Override ABS_Y maximum")
    parser.add_argument("--max-pressure", type=int, help="Override ABS_PRESSURE maximum")
    parser.add_argument("--list", action="store_true", help="Print rooted input devices and exit")
    parser.add_argument("--debug", action="store_true", help="Enable verbose logging")
    parser.add_argument("--tcp", action="store_true", help="Use the optional TCP tablet protocol")
    parser.add_argument("--host", help="Phone IP address for --tcp")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="[%(asctime)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    width, height = args.screen_w, args.screen_h
    if not width or not height:
        detected_w, detected_h = screen_size()
        width = width or detected_w
        height = height or detected_h
    adb = adb_command(args.serial)
    if args.list and not args.tcp:
        try:
            print(adb_output(list(adb) + ["shell", "su", "-c", "getevent -lp"]))
            return 0
        except (OSError, RuntimeError) as error:
            LOG.error("%s", error)
            return 1

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
                    capabilities = parse_capabilities(adb_output(list(adb) + ["shell", "su", "-c", "getevent -lp"]))
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
            worker = AdbPenEmulator(adb, device, capabilities, backend, width, height)
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
