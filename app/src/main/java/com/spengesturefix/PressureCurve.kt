package com.spengesturefix

import kotlin.math.pow

object PressureCurve {
    private const val EPSILON = 0.0001f

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

    fun applyWithClamp(
        input: Float,
        curveType: PressureCurveType,
        min: Float,
        max: Float,
        customPoints: List<Float>?
    ): Float {
        val rawOut = apply(input, curveType, customPoints)
        val safeMin = min.coerceIn(0f, 1f)
        val safeMax = max.coerceIn(0f, 1f)
        if (safeMax <= safeMin + EPSILON) {
            return if (rawOut >= safeMax) 1f else 0f
        }
        if (rawOut <= safeMin) return 0f
        return ((rawOut - safeMin) / (safeMax - safeMin)).coerceIn(0f, 1f)
    }

    private fun evaluateCustom(x: Float, points: List<Float>?): Float {
        if (points == null || points.size < 4 || points.size % 2 != 0) return x

        val pairs = points.asSequence()
            .chunked(2)
            .mapNotNull { pair ->
                val pointX = pair[0]
                val pointY = pair[1]
                if (!pointX.isFinite() || !pointY.isFinite()) null
                else pointX.coerceIn(0f, 1f) to pointY.coerceIn(0f, 1f)
            }
            .sortedBy { it.first }
            .toList()
        if (pairs.size < 2) return x

        if (x <= pairs.first().first) return pairs.first().second
        if (x >= pairs.last().first) return pairs.last().second

        for (index in 0 until pairs.size - 1) {
            val first = pairs[index]
            val second = pairs[index + 1]
            if (x >= first.first && x <= second.first) {
                val deltaX = second.first - first.first
                if (deltaX <= EPSILON) continue
                val t = (x - first.first) / deltaX
                return first.second + t * (second.second - first.second)
            }
        }
        return x
    }
}
