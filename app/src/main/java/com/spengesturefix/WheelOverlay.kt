package com.denis.spenfix

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Owns two overlay windows: a non-touchable visual backdrop and a compact ring. */
class WheelOverlay(private val context: android.content.Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: OverlaySession? = null
    @Volatile private var visible = false

    fun toggle() {
        mainHandler.post { if (visible) dismissOnMain() else showOnMain() }
    }

    fun show() {
        mainHandler.post { showOnMain() }
    }

    private fun showOnMain() {
        if (visible) return
        visible = true
        val slots = WheelConfig.loadSlots(context)
        // URI decoding is I/O; keep it off the main looper so showing Air Command
        // can never delay the input pipeline.
        Thread {
            val background = WheelConfig.loadBackgroundBitmap(context)
            mainHandler.post {
                if (!visible) return@post
                addSession(slots, background)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun addSession(slots: List<PenAction>, background: Bitmap?) {
        if (session != null) return
        val state = OverlaySession()
        val lifecycleOwner = OverlayLifecycleOwner()
        val backdrop = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { SpenFixTheme { WheelBackdrop(state.visible) } }
        }
        val wheel = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                SpenFixTheme {
                    WheelRing(
                        slots = slots,
                        background = background,
                        visible = state.visible,
                        onAction = { action ->
                            ActionExecutor.execute(context, action, this@WheelOverlay)
                            dismiss()
                        },
                        onDismiss = ::dismiss,
                        onExitFinished = { removeSession(state) }
                    )
                }
            }
        }
        val backdropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val size = (context.resources.displayMetrics.density * 420f).toInt()
            .coerceAtMost(min(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels))
        val wheelParams = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        // ComposeView is hosted by a Service window, not an Activity. Install a
        // lifecycle owner before attachment so WindowRecomposer can be created.
        backdrop.setViewTreeLifecycleOwner(lifecycleOwner)
        backdrop.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        backdrop.setViewTreeViewModelStoreOwner(lifecycleOwner)
        wheel.setViewTreeLifecycleOwner(lifecycleOwner)
        wheel.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        wheel.setViewTreeViewModelStoreOwner(lifecycleOwner)
        try {
            windowManager.addView(backdrop, backdropParams)
            windowManager.addView(wheel, wheelParams)
            state.backdrop = backdrop
            state.wheel = wheel
            state.lifecycleOwner = lifecycleOwner
            session = state
        } catch (_: Exception) {
            try { windowManager.removeViewImmediate(backdrop) } catch (_: Exception) { }
            lifecycleOwner.destroy()
            visible = false
        }
    }

    fun dismiss() {
        mainHandler.post { dismissOnMain() }
    }

    private fun dismissOnMain() {
        if (!visible) return
        visible = false
        session?.visible = false
    }

    private fun removeSession(state: OverlaySession) {
        if (session?.id != state.id) return
        try { state.wheel?.let(windowManager::removeView) } catch (_: Exception) { }
        try { state.backdrop?.let(windowManager::removeView) } catch (_: Exception) { }
        state.lifecycleOwner?.destroy()
        session = null
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
    }

    private class OverlaySession(val id: Long = System.nanoTime()) {
        var visible by mutableStateOf(true)
        var backdrop: ComposeView? = null
        var wheel: ComposeView? = null
        var lifecycleOwner: OverlayLifecycleOwner? = null
    }

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val models = ViewModelStore()

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle
            get() = registry

        override val savedStateRegistry
            get() = savedStateController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = models

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            models.clear()
        }
    }
}

@Composable
private fun WheelBackdrop(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "backdrop-alpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                        22f * alpha,
                        22f * alpha,
                        android.graphics.Shader.TileMode.CLAMP
                    ).asComposeRenderEffect()
                }
            }
            .background(Color.Black.copy(alpha = .62f * alpha))
    )
}

private data class WheelGeometry(
    val center: Float,
    val ringRadius: Float,
    val slotSize: Float,
    val closeRadius: Float
)

