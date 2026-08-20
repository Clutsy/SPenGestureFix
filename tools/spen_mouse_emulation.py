#!/usr/bin/env python3
"""Compatibility entry point; use scripts/spen_mouse_emulator.py directly."""
from pathlib import Path
import runpy


if __name__ == "__main__":
    canonical = Path(__file__).resolve().parents[1] / "scripts" / "spen_mouse_emulator.py"
    runpy.run_path(str(canonical), run_name="__main__")
