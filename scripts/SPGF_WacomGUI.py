#!/usr/bin/env python3
"""SPGF Wacom GUI — the desktop control panel for Tablet Mode.

A single-window desktop app (customtkinter) that drives ``SPGF_Wacom.py`` and
shows exactly what the phone receives. Five pages:

  DASHBOARD  live mirror of the JPEG frames the phone gets, a session state
             card, stat tiles (fps / quality / scale / frames) and the
             start / pause / resume / stop controls;
  PHONE      adb detection with a real photo of the detected Galaxy Note
             generation, plus a browsable gallery of the whole photo database;
  OPTIONS    every streaming option (host, ports, width, quality, fps,
             GDI/DXGI, debug) grouped in cards, with validation;
  LOG        filterable live event feed with severity colours;
  GUIDE      what every control does and the order to click them in.

Everything it needs is bundled: bundled with PyInstaller
(``SPGF_WacomGUI.spec``) it becomes ``SPGF_WacomGUI.exe``, a portable exe that
carries the whole UI, the photo database and the streaming engine. Its settings
live in ``SPGF_WacomGUI.json`` next to the exe, so the folder can be copied to
another machine as-is; editing code is never required.

Run from source:
    python scripts/SPGF_WacomGUI.py
    python scripts/SPGF_WacomGUI.py --smoke     # build every page and exit
"""
from __future__ import annotations

import argparse
import io
import json
import queue
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Optional

import SPGF_Wacom as core

try:  # customtkinter is bundled by the spec; missing only in a bare Python.
    import customtkinter as ctk
    from tkinter import font as tkfont
    from tkinter import messagebox

    UI_AVAILABLE = True
    UI_ERROR = ""
except Exception as _ui_error:  # pragma: no cover - import guard
    ctk = None  # type: ignore[assignment]
    tkfont = None  # type: ignore[assignment]
    messagebox = None  # type: ignore[assignment]
    UI_AVAILABLE = False
    UI_ERROR = str(_ui_error)

try:
    from PIL import Image, ImageDraw, ImageFilter

    PIL_AVAILABLE = True
    LANCZOS = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
    AFFINE = getattr(getattr(Image, "Transform", Image), "AFFINE")
except Exception:  # pragma: no cover - optional at runtime
    Image = None  # type: ignore[assignment]
    PIL_AVAILABLE = False
    LANCZOS = AFFINE = None

APP_TITLE = "SPGF Wacom — Tablet Mode"
APP_VERSION = "2.1"
APP_HOME = "https://github.com/Clutsy"
APP_PHOTOS = "https://commons.wikimedia.org"

HERE = Path(__file__).resolve().parent
ASSETS = HERE / "gui_assets"

# ------------------------------------------------------------------- palette --
# True-black AMOLED theme, matching the Android app: pure black surfaces with
# near-black raised panels, a single blue accent and clear status colours.
# customtkinter paints the widgets, so the app stays sharp on any Windows DPI
# setting and reads the same on macOS and Linux.
BG = "#000000"
SIDEBAR = "#000000"
PANEL = "#0c0e12"
PANEL_ALT = "#12151a"
PANEL_HI = "#1a1e26"
STROKE = "#242936"
FG = "#e8ecf3"
FG_DIM = "#8b93a3"
FG_MUTED = "#646d7e"
ACCENT = "#3b82f6"
ACCENT_HOVER = "#5b9bff"
ACCENT_DEEP = "#1d4ed8"
GREEN = "#22c55e"
GREEN_DIM = "#15803d"
AMBER = "#f59e0b"
RED = "#ef4444"
CYAN = "#22d3ee"
ON_ACCENT = "#08121f"

MIRROR_BG = "#000000"

# Font families are resolved at runtime: the app must not depend on Segoe UI.
FAMILY = "Segoe UI"
MONO_FAMILY = "Consolas"


def resolve_fonts() -> None:
    """Pick an installed UI/mono family so the layout survives other OSes."""
    global FAMILY, MONO_FAMILY
    if tkfont is None:
        return
    try:
        installed = set(tkfont.families())
    except Exception:  # pragma: no cover - no Tk root yet
        return
    for candidate in ("Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue", "Ubuntu", "DejaVu Sans"):
        if candidate in installed:
            FAMILY = candidate
            break
    for candidate in ("Cascadia Mono", "Consolas", "SF Mono", "Menlo", "DejaVu Sans Mono", "Courier New"):
        if candidate in installed:
            MONO_FAMILY = candidate
            break


def icon_image(kind: str, size: int = 17, color: str = FG) -> "object":
    """Draws an icon with PIL so it renders identically on every OS.

    Unicode glyphs are not an option here: Segoe UI has no ▶, ⟳ or ⚙ and
    shows them as empty boxes. Drawing them guarantees the same sharp result
    on Windows, macOS and Linux, at 2x for DPI-scaled displays.
    """
    if not PIL_AVAILABLE:
        return None
    s = max(12, int(size)) * 2
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    w = max(2, int(round(s * 0.09)))

    def p(*points):
        return [(x * s, y * s) for x, y in points]

    if kind == "play":
        d.polygon(p((0.30, 0.16), (0.84, 0.50), (0.30, 0.84)), fill=color)
    elif kind == "pause":
        d.rounded_rectangle((0.28 * s, 0.18 * s, 0.44 * s, 0.82 * s), radius=w, fill=color)
        d.rounded_rectangle((0.56 * s, 0.18 * s, 0.72 * s, 0.82 * s), radius=w, fill=color)
    elif kind == "stop":
        d.rounded_rectangle((0.22 * s, 0.22 * s, 0.78 * s, 0.78 * s), radius=w, fill=color)
    elif kind == "refresh":
        d.arc((0.16 * s, 0.16 * s, 0.84 * s, 0.84 * s), start=-40, end=235, fill=color, width=w)
        d.polygon(p((0.60, 0.08), (0.94, 0.26), (0.58, 0.40)), fill=color)
    elif kind == "grid":
        for x, y in ((0.14, 0.14), (0.55, 0.14), (0.14, 0.55), (0.55, 0.55)):
            d.rounded_rectangle((x * s, y * s, (x + 0.31) * s, (y + 0.31) * s), radius=w, fill=color)
    elif kind == "phone":
        d.rounded_rectangle((0.27 * s, 0.09 * s, 0.73 * s, 0.91 * s), radius=0.11 * s,
                            outline=color, width=w)
        d.line((0.43 * s, 0.19 * s, 0.57 * s, 0.19 * s), fill=color, width=w)
        d.ellipse((0.46 * s, 0.76 * s, 0.54 * s, 0.82 * s), fill=color)
    elif kind == "sliders":
        for y, knob in ((0.28, 0.36), (0.50, 0.66), (0.72, 0.46)):
            d.line((0.15 * s, y * s, 0.85 * s, y * s), fill=color, width=w)
            d.ellipse(((knob - 0.08) * s, (y - 0.08) * s, (knob + 0.08) * s, (y + 0.08) * s), fill=color)
    elif kind == "list":
        for y in (0.28, 0.50, 0.72):
            d.ellipse((0.13 * s, (y - 0.055) * s, 0.24 * s, (y + 0.055) * s), fill=color)
            d.line((0.34 * s, y * s, 0.87 * s, y * s), fill=color, width=w)
    elif kind == "book":
        d.rounded_rectangle((0.17 * s, 0.16 * s, 0.83 * s, 0.84 * s), radius=0.09 * s,
                            outline=color, width=w)
        d.line((0.50 * s, 0.20 * s, 0.50 * s, 0.80 * s), fill=color, width=max(2, w - 1))
        d.line((0.26 * s, 0.32 * s, 0.42 * s, 0.32 * s), fill=color, width=max(2, w - 1))
        d.line((0.58 * s, 0.32 * s, 0.74 * s, 0.32 * s), fill=color, width=max(2, w - 1))
    elif kind == "terminal":
        d.rounded_rectangle((0.10 * s, 0.18 * s, 0.90 * s, 0.82 * s), radius=0.11 * s,
                            outline=color, width=w)
        d.line((0.26 * s, 0.38 * s, 0.42 * s, 0.50 * s), fill=color, width=w)
        d.line((0.42 * s, 0.50 * s, 0.26 * s, 0.62 * s), fill=color, width=w)
        d.line((0.54 * s, 0.63 * s, 0.74 * s, 0.63 * s), fill=color, width=w)
    elif kind == "copy":
        d.rounded_rectangle((0.12 * s, 0.12 * s, 0.60 * s, 0.60 * s), radius=0.09 * s,
                            outline=color, width=w)
        d.rounded_rectangle((0.40 * s, 0.40 * s, 0.88 * s, 0.88 * s), radius=0.09 * s,
                            outline=color, width=w)
    elif kind == "cross":
        d.line((0.24 * s, 0.24 * s, 0.76 * s, 0.76 * s), fill=color, width=w)
        d.line((0.76 * s, 0.24 * s, 0.24 * s, 0.76 * s), fill=color, width=w)
    elif kind == "dot":
        d.ellipse((0.24 * s, 0.24 * s, 0.76 * s, 0.76 * s), fill=color)
    return image


LOGO_SOURCE = "logo.png"
_LOGO_CACHE: dict[tuple, object] = {}


def logo_mark() -> "object":
    """The app mark from gui_assets/logo.png on transparency.

    The supplied artwork is white line art on a near-black square: its
    luminance becomes the alpha channel, so the mark sits cleanly on any panel
    colour (dark sidebar, accent button) without a black box around it.
    """
    if not PIL_AVAILABLE:
        return None
    cached = _LOGO_CACHE.get("mark", "missing")
    if cached != "missing":
        return cached
    mark = None
    path = asset_path(LOGO_SOURCE)
    if path is not None:
        try:
            source = Image.open(path).convert("RGB")
            grey = source.convert("L")
            alpha = grey.point(lambda v: 255 if v > 238 else min(255, int(v * 1.10)))
            mark = Image.new("RGBA", source.size, (255, 255, 255, 0))
            mark.putalpha(alpha)
            mark = mark.crop(alpha.getbbox() or (0, 0, source.width, source.height))
        except Exception:
            mark = None
    _LOGO_CACHE["mark"] = mark
    return mark


