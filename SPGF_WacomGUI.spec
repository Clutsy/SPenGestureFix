# -*- mode: python ; coding: utf-8 -*-
"""Portable, single-file build of the SPGF Wacom desktop panel.

    python scripts/SPGF_WacomGUI.py --write-icon   # refresh gui_assets/app.ico
    pyinstaller --noconfirm SPGF_WacomGUI.spec     # -> dist/SPGF_WacomGUI.exe

The exe carries the whole UI, the streaming engine, the photo database and the
customtkinter assets, so it runs on a machine that has neither Python nor any
package installed. It keeps its settings in SPGF_WacomGUI.json next to the exe:
the whole dist folder can be copied to a USB stick and just works.
"""
from PyInstaller.utils.hooks import collect_data_files

# gui_assets holds the device photos, the credits and the app icon.
datas = [("scripts/gui_assets", "gui_assets")]
# customtkinter ships its themes and assets as package data; without these the
# frozen app raises "cannot find theme" on the first widget it builds.
datas += collect_data_files("customtkinter")

a = Analysis(
    ["scripts/SPGF_WacomGUI.py"],
    pathex=["scripts"],
    binaries=[],
    datas=datas,
    # PIL discovers its tkinter integration dynamically.
    hiddenimports=["PIL._tkinter_finder"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    # Everything below is either optional or would triple the exe for nothing.
    excludes=[
        "dxcam", "numpy", "matplotlib", "scipy", "pandas", "pytest",
        "setuptools", "pkg_resources", "pip", "PyQt5", "PyQt6",
        "PySide2", "PySide6", "tkinter.test", "test",
    ],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="SPGF_WacomGUI",
    icon="scripts/gui_assets/app.ico",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
