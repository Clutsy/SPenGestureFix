package com.spengesturefix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the measured constants extracted from the original SpenCommand
 * APK (`SpenCommand_1238_pp_BETA1.apk`): disc centers, anchor position and
 * animation timing must match `res/layout/activity_popup.xml` and
 * `popup_animation_open.xml` exactly, because the classic wheel renders the
 * original bitmaps and would misalign its icons otherwise.
 */
class RetroWheelGeometryTest {

    @Test
    fun `six authentic disc centers match the layout margins`() {
        assertEquals(6, RetroWheelLayout.SLOT_CENTERS_DP.size)
        // (centerX, centerY) derived from marginBottom/marginEnd on a 180dp
        // panel with 32dp buttons: e.g. b1 end=128 bottom=47 -> (36,117).
        assertEquals(36f, RetroWheelLayout.SLOT_CENTERS_DP[0].first, 0.01f)
        assertEquals(117f, RetroWheelLayout.SLOT_CENTERS_DP[0].second, 0.01f)
        assertEquals(27f, RetroWheelLayout.SLOT_CENTERS_DP[1].first, 0.01f)
        assertEquals(79f, RetroWheelLayout.SLOT_CENTERS_DP[1].second, 0.01f)
        assertEquals(44f, RetroWheelLayout.SLOT_CENTERS_DP[2].first, 0.01f)
        assertEquals(45f, RetroWheelLayout.SLOT_CENTERS_DP[2].second, 0.01f)
        assertEquals(76f, RetroWheelLayout.SLOT_CENTERS_DP[3].first, 0.01f)
        assertEquals(27f, RetroWheelLayout.SLOT_CENTERS_DP[3].second, 0.01f)
        assertEquals(115f, RetroWheelLayout.SLOT_CENTERS_DP[4].first, 0.01f)
        assertEquals(34f, RetroWheelLayout.SLOT_CENTERS_DP[4].second, 0.01f)
        assertEquals(148f, RetroWheelLayout.SLOT_CENTERS_DP[5].first, 0.01f)
        assertEquals(52f, RetroWheelLayout.SLOT_CENTERS_DP[5].second, 0.01f)
    }

    @Test
    fun `anchor center matches the ToggleButton margins`() {
        // end=95, bottom=20, 36dp button on a 180dp panel -> (67,142).
        assertEquals(67f, RetroWheelLayout.ANCHOR_CENTER_DP.first, 0.01f)
        assertEquals(142f, RetroWheelLayout.ANCHOR_CENTER_DP.second, 0.01f)
    }

    @Test
    fun `discs sit inside the panel with usable touch targets`() {
        val half = RetroWheelLayout.SLOT_DIAMETER_DP / 2f
        RetroWheelLayout.SLOT_CENTERS_DP.forEach { (x, y) ->
            assertTrue("x $x inside", x - half >= 0f)
            assertTrue("y $y inside", y - half >= 0f)
            assertTrue("x $x fits", x + half <= RetroWheelLayout.PANEL_DP)
            assertTrue("y $y fits", y + half <= RetroWheelLayout.PANEL_DP)
        }
    }

    @Test
    fun `anchor does not overlap any disc`() {
        val (ax, ay) = RetroWheelLayout.ANCHOR_CENTER_DP
        val minCenterDistance =
            RetroWheelLayout.ANCHOR_DIAMETER_DP / 2f + RetroWheelLayout.SLOT_DIAMETER_DP / 2f
        RetroWheelLayout.SLOT_CENTERS_DP.forEach { (x, y) ->
            val distance = kotlin.math.hypot((x - ax).toDouble(), (y - ay).toDouble())
            assertTrue(
                "anchor-disc distance $distance",
                distance >= minCenterDistance
            )
        }
    }

    @Test
    fun `readout sits inside the panel above the bottom edge`() {
        assertTrue(RetroWheelLayout.READOUT_END_DP > 0f)
        assertTrue(RetroWheelLayout.READOUT_BOTTOM_DP > 0f)
        assertTrue(
            RetroWheelLayout.READOUT_END_DP + RetroWheelLayout.READOUT_WIDTH_DP <=
                RetroWheelLayout.PANEL_DP
        )
        assertTrue(
            RetroWheelLayout.READOUT_BOTTOM_DP + RetroWheelLayout.READOUT_HEIGHT_DP <=
                RetroWheelLayout.PANEL_DP
        )
    }

