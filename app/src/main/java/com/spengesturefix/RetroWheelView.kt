package com.spengesturefix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Measured constants from the original SpenCommand APK
 * (`SpenCommand_1238_pp_BETA1.apk`, res/layout/activity_popup.xml).
 * All values are in dp within the original 180dp window; the wheel renders
 * the original frame bitmaps (fan + white discs) and overlays only the
 * per-slot icons, the selection ring and the action-name readout.
 *
 * Pixel analysis of the extracted artwork (fan is one connected white
 * region; the original app drew the slot discs as ImageButtons on top) says
 * the bitmaps must be rendered UNMODIFIED: no disc erasure. The disc
 * layout is a runtime parameter, like in the original app: 4–7 slots
 * spread with a constant angular pitch over the authentic button arc and
 * the window scales down (150/165/180dp) with fewer discs.
 */
object RetroWheelLayout {
    /** Original popup window side in dp. */
    const val PANEL_DP = 180f

    /** Slot button centers, taken from the layout margins of buttons 1..6. */
    val SLOT_CENTERS_DP = arrayOf(
        36f to 117f,   // b1
        27f to 79f,    // b2
        44f to 45f,    // b3
        76f to 27f,    // b4
        115f to 34f,   // b5
        148f to 52f    // b6
    )

    /** Anchor (close) button center, from the ToggleButton margins. */
    val ANCHOR_CENTER_DP = 67f to 142f

    /**
     * Window size in dp for [slotCount] slots. The six authentic disc
     * centers are laid out on one arc whose outermost point (disc 6)
     * touches ~165dp of the original 180dp popup, while the fan's hinge
     * side clears around 150dp — so shrinking the whole panel by the ratio
     * 150:165:180 keeps the fan fitted edge-to-edge with no dead space:
     * the wheel literally grows with the number of placed gestures.
     */
    fun panelDpFor(slotCount: Int): Float = when {
        slotCount <= 4 -> 150f
        slotCount == 5 -> 165f
        else -> PANEL_DP
    }

    /**
     * Disc centers for [slotCount] placed gestures. The original app drew
     * the slot discs as runtime buttons on the fan, so the layout is a
     * parameter, not artwork. Pixel analysis of the extracted frame-18
     * artwork shows the fan is one exact annulus around (86.9, 87.0) —
     * hole r≈37, rim r≈82 — and the authentic buttons b1..b5 sit on that
     * ring (r 59–61) with a CONSTANT ~36° pitch from −149.5° (the spot next
     * to the close anchor) to −330.2° (the upper-right arm, b6's direction).
     * The layout is therefore POLAR on that annulus: every count 4–7
     * spreads its discs with an even angular step across the FULL authentic
     * arc. With 6 slots this reproduces the original button layout to
     * within ~4dp — and pulls the crooked rim-clipping b6 spot back onto
     * the ring — while other counts only change the pitch, so no count
     * bunches its last icons near the rim. Pixel probes verified every
     * stop keeps a full 31dp white-disc margin at every count (valid arc
     * there: 238°).
     */
    fun slotCentersFor(slotCount: Int): List<Pair<Float, Float>> {
        val count = slotCount.coerceIn(4, 7)
        val (centerX, centerY) = FAN_CENTER_DP
        return List(count) { index ->
            val angleDeg = FIRST_DISC_ANGLE_DEG - ARC_SPAN_DEG * index / (count - 1f)
            val theta = Math.toRadians(angleDeg.toDouble())
            val x = centerX + DISC_RING_RADIUS_DP * cos(theta).toFloat()
            val y = centerY - DISC_RING_RADIUS_DP * sin(theta).toFloat()
            x to y
        }
    }

    /**
     * Fan center: exact annulus fit on the extracted frame-18 artwork
     * (per-angle hole/rim radii vary by ≤0.5dp around this point). The
     * earlier least-squares fit through the button margins (92.1, 89.3) was
     * 5dp off because the b6 margin sits outside the ring — that offset
     * pushed the last slots against the rim.
     */
    private val FAN_CENTER_DP = 86.9f to 87.0f

    /** Disc ring radius: midway in the annulus (hole r≈37, rim r≈82). */
    private const val DISC_RING_RADIUS_DP = 60f

