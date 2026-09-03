package com.spengesturefix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class WheelGeometryTest {

    @Test
    fun `first slot sits at the top`() {
        val angle = WheelGeometry.slotAngle(0, 7)
        assertEquals(-PI / 2.0, angle, 0.0001)
    }

    @Test
    fun `slots are evenly spaced around the full circle`() {
        val count = 7
        val step = (2.0 * PI) / count
        (1 until count).forEach { index ->
            val delta = WheelGeometry.slotAngle(index, count) - WheelGeometry.slotAngle(index - 1, count)
            assertEquals(step, delta, 0.0001)
        }
        // The last slot is one step short of a full turn back at the top.
        val wrap = WheelGeometry.slotAngle(0, count) + 2.0 * PI - WheelGeometry.slotAngle(count - 1, count)
        assertEquals(step, wrap, 0.0001)
    }

    @Test
    fun `single slot is at the top`() {
        val angle = WheelGeometry.slotAngle(0, 1)
        assertEquals(-PI / 2.0, angle, 0.0001)
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
    fun `adjacent slots never overlap on the full circle`() {
        // Real WheelView proportions for seven slots: ring = 0.45*side,
        // disc = 0.080*side (the needed-radius formula lands on 0.45).
        val side = 1000f
        val ringRadius = side * 0.45f
        val slotRadius = side * 0.080f
        val minDistance = WheelGeometry.minimumCenterDistance(7, ringRadius)
        assertTrue(
            "centers $minDistance too close for disc radius $slotRadius",
            minDistance > 2 * slotRadius * 1.1f
        )
    }

    @Test
    fun `chord distance matches the even-circle math`() {
        val ringRadius = 500f
        val expected = 2f * ringRadius * kotlin.math.sin(PI / 7.0).toFloat()
        assertEquals(expected, WheelGeometry.minimumCenterDistance(7, ringRadius), 0.01f)
    }

    @Test
    fun `center close disc never collides with the ring`() {
        // Window proportions from WheelView.onSizeChanged for seven slots.
        val side = 1000f
        val ringRadius = side * 0.45f
        val slotRadius = side * 0.080f
        val centerRadius = side * 0.075f
        assertTrue(ringRadius - slotRadius > centerRadius * 1.6f)
    }
}
