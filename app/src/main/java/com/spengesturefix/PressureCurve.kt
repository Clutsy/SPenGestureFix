package com.denis.spenfix

import kotlin.math.pow

object PressureCurve {

    fun apply(input: Float, curveType: PressureCurveType, customPoints: List<Float>? = null): Float {
        val inVal = input.coerceIn(0f, 1f)
        val outVal = when (curveType) {
            PressureCurveType.LINEAR -> inVal
            PressureCurveType.SOFT -> inVal.pow(0.5f)
            PressureCurveType.FIRM -> inVal.pow(2.0f)
            PressureCurveType.S_CURVE -> inVal * inVal * (3f - 2f * inVal)
            PressureCurveType.CUSTOM -> evaluateCustom(inVal, customPoints)
        }
        return outVal.coerceIn(0f, 1f)
    }

    fun applyWithClamp(input: Float, curveType: PressureCurveType, min: Float, max: Float, customPoints: List<Float>?): Float {
        val rawOut = apply(input, curveType, customPoints)
        if (rawOut < min) return 0f // deadzone
        val range = max - min
        if (range <= 0f) return 1f
        val clamped = (rawOut - min) / range
        return clamped.coerceIn(0f, 1f)
    }

    private fun evaluateCustom(x: Float, points: List<Float>?): Float {
        if (points == null || points.size < 4 || points.size % 2 != 0) {
            return x // fallback
        }
        // Points are [x0, y0, x1, y1, ...]
        // Sort pairs by x
        val pairs = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until points.size step 2) {
            pairs.add(Pair(points[i], points[i + 1]))
        }
        pairs.sortBy { it.first }

        if (x <= pairs.first().first) return pairs.first().second
        if (x >= pairs.last().first) return pairs.last().second

        // Find the segment containing x
        for (i in 0 until pairs.size - 1) {
            val p0 = pairs[i]
            val p1 = pairs[i + 1]
            if (x >= p0.first && x <= p1.first) {
                val t = (x - p0.first) / (p1.first - p0.first)
                return p0.second + t * (p1.second - p0.second)
            }
        }
        return x
    }
}