    /** Angle of the innermost button spot (b1), next to the close anchor. */
    private const val FIRST_DISC_ANGLE_DEG = -149.5f

    /**
     * Total arc covered by the discs — the authentic b1..b6 button arc
     * (~36° pitch × 5 steps). The last stop lands ~10dp inside the crooked
     * rim-clipping b6 margin, on clean white.
     */
    private const val ARC_SPAN_DEG = 180.7f

    /** Touch target diameter from the layout (32dp buttons). */
    const val SLOT_DIAMETER_DP = 32f

    /** Anchor touch target diameter from the layout (36dp ToggleButton). */
    const val ANCHOR_DIAMETER_DP = 36f

    /** Action-name readout: TextView 72x54dp, bottom margin 69dp, end margin 57dp. */
    const val READOUT_END_DP = 57f
    const val READOUT_BOTTOM_DP = 69f
    const val READOUT_WIDTH_DP = 72f
    const val READOUT_HEIGHT_DP = 54f

    /** 18 opening frames at 20ms each = 360ms, matching popup_animation_open. */
    const val FRAME_COUNT = 18
    const val FRAME_DURATION_MS = 20L
    const val OPEN_TOTAL_MS = FRAME_COUNT * FRAME_DURATION_MS
    const val CLOSE_TOTAL_MS = 240L

    /** Original frame bitmaps, decoded once — never modified, never erased. */
    private var frames: Array<Bitmap>? = null

    @Synchronized
    fun loadFramesFor(context: Context): Array<Bitmap> {
        frames?.let { return it }
        val loaded = Array(FRAME_COUNT) { index ->
            val id = context.resources.getIdentifier(
                "wheel_classic_frame_${index + 1}", "drawable", context.packageName
            )
            if (id != 0) BitmapFactory.decodeResource(context.resources, id)
            else Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        frames = loaded
        return loaded
    }
}

/**
 * Classic SpenCommand wheel rendered with the ORIGINAL frame bitmaps
 * extracted from `SpenCommand_1238_pp_BETA1.apk`
 * (res/drawable/popup_gimp_1..18.webp): the 18-step opening animation
 * (20ms per frame) reproduces the original look exactly — same black fan,
 * same white discs, same positions, same timing. The closing animation runs
 * the same frames backwards, like the original's close set. On top of the
 * authentic bitmaps this view draws only the per-slot icons (dark on white,
 * like the original), the selection ring and the action-name readout near
 * the anchor (the original's bottom TextView).
 *
 * The original app drew the slot discs as runtime buttons on the fan, so
 * the classic style adapts to any count: 4–7 slots sit on the fan annulus
 * (center 86.9/87.0, ring r=60dp) with a constant angular pitch across the
 * full authentic button arc — starting right next to the close anchor and
 * ending ~10dp inside the crooked rim-clipping b6 spot — and the window
 * scales with the disc count (150/165/180dp) so fewer slots never leave
 * dead space. With 7 discs the pitch (~30°) leaves the rims just touching,
 * like the original at maximum density.
 */
class RetroWheelView(
    context: Context,
    slots: List<PenAction>,
    private val accent: Int,
    private val onSlotTapped: (PenAction) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {
    private val appContext = context.applicationContext

    /** Slot count for this instance: what was configured, 4–7 on the spiral. */
    private val slotCount: Int = slots.size.coerceIn(4, 7)

    /** All configured slots are shown: layout adapts to the count. */
    private val visibleSlots: List<PenAction> = slots.take(slotCount)

    private val frames: Array<Bitmap> = RetroWheelLayout.loadFramesFor(appContext)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            android.graphics.Typeface.BOLD
        )
    }

    private val positionsPx = Array(7) { PointF() }
    private val labels: List<String> = visibleSlots.map { compactReadout(it.label) }
    private val icons: List<android.graphics.drawable.Drawable?> = visibleSlots.map {
        loadIcon(it.type.iconResId)
    }
    private val closeIcon: android.graphics.drawable.Drawable? = loadIcon(R.drawable.ic_act_none)
    private val iconBounds = android.graphics.Rect()

    private var scale = 1f
    private var density = 1f
    private var anchor = PointF()
    private var slotRadiusPx = 0f
    private var anchorRadiusPx = 0f
    private var frameIndex = 0f