@Composable
private fun WheelRing(
    slots: List<PenAction>,
    background: Bitmap?,
    visible: Boolean,
    onAction: (PenAction) -> Unit,
    onDismiss: () -> Unit,
    onExitFinished: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 260 else 180, easing = FastOutSlowInEasing),
        label = "wheel-progress"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 220 else 150),
        label = "wheel-alpha"
    )
    LaunchedEffect(visible) {
        if (!visible) {
            delay(210)
            onExitFinished()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = .82f + .18f * progress
                scaleY = .82f + .18f * progress
                rotationZ = -8f * (1f - progress)
            },
        contentAlignment = Alignment.Center
    ) {
        val side = min(maxWidth, maxHeight)
        val density = LocalDensity.current
        val sidePx = with(density) { side.toPx() }
        val center = sidePx / 2f
        val slotSize = (sidePx * .205f).coerceIn(68f, 98f)
        val geometry = WheelGeometry(
            center = center,
            ringRadius = (sidePx * .31f).coerceIn(104f, 142f),
            slotSize = slotSize,
            closeRadius = slotSize * .34f
        )
        var activeSlot by remember { mutableIntStateOf(-1) }
        Box(
            modifier = Modifier
                .size(side)
                .pointerInput(slots, geometry, progress) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val initialSlot = slotAt(down.position.x, down.position.y, geometry, slots.size)
                        val initialDistance = hypot(
                            down.position.x - geometry.center,
                            down.position.y - geometry.center
                        )
                        // Do not consume unrelated taps/pen hover in the compact
                        // overlay window; let the underlying app receive them.
                        if (initialSlot < 0 && initialDistance > geometry.closeRadius) {
                            return@awaitEachGesture
                        }
                        down.consume()
                        activeSlot = initialSlot
                        var up: PointerInputChange? = null
                        while (up == null) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) {
                                up = change
                            } else {
                                activeSlot = slotAt(change.position.x, change.position.y, geometry, slots.size)
                                change.consume()
                            }
                        }
                        val x = up?.position?.x ?: geometry.center
                        val y = up?.position?.y ?: geometry.center
                        val distance = hypot(x - geometry.center, y - geometry.center)
                        if (distance <= geometry.closeRadius) onDismiss()
                        else if (activeSlot in slots.indices) onAction(slots[activeSlot])
                        activeSlot = -1
                    }
                }
        ) {
            if (background != null) {
                Image(
                    background.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .graphicsLayer { this.alpha = .52f }
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xBB171522), Color(0xEE050507))
                        )
                    )
            )
            slots.forEachIndexed { index, action ->
                val angle = -PI / 2.0 + 2.0 * PI * index / maxOf(1, slots.size)
                val x = center + geometry.ringRadius * cos(angle).toFloat() - geometry.slotSize / 2f
                val y = center + geometry.ringRadius * sin(angle).toFloat() - geometry.slotSize / 2f
                val selected = activeSlot == index
                Surface(
                    color = if (selected) Color(0xFF5D56A8) else Color(0xE51A1924),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = if (selected) 14.dp else 4.dp,
                    modifier = Modifier
                        .size(with(density) { geometry.slotSize.toDp() })
                        .offset { IntOffset(x.toInt(), y.toInt()) }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        modifier = Modifier.padding(5.dp)
                    ) {
                        Text(action.type.icon, fontSize = 25.sp)
                        Text(
                            action.label.take(16),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Surface(
                color = Color(0xEE08080C),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .size(with(density) { (geometry.closeRadius * 2f).toDp() })
                    .offset {
                        IntOffset(
                            (center - geometry.closeRadius).toInt(),
                            (center - geometry.closeRadius).toInt()
                        )
                    }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("×", fontSize = 28.sp)
                }
            }
        }
    }
}

private fun slotAt(x: Float, y: Float, geometry: WheelGeometry, count: Int): Int {
    for (index in 0 until count) {
        val angle = -PI / 2.0 + 2.0 * PI * index / maxOf(1, count)
        val sx = geometry.center + geometry.ringRadius * cos(angle).toFloat()
        val sy = geometry.center + geometry.ringRadius * sin(angle).toFloat()
        if (hypot(x - sx, y - sy) <= geometry.slotSize / 2f) return index
    }
    return -1
}