def brand_image(size: int = 256) -> "object":
    """Icon tile for the window and the .exe: the mark on a rounded dark plate."""
    if not PIL_AVAILABLE:
        return None
    s = int(size)
    image = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.22), fill="#000000")
    mark = logo_mark()
    if mark is None:  # drawn fallback: keeps the icon alive without the artwork
        d.rectangle((int(0.22 * s), int(0.22 * s), int(0.78 * s), int(0.78 * s)),
                    outline="#ffffff", width=max(2, int(s * 0.06)))
        d.line((0.32 * s, 0.32 * s, 0.68 * s, 0.68 * s), fill="#ffffff", width=max(2, int(s * 0.07)))
        return image
    inner = mark.copy()
    inner.thumbnail((int(s * 0.80), int(s * 0.80)), LANCZOS)
    image.alpha_composite(inner, ((s - inner.width) // 2, (s - inner.height) // 2))
    return image


def logo_image(size: int = 40, tile: bool = True):
    """Cached CTkImage of the logo: rounded plate (default) or bare mark."""
    if not PIL_AVAILABLE or ctk is None:
        return None
    key = ("logo", int(size), bool(tile))
    handle = _LOGO_CACHE.get(key)
    if handle is not None:
        return handle
    if tile:
        image = brand_image(int(size))
    else:
        mark = logo_mark()
        if mark is None:
            return None
        image = mark.copy()
        image.thumbnail((int(size), int(size)), LANCZOS)
    if image is None:
        return None
    handle = ctk.CTkImage(light_image=image, dark_image=image, size=image.size)
    _LOGO_CACHE[key] = handle
    return handle


def write_app_icon() -> Optional[Path]:
    """Regenerates gui_assets/app.ico (and app.png) from the logo artwork."""
    if not PIL_AVAILABLE:
        return None
    ASSETS.mkdir(parents=True, exist_ok=True)
    target = ASSETS / "app.ico"
    image = brand_image(256)
    image.save(target, sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    brand_image(512).save(ASSETS / "app.png")
    return target


def apply_dark_titlebar(window) -> None:
    """Windows: paint the titlebar dark so the chrome matches the theme."""
    if sys.platform != "win32":
        return
    try:
        import ctypes
        from ctypes import wintypes

        hwnd = wintypes.HWND(ctypes.windll.user32.GetParent(window.winfo_id()))
        for attribute in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE, old/new SDKs
            value = wintypes.BOOL(1)
            if ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, ctypes.c_int(attribute), ctypes.byref(value), ctypes.sizeof(value)
            ) == 0:
                break
    except Exception:  # pragma: no cover - best effort only
        pass


def f(size: int, weight: str = "normal") -> tuple:
    return (FAMILY, size, weight)


def mono(size: int, weight: str = "normal") -> tuple:
    return (MONO_FAMILY, size, weight)


F_TITLE = lambda: f(21, "bold")
F_H1 = lambda: f(17, "bold")
F_BODY = lambda: f(13)
F_BODY_BOLD = lambda: f(13, "bold")
F_SMALL = lambda: f(11)
F_SECTION = lambda: f(10, "bold")
F_STAT = lambda: f(21, "bold")
F_NAV = lambda: f(13)
F_MONO = lambda: mono(12)
F_MONO_S = lambda: mono(11)

# ------------------------------------------------------------------ assets ----
def asset_path(name: str) -> Optional[Path]:
    """Resolve a bundled asset from the source tree or a PyInstaller bundle."""
    candidates = [ASSETS / name]
    bundle = getattr(sys, "_MEIPASS", None)
    if bundle:
        candidates.append(Path(bundle) / "gui_assets" / name)
        candidates.append(Path(bundle) / name)
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def load_json_asset(name: str, default):
    path = asset_path(name)
    if path is None:
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


# ------------------------------------------------------------ image helpers --
def _hex_rgba(color: str, alpha: int = 255) -> tuple[int, int, int, int]:
    value = color.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha)


def _contain(image: "Image.Image", box: tuple[int, int]) -> "Image.Image":
    """Scales down to fit the box while keeping the aspect ratio exactly."""
    copy = image.copy()
    copy.thumbnail(box, LANCZOS)
    return copy


def rounded_mask(size: tuple[int, int], radius: int) -> "Image.Image":
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size[0] - 1, size[1] - 1), radius, fill=255
    )
    return mask


# --------------------------------------------------------- device recognition --
# Model-code fragments -> (photo key, human name) for every Note generation.
NOTE_MODELS: list[tuple[str, str, str]] = [
    ("sm-n980", "note20", "Galaxy Note20"),
    ("sm-n981", "note20", "Galaxy Note20"),
    ("sm-n985", "note20", "Galaxy Note20 Ultra"),
    ("sm-n986", "note20", "Galaxy Note20 Ultra"),
    ("note20", "note20", "Galaxy Note20"),
    ("sm-n970", "note10", "Galaxy Note10"),
    ("sm-n971", "note10", "Galaxy Note10+"),
    ("sm-n975", "note10", "Galaxy Note10+"),
    ("note10", "note10", "Galaxy Note10"),
    ("sm-n930", "note7", "Galaxy Note7"),
    ("note7", "note7", "Galaxy Note7"),
    ("sm-n960", "note9", "Galaxy Note9"),
    ("note9", "note9", "Galaxy Note9"),
    ("sm-n950", "note8", "Galaxy Note8"),
    ("note8", "note8", "Galaxy Note8"),
    ("sm-n920", "note5", "Galaxy Note5"),
    ("note5", "note5", "Galaxy Note5"),
    ("sm-n910", "note4", "Galaxy Note4"),
    ("sm-n915", "noteedge", "Galaxy Note Edge"),
    ("noteedge", "noteedge", "Galaxy Note Edge"),
    ("note4", "note4", "Galaxy Note4"),
    ("sm-n900", "note3", "Galaxy Note3"),
    ("gt-n900", "note3", "Galaxy Note3"),
    ("note3", "note3", "Galaxy Note3"),
    ("gt-n7100", "note2", "Galaxy Note II"),
    ("gt-n7105", "note2", "Galaxy Note II"),
    ("note2", "note2", "Galaxy Note II"),
    ("gt-n7000", "note1", "Galaxy Note (2011)"),
    ("n7000", "note1", "Galaxy Note (2011)"),
    ("note", "note5", "Samsung Note"),
]

GALLERY_ORDER = [
    "note1", "note2", "note3", "note4", "noteedge", "note5", "note7",
    "note8", "note9", "note10", "note20",
]


def match_note_model(model: str) -> tuple[str, str]:
    """Returns (photo key, display name) for a detected model string."""
    needle = (model or "").lower().replace(" ", "").replace("-", "")
    for fragment, key, name in NOTE_MODELS:
        if fragment.replace(" ", "").replace("-", "") in needle:
            return key, name
    return "note5", model or "Samsung Note"


# ------------------------------------------------------------------ settings --
def config_path() -> Path:
    """Settings live next to the exe (portable) or next to the script."""
    if getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
    else:
        base = HERE
    return base / "SPGF_WacomGUI.json"


DEFAULTS = {
    "host": "",
    "preview_port": int(getattr(core, "PREVIEW_PORT", 7655)),
    "tablet_port": int(getattr(core, "DEFAULT_PORT", 7654)),
    "width": 960,
    "quality": 55,
    "fps": 30.0,
    "capture": "gdi",
    "debug": False,
    "geometry": "1180x760",
    "last_device": "note3",
}