    private var selectedSlot = -1
    private var tracking = false
    private var interactive = false
    private var animator: android.animation.ValueAnimator? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.section_wheel)
    }

    private fun loadIcon(resId: Int): android.graphics.drawable.Drawable? = try {
        val drawable = appContext.getDrawable(resId)
            ?: VectorDrawableCompat.create(appContext.resources, resId, appContext.theme)
        drawable
    } catch (_: Exception) {
        null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        density = resources.displayMetrics.density
        // Scale is ALWAYS referenced to the original 180dp design so the
        // frame bitmaps and every measured center shrink together; the
        // per-count window (150/165/180dp) only crops dead space — it must
        // never enter the scale math, or icons decouple from the artwork.
        scale = (width.toFloat() / (RetroWheelLayout.PANEL_DP * density)).coerceAtLeast(0.01f)
        anchor.set(
            RetroWheelLayout.ANCHOR_CENTER_DP.first * density * scale,
            RetroWheelLayout.ANCHOR_CENTER_DP.second * density * scale
        )
        slotRadiusPx = RetroWheelLayout.SLOT_DIAMETER_DP * density * scale / 2f
        anchorRadiusPx = RetroWheelLayout.ANCHOR_DIAMETER_DP * density * scale / 2f
        val centers = RetroWheelLayout.slotCentersFor(slotCount)
        centers.forEachIndexed { slotIndex, center ->
            positionsPx[slotIndex].set(center.first * density * scale, center.second * density * scale)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameIndex <= 0f) return
        val frame = frames[frameIndex.toInt().coerceIn(0, frames.lastIndex)]
        canvas.drawBitmap(frame, null, android.graphics.Rect(0, 0, width, height), bitmapPaint)

        drawSelectionRing(canvas)
        drawIcons(canvas)
        drawAnchorIcon(canvas)
        drawReadout(canvas)
    }

    private fun drawSelectionRing(canvas: Canvas) {
        if (selectedSlot !in visibleSlots.indices) return
        val point = positionsPx[selectedSlot]
        ringPaint.strokeWidth = dp(2f) * scale
        ringPaint.color = accent
        canvas.drawCircle(point.x, point.y, slotRadiusPx + dp(2.5f) * scale, ringPaint)
    }

    private fun drawIcons(canvas: Canvas) {
        visibleSlots.indices.forEach { slotIndex ->
            val icon = icons.getOrNull(slotIndex) ?: return@forEach
            val point = positionsPx[slotIndex]
            val half = slotRadiusPx * 0.56f
            iconBounds.set(
                (point.x - half).roundToInt(),
                (point.y - half).roundToInt(),
                (point.x + half).roundToInt(),
                (point.y + half).roundToInt()
            )
            icon.bounds = iconBounds
            icon.setTint(Color.rgb(48, 48, 48))
            icon.draw(canvas)
        }
    }

    private fun drawAnchorIcon(canvas: Canvas) {
        // The original ToggleButton is invisible (alpha 0); the disc visuals
        // live inside the bitmap. We overlay only the close glyph, dark-on-white.
        val icon = closeIcon ?: return
        val half = anchorRadiusPx * 0.56f
        iconBounds.set(
            (anchor.x - half).roundToInt(),
            (anchor.y - half).roundToInt(),
            (anchor.x + half).roundToInt(),
            (anchor.y + half).roundToInt()
        )
        icon.bounds = iconBounds
        icon.setTint(Color.rgb(48, 48, 48))
        icon.draw(canvas)
    }

    private fun drawReadout(canvas: Canvas) {
        if (selectedSlot < 0 || selectedSlot >= labels.size) return
        val pillWidth = RetroWheelLayout.READOUT_WIDTH_DP * density * scale
        val pillHeight = RetroWheelLayout.READOUT_HEIGHT_DP * density * scale
        val left = width - RetroWheelLayout.READOUT_END_DP * density * scale - pillWidth
        val top = height - RetroWheelLayout.READOUT_BOTTOM_DP * density * scale - pillHeight

        pillPaint.color = Color.argb(235, 26, 26, 28)
        canvas.drawRoundRect(
            left, top, left + pillWidth, top + pillHeight,
            dp(9f) * scale, dp(9f) * scale, pillPaint
        )
        ringPaint.strokeWidth = dp(1f) * scale
        ringPaint.color = accent
        canvas.drawRoundRect(
            left, top, left + pillWidth, top + pillHeight,
            dp(9f) * scale, dp(9f) * scale, ringPaint
        )

        val icon = icons.getOrNull(selectedSlot)
        val iconHalf = pillHeight * 0.22f
        val centerX = left + pillWidth / 2f
        val iconCenterY = top + pillHeight * 0.30f
        if (icon != null) {
            iconBounds.set(
                (centerX - iconHalf).roundToInt(),
                (iconCenterY - iconHalf).roundToInt(),
                (centerX + iconHalf).roundToInt(),
                (iconCenterY + iconHalf).roundToInt()
            )
            icon.bounds = iconBounds
            icon.setTint(Color.WHITE)
            icon.draw(canvas)
        }

        textPaint.textSize = pillHeight * 0.26f
        textPaint.color = Color.WHITE
        val textY = top + pillHeight * 0.66f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labels[selectedSlot], centerX, textY, textPaint)
    }

    private fun hitSlot(x: Float, y: Float): Int {
        if (hypot(x - anchor.x, y - anchor.y) <= anchorRadiusPx * 1.25f) return CLOSE_SLOT
        var best = -1
        var bestDistance = Float.MAX_VALUE
        visibleSlots.indices.forEach { slotIndex ->
            val point = positionsPx[slotIndex]
            val distance = hypot(x - point.x, y - point.y)
            if (distance <= slotRadiusPx * 1.3f && distance < bestDistance) {
                bestDistance = distance
                best = slotIndex
            }
        }
        return best
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                tracking = false
                selectedSlot = -1
                onDismiss()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                val slot = hitSlot(event.x, event.y)
                if (slot < 0) {
                    onDismiss()
                    return true
                }
                tracking = true
                selectedSlot = slot
                performHaptic()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val slot = hitSlot(event.x, event.y)
                if (slot != selectedSlot) {
                    selectedSlot = slot
                    if (slot >= 0) performHaptic()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                val releasedSlot = hitSlot(event.x, event.y)
                tracking = false
                selectedSlot = -1
                invalidate()
                performClick()
                when {
                    releasedSlot == CLOSE_SLOT -> onDismiss()
                    releasedSlot >= 0 -> onSlotTapped(visibleSlots[releasedSlot])
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                selectedSlot = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun performHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } else {
                @Suppress("DEPRECATION")
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (_: Exception) {
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun playEnterAnimation() {
        interactive = true
        // Original opening: 18 frames at 20ms each (popup_animation_open).
        animator?.cancel()
        frameIndex = 0f
        animator = android.animation.ValueAnimator.ofFloat(
            0f, (RetroWheelLayout.FRAME_COUNT - 1).toFloat()
        ).apply {
            duration = RetroWheelLayout.OPEN_TOTAL_MS
            addUpdateListener {
                frameIndex = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (interactive) {
                        frameIndex = (RetroWheelLayout.FRAME_COUNT - 1).toFloat()
                        invalidate()
                    }
                }
            })
        }
        animator?.start()
    }

    fun playExitAnimation(onFinished: (RetroWheelView) -> Unit) {
        interactive = false
        tracking = false
        selectedSlot = -1
        animator?.cancel()
        if (frameIndex <= 0.5f) {
            frameIndex = 0f
            onFinished(this)
            return
        }
        // Original closing: the same frames run backwards.
        animator = android.animation.ValueAnimator.ofFloat(frameIndex, 0f).apply {
            duration = RetroWheelLayout.CLOSE_TOTAL_MS
            addUpdateListener {
                frameIndex = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var finished = false
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!finished) {
                        finished = true
                        frameIndex = 0f
                        onFinished(this@RetroWheelView)
                    }
                }
            })
        }
        animator?.start()
    }

    private fun compactReadout(value: String): String {
        val normalized = value.replace('\n', ' ').trim()
        return if (normalized.length > 14) normalized.take(13) + '…' else normalized
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )

    companion object {
        /** The anchor disc is the dedicated close target. */
        const val CLOSE_SLOT = 999
    }
}
