package com.spengesturefix

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputPipelineTest {
    @Test
    fun parsesGeteventWithDevicePrefix() {
        assertEquals(
            Triple("EV_KEY", "BTN_STYLUS", "DOWN"),
            EPenInputReader.parseLine("/dev/input/event3: EV_KEY BTN_STYLUS DOWN")
        )
    }

    @Test
    fun parsesLegacySwitchLayout() {
        assertEquals(
            Triple("EV_SW", "001a", "00000001"),
            EPenInputReader.parseLine("/dev/input/event16: EV_SW SW 001a 00000001")
        )
    }

    @Test
    fun ignoresSynchronisationEvents() {
        val event = EPenInputReader.parseLine("EV_SYN SYN_REPORT 00000000")
        assertEquals(Triple("EV_SYN", "SYN_REPORT", "00000000"), event)
    }

    @Test
    fun findsDigitizerDevicesFromProcInputFormat() {
        val proc = """
            I: Bus=0018 Vendor=0000 Product=0000 Version=0000
            N: Name="sec_e-pen"
            H: Handlers=kbd mouse0 event3 cpufreq

            N: Name="w1"
            H: Handlers=event16
        """.trimIndent()
        assertEquals("/dev/input/event3", EventDeviceFinder.findDevicePathFromInput(proc, "sec_e-pen"))
        assertEquals("/dev/input/event16", EventDeviceFinder.findDevicePathFromInput(proc, "w1"))
    }

    @Test
    fun findsDigitizerDevicesFromGeteventCapabilitiesFormat() {
        val getevent = """
            add device 1: /dev/input/event16
              name:     "w1"
              events:
                SW  (0005): 001a
            add device 2: /dev/input/event3
              name:     "sec_e-pen"
              events:
                KEY (0001): BTN_STYLUS
        """.trimIndent()
        assertEquals("/dev/input/event3", EventDeviceFinder.findDevicePathFromGetevent(getevent, "sec_e-pen"))
        assertEquals("/dev/input/event16", EventDeviceFinder.findDevicePathFromGetevent(getevent, "w1"))
    }

    @Test
    fun decodesInsertionSwitchWithoutTouchingDigitizer() {
        assertEquals(
            PenPresenceState.REMOVED,
            PenPresenceDecoder.decode("EV_SW", "SW_001A", "00000001")
        )
        assertEquals(
            PenPresenceState.INSERTED,
            PenPresenceDecoder.decode("EV_SW", "001a", "00000000")
        )
        assertEquals(
            PenPresenceState.REMOVED,
            PenPresenceDecoder.decode("EV_SW", "001a", "NG")
        )
        assertEquals(
            PenPresenceState.INSERTED,
            PenPresenceDecoder.decode("EV_SW", "001a", "OK")
        )
        assertNull(PenPresenceDecoder.decode("EV_KEY", "BTN_TOUCH", "DOWN"))
    }

    @Test
    fun normalizesScreenResolutionToLandscape() {
        assertEquals(1920 to 1080, TabletConfig.normalizeScreenResolution(1080, 1920))
        assertEquals(2560 to 1440, TabletConfig.normalizeScreenResolution(2560, 1440))
        assertEquals(null, TabletConfig.normalizeScreenResolution(0, 1080))
    }

    @Test
    fun mapsNaturalPenAxesToLandscapeDisplay() {
        assertEquals(.75f, TabletConfig.mapCoordinates(.25f, .75f, Surface.ROTATION_90).first, .0001f)
        assertEquals(.75f, TabletConfig.mapCoordinates(.25f, .75f, Surface.ROTATION_90).second, .0001f)
        assertEquals(.25f, TabletConfig.mapCoordinates(.25f, .75f, Surface.ROTATION_270).first, .0001f)
        assertEquals(.25f, TabletConfig.mapCoordinates(.25f, .75f, Surface.ROTATION_270).second, .0001f)
    }

    @Test
    fun tabletButtonActionIsIndependentFromNormalGestureBindings() {
        assertEquals(TabletButtonFlags(true, false, false), mapPenButtonAction(PenButtonAction.RIGHT_CLICK, true))
        assertEquals(TabletButtonFlags(false, true, false), mapPenButtonAction(PenButtonAction.MIDDLE_CLICK, true))
        assertEquals(TabletButtonFlags(false, false, true), mapPenButtonAction(PenButtonAction.ERASER, true))
        assertEquals(TabletButtonFlags(false, false, false), mapPenButtonAction(PenButtonAction.DISABLED, true))
        assertEquals(TabletButtonFlags(false, false, false), mapPenButtonAction(PenButtonAction.RIGHT_CLICK, false))
    }

    @Test
    fun landscapeRotationUsesDisplayBoundsWhenRotationIsZero() {
        assertEquals(Surface.ROTATION_90, TabletConfig.landscapeRotation(Surface.ROTATION_0, 1080, 1920))
        assertEquals(Surface.ROTATION_0, TabletConfig.landscapeRotation(Surface.ROTATION_0, 1920, 1080))
        assertEquals(Surface.ROTATION_270, TabletConfig.landscapeRotation(Surface.ROTATION_270, 1080, 1920))
    }

    @Test
    fun tabletMetadataIsLineDelimitedAndExplicit() {
        assertEquals(
            "#SPEN_TABLET 1 1920 1080 1 landscape\n",
            TabletNetworkServer.metadataLine(1920, 1080, Surface.ROTATION_90, "landscape")
        )
    }

    @Test
    fun marksPenInsertedOnlyAfterMoreThanFiveSecondsWithoutInput() {
        assertEquals(false, SPenGestureService.isPenIdle(5_000L, 1L))
        assertEquals(false, SPenGestureService.isPenIdle(6_000L, 1_000L))
        assertEquals(true, SPenGestureService.isPenIdle(6_001L, 1_000L))
        assertEquals(false, SPenGestureService.isPenIdle(5_001L, 0L))
    }

    @Test
    fun normalizesDeviceRanges() {
        assertEquals(0f, TabletInputCapture.normalize(10, 10, 101), 0.0001f)
        assertEquals(1f, TabletInputCapture.normalize(101, 10, 101), 0.0001f)
        assertEquals(.5f, TabletInputCapture.normalize(55, 10, 100), 0.0001f)
    }

    @Test
    fun parsesHexAndSymbolicValues() {
        assertEquals(10, TabletInputCapture.parseValue("0000000a"))
        assertEquals(1, TabletInputCapture.parseValue("DOWN"))
        assertEquals(0, TabletInputCapture.parseValue("UP"))
        assertEquals(true, TabletInputCapture.isDown("00000001"))
        assertEquals(false, TabletInputCapture.isDown("00000000"))
    }

    @Test
    fun pressureClampHonoursDeadZone() {
        assertEquals(0f, PressureCurve.applyWithClamp(.1f, PressureCurveType.LINEAR, .2f, .9f, null), .0001f)
        assertEquals(1f, PressureCurve.applyWithClamp(1f, PressureCurveType.LINEAR, .2f, .9f, null), .0001f)
        assertEquals(.5f, PressureCurve.applyWithClamp(.55f, PressureCurveType.LINEAR, .1f, 1f, null), .0001f)
    }
}