class Settings:
    """Tiny portable JSON store: no registry, no installer, no surprises."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.values = dict(DEFAULTS)
        self._fallback: Optional[Path] = None
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                self.values.update({k: v for k, v in raw.items() if k in DEFAULTS})
        except (OSError, ValueError):
            pass

    def get(self, key: str):
        return self.values.get(key, DEFAULTS.get(key))

    def set(self, key: str, value) -> None:
        self.values[key] = value

    def save(self) -> None:
        target = self._fallback or self.path
        try:
            target.write_text(json.dumps(self.values, indent=2), encoding="utf-8")
        except OSError:
            # Read-only folder (e.g. an exe on a locked share): keep working,
            # store the settings in the user profile instead.
            self._fallback = Path.home() / ".spgf_wacom_gui.json"
            try:
                self._fallback.write_text(json.dumps(self.values, indent=2), encoding="utf-8")
            except OSError:
                pass


# ------------------------------------------------------------------- adb probe --
class AdbWorker:
    """Reads `adb devices` + model in the background; posts results on a queue."""

    def __init__(self, events: "queue.Queue[tuple]") -> None:
        self._events = events
        self._busy = False

    def probe(self, serial: Optional[str] = None) -> None:
        if self._busy:
            self._events.put(("log", "detect already running", "warn"))
            return
        self._busy = True
        threading.Thread(target=self._run, args=(serial,), daemon=True).start()

    def _run(self, serial: Optional[str]) -> None:
        try:
            adb = list(core.adb_command(serial))
            listing = core.adb_output(adb + ["devices"], timeout=10)
            devices: list[str] = []
            for line in listing.splitlines()[1:]:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    devices.append(parts[0])
            if not devices:
                self._events.put(("adb", None, None))
                return
            target = serial if serial in devices else devices[0]
            model, ip, resolution = "", "", ""
            try:
                model = core.adb_output(
                    adb + ["-s", target, "shell", "getprop", "ro.product.model"], timeout=10
                ).strip().splitlines()[-1].strip()
            except (OSError, RuntimeError, IndexError):
                model = ""
            try:
                route = core.adb_output(adb + ["-s", target, "shell", "ip", "route"], timeout=10)
                for line in route.splitlines():
                    if "wlan0" in line and " src " in line:
                        ip = line.split(" src ")[1].split()[0]
                        break
            except (OSError, RuntimeError, IndexError):
                ip = ""
            try:
                size = core.adb_output(adb + ["-s", target, "shell", "wm", "size"], timeout=10)
                parsed = core.parse_wm_size(size)
                if parsed:
                    resolution = f"{parsed[0]}×{parsed[1]}"
            except (OSError, RuntimeError, IndexError):
                resolution = ""
            self._events.put(("adb", (target, model, ip, resolution), None))
        except (OSError, RuntimeError) as error:
            self._events.put(("adb", None, str(error)))
        finally:
            self._busy = False


# ------------------------------------------------------------------ the panel --
_CTkBase = ctk.CTk if ctk is not None else object


class WacomGUI(_CTkBase):  # type: ignore[misc, valid-type]
    PAGES = ("dashboard", "phone", "options", "log", "guide")
    NAV = {
        "dashboard": ("Dashboard", "Live mirror and stream controls", "grid"),
        "phone": ("Phone", "Detected device and photo database", "phone"),
        "options": ("Options", "Connection, quality and capture", "sliders"),
        "log": ("Log", "Everything the engine reports", "list"),
        "guide": ("Guide", "What to click, in order", "book"),
    }

    def __init__(self, settings: Optional[Settings] = None, auto_detect: bool = True) -> None:
        if ctk is None:
            raise RuntimeError(f"customtkinter is required: {UI_ERROR}")
        super().__init__()
        resolve_fonts()

        self.settings = settings or Settings(config_path())
        self.events: "queue.Queue[tuple]" = queue.Queue()
        self.adb = AdbWorker(self.events)
        self.hub: Optional[core.RuntimeCommandHub] = None
        self.backend = None
        self._credits = load_json_asset("photo_credits.json", {})
        self._device_key: Optional[str] = None
        self._hero_key: Optional[str] = None
        self._hero_size = (0, 0)
        self._hero_pending = False

        # Image handles: customtkinter drops the image if Python does.
        self._images: dict[str, object] = {}
        self._last_frame_marker = -1
        self._last_mirror_box = (0, 0)
        self._state = "idle"  # idle | live | paused

        self.title(APP_TITLE)
        self.geometry(self._initial_geometry())
        self.minsize(1040, 660)
        self.configure(fg_color=BG)
        self._set_window_icon()
        try:
            ctk.set_appearance_mode("dark")
            ctk.set_default_color_theme("blue")
        except Exception:
            pass

        self._build_layout()
        self._load_settings()
        self.show_page("dashboard")
        self._bind_shortcuts()
        self.after(60, lambda: apply_dark_titlebar(self))

        self.after(120, self._poll_events)
        self.after(400, self._poll_stream)
        self.after(220, self._pulse)
        self.after(80, self._fit_to_screen)
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        if auto_detect:
            self.after(700, lambda: self.adb.probe())

    # ---------------------------------------------------------- window setup --
    def _initial_geometry(self) -> str:
        """Remembered size, clamped to the screen and centred.

        A 1180x760 window does not fit a 1366x768 laptop panel, and a window
        taller than the screen silently loses its bottom row of controls.
        """
        raw = str(self.settings.get("geometry") or "1180x760")
        try:
            width, height = (int(part) for part in raw.split("x")[:2])
        except ValueError:
            width, height = 1180, 760
        screen_w, screen_h = self.winfo_screenwidth(), self.winfo_screenheight()
        width = max(1040, min(width, screen_w - 40))
        height = max(660, min(height, screen_h - 90))
        x = max(0, (screen_w - width) // 2)
        y = max(0, (screen_h - height) // 2 - 24)
        return f"{width}x{height}+{x}+{y}"

    def _set_window_icon(self) -> None:
        """Windows gets the bundled .ico; everywhere else the .png mark."""
        icon = asset_path("app.ico")
        if icon is not None:
            try:
                self.iconbitmap(str(icon))
            except Exception:
                pass
        png = asset_path("app.png")
        if png is not None and PIL_AVAILABLE:
            try:
                from PIL import ImageTk

                self._window_icon = ImageTk.PhotoImage(Image.open(png).convert("RGBA"))
                self.iconphoto(True, self._window_icon)
            except Exception:
                pass

    def _fit_to_screen(self) -> None:
        """Second pass: DPI scaling can round the window past the screen edge."""
        try:
            self.update_idletasks()
            width, height = self.winfo_width(), self.winfo_height()
            screen_w, screen_h = self.winfo_screenwidth(), self.winfo_screenheight()
            if width > screen_w - 24 or height > screen_h - 64:
                self.geometry(f"{min(width, screen_w - 40)}x{min(height, screen_h - 96)}")
        except Exception:
            pass

    def _icon(self, kind: str, color: str = FG, size: int = 17):
        """Cached CTkImage for a drawn icon."""
        cache_key = f"icon_{kind}_{color}_{size}"
        handle = self._images.get(cache_key)
        if handle is None and PIL_AVAILABLE:
            image = icon_image(kind, size=size, color=color)
            if image is not None:
                handle = ctk.CTkImage(light_image=image, dark_image=image, size=(size, size))
                self._images[cache_key] = handle
        return handle

    # ---------------------------------------------------------------- layout --
    def _build_layout(self) -> None:
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        self._build_sidebar()

        outer = ctk.CTkFrame(self, fg_color=BG, corner_radius=0)
        outer.grid(row=0, column=1, sticky="nsew", padx=(6, 18), pady=(14, 14))
        outer.grid_columnconfigure(0, weight=1)
        outer.grid_rowconfigure(1, weight=1)

        header = ctk.CTkFrame(outer, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", pady=(0, 12))
        header.grid_columnconfigure(0, weight=1)

        titles = ctk.CTkFrame(header, fg_color="transparent")
        titles.grid(row=0, column=0, sticky="w")
        self.page_title = ctk.CTkLabel(titles, text="", font=F_TITLE(), anchor="w")
        self.page_title.grid(row=0, column=0, sticky="w")
        self.page_subtitle = ctk.CTkLabel(
            titles, text="", font=F_SMALL(), text_color=FG_DIM, anchor="w"
        )
        self.page_subtitle.grid(row=1, column=0, sticky="w", pady=(2, 0))

        self.device_chip = ctk.CTkButton(
            header, text="No phone detected", font=F_SMALL(),
            image=self._icon("phone", FG_DIM, 14), compound="left",
            fg_color=PANEL, hover_color=PANEL_HI, text_color=FG_DIM,
            border_width=1, border_color=STROKE, corner_radius=18, height=34,
            command=lambda: self.show_page("phone"),
        )
        self.device_chip.grid(row=0, column=1, sticky="e")

        self.content = ctk.CTkFrame(outer, fg_color="transparent", corner_radius=0)
        self.content.grid(row=1, column=0, sticky="nsew")
        self.content.grid_rowconfigure(0, weight=1)
        self.content.grid_columnconfigure(0, weight=1)

        self.pages: dict[str, ctk.CTkFrame] = {}
        for key in self.PAGES:
            page = ctk.CTkFrame(self.content, fg_color="transparent", corner_radius=0)
            page.grid(row=0, column=0, sticky="nsew")
            page.grid_rowconfigure(0, weight=1)
            page.grid_columnconfigure(0, weight=1)
            self.pages[key] = page

        self._build_dashboard_page(self.pages["dashboard"])
        self._build_phone_page(self.pages["phone"])
        self._build_options_page(self.pages["options"])
        self._build_log_page(self.pages["log"])
        self._build_guide_page(self.pages["guide"])

    def _build_sidebar(self) -> None:
        bar = ctk.CTkFrame(self, width=248, corner_radius=0, fg_color=SIDEBAR)
        bar.grid(row=0, column=0, sticky="nsw")
        bar.grid_propagate(False)
        bar.grid_columnconfigure(0, weight=1)
        bar.grid_rowconfigure(len(self.PAGES) + 2, weight=1)

        brand = ctk.CTkFrame(bar, fg_color="transparent")
        brand.grid(row=0, column=0, sticky="ew", padx=20, pady=(22, 18))
        logo = logo_image(40)
        badge = ctk.CTkLabel(
            brand, text="" if logo else "SP", image=logo, font=f(15, "bold"),
            width=40, height=40, fg_color=ACCENT if logo is None else "transparent",
            text_color=ON_ACCENT, corner_radius=12,
        )
        badge.grid(row=0, column=0, rowspan=2, sticky="w", padx=(0, 12))
        ctk.CTkLabel(brand, text="SPGF Wacom", font=F_H1(), anchor="w").grid(
            row=0, column=1, sticky="sw"
        )
        ctk.CTkLabel(
            brand, text="Tablet Mode for the S Pen", font=F_SMALL(), text_color=FG_DIM, anchor="w"
        ).grid(row=1, column=1, sticky="nw")

        self.nav_buttons: dict[str, ctk.CTkButton] = {}
        self.nav_bars: dict[str, ctk.CTkFrame] = {}
        for index, key in enumerate(self.PAGES):
            label, _, glyph = self.NAV[key]
            row = ctk.CTkFrame(bar, fg_color="transparent")
            row.grid(row=1 + index, column=0, sticky="ew", padx=12, pady=2)
            row.grid_columnconfigure(1, weight=1)

            marker = ctk.CTkFrame(row, width=3, height=26, fg_color="transparent", corner_radius=2)
            marker.grid(row=0, column=0, sticky="w", padx=(0, 6))
            self.nav_bars[key] = marker

            button = ctk.CTkButton(
                row, text=f"   {label}", image=self._icon(glyph, FG_DIM), compound="left",
                font=F_NAV(), anchor="w", height=42, corner_radius=10,
                fg_color="transparent", text_color=FG_DIM, hover_color=PANEL,
                command=lambda k=key: self.show_page(k),
            )
            button.grid(row=0, column=1, sticky="ew")
            self.nav_buttons[key] = button

        status = ctk.CTkFrame(bar, fg_color=PANEL, corner_radius=12)
        status.grid(row=len(self.PAGES) + 2, column=0, sticky="ew", padx=16, pady=(0, 18))
        status.grid_columnconfigure(1, weight=1)
        self.status_dot = ctk.CTkLabel(status, text="●", font=f(14), text_color=FG_MUTED)
        self.status_dot.grid(row=0, column=0, sticky="w", padx=(14, 8), pady=(12, 0))
        self.status_label = ctk.CTkLabel(status, text="Idle", font=F_BODY_BOLD(), anchor="w")
        self.status_label.grid(row=0, column=1, sticky="ew", padx=(0, 14), pady=(12, 0))
        self.status_detail = ctk.CTkLabel(
            status, text="stream not started", font=F_SMALL(), text_color=FG_DIM,
            anchor="w", wraplength=190, justify="left",
        )
        self.status_detail.grid(row=1, column=0, columnspan=2, sticky="ew", padx=14, pady=(0, 12))

        footer = ctk.CTkFrame(bar, fg_color="transparent")
        footer.grid(row=len(self.PAGES) + 3, column=0, sticky="ew", padx=18, pady=(0, 14))
        footer.grid_columnconfigure(1, weight=1)
        foot_mark = logo_image(22, tile=False)
        ctk.CTkLabel(footer, text="", image=foot_mark).grid(row=0, column=0, sticky="w", padx=(0, 7))
        author = ctk.CTkLabel(
            footer, text="by Clutsy", font=F_SMALL(), text_color=FG_DIM,
            cursor="hand2", anchor="w",
        )
        author.grid(row=0, column=1, sticky="w")
        author.bind("<Button-1>", lambda _e: self._open_link(APP_HOME))
        ctk.CTkLabel(footer, text=f"v{APP_VERSION}", font=F_SMALL(), text_color=FG_MUTED).grid(
            row=0, column=2, sticky="e"
        )

    # ---------------------------------------------------------- card helpers --
    def _card(self, parent, **kwargs) -> "ctk.CTkFrame":
        return ctk.CTkFrame(parent, fg_color=PANEL, corner_radius=14, **kwargs)

    def _section(self, parent, text: str, row: int, column: int = 0, span: int = 1, padx=18, pady=(14, 6)):
        label = ctk.CTkLabel(parent, text=text.upper(), font=F_SECTION(), text_color=FG_MUTED, anchor="w")
        label.grid(row=row, column=column, columnspan=span, sticky="w", padx=padx, pady=pady)
        return label

    def _button(self, parent, text, command, kind: str = "ghost", height: int = 38,
                icon: Optional[str] = None, icon_color: Optional[str] = None, **kwargs):
        palette = {
            "primary": dict(fg_color=ACCENT, hover_color=ACCENT_HOVER, text_color=ON_ACCENT),
            "danger": dict(fg_color=PANEL_ALT, hover_color="#3a1f26", text_color="#fca5a5",
                           border_width=1, border_color="#5b2b34"),
            "ghost": dict(fg_color=PANEL_ALT, hover_color=PANEL_HI, text_color=FG,
                          border_width=1, border_color=STROKE),
            "quiet": dict(fg_color="transparent", hover_color=PANEL_ALT, text_color=FG_DIM),
        }[kind]
        handle = None
        if icon:
            default_colour = {"primary": ON_ACCENT, "ghost": FG, "danger": "#fca5a5"}.get(kind, FG_DIM)
            handle = self._icon(icon, icon_color or default_colour, 15)
        return ctk.CTkButton(
            parent, text=text, image=handle, compound="left",
            font=F_BODY_BOLD() if kind == "primary" else F_BODY(),
            height=height, corner_radius=10, command=command, **{**palette, **kwargs},
        )

    def _stat_tile(self, parent, row: int, column: int, label: str, initial: str, accent=FG):
        tile = ctk.CTkFrame(parent, fg_color=PANEL_ALT, corner_radius=12)
        tile.grid(row=row, column=column, sticky="nsew", padx=(0 if column == 0 else 6, 0), pady=(0, 6))
        tile.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(tile, text=label.upper(), font=F_SECTION(), text_color=FG_MUTED, anchor="w").grid(
            row=0, column=0, sticky="w", padx=12, pady=(10, 0)
        )
        value = ctk.CTkLabel(tile, text=initial, font=F_STAT(), text_color=accent, anchor="w")
        value.grid(row=1, column=0, sticky="w", padx=12, pady=(0, 10))
        return value

    # ------------------------------------------------------- dashboard page --
    def _build_dashboard_page(self, page: "ctk.CTkFrame") -> None:
        page.grid_columnconfigure(0, weight=5, minsize=420)
        page.grid_columnconfigure(1, weight=2, minsize=320)
        page.grid_rowconfigure(0, weight=1)

        mirror = self._card(page)
        mirror.grid(row=0, column=0, sticky="nsew", padx=(0, 12))
        mirror.grid_columnconfigure(0, weight=1)
        mirror.grid_rowconfigure(2, weight=1)

        head = ctk.CTkFrame(mirror, fg_color="transparent")
        head.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 6))
        head.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(head, text="Live mirror", font=F_H1(), anchor="w").grid(row=0, column=0, sticky="w")
        self.mirror_badge = ctk.CTkLabel(
            head, text="waiting for the phone", font=F_SMALL(), text_color=FG_DIM,
            image=self._icon("dot", FG_MUTED, 12), compound="left",
            fg_color=PANEL_ALT, corner_radius=10, padx=10, height=24,
        )
        self.mirror_badge.grid(row=0, column=1, sticky="e")

        self.mirror_info = ctk.CTkLabel(
            mirror, text="Frames appear here as soon as streaming starts.",
            font=F_SMALL(), text_color=FG_DIM, anchor="w",
        )
        self.mirror_info.grid(row=1, column=0, sticky="ew", padx=16, pady=(0, 8))

        # A host frame carries the border (CTkLabel has none) so the mirror can
        # flash green while live and amber while paused.
        self.mirror_host = ctk.CTkFrame(
            mirror, fg_color=MIRROR_BG, corner_radius=12,
            border_width=1, border_color=STROKE,
        )
        self.mirror_host.grid(row=2, column=0, sticky="nsew", padx=14, pady=(0, 14))
        self.mirror_host.grid_rowconfigure(0, weight=1)
        self.mirror_host.grid_columnconfigure(0, weight=1)
        self.mirror = ctk.CTkLabel(self.mirror_host, text="", fg_color="transparent")
        self.mirror.grid(row=0, column=0, sticky="nsew", padx=2, pady=2)
        # Empty state: the brand mark waits where the frames will land, so the
        # largest surface of the dashboard never reads as a dead black hole.
        empty_mark = logo_image(88)
        self.mirror_empty = ctk.CTkLabel(
            self.mirror_host, text="No frames yet — press «Start streaming» to go live",
            image=empty_mark, compound="top", font=F_BODY(), text_color=FG_MUTED,
            fg_color="transparent", justify="center",
        )
        self.mirror_empty.grid(row=0, column=0)
        self.mirror.lower()

        side = ctk.CTkFrame(page, fg_color="transparent")
        side.grid(row=0, column=1, sticky="nsew")
        side.grid_columnconfigure(0, weight=1)

        session = self._card(side)
        session.grid(row=0, column=0, sticky="ew")
        session.grid_columnconfigure(0, weight=1)
        session.grid_columnconfigure(1, weight=1)
        self._section(session, "Session", 0, span=2)

        self.session_state = ctk.CTkLabel(session, text="Idle", font=F_H1(), anchor="w")
        self.session_state.grid(row=1, column=0, columnspan=2, sticky="w", padx=18)
        self.stat_fps = self._stat_tile(session, 2, 0, "Frame rate", "0.0", GREEN)
        self.stat_quality = self._stat_tile(session, 2, 1, "JPEG", "—")
        self.stat_scale = self._stat_tile(session, 3, 0, "Scale", "—")
        self.stat_frames = self._stat_tile(session, 3, 1, "Frames", "0", CYAN)
        self.link_row = ctk.CTkLabel(
            session, text="not connected", font=F_SMALL(), text_color=FG_DIM,
            anchor="w", justify="left", wraplength=280,
        )
        self.link_row.grid(row=4, column=0, columnspan=2, sticky="ew", padx=18, pady=(6, 14))

        controls = self._card(side)
        controls.grid(row=1, column=0, sticky="ew", pady=(12, 0))
        controls.grid_columnconfigure(0, weight=1)
        controls.grid_columnconfigure(1, weight=1)
        self._section(controls, "Controls", 0, span=2)
        self.start_button = self._button(
            controls, "Start streaming", self._on_start, "primary", height=42, icon="play"
        )
        self.start_button.grid(row=1, column=0, columnspan=2, sticky="ew", padx=16, pady=(2, 6))
        self.pause_button = self._button(
            controls, "Pause", self._on_pause, "ghost", height=34, icon="pause"
        )
        self.pause_button.grid(row=2, column=0, sticky="ew", padx=(16, 3), pady=4)
        self.resume_button = self._button(
            controls, "Resume", self._on_resume, "ghost", height=34, icon="play"
        )
        self.resume_button.grid(row=2, column=1, sticky="ew", padx=(3, 16), pady=4)
        self.stop_button = self._button(
            controls, "Stop session", self._on_stop, "danger", height=34, icon="stop"
        )
        self.stop_button.grid(row=3, column=0, columnspan=2, sticky="ew", padx=16, pady=(4, 14))
        self.pause_button.configure(state="disabled")
        self.resume_button.configure(state="disabled")
        self.stop_button.configure(state="disabled")

        quick = self._card(side)
        quick.grid(row=2, column=0, sticky="ew", pady=(12, 0))
        quick.grid_columnconfigure(0, weight=1)
        quick.grid_columnconfigure(1, weight=1)
        self._section(quick, "Quick actions", 0, span=2)
        self._button(
            quick, "Detect phone", self._on_detect, "ghost", height=34, icon="refresh"
        ).grid(row=1, column=0, sticky="ew", padx=(16, 3), pady=(2, 4))
        self._button(
            quick, "Classic console", self._on_open_console, "ghost", height=34, icon="terminal"
        ).grid(row=1, column=1, sticky="ew", padx=(3, 16), pady=(2, 4))
        self._button(
            quick, "Open log", lambda: self.show_page("log"), "quiet", height=30, icon="list"
        ).grid(row=2, column=0, columnspan=2, sticky="ew", padx=16, pady=(2, 14))

    # ----------------------------------------------------------- phone page --
    def _build_phone_page(self, page: "ctk.CTkFrame") -> None:
        page.grid_rowconfigure(0, weight=1)
        page.grid_columnconfigure(0, weight=1)

        # The page scrolls: a short window must never squash the product shot.
        outer = ctk.CTkScrollableFrame(page, fg_color="transparent")
        outer.grid(row=0, column=0, sticky="nsew")
        outer.grid_columnconfigure(0, weight=1)
        self.phone_scroll = outer

        hero = self._card(outer)
        hero.grid(row=0, column=0, sticky="ew")
        hero.grid_columnconfigure(1, weight=1)

        self._section(hero, "Connected device", 0, span=2)

        # The render is composed for the exact box it gets and recomposed when
        # that changes: the phone can never be stretched or clipped by it.
        self.hero_box = ctk.CTkFrame(hero, fg_color="transparent", width=300, height=340)
        self.hero_box.grid(row=1, column=0, sticky="n", padx=(18, 12), pady=(2, 16))
        self.hero_box.grid_propagate(False)
        self.hero_box.bind("<Configure>", self._on_hero_resize)
        self.device_photo = ctk.CTkLabel(
            self.hero_box, text="—", font=F_SMALL(), text_color=FG_MUTED, fg_color="transparent"
        )
        self.device_photo.place(relx=0.5, rely=0.5, anchor="center")

        info = ctk.CTkFrame(hero, fg_color="transparent")
        info.grid(row=1, column=1, sticky="new", padx=(6, 18), pady=(6, 0))
        info.grid_columnconfigure(0, weight=1)
        self.device_name = ctk.CTkLabel(info, text="No phone", font=F_TITLE(), anchor="w")
        self.device_name.grid(row=0, column=0, sticky="w")
        self.device_model = ctk.CTkLabel(
            info, text="Connect the phone over USB and press Detect.",
            font=F_BODY(), text_color=FG_DIM, anchor="w", justify="left", wraplength=460,
        )
        self.device_model.grid(row=1, column=0, sticky="w", pady=(6, 0))

        specs = ctk.CTkFrame(info, fg_color="transparent")
        specs.grid(row=2, column=0, sticky="ew", pady=(14, 0))
        for column in range(3):
            specs.grid_columnconfigure(column, weight=1)
        self.spec_serial = self._spec(specs, 0, "Serial")
        self.spec_resolution = self._spec(specs, 1, "Display")
        self.spec_ip = self._spec(specs, 2, "Wi-Fi IP")

        row = ctk.CTkFrame(info, fg_color="transparent")
        row.grid(row=3, column=0, sticky="ew", pady=(16, 0))
        self._button(
            row, "Detect", self._on_detect, "primary", height=36, width=130, icon="refresh"
        ).pack(side="left")
        self._button(row, "Use IP in options", self._use_detected_ip, "ghost", height=36).pack(
            side="left", padx=8
        )
        self._button(
            row, "Copy info", self._copy_device_info, "quiet", height=36, icon="copy"
        ).pack(side="left")

        self.device_credit = ctk.CTkLabel(
            info, text="", font=F_SMALL(), text_color=FG_MUTED, anchor="w",
            justify="left", wraplength=460,
        )
        self.device_credit.grid(row=4, column=0, sticky="w", pady=(14, 0))

        # Every generation sits on the page at once: nothing hides in a
        # sideways scroller the user has to discover.
        gallery = self._card(outer)
        gallery.grid(row=1, column=0, sticky="ew", pady=(12, 4))
        gallery.grid_columnconfigure(0, weight=1)
        self._section(
            gallery,
            f"Photo database — all {len(GALLERY_ORDER)} Galaxy Note generations, 2011 → 2020",
            0, pady=(14, 2),
        )
        strip = ctk.CTkFrame(gallery, fg_color="transparent")
        strip.grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 14))
        self.gallery_strip = strip
        self.gallery_buttons: dict[str, ctk.CTkButton] = {}
        for key in GALLERY_ORDER:
            name = str(self._credits.get(key, {}).get("name", key)).replace("Galaxy ", "")
            button = ctk.CTkButton(
                strip, text=name, image=self._thumb_image(key),
                compound="top", font=F_SMALL(), fg_color="transparent",
                hover_color=PANEL_ALT, text_color=FG_DIM, corner_radius=10,
                width=118, height=140,
                command=lambda k=key: self._show_device_key(k, detected=False),
            )
            self.gallery_buttons[key] = button
        strip.bind("<Configure>", lambda e: self._layout_gallery(e.width))
        self._layout_gallery(0)

    def _layout_gallery(self, width: int) -> None:
        """Wraps the device cards into as many columns as the width allows."""
        if len(getattr(self, "gallery_buttons", [])) == 0:
            return
        if width < 120:
            width = self.gallery_strip.winfo_width()
        if width < 120:
            return
        columns = max(3, min(len(self.gallery_buttons), max(1, width // 124)))
        if columns == getattr(self, "_gallery_columns", 0):
            return
        self._gallery_columns = columns
        for index, button in enumerate(self.gallery_buttons.values()):
            button.grid(row=index // columns, column=index % columns, padx=3, pady=3)
        for column in range(len(self.gallery_buttons)):
            self.gallery_strip.grid_columnconfigure(column, weight=0)

    def _spec(self, parent, column: int, label: str) -> "ctk.CTkLabel":
        box = ctk.CTkFrame(parent, fg_color=PANEL_ALT, corner_radius=10)
        box.grid(row=0, column=column, sticky="ew", padx=(0 if column == 0 else 8, 0))
        box.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(box, text=label.upper(), font=F_SECTION(), text_color=FG_MUTED, anchor="w").grid(
            row=0, column=0, sticky="w", padx=12, pady=(8, 0)
        )
        value = ctk.CTkLabel(box, text="—", font=F_BODY(), anchor="w")
        value.grid(row=1, column=0, sticky="w", padx=12, pady=(0, 8))
        return value

    # --------------------------------------------------------- options page --
    def _build_options_page(self, page: "ctk.CTkFrame") -> None:
        outer = ctk.CTkScrollableFrame(page, fg_color="transparent")
        outer.grid(row=0, column=0, sticky="nsew")
        outer.grid_columnconfigure(0, weight=1)
        self.options_scroll = outer

        self.var_host = ctk.StringVar()
        self.var_preview_port = ctk.StringVar(value=str(self.settings.get("preview_port")))
        self.var_tablet_port = ctk.StringVar(value=str(self.settings.get("tablet_port")))
        self.var_width = ctk.StringVar(value=str(self.settings.get("width")))
        self.var_quality = ctk.IntVar(value=int(self.settings.get("quality")))
        self.var_fps = ctk.DoubleVar(value=float(self.settings.get("fps")))
        self.var_capture = ctk.StringVar(value=str(self.settings.get("capture")))
        self.var_debug = ctk.BooleanVar(value=bool(self.settings.get("debug")))

        connection = self._field_card(outer, 0, "Connection", "Where the phone listens on the network")
        self._field_entry(connection, "Phone IP", self.var_host, width=220, mono=True)
        self._field_entry(connection, "Screen preview port", self.var_preview_port, width=110, mono=True)
        self._field_entry(connection, "Pen input port", self.var_tablet_port, width=110, mono=True)
        self._field_hint(
            connection,
            "The IP is filled automatically after Detect; ports only change if the phone's "
            "Tablet Mode uses different ones.",
        )

        quality = self._field_card(outer, 1, "Stream quality", "Trade bandwidth for sharpness")
        self._field_entry(quality, "Preview width (px)", self.var_width, width=110, mono=True)
        self.slider_quality, self.label_quality = self._field_slider(
            quality, "JPEG quality", 25, 95, self.var_quality
        )
        self.slider_fps, self.label_fps = self._field_slider(
            quality, "Target FPS", 10, 60, self.var_fps
        )
        self._field_hint(
            quality,
            "The encoder lowers quality and scale by itself when Wi-Fi cannot hold the target.",
        )

        capture = self._field_card(outer, 2, "Capture", "How the Windows desktop is grabbed")
        segmented = ctk.CTkSegmentedButton(
            capture, values=["GDI", "DXGI"], variable=self.var_capture, font=F_BODY(),
            height=34, selected_color=ACCENT, selected_hover_color=ACCENT_HOVER,
            unselected_color=PANEL_ALT, unselected_hover_color=PANEL_HI,
        )
        segmented.set(str(self.settings.get("capture")).upper())
        segmented.grid(row=self._next_row(capture), column=0, columnspan=2, sticky="w", padx=18, pady=(0, 8))
        self._field_hint(
            capture,
            "GDI is the stable default on every driver. DXGI can pass 60 fps on a good GPU and "
            "falls back to GDI automatically if it fails.",
        )
        ctk.CTkCheckBox(
            capture, text="Verbose logging (debug)", variable=self.var_debug, font=F_BODY(),
            fg_color=ACCENT, hover_color=ACCENT_HOVER, border_color=STROKE,
        ).grid(row=self._next_row(capture), column=0, columnspan=2, sticky="w", padx=18, pady=(0, 18))

        footer = ctk.CTkFrame(outer, fg_color="transparent")
        footer.grid(row=3, column=0, sticky="ew", pady=(4, 18))
        footer.grid_columnconfigure(0, weight=1)
        self._button(footer, "Save settings", self._save_options, "primary", height=38).grid(
            row=0, column=0, sticky="w"
        )
        self._button(footer, "Reset to defaults", self._reset_options, "ghost", height=38).grid(
            row=0, column=1, sticky="w", padx=8
        )
        ctk.CTkLabel(
            footer, text="Saved in SPGF_WacomGUI.json next to the app — copy the folder anywhere.",
            font=F_SMALL(), text_color=FG_MUTED, anchor="w",
        ).grid(row=1, column=0, columnspan=2, sticky="w", pady=(8, 0))

    def _field_card(self, parent, index: int, title: str, subtitle: str) -> "ctk.CTkFrame":
        card = self._card(parent)
        card.grid(row=index, column=0, sticky="ew", pady=(0, 12))
        card.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(card, text=title, font=F_H1(), anchor="w").grid(
            row=0, column=0, columnspan=2, sticky="w", padx=18, pady=(16, 0)
        )
        ctk.CTkLabel(card, text=subtitle, font=F_SMALL(), text_color=FG_DIM, anchor="w").grid(
            row=1, column=0, columnspan=2, sticky="w", padx=18, pady=(0, 10)
        )
        card._next_row = 2  # type: ignore[attr-defined]
        return card

    def _next_row(self, card) -> int:
        row = getattr(card, "_next_row", 2)
        card._next_row = row + 1  # type: ignore[attr-defined]
        return row

    def _field_entry(self, card, label: str, variable, width: int = 200, mono: bool = False):
        row = self._next_row(card)
        ctk.CTkLabel(card, text=label, font=F_BODY(), anchor="w").grid(
            row=row, column=0, sticky="w", padx=18, pady=6
        )
        entry = ctk.CTkEntry(
            card, textvariable=variable, width=width, font=F_MONO() if mono else F_BODY(),
            fg_color=PANEL_ALT, border_color=STROKE, corner_radius=8, height=34,
        )
        entry.grid(row=row, column=1, sticky="w", padx=(0, 18), pady=6)
        entry.bind("<FocusIn>", lambda _e: entry.configure(border_color=ACCENT))
        entry.bind("<FocusOut>", lambda _e: entry.configure(border_color=STROKE))
        return entry

    def _field_slider(self, card, label: str, low: int, high: int, variable):
        """One slider row with its live value chip."""
        row = self._next_row(card)
        ctk.CTkLabel(card, text=label, font=F_BODY(), anchor="w").grid(
            row=row, column=0, sticky="w", padx=18, pady=6
        )
        host = ctk.CTkFrame(card, fg_color="transparent")
        host.grid(row=row, column=1, sticky="ew", padx=(0, 18), pady=6)
        host.grid_columnconfigure(0, weight=1)
        slider = ctk.CTkSlider(
            host, from_=low, to=high, variable=variable, number_of_steps=high - low,
            progress_color=ACCENT, button_color=FG, button_hover_color=ACCENT_HOVER,
            fg_color=STROKE,
        )
        slider.grid(row=0, column=0, sticky="ew")
        value = ctk.CTkLabel(host, text=str(int(variable.get())), font=F_MONO(), width=46)
        value.grid(row=0, column=1, sticky="e", padx=(10, 0))
        variable.trace_add("write", lambda *_: value.configure(text=str(int(variable.get()))))
        return slider, value

    def _field_hint(self, card, text: str) -> None:
        ctk.CTkLabel(
            card, text=text, font=F_SMALL(), text_color=FG_DIM, anchor="w",
            justify="left", wraplength=620,
        ).grid(row=self._next_row(card), column=0, columnspan=2, sticky="w", padx=18, pady=(0, 18))

    # ------------------------------------------------------------- log page --
    def _build_log_page(self, page: "ctk.CTkFrame") -> None:
        card = self._card(page)
        card.grid(row=0, column=0, sticky="nsew")
        card.grid_columnconfigure(0, weight=1)
        card.grid_rowconfigure(1, weight=1)

        toolbar = ctk.CTkFrame(card, fg_color="transparent")
        toolbar.grid(row=0, column=0, sticky="ew", padx=16, pady=(14, 8))
        toolbar.grid_columnconfigure(0, weight=1)
        self.log_filter = ctk.CTkSegmentedButton(
            toolbar, values=["All", "Info", "Warn", "Error"], font=F_SMALL(), height=30,
            selected_color=ACCENT, selected_hover_color=ACCENT_HOVER,
            unselected_color=PANEL_ALT, unselected_hover_color=PANEL_HI,
            command=lambda _=None: self._render_log(),
        )
        self.log_filter.set("All")
        self.log_filter.grid(row=0, column=0, sticky="w")
        self.var_autoscroll = ctk.BooleanVar(value=True)
        ctk.CTkCheckBox(
            toolbar, text="Auto-scroll", variable=self.var_autoscroll, font=F_SMALL(),
            fg_color=ACCENT, hover_color=ACCENT_HOVER, border_color=STROKE, checkbox_width=18,
            checkbox_height=18,
        ).grid(row=0, column=1, sticky="e", padx=10)
        self._button(
            toolbar, "Copy", self._copy_log, "ghost", height=30, width=86, icon="copy"
        ).grid(row=0, column=2, padx=4)
        self._button(
            toolbar, "Clear", self._clear_log, "quiet", height=30, width=86, icon="cross"
        ).grid(row=0, column=3)

        self.log_box = ctk.CTkTextbox(
            card, font=F_MONO_S(), fg_color="#000000", text_color=FG,
            wrap="word", corner_radius=12, border_width=1, border_color=STROKE,
        )
        self.log_box.grid(row=1, column=0, sticky="nsew", padx=16, pady=(0, 16))
        self.log_box.tag_config("warn", foreground=AMBER)
        self.log_box.tag_config("error", foreground=RED)
        self.log_box.tag_config("ok", foreground=GREEN)
        self.log_box.tag_config("info", foreground=FG_DIM)
        self.log_box.configure(state="disabled")
        self._log_entries: list[tuple[str, str, str]] = []
        self.log_line("GUI ready. Press «Start streaming» on the dashboard.", "ok")

    def _render_log(self) -> None:
        wanted = self.log_filter.get().lower()
        self.log_box.configure(state="normal")
        self.log_box.delete("1.0", "end")
        for stamp, level, text in self._log_entries:
            if wanted not in ("all", level):
                continue
            prefix = {"warn": "WARN ", "error": "ERROR", "ok": "OK   "}.get(level, "INFO ")
            self.log_box.insert("end", f"[{stamp}] {prefix} {text}\n", level)
        self.log_box.configure(state="disabled")
        if self.var_autoscroll.get():
            self.log_box.see("end")

    def _copy_log(self) -> None:
        try:
            self.clipboard_clear()
            self.clipboard_append("\n".join(f"[{s}] {lvl.upper()} {t}" for s, lvl, t in self._log_entries))
            self._flash_status("Log copied to the clipboard")
        except Exception:
            pass

    def _clear_log(self) -> None:
        self._log_entries.clear()
        self._render_log()

    def log_line(self, text: str, level: str = "info") -> None:
        self._log_entries.append((time.strftime("%H:%M:%S"), level, text))
        if len(self._log_entries) > 500:
            del self._log_entries[:-500]
        if self.log_filter.get().lower() in ("all", level):
            prefix = {"warn": "WARN ", "error": "ERROR", "ok": "OK   "}.get(level, "INFO ")
            self.log_box.configure(state="normal")
            self.log_box.insert("end", f"[{time.strftime('%H:%M:%S')}] {prefix} {text}\n", level)
            self.log_box.configure(state="disabled")
            if self.var_autoscroll.get():
                self.log_box.see("end")

    # ----------------------------------------------------------- guide page --
    def _build_guide_page(self, page: "ctk.CTkFrame") -> None:
        outer = ctk.CTkScrollableFrame(page, fg_color="transparent")
        outer.grid(row=0, column=0, sticky="nsew")
        outer.grid_columnconfigure(0, weight=1)

        self.guide_cards: list = []  # top-level cards, in vertical order
        mark = logo_image(64)
        about = self._card(outer)
        self.guide_cards.append(about)
        about.grid(row=0, column=0, sticky="ew")
        about.grid_columnconfigure(1, weight=1)
        if mark is not None:
            ctk.CTkLabel(about, text="", image=mark).grid(
                row=0, column=0, rowspan=3, sticky="w", padx=(18, 14), pady=(16, 16)
            )
        ctk.CTkLabel(about, text="SPGF Wacom — Tablet Mode", font=F_TITLE(), anchor="w").grid(
            row=0, column=1, sticky="sw", padx=(0, 18), pady=(16, 0)
        )
        ctk.CTkLabel(
            about,
            text="Your PC screen and S Pen, straight onto the Galaxy Note over Wi-Fi.",
            font=F_BODY(), text_color=FG_DIM, anchor="w",
        ).grid(row=1, column=1, sticky="w", padx=(0, 18))
        ctk.CTkLabel(
            about,
            text=f"Version {APP_VERSION} · app & engine by Clutsy · photos from "
                 f"Wikimedia Commons (see photo_credits.json)",
            font=F_SMALL(), text_color=FG_MUTED, anchor="w",
        ).grid(row=2, column=1, sticky="nw", padx=(0, 18), pady=(0, 16))

        steps = [
            ("1 · Connect the phone", "Plug the Note in over USB with debugging enabled, then press "
                                      "Detect on the Phone page. adb fills the Wi-Fi IP in for you."),
            ("2 · Start streaming", "On the Dashboard press Start streaming. The desktop mirror shows "
                                    "exactly what the phone receives on port 7655."),
            ("3 · Enable Tablet Mode", "In the Android app turn Tablet Mode on. Pen input is only "
                                       "injected while Tablet Mode runs, and released the moment it stops."),
            ("4 · Draw", "Pick up the S Pen. The mirror overlays a cursor: blue hovering, red touching, "
                         "white ring with the barrel button."),
            ("5 · Tune the link", "If the stream stutters, lower Target FPS or JPEG quality in Options. "
                                  "The adaptive encoder also does this by itself when Wi-Fi sags."),
        ]
        card = self._card(outer)
        self.guide_cards.append(card)
        card.grid(row=1, column=0, sticky="ew", pady=(12, 0))
        card.grid_columnconfigure(0, weight=1)
        self._section(card, "Quick start", 0, pady=(16, 4))
        for index, (title, body) in enumerate(steps):
            ctk.CTkLabel(card, text=title, font=F_BODY_BOLD(), anchor="w").grid(
                row=1 + index * 2, column=0, sticky="w", padx=18, pady=(8, 0)
            )
            ctk.CTkLabel(
                card, text=body, font=F_SMALL(), text_color=FG_DIM, anchor="w",
                justify="left", wraplength=760,
            ).grid(row=2 + index * 2, column=0, sticky="w", padx=18)

        shortcuts = self._card(outer)
        shortcuts.grid(row=2, column=0, sticky="ew", pady=(12, 0))
        shortcuts.grid_columnconfigure(1, weight=1)
        self._section(shortcuts, "Keyboard", 0, span=2, pady=(16, 4))
        keys = [
            ("Ctrl + S", "Start streaming"),
            ("Ctrl + P", "Pause or resume"),
            ("Ctrl + D", "Detect the phone"),
            ("Ctrl + L", "Jump to the log"),
            ("F1", "Open this guide"),
        ]
        for index, (combo, description) in enumerate(keys):
            ctk.CTkLabel(shortcuts, text=combo, font=F_MONO(), anchor="w", width=90).grid(
                row=1 + index, column=0, sticky="w", padx=18, pady=3
            )
            ctk.CTkLabel(shortcuts, text=description, font=F_BODY(), text_color=FG_DIM, anchor="w").grid(
                row=1 + index, column=1, sticky="w", pady=3
            )
        ctk.CTkLabel(
            shortcuts,
            text="Settings live in SPGF_WacomGUI.json next to the app, so the whole folder "
                 "can be copied to another PC.",
            font=F_SMALL(), text_color=FG_MUTED, anchor="w",
        ).grid(row=len(keys) + 1, column=0, columnspan=2, sticky="w", padx=18, pady=(10, 16))

        credits = self._card(outer)
        self.guide_cards.append(credits)
        credits.grid(row=3, column=0, sticky="ew", pady=(12, 18))
        credits.grid_columnconfigure(1, weight=1)
        self._section(credits, "Credits", 0, span=2, pady=(16, 4))
        links = [
            ("App and streaming engine", "Clutsy on GitHub", APP_HOME),
            ("Device photography", "Wikimedia Commons contributors", APP_PHOTOS),
        ]
        for index, (role, label, url) in enumerate(links):
            ctk.CTkLabel(credits, text=role, font=F_BODY(), text_color=FG_DIM, anchor="w").grid(
                row=1 + index, column=0, sticky="w", padx=18, pady=4
            )
            link = ctk.CTkLabel(
                credits, text=label, font=F_BODY_BOLD(), text_color=ACCENT,
                cursor="hand2", anchor="w",
            )
            link.grid(row=1 + index, column=1, sticky="w", pady=4)
            link.bind("<Button-1>", lambda _e, u=url: self._open_link(u))
        ctk.CTkLabel(
            credits,
            text="Each photo keeps its author and licence in gui_assets/photo_credits.json.",
            font=F_SMALL(), text_color=FG_MUTED, anchor="w",
        ).grid(row=len(links) + 1, column=0, columnspan=2, sticky="w", padx=18, pady=(8, 16))

    # ----------------------------------------------------------- navigation --
    def show_page(self, key: str) -> None:
        label, subtitle, _ = self.NAV[key]
        self.page_title.configure(text=label)
        self.page_subtitle.configure(text=subtitle)
        for name, button in self.nav_buttons.items():
            active = name == key
            glyph = self.NAV[name][2]
            button.configure(
                fg_color=PANEL if active else "transparent",
                text_color=FG if active else FG_DIM,
                image=self._icon(glyph, FG if active else FG_DIM),
            )
            self.nav_bars[name].configure(fg_color=ACCENT if active else "transparent")
        self.pages[key].tkraise()

    def _bind_shortcuts(self) -> None:
        self.bind("<Control-s>", lambda _e: self._on_start())
        self.bind("<Control-p>", lambda _e: self._on_pause() if self._state == "live" else self._on_resume())
        self.bind("<Control-d>", lambda _e: self._on_detect())
        self.bind("<Control-l>", lambda _e: self.show_page("log"))
        self.bind("<F1>", lambda _e: self.show_page("guide"))

    def _flash_status(self, message: str) -> None:
        self.status_detail.configure(text=message)
        self.after(2600, self._refresh_status_detail)

    def _open_link(self, url: str) -> None:
        import webbrowser

        webbrowser.open(url)
        self.log_line(f"opened {url}")

    # -------------------------------------------------------- event pump ----
    def _poll_events(self) -> None:
        try:
            while True:
                kind, payload, error = self.events.get_nowait()
                if kind == "adb":
                    self._on_adb_result(payload, error)
                elif kind == "log":
                    self.log_line(str(payload), str(error or "info"))
        except queue.Empty:
            pass
        self.after(140, self._poll_events)

    def _on_adb_result(self, device: Optional[tuple], error: Optional[str]) -> None:
        if error or device is None:
            self.device_name.configure(text="No phone")
            self.device_model.configure(
                text=f"adb not available: {error}" if error else "Connect the phone over USB and press Detect."
            )
            self.device_chip.configure(
                text="No phone detected", image=self._icon("phone", FG_DIM, 14)
            )
            self.spec_serial.configure(text="—")
            self.spec_resolution.configure(text="—")
            self.spec_ip.configure(text="—")
            self.log_line(error or "no adb device connected", "warn")
            return
        serial, model, ip, resolution = device
        key, name = match_note_model(model)
        self.spec_serial.configure(text=serial or "—")
        self.spec_resolution.configure(text=resolution or "—")
        self.spec_ip.configure(text=ip or "set manually in Options")
        self.device_model.configure(text=f"{model or 'unknown model'}  ·  serial {serial}")
        self.device_chip.configure(
            text=f"{name}  ·  {serial}", image=self._icon("phone", GREEN, 14)
        )
        self._show_device_key(key, detected=True, name=name)
        self.log_line(f"phone detected: {name} ({model or '?'}) serial {serial}", "ok")
        if ip and not str(self.var_host.get()).strip():
            self.var_host.set(ip)
            self.log_line(f"phone IP filled in automatically: {ip}", "ok")

    def _load_photo(self, path: Optional[Path]):
        if path is None or not PIL_AVAILABLE:
            return None
        try:
            return Image.open(path).convert("RGBA")
        except Exception:
            return None

    def _thumb_image(self, key: str):
        """Bare device on transparency: same height for every generation, so
        the gallery reads as one consistent product line instead of a row of
        cards at different scales."""
        if not PIL_AVAILABLE:
            return None
        source = self._load_photo(asset_path(f"phone_{key}.png"))
        if source is None:
            source = self._load_photo(asset_path(f"photo_{key}.png"))
        if source is None:
            return None
        device = _contain(source, (86, 104))
        pad, drop = 5, 3
        canvas = Image.new(
            "RGBA", (device.width + pad * 2, device.height + pad * 2), (0, 0, 0, 0)
        )
        shadow = Image.new("L", canvas.size, 0)
        shadow.paste(device.split()[3], (pad, pad + drop))
        shade = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shade.putalpha(shadow.point(lambda v: int(v * 0.45)))
        shade = shade.filter(ImageFilter.GaussianBlur(5))
        canvas.alpha_composite(shade)
        canvas.alpha_composite(device, (pad, pad))
        handle = ctk.CTkImage(light_image=canvas, dark_image=canvas, size=canvas.size)
        self._images[f"thumb_{key}"] = handle
        return handle

    def _on_hero_resize(self, _event=None) -> None:
        """Recompose the render for the box the layout actually allocated."""
        if self._hero_key is None or self._hero_pending:
            return
        width = self.hero_box.winfo_width()
        height = self.hero_box.winfo_height()
        if width < 80 or height < 80:
            return
        if abs(width - self._hero_size[0]) < 6 and abs(height - self._hero_size[1]) < 6:
            return
        self._hero_pending = True
        self.after(80, self._hero_render)

    def _hero_render(self) -> None:
        """Draws the selected device at the current box size (never cropped)."""
        self._hero_pending = False
        key = self._hero_key
        if key is None:
            return
        box = (max(120, self.hero_box.winfo_width()), max(120, self.hero_box.winfo_height()))
        self._hero_size = box
        hero = self._hero_image(key, box)
        if hero is not None:
            self.device_photo.configure(image=hero, text="")
        else:
            self.device_photo.configure(image=None, text="No photo\n(run fetch_device_photos.py)")

    def _hero_image(self, key: str, box: tuple[int, int]):
        """Product shot for exactly this box: plate, halo, shadow, device."""
        if not PIL_AVAILABLE:
            return None
        source = self._load_photo(asset_path(f"phone_{key}.png"))
        if source is None:
            source = self._load_photo(asset_path(f"photo_{key}.png"))
        if source is None:
            return None
        w, h = box[0] * 2, box[1] * 2
        radius = 28
        canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        plate = Image.new("RGBA", (w, h), _hex_rgba(PANEL_ALT))
        plate.putalpha(rounded_mask((w, h), radius))
        canvas.alpha_composite(plate)

        glow = Image.new("L", (max(1, w // 8), max(1, h // 8)), 0)
        ImageDraw.Draw(glow).ellipse((4, 8, glow.width - 4, glow.height - 4), fill=46)
        glow = glow.filter(ImageFilter.GaussianBlur(9)).resize((w, h), LANCZOS)
        halo = Image.new("RGBA", (w, h), _hex_rgba(ACCENT, 0))
        halo.putalpha(glow)
        canvas.alpha_composite(halo)

        device = source.copy()
        device.thumbnail((int(w * 0.74), int(h * 0.88)), LANCZOS)
        x = (w - device.width) // 2
        y = (h - device.height) // 2
        shadow = Image.new("L", (w, h), 0)
        shadow.paste(device.split()[3], (x, y))
        shade = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        shade.putalpha(shadow.point(lambda v: int(v * 0.5)))
        shade = shade.filter(ImageFilter.GaussianBlur(14)).transform(
            (w, h), AFFINE, (1, 0, 0, 0, 1, -10)
        )
        canvas.alpha_composite(shade)
        canvas.alpha_composite(device, (x, y))

        stroke = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(stroke).rounded_rectangle(
            (0, 0, w - 1, h - 1), radius, outline=_hex_rgba(STROKE), width=2
        )
        canvas.alpha_composite(stroke)

        handle = ctk.CTkImage(light_image=canvas, dark_image=canvas, size=box)
        self._images[f"hero_{key}"] = handle
        return handle

    def _show_device_key(self, key: str, detected: bool = False, name: Optional[str] = None) -> None:
        credit = self._credits.get(key, {})
        label = name or str(credit.get("name", key))
        self._device_key = key
        self._hero_key = key
        self._hero_size = (0, 0)
        self.settings.set("last_device", key)
        self.device_name.configure(text=label)
        if detected:
            pass  # keep the adb model line
        else:
            model = credit.get("model") or ""
            self.device_model.configure(
                text=f"{model}  ·  photo database entry" if model else "photo database entry"
            )
        self._hero_render()
        if credit.get("fallback"):
            self.device_credit.configure(text="Generated placeholder — run fetch_device_photos.py for a real photo.")
        elif credit:
            self.device_credit.configure(
                text=f"Photo: {credit.get('title', '')} · {credit.get('credit', '')} · Wikimedia Commons"
            )
        else:
            self.device_credit.configure(text="")
        self._highlight_gallery(key)

    def _highlight_gallery(self, key: Optional[str]) -> None:
        """Rings the generation currently shown in the hero panel."""
        for name, button in self.gallery_buttons.items():
            active = name == key
            button.configure(
                fg_color=PANEL_HI if active else "transparent",
                text_color=FG if active else FG_DIM,
                border_width=1 if active else 0,
                border_color=ACCENT if active else STROKE,
            )

    # ------------------------------------------------------------- stream ---
    def _preview(self):
        hub = self.hub
        return hub.preview if hub is not None else None

    def _poll_stream(self) -> None:
        preview = self._preview()
        if preview is not None:
            fps = preview.preview_fps()
            quality = getattr(preview, "_quality", self.var_quality.get())
            scale = getattr(preview, "_scale", 1.0)
            frames = len(getattr(preview, "_sizes", []))
            self.stat_fps.configure(text=f"{fps:.1f}")
            self.stat_quality.configure(text=str(quality))
            self.stat_scale.configure(text=f"{scale:.2f}×")
            self.stat_frames.configure(text=str(frames))
            paused = preview.capture_paused.is_set()
            state = "Paused" if paused else "Streaming"
            self._state = "paused" if paused else "live"
            self.session_state.configure(
                text=state, text_color=AMBER if paused else GREEN
            )
            self.status_label.configure(text=state)
            self.status_detail.configure(
                text=f"{fps:.1f} fps → {self.hub.host}:{self.hub.preview_port}"
            )
            self.link_row.configure(
                text=f"{self.hub.host}  ·  preview {self.hub.preview_port}  ·  input {self.hub.tablet_port}"
            )
            live = not paused
            self.mirror_badge.configure(
                text=f"{'live' if live else 'paused'} · port {preview.port}",
                image=self._icon("dot", GREEN if live else AMBER, 12),
            )
            border = GREEN if live else AMBER
            if border != getattr(self, "_mirror_border", None):
                self._mirror_border = border
                self.mirror_host.configure(border_color=border)
            self._draw_latest_frame(preview)
        self.after(360, self._poll_stream)

    def _draw_latest_frame(self, preview) -> None:
        if not PIL_AVAILABLE:
            return
        jpeg = preview.latest_frame_jpeg()
        if jpeg is None:
            return
        marker = len(jpeg) * 131 + (jpeg[0] if jpeg else 0)
        width = max(self.mirror.winfo_width(), 240)
        height = max(self.mirror.winfo_height(), 180)
        box = (width, height)
        if marker == self._last_frame_marker and box == self._last_mirror_box:
            return
        self._last_frame_marker = marker
        self._last_mirror_box = box
        try:
            image = Image.open(io.BytesIO(jpeg))
            source = (image.width, image.height)
            image.thumbnail((width - 8, height - 8), LANCZOS)
            handle = ctk.CTkImage(light_image=image, dark_image=image, size=image.size)
            self._images["mirror"] = handle
            self.mirror.configure(image=handle, text="")
            if self.mirror_empty.winfo_ismapped():
                self.mirror_empty.grid_remove()
            self.mirror_info.configure(
                text=f"Phone sees {source[0]}×{source[1]} at JPEG quality {getattr(preview, '_quality', '?')}"
            )
        except Exception:
            pass

    def _pulse(self) -> None:
        """Slow heartbeat on the status dot while streaming."""
        if self._state == "live":
            current = self.status_dot.cget("text_color")
            self.status_dot.configure(text_color=GREEN_DIM if current == GREEN else GREEN)
        self.after(1100, self._pulse)

    def _refresh_status_detail(self) -> None:
        preview = self._preview()
        if preview is None:
            self.status_detail.configure(text="stream not started")
        else:
            self.status_detail.configure(
                text=f"{preview.preview_fps():.1f} fps → {self.hub.host}:{self.hub.preview_port}"
            )

    # ----------------------------------------------------------- commands ---
    def _load_settings(self) -> None:
        self.var_host.set(str(self.settings.get("host") or ""))
        # The classic client remembers its own host in SPGF_Wacom.ini. Inside a
        # frozen exe that file would live in the extraction temp dir, so only
        # consult it when running from source.
        if not getattr(sys, "frozen", False):
            try:
                saved = core.load_saved_config()
                if not self.var_host.get().strip() and saved.get("host"):
                    self.var_host.set(str(saved["host"]))
                if saved.get("preview_port"):
                    self.var_preview_port.set(str(saved["preview_port"]))
                if saved.get("port"):
                    self.var_tablet_port.set(str(saved["port"]))
            except Exception:
                pass
        self._show_device_key(str(self.settings.get("last_device") or "note3"))

    def _collect_options(self) -> Optional[dict]:
        host = self.var_host.get().strip()
        if not host:
            messagebox.showwarning(APP_TITLE, "Enter the phone IP (e.g. 192.168.1.142) first.")
            self.show_page("options")
            return None
        try:
            preview_port = int(str(self.var_preview_port.get()).strip())
            tablet_port = int(str(self.var_tablet_port.get()).strip())
            width = int(str(self.var_width.get()).strip())
        except ValueError:
            messagebox.showwarning(APP_TITLE, "Ports and width must be whole numbers.")
            self.show_page("options")
            return None
        if not all(1 <= port <= 65535 for port in (preview_port, tablet_port)):
            messagebox.showwarning(APP_TITLE, "Ports must be between 1 and 65535.")
            self.show_page("options")
            return None
        if not 160 <= width <= 4096:
            messagebox.showwarning(APP_TITLE, "Preview width must be between 160 and 4096 pixels.")
            self.show_page("options")
            return None
        return {
            "host": host,
            "preview_port": preview_port,
            "tablet_port": tablet_port,
            "preview_args": {
                "max_width": width,
                "quality": int(self.var_quality.get()),
                "fps": float(self.var_fps.get()),
                "capture": str(self.var_capture.get()).lower(),
            },
            "debug": bool(self.var_debug.get()),
        }

    def _persist(self, options: Optional[dict] = None) -> None:
        values = {
            "host": options["host"] if options else str(self.var_host.get()).strip(),
            "preview_port": int(str(self.var_preview_port.get()) or 0) or int(DEFAULTS["preview_port"]),
            "tablet_port": int(str(self.var_tablet_port.get()) or 0) or int(DEFAULTS["tablet_port"]),
            "width": int(str(self.var_width.get()) or 0) or int(DEFAULTS["width"]),
            "quality": int(self.var_quality.get()),
            "fps": float(self.var_fps.get()),
            "capture": str(self.var_capture.get()).lower(),
            "debug": bool(self.var_debug.get()),
            "geometry": self.geometry().split("+")[0],
            "last_device": self._device_key or DEFAULTS["last_device"],
        }
        for key, value in values.items():
            self.settings.set(key, value)
        self.settings.save()
        if options and not getattr(sys, "frozen", False):
            # Keep the classic script in sync when both live in the same folder.
            core.save_saved_config(
                {"host": options["host"], "port": options["tablet_port"], "preview_port": options["preview_port"]}
            )

    def _save_options(self) -> None:
        try:
            self._persist()
            self.log_line("settings saved", "ok")
            self._flash_status("Settings saved")
        except ValueError:
            messagebox.showwarning(APP_TITLE, "Some fields are not valid numbers.")

    def _reset_options(self) -> None:
        self.var_preview_port.set(str(DEFAULTS["preview_port"]))
        self.var_tablet_port.set(str(DEFAULTS["tablet_port"]))
        self.var_width.set(str(DEFAULTS["width"]))
        self.var_quality.set(int(DEFAULTS["quality"]))
        self.var_fps.set(float(DEFAULTS["fps"]))
        self.var_capture.set(str(DEFAULTS["capture"]))
        self.var_debug.set(bool(DEFAULTS["debug"]))
        self.log_line("options reset to defaults", "warn")

    def _use_detected_ip(self) -> None:
        ip = self.spec_ip.cget("text")
        if ip and ip not in ("—", "set manually in Options"):
            self.var_host.set(ip)
            self.show_page("options")
            self._flash_status(f"Phone IP set to {ip}")
        else:
            self._flash_status("No detected IP yet — press Detect first")

    def _copy_device_info(self) -> None:
        text = " · ".join(
            [
                self.device_name.cget("text"),
                self.device_model.cget("text"),
                f"serial {self.spec_serial.cget('text')}",
                f"display {self.spec_resolution.cget('text')}",
                f"ip {self.spec_ip.cget('text')}",
            ]
        )
        try:
            self.clipboard_clear()
            self.clipboard_append(text)
            self._flash_status("Device info copied")
        except Exception:
            pass

    def _on_start(self) -> None:
        if self.hub is not None:
            self.log_line("streaming already running", "warn")
            return
        options = self._collect_options()
        if options is None:
            return
        if options["debug"]:
            import logging

            logging.basicConfig(
                level=logging.DEBUG, format="[%(asctime)s] %(levelname)s: %(message)s", datefmt="%H:%M:%S"
            )
        self.status_dot.configure(text_color=AMBER)
        self.status_label.configure(text="Starting")
        self.log_line(f"starting stream towards {options['host']}:{options['preview_port']}")
        try:
            self.backend = core.create_backend(*core.screen_size())
            self.hub = core.RuntimeCommandHub(
                options["host"],
                preview_port=options["preview_port"],
                tablet_port=options["tablet_port"],
                preview_args=options["preview_args"],
            )
            self.hub.attach_backend(self.backend, tuple(core.screen_size()))
        except Exception as error:
            self.status_dot.configure(text_color=RED)
            self.status_label.configure(text="Idle")
            self.session_state.configure(text="Failed", text_color=RED)
            self._teardown()
            self.log_line(f"start failed: {error}", "error")
            messagebox.showerror(APP_TITLE, f"Start failed:\n{error}")
            return
        self._persist(options)
        self.start_button.configure(state="disabled")
        self.pause_button.configure(state="normal")
        self.stop_button.configure(state="normal")
        self.mirror_badge.configure(text="connecting…", image=self._icon("dot", AMBER, 12))
        self.log_line("streaming active and pen input armed", "ok")
        self._flash_status("Streaming — turn Tablet Mode on in the app")

    def _on_pause(self) -> None:
        preview = self._preview()
        if preview is None:
            return
        preview.capture_paused.set()
        self.pause_button.configure(state="disabled")
        self.resume_button.configure(state="normal")
        self.log_line("stream paused", "warn")

    def _on_resume(self) -> None:
        preview = self._preview()
        if preview is None:
            return
        preview.capture_paused.clear()
        self.resume_button.configure(state="disabled")
        self.pause_button.configure(state="normal")
        self.log_line("stream resumed", "ok")

    def _on_stop(self) -> None:
        if self.hub is None:
            return
        self._teardown()
        self.log_line("session stopped", "warn")
        self._flash_status("Session stopped")

    def _teardown(self) -> None:
        try:
            if self.hub is not None:
                self.hub.shutdown()
        except Exception:
            pass
        try:
            if self.backend is not None:
                self.backend.close()
        except Exception:
            pass
        self.hub = None
        self.backend = None
        self._state = "idle"
        self._last_frame_marker = -1
        self.start_button.configure(state="normal")
        self.pause_button.configure(state="disabled")
        self.resume_button.configure(state="disabled")
        self.stop_button.configure(state="disabled")
        self.status_dot.configure(text_color=FG_MUTED)
        self.status_label.configure(text="Idle")
        self.status_detail.configure(text="stream not started")
        self.session_state.configure(text="Idle", text_color=FG)
        self.mirror_badge.configure(
            text="waiting for the phone", image=self._icon("dot", FG_MUTED, 12)
        )
        self._mirror_border = None
        self.mirror.configure(image=None, text="")
        self.mirror_host.configure(border_color=STROKE)
        self.mirror_empty.grid()
        self.mirror_info.configure(text="Frames appear here as soon as streaming starts.")
        self.stat_fps.configure(text="0.0")
        self.stat_quality.configure(text="—")
        self.stat_scale.configure(text="—")
        self.stat_frames.configure(text="0")
        self.link_row.configure(text="not connected")

    def _on_detect(self) -> None:
        self.device_name.configure(text="Detecting…")
        self.device_model.configure(text="Looking for a device over adb…")
        self.log_line("probing adb for a connected phone")
        self.adb.probe()

    def _on_open_console(self) -> None:
        """Open the classic terminal client in a new window, on any platform."""
        frozen = getattr(sys, "frozen", False)
        script = HERE / "SPGF_Wacom.py"
        if not script.exists() and frozen:
            script = Path(sys.executable).resolve().parent / "SPGF_Wacom.py"
        if not script.exists():
            messagebox.showinfo(
                APP_TITLE,
                "The classic console runs from source.\n\n"
                "Put SPGF_Wacom.py next to SPGF_WacomGUI.exe (or use the GUI controls) "
                "and try again.",
            )
            return
        try:
            if sys.platform == "win32":
                batch = script.parent / "SPGF_Wacom.bat"
                if batch.exists():
                    subprocess.Popen(["cmd", "/c", "start", "", str(batch)], cwd=str(script.parent))
                else:
                    launcher = "python" if frozen else sys.executable
                    subprocess.Popen(
                        ["cmd", "/c", "start", "", launcher, str(script)],
                        cwd=str(script.parent),
                    )
            elif sys.platform == "darwin":
                subprocess.Popen(["open", "-a", "Terminal", str(script.parent)])
            else:
                for terminal in ("x-terminal-emulator", "gnome-terminal", "konsole", "xterm"):
                    if subprocess.call(["which", terminal], stdout=subprocess.DEVNULL) == 0:
                        subprocess.Popen([terminal], cwd=str(script.parent))
                        break
                else:
                    messagebox.showinfo(APP_TITLE, f"Run it manually:\n\npython {script}")
                    return
            self.log_line("classic console opened in a new window")
        except OSError as error:
            messagebox.showerror(APP_TITLE, f"Cannot open the console:\n{error}")

    def on_close(self) -> None:
        try:
            self._persist()
        except Exception:
            pass
        self._teardown()
        self.destroy()


# --------------------------------------------------------------- entry point --
def _smoke_test() -> int:
    """Builds every page and destroys the window: non-zero on any error."""
    ctk.set_appearance_mode("dark")
    ctk.set_default_color_theme("blue")
    app = WacomGUI(auto_detect=False)
    for page in WacomGUI.PAGES:
        app.show_page(page)
        app.update()
    app.after(300, app.on_close)
    app.mainloop()
    print("smoke test OK: every page built")
    return 0


def doctor() -> int:
    """Prints a one-screen health report: useful on a fresh portable copy."""
    print(f"SPGF Wacom GUI {APP_VERSION}")
    print(f"  python      {sys.version.split()[0]}  ({sys.platform})")
    print(f"  frozen      {bool(getattr(sys, 'frozen', False))}  {sys.executable}")
    print(f"  customtkinter {'ok' if ctk is not None else 'MISSING: ' + UI_ERROR}")
    print(f"  pillow      {'ok' if PIL_AVAILABLE else 'MISSING'}")
    settings = config_path()
    print(f"  settings    {settings}")
    photos = [key for key in GALLERY_ORDER if asset_path(f"photo_{key}.png")]
    print(f"  photos      {len(photos)}/{len(GALLERY_ORDER)} cards")
    if len(photos) < len(GALLERY_ORDER):
        missing = ", ".join(key for key in GALLERY_ORDER if key not in photos)
        print(f"              missing: {missing} (run fetch_device_photos.py)")
    print(f"  hero shots  {sum(1 for k in GALLERY_ORDER if asset_path(f'phone_{k}.png'))}/{len(GALLERY_ORDER)}")
    print(f"  app icon    {'ok' if asset_path('app.ico') else 'missing'}")
    print(f"  logo        {'ok' if asset_path(LOGO_SOURCE) else 'missing (drawn fallback in use)'}")
    print(f"  project     {APP_HOME}")
    adb = shutil.which("adb") or shutil.which("adb.exe")
    print(f"  adb         {adb or 'not on PATH (usb detection unavailable)'}")
    core_script = HERE / "SPGF_Wacom.py"
    if getattr(sys, "frozen", False):
        extra = "" if core_script.exists() else " (classic console needs SPGF_Wacom.py beside the exe)"
        print(f"  engine      bundled{extra}")
    else:
        print(f"  engine      {'ok' if core_script.exists() else 'SPGF_Wacom.py not found next to the app'}")
    return 0 if photos and ctk is not None else 1


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(description="SPGF Wacom desktop control panel.")
    parser.add_argument("--smoke", action="store_true", help="build every page and exit")
    parser.add_argument("--config", help="settings file to use (default: next to the app)")
    parser.add_argument("--no-detect", action="store_true", help="skip the automatic adb probe")
    parser.add_argument("--write-icon", action="store_true", help="regenerate gui_assets/app.ico")
    parser.add_argument("--doctor", action="store_true", help="check the installation and exit")
    parser.add_argument("--version", action="version", version=f"SPGF Wacom GUI {APP_VERSION}")
    args = parser.parse_args(argv)

    if args.doctor:
        return doctor()

    if args.write_icon:
        target = write_app_icon()
        if target is None:
            print("Pillow is required to draw the icon.", file=sys.stderr)
            return 2
        print(f"wrote {target}")
        return 0

    if not UI_AVAILABLE:
        print(
            "The GUI needs customtkinter and Pillow:\n"
            "    python -m pip install customtkinter pillow\n"
            f"({UI_ERROR})",
            file=sys.stderr,
        )
        return 2

    if sys.platform == "win32":
        core.enable_windows_dpi_awareness()

    ctk.set_appearance_mode("dark")
    ctk.set_default_color_theme("blue")

    if args.smoke:
        return _smoke_test()

    settings = Settings(Path(args.config).expanduser() if args.config else config_path())
    app = WacomGUI(settings=settings, auto_detect=not args.no_detect)
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
