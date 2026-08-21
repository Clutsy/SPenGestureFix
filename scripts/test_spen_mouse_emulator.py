#!/usr/bin/env python3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spen_mouse_emulator import (  # noqa: E402
    AdbPenEmulator,
    AxisRange,
    DeviceCapabilities,
    PenState,
    find_epen_device,
    orient_normalized,
    parse_capabilities,
    parse_display_rotation,
    parse_event_line,
    parse_value,
    parse_wm_size,
)


class FakeBackend:
    def __init__(self):
        self.moves = []
        self.pressed = []
        self.released = []

    def move_absolute(self, x, y):
        self.moves.append((x, y))

    def press(self, button):
        self.pressed.append(button)

    def release(self, button):
        self.released.append(button)

    def close(self):
        pass


class EmulatorTests(unittest.TestCase):
    def test_parse_event_path_with_colon(self):
        self.assertEqual(
            parse_event_line("/dev/input/event3: EV_ABS ABS_X 00001000"),
            ("EV_ABS", "ABS_X", "00001000"),
        )

    def test_parse_key_values(self):
        self.assertEqual(parse_value("DOWN"), 1)
        self.assertEqual(parse_value("UP"), 0)
        self.assertEqual(parse_value("0000000a"), 10)
        self.assertIsNone(parse_value("garbage"))

    def test_discovers_device_when_getevent_prefixes_the_path(self):
        output = """
        add device 3: /dev/input/event7
          name:     "sec_e-pen"
        """
        self.assertEqual(find_epen_device(output), "/dev/input/event7")

    def test_parses_physical_resolution_before_override(self):
        output = "Override size: 720x1280" + chr(10) + "Physical size: 1080x1920"
        self.assertEqual(parse_wm_size(output), (1920, 1080))
        self.assertEqual(parse_wm_size("Physical size: 2560x1440"), (2560, 1440))
        self.assertIsNone(parse_wm_size("Physical density: 480"))

    def test_parses_display_rotation(self):
        self.assertEqual(
            parse_display_rotation("mViewports=[DisplayViewport{orientation=1} ]"),
            1,
        )
        self.assertEqual(parse_display_rotation("rotation=3"), 3)
        self.assertEqual(parse_display_rotation("mCurrentRotation=ROTATION_90"), 1)
        self.assertEqual(parse_display_rotation("rotation=270"), 3)
        self.assertIsNone(parse_display_rotation("no rotation"))

    def test_rotates_natural_pen_axes_for_landscape(self):
        self.assertEqual(orient_normalized(0.25, 0.75, 1), (0.75, 0.75))
        self.assertEqual(orient_normalized(0.25, 0.75, 3), (0.25, 0.25))

    def test_parse_capabilities(self):
        capabilities = parse_capabilities(
            """
            ABS_X (0000) : value 0, min 12, max 4090, fuzz 0
            ABS_Y (0001) : value 0, min 4, max 3072, fuzz 0
            ABS_PRESSURE (0018) : value 0, min 2, max 2048, fuzz 0
            """
        )
        self.assertEqual(capabilities.x, AxisRange(12, 4090))
        self.assertEqual(capabilities.y, AxisRange(4, 3072))
        self.assertEqual(capabilities.pressure, AxisRange(2, 2048))

    def test_absolute_mapping_and_buttons(self):
        backend = FakeBackend()
        emulator = AdbPenEmulator(
            adb=["adb"],
            device_path="/dev/input/event3",
            capabilities=DeviceCapabilities(
                x=AxisRange(0, 100), y=AxisRange(0, 200), pressure=AxisRange(0, 1024)
            ),
            backend=backend,
            screen_width=1000,
            screen_height=500,
        )
        emulator.handle_event(("EV_ABS", "ABS_X", "00000064"))
        emulator.handle_event(("EV_ABS", "ABS_Y", "000000c8"))
        self.assertEqual(backend.moves[-1], (999, 499))
        emulator.handle_event(("EV_KEY", "BTN_TOUCH", "DOWN"))
        emulator.handle_event(("EV_KEY", "BTN_STYLUS", "00000001"))
        self.assertEqual(backend.pressed, [0, 1])
        emulator.release_buttons()
        self.assertEqual(backend.released, [0, 1])

    def test_absolute_mapping_rotates_landscape_axes(self):
        backend = FakeBackend()
        emulator = AdbPenEmulator(
            adb=["adb"],
            device_path="/dev/input/event3",
            capabilities=DeviceCapabilities(
                x=AxisRange(0, 100), y=AxisRange(0, 200), pressure=AxisRange(0, 1024)
            ),
            backend=backend,
            screen_width=1000,
            screen_height=500,
            orientation="landscape",
        )
        emulator.handle_event(("EV_ABS", "ABS_X", "00000019"))
        emulator.handle_event(("EV_ABS", "ABS_Y", "00000096"))
        self.assertEqual(backend.moves[-1], (749, 374))


if __name__ == "__main__":
    unittest.main()