    @Test
    fun `animation timing matches the original animation list`() {
        // popup_animation_open: 18 items x 20ms.
        assertEquals(18, RetroWheelLayout.FRAME_COUNT)
        assertEquals(20L, RetroWheelLayout.FRAME_DURATION_MS)
        assertEquals(360L, RetroWheelLayout.OPEN_TOTAL_MS)
        assertTrue(RetroWheelLayout.CLOSE_TOTAL_MS > 0L)
    }    @Test
    fun `slot discs spread evenly on the fan annulus for every count`() {
        // The original app drew its slot buttons at runtime on the fan; pixel
        // analysis of the extracted frame-18 artwork shows the fan is one
        // annulus around (86.9, 87.0) (hole r~37, rim r~82) and the authentic
        // buttons sit on that ring with a constant ~36 deg pitch over 180.7
        // deg. The layout is polar on that annulus for every count.
        val (cx, cy) = 86.9f to 87.0f
        val centers = RetroWheelLayout.SLOT_CENTERS_DP
        for (count in 4..7) {
            val stops = RetroWheelLayout.slotCentersFor(count)
            assertEquals("count $count", count, stops.size)
            // First stop sits on the innermost button spot, next to the anchor.
            val (b1x, b1y) = centers[0]
            val firstOffset = kotlin.math.hypot(
                (stops[0].first - b1x).toDouble(),
                (stops[0].second - b1y).toDouble()
            )
            assertTrue("first stop near b1, count $count: $firstOffset", firstOffset < 2.0)
            // CONSTANT ANGULAR step around the fan annulus center: uniform
            // angles are what reads as uniform spacing on a wheel. Deltas are
            // normalized to (-180, 180] because atan2 wraps at the branch cut.
            fun angularDelta(from: Double, to: Double): Double {
                var d = from - to
                while (d > 180.0) d -= 360.0
                while (d < -180.0) d += 360.0
                return d
            }
            val angles = stops.map { (x, y) ->
                kotlin.math.atan2(cy - y, x - cx) * 180.0 / kotlin.math.PI
            }
            val step = angularDelta(angles[0], angles[1])
            assertTrue("count $count opens toward the anchor side", step > 0.0)
            for (i in 1 until angles.size) {
                val delta = angularDelta(angles[i - 1], angles[i])
                assertTrue(
                    "count $count angular step $i: $delta vs $step",
                    kotlin.math.abs(delta - step) < 0.2
                )
            }
            assertEquals("span count $count", 180.7, step * (count - 1), 0.25)
            // Every disc sits fully inside the full 180dp design panel: the
            // per-count window scales the whole artwork (bitmaps, centers and
            // disc radii) by panel/180, so containment is invariant in design
            // space — comparing raw design coordinates against the smaller
            // window would double-count the scaling.
            stops.forEach { (x, y) ->
                assertTrue("($x,$y) fits design panel",
                    x - 16f >= 0f && y - 16f >= 0f && x + 16f <= RetroWheelLayout.PANEL_DP &&
                        y + 16f <= RetroWheelLayout.PANEL_DP)
            }
            // Every stop lies on the fan annulus ring (r=60, 1dp tolerance):
            // the layout must never drift off the white band toward the hole
            // or the rim (the old 5dp-off center made the last slots hug the
            // rim and bunch up).
            stops.forEach { (x, y) ->
                val radius = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble())
                assertTrue("radius $radius at count $count", kotlin.math.abs(radius - 60.0) < 1.0)
            }
        }
        // FIXED SPAN: the last stop is the same point for every count, so 4
        // discs stretch across the whole fan instead of huddling inward.
        val four = RetroWheelLayout.slotCentersFor(4)
        val seven = RetroWheelLayout.slotCentersFor(7)
        assertTrue(
            "last stops coincide",
            kotlin.math.abs(four[3].first - seven[6].first) < 0.1f &&
                kotlin.math.abs(four[3].second - seven[6].second) < 0.1f
        )
        // Six stops reproduce the authentic layout: b1..b5 within ~4dp, and
        // the last stop is pulled INSIDE the crooked rim-clipping b6 margin.
        val six = RetroWheelLayout.slotCentersFor(6)
        for (i in 0..4) {
            val (ax, ay) = centers[i]
            val d = kotlin.math.hypot(
                (six[i].first - ax).toDouble(), (six[i].second - ay).toDouble()
            )
            assertTrue("slot ${i + 1} reproduces b${i + 1}: $d", d < 5.0)
        }
        val (lx, ly) = six.last()
        val (bx, by) = centers[5]
        val d6 = kotlin.math.hypot(lx - bx, ly - by)
        assertTrue("last stop must differ from b6", d6 > 2f)
        // ...and the corrected stop is closer to the fan center than the
        // rim-clipping b6 margin (on the ring, not past the rim).
        assertTrue(
            "b6 corrected onto the ring",
            kotlin.math.hypot(lx - cx, ly - cy) < kotlin.math.hypot(bx - cx, by - cy)
        )
    }

    @Test
    fun `window scales with the number of placed gestures`() {
        // The disc-6 rim touches ~165dp of the 180dp popup and the hinge
        // side clears ~150dp, so the panel shrinks by 150:165:180 to keep
        // the fan fitted with no dead space for 4/5/6 discs.
        assertEquals(150f, RetroWheelLayout.panelDpFor(4), 0.01f)
        assertEquals(165f, RetroWheelLayout.panelDpFor(5), 0.01f)
        assertEquals(180f, RetroWheelLayout.panelDpFor(6), 0.01f)
        // Clamps outside 4..6 behave like the nearest supported count.
        assertEquals(150f, RetroWheelLayout.panelDpFor(3), 0.01f)
        assertEquals(180f, RetroWheelLayout.panelDpFor(12), 0.01f)
        assertEquals(180f, RetroWheelLayout.PANEL_DP, 0.01f)
    }
}
