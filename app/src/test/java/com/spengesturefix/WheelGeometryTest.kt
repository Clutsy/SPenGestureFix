package com.spengesturefix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class WheelGeometryTest {

    @Test
    fun `first slot anchors at the left`() {
        val angle = WheelGeometry.slotAngle(0, 7)
        assertEquals(PI, angle, 0.0001)
    }

    @Test
    fun `last slot ends at the top`() {
        val angle = WheelGeometry.slotAngle(6, 7)
        assertEquals(1.5 * PI, angle, 0.0001)
    }

    @Test
    fun `slots sweep upward in a quarter arc`() {
        val angles = (0 until 7).map { WheelGeometry.slotAngle(it, 7) }
        assertTrue(angles.zipWithNext().all { (a, b) -> b > a })
        assertTrue(angles.all { it in PI..1.5 * PI + 0.0001 })
    }

    @Test
    fun `angles are evenly spaced between consecutive slots`() {
        val count = 7
        val step = (PI / 2.0) / (count - 1)
        (1 until count).forEach { index ->
            val delta = WheelGeometry.slotAngle(index, count) - WheelGeometry.slotAngle(index - 1, count)
            assertEquals(step, delta, 0.0001)
        }
    }

    @Test
    fun `single slot is centered in the arc`() {
        val angle = WheelGeometry.slotAngle(0, 1)
        assertEquals(1.25 * PI, angle, 0.0001)
    }

    @Test
    fun `every slot sits exactly on the ring`() {
        val centerX = 200f
        val centerY = 200f
        val ringRadius = 110f
        (0 until 7).forEach { index ->
            val (x, y) = WheelGeometry.slotCenter(index, 7, centerX, centerY, ringRadius)
            val distance = hypot(x - centerX, y - centerY)
            assertEquals(ringRadius, distance, 0.01f)
        }
    }

    @Test
    fun `adjacent slots never overlap on the quarter arc`() {
        // Reproduce the WheelView sizing formula for 7 slots:
        // needed = slotRadius * 2.25 / unitChord, unitChord = 2 sin(PI/24).
        val side = 1000f
        val slotRadius = side * 0.075f
        val unitChord = WheelGeometry.minimumCenterDistance(7, 1f)
        val ringRadius = (slotRadius * 2.25f / unitChord).coerceAtMost(side * 0.77f)
        val minDistance = WheelGeometry.minimumCenterDistance(7, ringRadius)
        assertTrue(
            "centers $minDistance too close for disc radius $slotRadius",
            minDistance > 2 * slotRadius * 1.1f
        )
    }

    @Test
    fun `chord distance matches the quarter-arc math`() {
        val ringRadius = 500f
        val expected = 2f * ringRadius * kotlin.math.sin(PI / 24.0).toFloat()
        assertEquals(expected, WheelGeometry.minimumCenterDistance(7, ringRadius), 0.01f)
    }

    @Test
    fun `center close disc never collides with the ring`() {
        // Window proportions from WheelView.onSizeChanged for seven slots.
        val side = 1000f
        val slotRadius = side * 0.075f
        val unitChord = WheelGeometry.minimumCenterDistance(7, 1f)
        val ringRadius = (slotRadius * 2.25f / unitChord).coerceAtMost(side * 0.77f)
        val centerRadius = side * 0.072f
        assertTrue(ringRadius - slotRadius > centerRadius * 1.6f)
    }

    @Test
    fun `fan stays inside the corner window`() {
        // The anchor sits at 0.86*side; the farthest slot center plus its disc
        // and a margin must remain inside the window on both axes.
        val side = 1000f
        val anchor = side * 0.86f
        val slotRadius = side * 0.075f
        val unitChord = WheelGeometry.minimumCenterDistance(7, 1f)
        val ringRadius = (slotRadius * 2.25f / unitChord).coerceAtMost(side * 0.77f)
        assertTrue(anchor - ringRadius - slotRadius > 0f)
    }
}
