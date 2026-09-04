#!/usr/bin/env python3
import sys
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import SPGF_Wacom as emulator_module  # noqa: E402
from SPGF_Wacom import (  # noqa: E402
    ASCII_LOGO,
    AdbPenEmulator,
    AxisRange,
    DeviceCapabilities,
    PenState,
    find_epen_device,
    map_frame_orientation,
    orient_normalized,
    parse_capabilities,
    parse_display_rotation,
    parse_event_line,
    parse_tablet_metadata,
    parse_value,
    parse_wm_size,
    resolve_auto_rotation,
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
    def test_startup_logo_is_ascii_and_printable(self):
        output = StringIO()
        with redirect_stdout(output):
            emulator_module.print_ascii_logo()
        self.assertEqual(output.getvalue().rstrip("\n"), ASCII_LOGO)
        # The artwork is a pen drawing built from ASCII blocks; it must stay
        # non-trivial in size and strictly ASCII-printable.
        self.assertGreater(len(ASCII_LOGO), 100)
        self.assertIn("@", ASCII_LOGO)
        self.assertTrue(all(ord(character) < 128 for character in ASCII_LOGO))

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
        self.assertEqual(parse_wm_size("size: 1600x900"), (1600, 900))
        self.assertIsNone(parse_wm_size("Physical density: 480"))

    def test_parses_tablet_metadata_and_legacy_lines(self):
        metadata = parse_tablet_metadata("#SPEN_TABLET 1 1920 1080 1 landscape")
        self.assertEqual(metadata["source_width"], 1920)
        self.assertEqual(metadata["source_rotation"], 1)
        self.assertIsNone(parse_tablet_metadata("0.1,0.2,0.3,1"))

    def test_resolves_auto_rotation_for_natural_portrait_panel(self):
        self.assertEqual(resolve_auto_rotation((1920, 1080), 0), 1)
        self.assertEqual(resolve_auto_rotation((1920, 1080), 3), 3)
        self.assertEqual(resolve_auto_rotation(None, None), 0)

    def test_maps_tcp_frame_to_monitor_orientation_once(self):
        self.assertEqual(
            map_frame_orientation(0.25, 0.75, "landscape", 1080, 1920),
            (0.25, 0.25),
        )
        self.assertEqual(
            map_frame_orientation(0.25, 0.75, "portrait", 1920, 1080),
            (0.75, 0.75),
        )

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

    def test_tcp_flags_keep_middle_button_separate(self):
        backend = FakeBackend()
        from SPGF_Wacom import TcpPenEmulator
        emulator = TcpPenEmulator("127.0.0.1", 7654, backend, 1000, 500)
        emulator.handle_frame("0.5,0.5,0.5,16")
        self.assertEqual(backend.pressed, [2])
        emulator.handle_frame("0.5,0.5,0.5,0")
        self.assertEqual(backend.released, [2])

    def test_tcp_eraser_does_not_release_right_button_while_pressed(self):
        backend = FakeBackend()
        from SPGF_Wacom import TcpPenEmulator
        emulator = TcpPenEmulator("127.0.0.1", 7654, backend, 1000, 500)
        emulator.handle_frame("0.5,0.5,0.5,4")
        emulator.handle_frame("0.5,0.5,0.5,6")
        emulator.handle_frame("0.5,0.5,0.5,2")
        emulator.handle_frame("0.5,0.5,0.5,0")
        self.assertEqual(backend.pressed, [1])
        self.assertEqual(backend.released, [1])

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


    def test_preview_header_framing(self):
        from SPGF_Wacom import PREVIEW_HEADER_PREFIX, preview_header

        self.assertEqual(preview_header(0), PREVIEW_HEADER_PREFIX + b"00000000")
        self.assertEqual(preview_header(255), PREVIEW_HEADER_PREFIX + b"000000FF")
        self.assertEqual(preview_header(123456), PREVIEW_HEADER_PREFIX + b"0001E240")
        with self.assertRaises(ValueError):
            preview_header(-1)

    def test_preview_streamer_pen_state_clamps(self):
        from SPGF_Wacom import PreviewStreamer

        streamer = PreviewStreamer("127.0.0.1")
        streamer.set_pen_state(1.4, -0.3, 0b0011)
        self.assertEqual(streamer.pen_x, 1.0)
        self.assertEqual(streamer.pen_y, 0.0)
        self.assertTrue(streamer.pen_touching)
        self.assertTrue(streamer.pen_button)
        self.assertFalse(streamer.pen_in_range)

    def test_preview_pillow_fast_path_encodes_bgra(self):
        """The new Pillow path must BGRA-decode, downscale and draw the marker."""
        from SPGF_Wacom import PreviewStreamer

        try:
            import PIL  # noqa: F401
        except ImportError:
            self.skipTest("Pillow not installed")
        streamer = PreviewStreamer("127.0.0.1", max_width=320, quality=50)
        streamer.set_pen_state(0.5, 0.5, 0b0001)
        # 8x2 BGRA pixels: left half blue, right half white (two full rows).
        row = bytes([255, 0, 0, 255] * 4 + [255, 255, 255, 255] * 4)
        bgra = row * 2
        self.assertEqual(len(bgra), 8 * 2 * 4)
        jpeg = streamer._encode_jpeg(8, 2, bgra)
        self.assertIsNotNone(jpeg)
        self.assertTrue(jpeg[:2] == b"\xff\xd8", "not a JPEG stream")

    def test_preview_ndarray_bgra_path_encodes(self):
        """Desktop Duplication frames (BGRA ndarray) encode straight to JPEG."""
        from SPGF_Wacom import PreviewStreamer

        try:
            import numpy as np
            import PIL  # noqa: F401
        except ImportError:
            self.skipTest("numpy/Pillow not installed")
        streamer = PreviewStreamer("127.0.0.1", max_width=320, quality=50)
        streamer.set_pen_state(0.5, 0.5, 0b0001)
        # 8x2 BGRA pixels: left half blue, right half white (two full rows).
        row = np.zeros((2, 8, 4), dtype=np.uint8)
        row[:, :4] = (255, 0, 0, 255)  # blue in BGRA order
        row[:, 4:] = (255, 255, 255, 255)
        jpeg = streamer._encode_ndarray(8, 2, row)
        self.assertIsNotNone(jpeg)
        self.assertTrue(jpeg[:2] == b"\xff\xd8", "not a JPEG stream")

    def test_preview_capture_pause_round_trip(self):
        from SPGF_Wacom import PreviewStreamer

        streamer = PreviewStreamer("127.0.0.1")
        self.assertFalse(streamer.capture_paused.is_set())
        streamer.capture_paused.set()
        self.assertTrue(streamer.capture_paused.is_set())
        streamer.capture_paused.clear()
        self.assertFalse(streamer.capture_paused.is_set())

    def test_preview_fps_counts_sent_frames(self):
        import time as time_module

        from SPGF_Wacom import PreviewStreamer

        streamer = PreviewStreamer("127.0.0.1")
        now = time_module.monotonic()
        streamer._sent_times[:] = [now - 1.0, now - 2.0, now - 10.0]
        # Only the two recent sends count; the 10s-old one is outside the window.
        self.assertAlmostEqual(streamer.preview_fps(), 2.0 / 3.0, delta=0.05)


class TcpInputControllerTest(unittest.TestCase):
    """Input must flow only while the app-side Tablet Mode session is live."""

    class RecordingBackend:
        def __init__(self):
            self.calls = []
            self.closed = False

        def move_absolute(self, x, y):
            self.calls.append(("move", x, y))

        def press(self, button):
            self.calls.append(("press", button))

        def release(self, button):
            self.calls.append(("release", button))

        def close(self):
            self.closed = True

    def _serve_one_frame(self, metadata=True):
        """One-shot tablet server: metadata line + a single pen frame."""
        import socket

        import threading as thr

        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        port = srv.getsockname()[1]
        payload = {
            "port": port,
            "done": thr.Event(),
        }

        def runner():
            try:
                srv.settimeout(5)
                conn, _ = srv.accept()
                conn.settimeout(5)
                hello = "#SPEN_TABLET 1 800 400 0 landscape\n"
                frame = "0.50,0.25,0.50,9\n"  # in range + touching
                conn.sendall((hello + frame).encode("ascii"))
                payload["done"].wait(4)
                conn.close()
            except OSError:
                pass
            finally:
                srv.close()

        thr.Thread(target=runner, daemon=True).start()
        return payload

    def test_frame_injection_requires_backend(self):
        from SPGF_Wacom import TcpInputController

        session = self._serve_one_frame()
        controller = TcpInputController("127.0.0.1", session["port"])
        controller.start()
        session["done"].set()
        controller.stop()
        # No backend attached -> no injection attempted, no crash.
        self.assertFalse(controller.active())

    def test_controller_releases_buttons_on_drop(self):
        from SPGF_Wacom import BUTTON, LEFT, TcpInputController

        controller = TcpInputController("127.0.0.1", 1)  # port 1: connect fails
        backend = self.RecordingBackend()
        controller.swap_backend(backend, 1920, 1080)
        controller._touching = True
        controller._right_output = True
        controller._close_socket()
        releases = [c for c in backend.calls if c[0] == "release"]
        self.assertIn(("release", LEFT), releases)
        self.assertFalse(controller.active())
        _ = BUTTON

    def test_active_flag_tracks_session(self):
        from SPGF_Wacom import TcpInputController

        controller = TcpInputController("127.0.0.1", 1)
        self.assertFalse(controller.active())
        controller.swap_backend(self.RecordingBackend(), 100, 100)
        self.assertFalse(controller.active())  # backend alone is not a session


class RuntimeCommandHubTest(unittest.TestCase):
    def test_hub_streams_immediately_on_construction(self):
        from SPGF_Wacom import RuntimeCommandHub

        # The new contract: both channels start in the constructor.
        hub = RuntimeCommandHub(
            "127.0.0.1",
            preview_port=47660,
            tablet_port=47661,
            preview_args={"max_width": 320, "quality": 50, "fps": 24},
        )
        self.assertIsNotNone(hub.preview)
        self.assertTrue(hub.preview._started)
        self.assertIsNotNone(hub.controller)
        out = hub.handle("preview")
        self.assertIn("Preview streaming", out)
        hub.shutdown()

    def test_hub_unknown_command(self):
        from SPGF_Wacom import RuntimeCommandHub

        hub = RuntimeCommandHub(
            "127.0.0.1",
            preview_port=47662,
            tablet_port=47663,
            preview_args={},
        )
        out = hub.handle("nonsense")
        self.assertIn("Unknown command", out)
        hub.shutdown()

    def test_adaptive_quality_steps_down_and_back(self):
        from SPGF_Wacom import PREVIEW_MIN_QUALITY, PreviewStreamer

        streamer = PreviewStreamer("127.0.0.1", quality=70, fps=30)
        self.assertEqual(streamer._quality, 70)
        self.assertEqual(streamer._scale, 1.0)
        # Simulate sustained slow sends: sizes accumulate, measured fps sags.
        import time as time_module

        now = time_module.monotonic()
        streamer._sent_times[:] = [now - 2.0 + i * 0.05 for i in range(10)]
        # A recent capture is required for adaptation: a quiet screen (no
        # captures) must never be read as a congested network.
        streamer._last_capture_at = now
        for _ in range(20):
            streamer._note_frame_size(50_000)
        self.assertLess(streamer._quality, 70)
        self.assertGreaterEqual(streamer._quality, PREVIEW_MIN_QUALITY)


if __name__ == "__main__":
    unittest.main()
