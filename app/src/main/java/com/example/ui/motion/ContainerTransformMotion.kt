package com.example.ui.motion

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.rememberSystemAnimationScale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Unified Material 3 Expressive motion specification & transition choreography
 * shared across all folder widget sizes (3x3, 2x2, 1x4, 1x2) and Large Icon previews (1x1, 1x2, 2x1).
 */
object ContainerTransformSpec {
    // Spatial spring tokens (M3 Expressive physics): open ~380-420ms, close ~240-280ms critically damped
    const val OPEN_STIFFNESS = 380f
    const val OPEN_DAMPING = 0.88f

    // Damping = 1.0f prevents overshoot past widget bounds on collapse
    const val CLOSE_STIFFNESS = 780f
    const val CLOSE_DAMPING = 1.0f

    const val CANCEL_RECOVERY_STIFFNESS = 540f
    const val CANCEL_RECOVERY_DAMPING = 0.86f

    // Predictive back minimum scale & edge margin requirement
    const val PREDICTIVE_BACK_MIN_SCALE = 0.90f
    val PREDICTIVE_BACK_EDGE_MARGIN = 8.dp

    // Emphasized curves (tween fallbacks / predictive back decelerate)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
    val PredictiveBackDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)

    fun openSpatialSpring(animScale: Float): AnimationSpec<Float> {
        if (animScale <= 0.05f) return snap()
        val stiffness = (OPEN_STIFFNESS / (animScale * animScale)).coerceIn(30f, 5000f)
        return spring(dampingRatio = OPEN_DAMPING, stiffness = stiffness)
    }

    fun closeSpatialSpring(animScale: Float): AnimationSpec<Float> {
        if (animScale <= 0.05f) return snap()
        val stiffness = (CLOSE_STIFFNESS / (animScale * animScale)).coerceIn(45f, 6500f)
        return spring(dampingRatio = CLOSE_DAMPING, stiffness = stiffness)
    }

    fun cancelRecoverySpring(animScale: Float): AnimationSpec<Float> {
        if (animScale <= 0.05f) return snap()
        val stiffness = (CANCEL_RECOVERY_STIFFNESS / (animScale * animScale)).coerceIn(40f, 5500f)
        return spring(dampingRatio = CANCEL_RECOVERY_DAMPING, stiffness = stiffness)
    }
}

enum class ContainerTransitionPhase {
    OPENING,
    OPEN,
    DISMISSING,
    DISMISSED
}

data class PredictiveBackVisualState(
    val scale: Float,
    val offset: Offset,
    val easedProgress: Float
)

data class ContainerVisualFrame(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val cornerRadiusRxPx: Float,
    val cornerRadiusRyPx: Float,
    val elevationPx: Float,
    val containerAlpha: Float,
    val compactAlpha: Float,
    val expandedAlpha: Float,
    val expandedInnerScale: Float,
    val scrimAlpha: Float,
    val geometryProgress: Float,
    val occlusionAlpha: Float = 0f
)

/**
 * Temporary opaque Surface shield active during open/collapse transitions (`0.00..0.82`) so the
 * underlying home-screen widget (3-dots menu & icons) cannot bleed through translucent cardBgColor
 * as system blur weakens. Stays `1f` through `0.00` while [computeContainerAlpha] fades the entire
 * card offscreen into the real home-screen widget.
 */
fun computeOcclusionAlpha(progress: Float, isTransitioning: Boolean): Float {
    if (!isTransitioning) return 0f
    val p = progress.coerceIn(0f, 1f)
    return when {
        p <= 0.50f -> 1f
        p < 0.82f -> ((0.82f - p) / 0.32f).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * Defers composition of transition subtrees until [shouldCompose] becomes true (optionally waiting
 * one vsync frame when [deferOneFrame] is enabled) and cleanly disposes them when [shouldCompose]
 * becomes false (unless [keepAliveAfterComposed] is true).
 */
@Composable
fun DeferredComposition(
    shouldCompose: Boolean,
    deferOneFrame: Boolean = false,
    keepAliveAfterComposed: Boolean = false,
    content: @Composable () -> Unit
) {
    var hasEverComposed by remember { mutableStateOf(shouldCompose && !deferOneFrame) }
    var isDeferredFrameReady by remember(deferOneFrame) {
        mutableStateOf(shouldCompose && !deferOneFrame)
    }

    LaunchedEffect(shouldCompose, deferOneFrame, keepAliveAfterComposed) {
        if (shouldCompose) {
            if (deferOneFrame && !isDeferredFrameReady) {
                withFrameNanos { }
                isDeferredFrameReady = true
            }
            hasEverComposed = true
        } else if (!keepAliveAfterComposed) {
            isDeferredFrameReady = false
        }
    }

    val active = when {
        !deferOneFrame -> if (keepAliveAfterComposed) (shouldCompose || hasEverComposed) else shouldCompose
        keepAliveAfterComposed -> (isDeferredFrameReady || hasEverComposed)
        else -> shouldCompose && isDeferredFrameReady
    }
    if (active) {
        content()
    }
}

/**
 * When a concrete widget origin exists (`hasOriginBounds == true`), container geometry tracks
 * spatial `progress` 1:1.
 * When no origin bounds exist (centered known-size fallback), `geometryProgress` settles back to
 * the widget's known size by `p = 0.20f`, after which `containerAlpha` fades cleanly to `0f`.
 */
fun computeGeometryProgress(progress: Float, hasOriginBounds: Boolean): Float {
    val p = progress.coerceIn(0f, 1.06f)
    return if (hasOriginBounds) {
        p
    } else {
        ((p - 0.20f) / 0.80f).coerceIn(0f, 1.06f)
    }
}

fun computeContainerAlpha(
    progress: Float,
    hasOriginBounds: Boolean,
    hasMeasuredLayout: Boolean
): Float {
    if (!hasMeasuredLayout) return 0f
    val clamped = progress.coerceIn(0f, 1f)
    return if (hasOriginBounds) {
        // Hold 1f across travel; fade the whole offscreen-composited card seamlessly over final 8%
        // onto the real home-screen widget so there is zero double-icon/3-dot ghosting at landing.
        (clamped / 0.08f).coerceIn(0f, 1f)
    } else {
        (clamped / 0.30f).coerceIn(0f, 1f)
    }
}

/**
 * Expanded popup grid/chrome fades in late after opening past 48% and fades out completely by 48%
 * progress on close before the compact widget snapshot becomes visible.
 */
fun computeExpandedContentAlpha(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return ((clamped - 0.48f) / 0.36f).coerceIn(0f, 1f)
}

fun computeExpandedContentScale(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    val t = ((clamped - 0.48f) / 0.52f).coerceIn(0f, 1f)
    return lerp(0.88f, 1.0f, t)
}

/**
 * Compact widget snapshot is visible (`1f`) below `0.12f` and fades cleanly across `0.36 -> 0.12`,
 * leaving a dead zone (`0.36..0.48`) against [computeExpandedContentAlpha] so expanded and compact
 * icons never overlap on screen.
 */
fun computeCompactContentAlpha(progress: Float, hasOriginBounds: Boolean): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return if (hasOriginBounds) {
        (1f - ((clamped - 0.12f) / 0.24f)).coerceIn(0f, 1f)
    } else {
        (1f - ((clamped - 0.12f) / 0.24f)).coerceIn(0f, 1f)
    }
}

fun computePredictiveBackState(
    rawProgress: Float,
    swipeEdge: Int,
    touchDeltaYRatio: Float,
    cardCenter: Offset,
    cardSize: IntSize,
    screenWidthPx: Float,
    screenHeightPx: Float,
    edgeMarginPx: Float,
    density: Float
): PredictiveBackVisualState {
    val eased = ContainerTransformSpec.PredictiveBackDecelerate.transform(rawProgress.coerceIn(0f, 1f))
    val scale = lerp(1f, ContainerTransformSpec.PREDICTIVE_BACK_MIN_SCALE, eased)
        .coerceIn(ContainerTransformSpec.PREDICTIVE_BACK_MIN_SCALE, 1f)

    val edgeSign = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val desiredDx = 12f * density * eased * edgeSign
    val desiredDy = 8f * density * eased * touchDeltaYRatio.coerceIn(-1f, 1f)

    val halfScaledW = (cardSize.width * scale) / 2f
    val halfScaledH = (cardSize.height * scale) / 2f

    val minTx = (edgeMarginPx + halfScaledW) - cardCenter.x
    val maxTx = (screenWidthPx - edgeMarginPx - halfScaledW) - cardCenter.x
    val clampedTx = if (minTx <= maxTx) {
        desiredDx.coerceIn(minTx, maxTx)
    } else {
        (minTx + maxTx) / 2f
    }

    val minTy = (edgeMarginPx + halfScaledH) - cardCenter.y
    val maxTy = (screenHeightPx - edgeMarginPx - halfScaledH) - cardCenter.y
    val clampedTy = if (minTy <= maxTy) {
        desiredDy.coerceIn(minTy, maxTy)
    } else {
        (minTy + maxTy) / 2f
    }

    return PredictiveBackVisualState(
        scale = scale,
        offset = Offset(clampedTx, clampedTy),
        easedProgress = eased
    )
}

fun computeContainerVisualFrame(
    progress: Float,
    originBounds: Rect,
    originCornerRadiusPx: Float,
    expandedCenter: Offset,
    expandedSize: IntSize,
    expandedCornerRadiusPx: Float,
    expandedElevationPx: Float,
    hasOriginBounds: Boolean,
    hasMeasuredLayout: Boolean,
    preCollapseScale: Float,
    preCollapseOffset: Offset,
    isTransitioning: Boolean = false
): ContainerVisualFrame {
    val clampedP = progress.coerceIn(0f, 1f)
    val geomP = computeGeometryProgress(progress, hasOriginBounds)
    val clampedGeomP = geomP.coerceIn(0f, 1f)

    val expW = expandedSize.width.toFloat().coerceAtLeast(1f)
    val expH = expandedSize.height.toFloat().coerceAtLeast(1f)

    val startScaleX = (originBounds.width / expW).coerceIn(0.12f, 2.5f)
    val startScaleY = (originBounds.height / expH).coerceIn(0.10f, 2.5f)

    val targetTopScaleX = preCollapseScale.coerceIn(0.75f, 1.0f)
    val targetTopScaleY = preCollapseScale.coerceIn(0.75f, 1.0f)

    val sX = lerp(startScaleX, targetTopScaleX, geomP).coerceAtLeast(0.05f)
    val sY = lerp(startScaleY, targetTopScaleY, geomP).coerceAtLeast(0.05f)

    val targetTopCenter = expandedCenter + preCollapseOffset
    val curCenterX = lerp(originBounds.center.x, targetTopCenter.x, geomP)
    val curCenterY = lerp(originBounds.center.y, targetTopCenter.y, geomP)

    val tx = curCenterX - expandedCenter.x
    val ty = curCenterY - expandedCenter.y

    val visualCornerPx = lerp(originCornerRadiusPx, expandedCornerRadiusPx, clampedGeomP)
    val compensatedRx = visualCornerPx / sX
    val compensatedRy = visualCornerPx / sY

    val containerAlpha = computeContainerAlpha(clampedP, hasOriginBounds, hasMeasuredLayout)
    val expandedAlpha = if (hasMeasuredLayout) computeExpandedContentAlpha(clampedP) else 0f
    val expandedInnerScale = computeExpandedContentScale(clampedP)
    val compactAlpha = if (hasMeasuredLayout) computeCompactContentAlpha(clampedP, hasOriginBounds) else 0f
    val elevationPx = lerp(0f, expandedElevationPx, clampedGeomP)
    val scrimAlpha = if (hasMeasuredLayout) clampedP else 0f
    val occlusionAlpha = if (hasMeasuredLayout) computeOcclusionAlpha(clampedP, isTransitioning) else 0f

    return ContainerVisualFrame(
        scaleX = sX,
        scaleY = sY,
        translationX = tx,
        translationY = ty,
        cornerRadiusRxPx = compensatedRx,
        cornerRadiusRyPx = compensatedRy,
        elevationPx = elevationPx,
        containerAlpha = containerAlpha,
        compactAlpha = compactAlpha,
        expandedAlpha = expandedAlpha,
        expandedInnerScale = expandedInnerScale,
        scrimAlpha = scrimAlpha,
        geometryProgress = geomP,
        occlusionAlpha = occlusionAlpha
    )
}

private class CompensatedRoundedShape(
    private val rxPx: Float,
    private val ryPx: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val clampedRx = rxPx.coerceIn(0f, size.width / 2f)
        val clampedRy = ryPx.coerceIn(0f, size.height / 2f)
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(clampedRx, clampedRy)
            )
        )
    }
}

/**
 * Single reusable container-transform overlay driving:
 * - Spatial spring opening (~380-420ms) and critically damped collapse (~250ms, no overshoot)
 * - Predictive back (OnBackPressedCallback + decelerate curve + >=90% scale + >=8dp margin)
 * - Interruptible vertical swipe-to-dismiss & scrim/card tap reversal preserving velocity
 * - Early expanded content fade/scale-out & late compact widget fade-in (1:1 aspect match at p=0)
 * - Scrim & static blur alpha tracking progress with zero per-frame blur-radius relayout
 */
@Composable
fun SharedContainerTransformOverlay(
    originBounds: Rect?,
    originBoundsAreScreenCoords: Boolean,
    originCornerRadiusDp: Float,
    hasExactOrCachedOrigin: Boolean,
    expandedCornerRadiusDp: Float = FolderBlurSpec.EXPANDED_CARD_CORNER_RADIUS_DP,
    expandedElevationDp: Dp = 20.dp,
    cardBgColor: Color,
    cardBorderColor: Color,
    opaqueSurfaceColor: Color? = null,
    cardWidthFraction: Float = 0.91f,
    cardMaxWidth: Dp = 420.dp,
    externalDismissTrigger: Int = 0,
    returnDismissTrigger: Int = 0,
    isGestureDisabled: Boolean = false,
    isSystemBlurEnabled: Boolean = false,
    staticBackdropBitmap: Bitmap? = null,
    onBlurProgressChanged: ((Float) -> Unit)? = null,
    blurLifecycleInterceptor: WindowBlurLifecycleInterceptor? = null,
    onDismissRequestedBeforeClose: () -> Boolean = { false },
    onRequestDismiss: (() -> Unit)? = null,
    keepComposedUntilHostFinishes: Boolean = false,
    onDismissCompleted: () -> Unit,
    overlayTestTag: String = "shared_container_overlay",
    cardTestTag: String = "shared_container_card",
    headerContent: @Composable BoxScope.(isDismissing: Boolean, expandedAlphaProvider: () -> Float) -> Unit = { _, _ -> },
    bottomSpacerWeight: Float = 0.08f,
    topSpacerWeight: Float = 0.14f,
    compactContent: @Composable BoxScope.() -> Unit,
    expandedContent: @Composable BoxScope.(isDismissing: Boolean, triggerClose: () -> Unit) -> Unit
) {
    val density = LocalDensity.current
    val hostView = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val animScale = rememberSystemAnimationScale()
    val currentAnimScale by rememberUpdatedState(animScale)
    val areAnimationsEnabled = animScale > 0.05f

    val animProgress = remember { Animatable(0f) }
    val backScale = remember { Animatable(1f) }
    val backOffsetX = remember { Animatable(0f) }
    val backOffsetY = remember { Animatable(0f) }
    val swipeOffsetX = remember { Animatable(0f) }
    val swipeOffsetY = remember { Animatable(0f) }

    var phase by remember { mutableStateOf(ContainerTransitionPhase.OPENING) }
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var isSwipingDismiss by remember { mutableStateOf(false) }
    var hasStartedEntry by remember { mutableStateOf(false) }
    var hasDispatchedComplete by remember { mutableStateOf(false) }

    // Synchronous predictive-back state updated directly on UI input with zero async snapTo lag
    var liveBackScale by remember { mutableFloatStateOf(1f) }
    var liveBackOffsetX by remember { mutableFloatStateOf(0f) }
    var liveBackOffsetY by remember { mutableFloatStateOf(0f) }
    var liveBackRawProgress by remember { mutableFloatStateOf(0f) }
    val backProgressAnim = remember { Animatable(0f) }
    var backRecoveryJob by remember { mutableStateOf<Job?>(null) }

    var cardSlotSize by remember { mutableStateOf(IntSize.Zero) }
    var cardSlotCenter by remember { mutableStateOf(Offset.Zero) }
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }
    val hasMeasuredLayout by remember {
        derivedStateOf {
            cardSlotSize.width > 0 && cardSlotSize.height > 0 &&
                viewportSizePx.width > 0 && viewportSizePx.height > 0
        }
    }

    val originCornerPx = with(density) { originCornerRadiusDp.dp.toPx() }
    val expandedCornerPx = with(density) { expandedCornerRadiusDp.dp.toPx() }
    val expandedElevationPx = with(density) { expandedElevationDp.toPx() }
    val borderWidthPx = with(density) { FolderBlurSpec.HIGHLIGHT_EDGE_WIDTH.toPx() }
    val edgeMarginPx = with(density) { ContainerTransformSpec.PREDICTIVE_BACK_EDGE_MARGIN.toPx() }
    val dismissDragThresholdPx = with(density) { 96.dp.toPx() }
    val dismissMaxDragPx = with(density) { 260.dp.toPx() }

    val currentIsSystemBlurEnabled by rememberUpdatedState(isSystemBlurEnabled)
    val currentEdgeMarginPx by rememberUpdatedState(edgeMarginPx)
    val currentDensityValue by rememberUpdatedState(density.density)
    val currentOnBlurProgressChanged by rememberUpdatedState(onBlurProgressChanged)
    val currentBlurInterceptor by rememberUpdatedState(blurLifecycleInterceptor)
    val currentOnDismissBeforeClose by rememberUpdatedState(onDismissRequestedBeforeClose)
    val currentOnRequestDismiss by rememberUpdatedState(onRequestDismiss)
    val currentOnDismissCompleted by rememberUpdatedState(onDismissCompleted)
    val currentIsGestureDisabled by rememberUpdatedState(isGestureDisabled)
    var pendingCollapseVelocity by remember { mutableStateOf<Float?>(null) }

    val resolvedOpaqueSurfaceColor = remember(opaqueSurfaceColor, cardBgColor) {
        opaqueSurfaceColor ?: run {
            val base = if (cardBgColor.luminance() > 0.5f) Color(0xFFF1F5F9) else Color(0xFF10141E)
            cardBgColor.compositeOver(base).copy(alpha = 1f)
        }
    }
    val isTransitioning = phase == ContainerTransitionPhase.OPENING ||
        phase == ContainerTransitionPhase.DISMISSING ||
        isPredictiveBackActive ||
        isSwipingDismiss
    val shouldComposeCompact by remember(keepComposedUntilHostFinishes) {
        derivedStateOf {
            hasMeasuredLayout && (keepComposedUntilHostFinishes || phase != ContainerTransitionPhase.DISMISSED)
        }
    }
    val shouldComposeExpanded by remember(keepComposedUntilHostFinishes) {
        derivedStateOf {
            keepComposedUntilHostFinishes || phase != ContainerTransitionPhase.DISMISSED
        }
    }

    val effectiveOrigin = remember(originBounds, cardSlotCenter, cardSlotSize, density) {
        if (originBounds != null && !originBounds.isEmpty) {
            originBounds
        } else if (cardSlotSize.width > 0 && cardSlotSize.height > 0) {
            val w = (cardSlotSize.width * 0.56f).coerceAtLeast(120f)
            val h = (cardSlotSize.height * 0.44f).coerceAtLeast(120f)
            Rect(
                left = cardSlotCenter.x - w / 2f,
                top = cardSlotCenter.y - h / 2f,
                right = cardSlotCenter.x + w / 2f,
                bottom = cardSlotCenter.y + h / 2f
            )
        } else {
            Rect(0f, 0f, 160f, 160f)
        }
    }

    fun currentBackDragProgress(): Float =
        if (isPredictiveBackActive) liveBackRawProgress else backProgressAnim.value

    fun currentEffectiveBlurProgress(): Float =
        if (hasMeasuredLayout) {
            FolderBlurSpec.computeEffectiveBlurProgress(
                containerProgress = animProgress.value,
                backDragProgress = currentBackDragProgress()
            )
        } else {
            0f
        }

    fun pushWindowBlurNow() {
        currentOnBlurProgressChanged?.invoke(
            if (currentIsSystemBlurEnabled) currentEffectiveBlurProgress() else 0f
        )
    }

    fun emitBlurFrame(
        stage: BlurLifecycleStage,
        rawGestureProgress: Float = currentBackDragProgress(),
        easedGestureProgress: Float = if (isPredictiveBackActive) liveBackRawProgress else backProgressAnim.value,
        gestureScale: Float = if (isPredictiveBackActive) liveBackScale else backScale.value,
        gestureTx: Float = (if (isPredictiveBackActive) liveBackOffsetX else backOffsetX.value) + swipeOffsetX.value,
        gestureTy: Float = (if (isPredictiveBackActive) liveBackOffsetY else backOffsetY.value) + swipeOffsetY.value
    ) {
        currentBlurInterceptor?.recordGestureFrame(
            BlurGestureFrameContext(
                stage = stage,
                containerProgress = animProgress.value,
                rawGestureProgress = rawGestureProgress,
                easedGestureProgress = easedGestureProgress,
                gestureScale = gestureScale,
                gestureTranslationXPx = gestureTx,
                gestureTranslationYPx = gestureTy,
                timestampNanos = System.nanoTime()
            )
        )
        pushWindowBlurNow()
    }

    fun finishOnce() {
        if (!hasDispatchedComplete) {
            hasDispatchedComplete = true
            phase = ContainerTransitionPhase.DISMISSED
            currentBlurInterceptor?.recordGestureFrame(
                BlurGestureFrameContext(
                    stage = BlurLifecycleStage.WINDOW_DETACHED,
                    containerProgress = 0f,
                    rawGestureProgress = 0f,
                    easedGestureProgress = 0f,
                    gestureScale = 1f,
                    gestureTranslationXPx = 0f,
                    gestureTranslationYPx = 0f,
                    timestampNanos = System.nanoTime()
                )
            )
            currentOnBlurProgressChanged?.invoke(0f)
            currentOnDismissCompleted()
        }
    }

    fun animateToOpen(preserveVelocity: Float? = null) {
        if (hasDispatchedComplete) return
        backRecoveryJob?.cancel()
        backRecoveryJob = null
        liveBackScale = 1f
        liveBackOffsetX = 0f
        liveBackOffsetY = 0f
        liveBackRawProgress = 0f
        phase = ContainerTransitionPhase.OPENING
        val scale = currentAnimScale
        coroutineScope.launch {
            if (abs(backScale.value - 1f) > 0.0005f) {
                launch { backScale.animateTo(1f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
            }
            if (abs(backOffsetX.value) > 0.05f) {
                launch { backOffsetX.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
            }
            if (abs(backOffsetY.value) > 0.05f) {
                launch { backOffsetY.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
            }
            if (abs(backProgressAnim.value) > 0.0005f) {
                launch {
                    backProgressAnim.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) {
                        emitBlurFrame(
                            stage = BlurLifecycleStage.BACK_GESTURE_RECOVERY,
                            rawGestureProgress = value,
                            easedGestureProgress = value
                        )
                    }
                }
            }
            if (abs(swipeOffsetX.value) > 0.05f) {
                launch { swipeOffsetX.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
            }
            if (abs(swipeOffsetY.value) > 0.05f) {
                launch { swipeOffsetY.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
            }

            if (scale <= 0.05f) {
                animProgress.snapTo(1f)
                emitBlurFrame(BlurLifecycleStage.IDLE_OPEN)
                if (phase == ContainerTransitionPhase.OPENING) {
                    phase = ContainerTransitionPhase.OPEN
                }
            } else {
                val result = animProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = ContainerTransformSpec.openSpatialSpring(scale),
                    initialVelocity = preserveVelocity ?: animProgress.velocity
                ) {
                    // Synchronous update inside CALLBACK_ANIMATION before DecorView traversal/draw
                    emitBlurFrame(BlurLifecycleStage.OPEN_ANIMATION)
                }
                if (result.endReason == androidx.compose.animation.core.AnimationEndReason.Finished &&
                    phase == ContainerTransitionPhase.OPENING
                ) {
                    emitBlurFrame(BlurLifecycleStage.IDLE_OPEN)
                    phase = ContainerTransitionPhase.OPEN
                }
            }
        }
    }

    fun animateToClose(initialVelocity: Float? = null) {
        if (hasDispatchedComplete) return
        phase = ContainerTransitionPhase.DISMISSING
        val scale = currentAnimScale
        coroutineScope.launch {
            if (scale <= 0.05f) {
                animProgress.snapTo(0f)
                finishOnce()
            } else {
                val result = animProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = ContainerTransformSpec.closeSpatialSpring(scale),
                    initialVelocity = initialVelocity ?: animProgress.velocity
                ) {
                    // Synchronous update inside CALLBACK_ANIMATION before DecorView traversal/draw
                    emitBlurFrame(BlurLifecycleStage.CLOSE_ANIMATION)
                }
                if (result.endReason == androidx.compose.animation.core.AnimationEndReason.Finished &&
                    phase == ContainerTransitionPhase.DISMISSING
                ) {
                    finishOnce()
                }
            }
        }
    }

    // Re-sync blur immediately whenever system cross-window blur availability toggles at runtime
    LaunchedEffect(isSystemBlurEnabled) {
        pushWindowBlurNow()
    }

    // Initial open once card slot and viewport are measured
    LaunchedEffect(hasMeasuredLayout, animScale) {
        if (!hasMeasuredLayout || hasStartedEntry) return@LaunchedEffect
        hasStartedEntry = true
        if (!areAnimationsEnabled) {
            animProgress.snapTo(1f)
            emitBlurFrame(BlurLifecycleStage.IDLE_OPEN)
            phase = ContainerTransitionPhase.OPEN
        } else {
            animateToOpen(preserveVelocity = 0f)
        }
    }

    // External dismiss (e.g. host dismiss(), Home key / window leave)
    LaunchedEffect(externalDismissTrigger) {
        if (externalDismissTrigger > 0 && !hasDispatchedComplete && phase != ContainerTransitionPhase.DISMISSING) {
            val vel = pendingCollapseVelocity
            pendingCollapseVelocity = null
            animateToClose(initialVelocity = vel)
        }
    }

    // Return-from-app collapse (Strategy A: popup remained alive at 1f, smoothly shrinks into widget)
    LaunchedEffect(returnDismissTrigger) {
        if (returnDismissTrigger > 0 && !hasDispatchedComplete) {
            hasStartedEntry = true
            backRecoveryJob?.cancel()
            backRecoveryJob = null
            isPredictiveBackActive = false
            isSwipingDismiss = false
            liveBackScale = 1f
            liveBackOffsetX = 0f
            liveBackOffsetY = 0f
            liveBackRawProgress = 0f
            backScale.snapTo(1f)
            backOffsetX.snapTo(0f)
            backOffsetY.snapTo(0f)
            backProgressAnim.snapTo(0f)
            swipeOffsetX.snapTo(0f)
            swipeOffsetY.snapTo(0f)
            if (animProgress.value < 0.99f && hasMeasuredLayout) {
                animProgress.snapTo(1f)
            }
            pushWindowBlurNow()
            animateToClose(initialVelocity = 0f)
        }
    }

    // Explicit OnBackPressedCallback for Predictive Back (started, progressed, cancelled, pressed)
    val dispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backCallback = remember {
        object : OnBackPressedCallback(true) {
            private var startTouchY: Float = Float.NaN

            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (hasDispatchedComplete || currentIsGestureDisabled) return
                backRecoveryJob?.cancel()
                backRecoveryJob = null
                liveBackScale = backScale.value
                liveBackOffsetX = backOffsetX.value
                liveBackOffsetY = backOffsetY.value
                liveBackRawProgress = backProgressAnim.value
                startTouchY = if (backEvent.touchY > 0f) backEvent.touchY else Float.NaN
                isPredictiveBackActive = true
                emitBlurFrame(
                    stage = BlurLifecycleStage.BACK_GESTURE_START,
                    rawGestureProgress = backEvent.progress,
                    easedGestureProgress = liveBackRawProgress,
                    gestureScale = liveBackScale,
                    gestureTx = liveBackOffsetX,
                    gestureTy = liveBackOffsetY
                )
                if (phase == ContainerTransitionPhase.DISMISSING) {
                    animateToOpen()
                }
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (!isPredictiveBackActive || hasDispatchedComplete) return
                if (startTouchY.isNaN() && backEvent.touchY > 0f) {
                    startTouchY = backEvent.touchY
                }
                val viewW = viewportSizePx.width.toFloat().coerceAtLeast(1f)
                val viewH = viewportSizePx.height.toFloat().coerceAtLeast(1f)
                val deltaYRatio = if (!startTouchY.isNaN()) {
                    ((backEvent.touchY - startTouchY) / (viewH * 0.25f)).coerceIn(-1f, 1f)
                } else {
                    0f
                }
                val state = computePredictiveBackState(
                    rawProgress = backEvent.progress,
                    swipeEdge = backEvent.swipeEdge,
                    touchDeltaYRatio = deltaYRatio,
                    cardCenter = cardSlotCenter,
                    cardSize = cardSlotSize,
                    screenWidthPx = viewW,
                    screenHeightPx = viewH,
                    edgeMarginPx = currentEdgeMarginPx,
                    density = currentDensityValue
                )
                // Direct synchronous update -> zero coroutine dispatch lag or split-frame scale/offset skew
                liveBackScale = state.scale
                liveBackOffsetX = state.offset.x
                liveBackOffsetY = state.offset.y
                liveBackRawProgress = state.easedProgress
                emitBlurFrame(
                    stage = BlurLifecycleStage.BACK_GESTURE_PROGRESS,
                    rawGestureProgress = backEvent.progress,
                    easedGestureProgress = state.easedProgress,
                    gestureScale = state.scale,
                    gestureTx = state.offset.x,
                    gestureTy = state.offset.y
                )
            }

            override fun handleOnBackCancelled() {
                if (!isPredictiveBackActive) return
                val releaseScale = liveBackScale
                val releaseX = liveBackOffsetX
                val releaseY = liveBackOffsetY
                val releaseRawProgress = liveBackRawProgress
                startTouchY = Float.NaN
                val scale = currentAnimScale
                backRecoveryJob?.cancel()
                backRecoveryJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    backScale.snapTo(releaseScale)
                    backOffsetX.snapTo(releaseX)
                    backOffsetY.snapTo(releaseY)
                    backProgressAnim.snapTo(releaseRawProgress)
                    isPredictiveBackActive = false
                    emitBlurFrame(
                        stage = BlurLifecycleStage.BACK_GESTURE_CANCEL,
                        rawGestureProgress = releaseRawProgress,
                        easedGestureProgress = releaseRawProgress,
                        gestureScale = releaseScale,
                        gestureTx = releaseX,
                        gestureTy = releaseY
                    )
                    launch { backScale.animateTo(1f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                    launch { backOffsetX.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                    launch { backOffsetY.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                    launch {
                        backProgressAnim.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) {
                            emitBlurFrame(
                                stage = BlurLifecycleStage.BACK_GESTURE_RECOVERY,
                                rawGestureProgress = value,
                                easedGestureProgress = value
                            )
                        }
                    }
                }
            }

            override fun handleOnBackPressed() {
                if (hasDispatchedComplete) return
                val releaseScale = if (isPredictiveBackActive) liveBackScale else backScale.value
                val releaseX = if (isPredictiveBackActive) liveBackOffsetX else backOffsetX.value
                val releaseY = if (isPredictiveBackActive) liveBackOffsetY else backOffsetY.value
                val releaseRawProgress = if (isPredictiveBackActive) liveBackRawProgress else backProgressAnim.value
                startTouchY = Float.NaN
                backRecoveryJob?.cancel()
                backRecoveryJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    backScale.snapTo(releaseScale)
                    backOffsetX.snapTo(releaseX)
                    backOffsetY.snapTo(releaseY)
                    backProgressAnim.snapTo(releaseRawProgress)
                    isPredictiveBackActive = false
                    emitBlurFrame(
                        stage = BlurLifecycleStage.BACK_GESTURE_COMMIT,
                        rawGestureProgress = releaseRawProgress,
                        easedGestureProgress = releaseRawProgress,
                        gestureScale = releaseScale,
                        gestureTx = releaseX,
                        gestureTy = releaseY
                    )
                    if (currentOnDismissBeforeClose()) {
                        val scale = currentAnimScale
                        launch { backScale.animateTo(1f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                        launch { backOffsetX.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                        launch { backOffsetY.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) }
                        launch {
                            backProgressAnim.animateTo(0f, ContainerTransformSpec.cancelRecoverySpring(scale)) {
                                emitBlurFrame(
                                    stage = BlurLifecycleStage.BACK_GESTURE_RECOVERY,
                                    rawGestureProgress = value,
                                    easedGestureProgress = value
                                )
                            }
                        }
                    } else if (phase != ContainerTransitionPhase.DISMISSING) {
                        val onRequest = currentOnRequestDismiss
                        if (onRequest != null) {
                            onRequest()
                        } else {
                            animateToClose()
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(keepComposedUntilHostFinishes, hasDispatchedComplete, isGestureDisabled) {
        backCallback.isEnabled = if (keepComposedUntilHostFinishes) {
            true
        } else {
            !hasDispatchedComplete && !isGestureDisabled
        }
    }

    DisposableEffect(dispatcherOwner, lifecycleOwner, backCallback) {
        dispatcherOwner?.onBackPressedDispatcher?.addCallback(lifecycleOwner, backCallback)
        onDispose {
            backCallback.remove()
        }
    }

    val effectiveBlurActive = isSystemBlurEnabled || staticBackdropBitmap != null
    val scrimColor = remember(effectiveBlurActive) {
        Color.Black.copy(alpha = FolderBlurSpec.scrimAlpha(effectiveBlurActive))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(999f)
            .onGloballyPositioned { coords ->
                viewportSizePx = coords.size
            }
            .testTag(overlayTestTag)
    ) {
        // Optional static pre-captured blur backdrop for in-app transitions (faded via GPU alpha only)
        if (staticBackdropBitmap != null) {
            Image(
                bitmap = staticBackdropBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = currentEffectiveBlurProgress()
                    }
                    .blur(FolderBlurSpec.IN_APP_STATIC_BLUR_DP)
            )
        }

        // Scrim layer tracking FolderBlurSpec + back gesture progress cleanly in GPU graphicsLayer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = currentEffectiveBlurProgress()
                }
                .background(scrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (currentOnDismissBeforeClose()) return@clickable
                        if (!currentIsGestureDisabled && !hasDispatchedComplete && phase != ContainerTransitionPhase.DISMISSING) {
                            val onRequest = currentOnRequestDismiss
                            if (onRequest != null) {
                                onRequest()
                            } else {
                                animateToClose()
                            }
                        }
                    }
                )
                .testTag("${overlayTestTag}_scrim")
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (topSpacerWeight > 0f) {
                Spacer(modifier = Modifier.weight(topSpacerWeight))
            }

            // Optional floating header slot (Samsung folder title + "+" quick-add button)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = if (isPredictiveBackActive) liveBackScale else backScale.value
                        val ox = if (isPredictiveBackActive) liveBackOffsetX else backOffsetX.value
                        val oy = if (isPredictiveBackActive) liveBackOffsetY else backOffsetY.value
                        val geom = computeGeometryProgress(animProgress.value, hasExactOrCachedOrigin).coerceIn(0f, 1f)
                        val headerScale = lerp(1f, s, geom)
                        scaleX = headerScale
                        scaleY = headerScale
                        translationX = ox * geom
                        translationY = oy * geom
                    },
                contentAlignment = Alignment.Center
            ) {
                headerContent(phase == ContainerTransitionPhase.DISMISSING) {
                    if (hasMeasuredLayout) computeExpandedContentAlpha(animProgress.value) else 0f
                }
            }

            Box(
                modifier = Modifier
                    .widthIn(max = cardMaxWidth)
                    .fillMaxWidth(cardWidthFraction)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached && phase != ContainerTransitionPhase.DISMISSING) {
                            val topLeft = if (originBoundsAreScreenCoords) {
                                val loc = IntArray(2)
                                hostView.getLocationOnScreen(loc)
                                val inRoot = coords.positionInRoot()
                                Offset(loc[0] + inRoot.x, loc[1] + inRoot.y)
                            } else {
                                coords.positionInWindow()
                            }
                            cardSlotSize = coords.size
                            cardSlotCenter = Offset(
                                x = topLeft.x + coords.size.width / 2f,
                                y = topLeft.y + coords.size.height / 2f
                            )
                        }
                    }
            ) {
                val frameCalculator: () -> ContainerVisualFrame = {
                    val activeScale = if (isPredictiveBackActive) liveBackScale else backScale.value
                    val activeOffsetX = if (isPredictiveBackActive) liveBackOffsetX else backOffsetX.value
                    val activeOffsetY = if (isPredictiveBackActive) liveBackOffsetY else backOffsetY.value
                    computeContainerVisualFrame(
                        progress = animProgress.value,
                        originBounds = effectiveOrigin,
                        originCornerRadiusPx = originCornerPx,
                        expandedCenter = cardSlotCenter,
                        expandedSize = cardSlotSize,
                        expandedCornerRadiusPx = expandedCornerPx,
                        expandedElevationPx = expandedElevationPx,
                        hasOriginBounds = hasExactOrCachedOrigin,
                        hasMeasuredLayout = hasMeasuredLayout,
                        preCollapseScale = activeScale,
                        preCollapseOffset = Offset(
                            x = activeOffsetX + swipeOffsetX.value,
                            y = activeOffsetY + swipeOffsetY.value
                        ),
                        isTransitioning = isTransitioning
                    )
                }

                // Persistent container surface: bounds, position, corner radius, elevation & crossfade animate together
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(hasMeasuredLayout, currentIsGestureDisabled) {
                            if (!hasMeasuredLayout) return@pointerInput
                            val velocityTracker = VelocityTracker()
                            detectVerticalDragGestures(
                                onDragStart = {
                                    if (currentIsGestureDisabled || hasDispatchedComplete) return@detectVerticalDragGestures
                                    velocityTracker.resetTracking()
                                    isSwipingDismiss = true
                                    emitBlurFrame(
                                        stage = BlurLifecycleStage.SWIPE_DISMISS_START,
                                        rawGestureProgress = 0f,
                                        easedGestureProgress = 0f,
                                        gestureScale = 1f,
                                        gestureTx = swipeOffsetX.value,
                                        gestureTy = swipeOffsetY.value
                                    )
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    if (!isSwipingDismiss || currentIsGestureDisabled || hasDispatchedComplete) {
                                        return@detectVerticalDragGestures
                                    }
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    val newSwipeY = swipeOffsetY.value + dragAmount * 0.85f
                                    val dragFraction = (abs(newSwipeY) / dismissMaxDragPx).coerceIn(0f, 0.55f)
                                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        swipeOffsetY.snapTo(newSwipeY)
                                        animProgress.snapTo((1f - dragFraction).coerceIn(0.45f, 1f))
                                        emitBlurFrame(
                                            stage = BlurLifecycleStage.SWIPE_DISMISS_PROGRESS,
                                            rawGestureProgress = dragFraction,
                                            easedGestureProgress = dragFraction,
                                            gestureScale = 1f,
                                            gestureTx = swipeOffsetX.value,
                                            gestureTy = newSwipeY
                                        )
                                    }
                                },
                                onDragEnd = {
                                    if (!isSwipingDismiss) return@detectVerticalDragGestures
                                    isSwipingDismiss = false
                                    val vy = velocityTracker.calculateVelocity().y
                                    val curY = swipeOffsetY.value
                                    emitBlurFrame(
                                        stage = BlurLifecycleStage.SWIPE_DISMISS_END,
                                        rawGestureProgress = (abs(curY) / dismissMaxDragPx).coerceIn(0f, 0.55f),
                                        easedGestureProgress = (abs(curY) / dismissMaxDragPx).coerceIn(0f, 0.55f),
                                        gestureScale = 1f,
                                        gestureTx = swipeOffsetX.value,
                                        gestureTy = curY
                                    )
                                    val flungOut = abs(vy) > 650f && sign(vy) == sign(curY)
                                    val pastThreshold = abs(curY) > dismissDragThresholdPx
                                    if (flungOut || pastThreshold) {
                                        val collapseVel = -(abs(vy) / dismissMaxDragPx).coerceIn(0.4f, 4.5f)
                                        val onRequest = currentOnRequestDismiss
                                        if (onRequest != null) {
                                            pendingCollapseVelocity = collapseVel
                                            onRequest()
                                        } else {
                                            animateToClose(initialVelocity = collapseVel)
                                        }
                                    } else {
                                        val bounceVel = (abs(vy) / dismissMaxDragPx).coerceIn(0.2f, 3.0f)
                                        animateToOpen(preserveVelocity = bounceVel)
                                    }
                                },
                                onDragCancel = {
                                    if (!isSwipingDismiss) return@detectVerticalDragGestures
                                    isSwipingDismiss = false
                                    emitBlurFrame(stage = BlurLifecycleStage.SWIPE_DISMISS_END)
                                    animateToOpen()
                                }
                            )
                        }
                        .graphicsLayer {
                            val frame = frameCalculator()
                            currentBlurInterceptor?.recordVisualLayerRender(
                                scaleX = frame.scaleX,
                                translationY = frame.translationY
                            )
                            translationX = frame.translationX
                            translationY = frame.translationY
                            scaleX = frame.scaleX
                            scaleY = frame.scaleY
                            alpha = frame.containerAlpha
                            shadowElevation = frame.elevationPx
                            shape = CompensatedRoundedShape(frame.cornerRadiusRxPx, frame.cornerRadiusRyPx)
                            clip = true
                            compositingStrategy = if (frame.containerAlpha < 0.999f) {
                                CompositingStrategy.Offscreen
                            } else {
                                CompositingStrategy.Auto
                            }
                        }
                        .background(cardBgColor)
                        .drawWithContent {
                            drawContent()
                            val frame = frameCalculator()
                            val minScale = minOf(frame.scaleX, frame.scaleY).coerceAtLeast(0.15f)
                            val strokeW = borderWidthPx / minScale
                            val halfStroke = strokeW / 2f
                            drawRoundRect(
                                color = cardBorderColor,
                                topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke),
                                size = Size(
                                    width = (size.width - strokeW).coerceAtLeast(1f),
                                    height = (size.height - strokeW).coerceAtLeast(1f)
                                ),
                                cornerRadius = CornerRadius(
                                    frame.cornerRadiusRxPx.coerceIn(0f, size.width / 2f),
                                    frame.cornerRadiusRyPx.coerceIn(0f, size.height / 2f)
                                ),
                                style = Stroke(width = strokeW)
                            )
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                // Mid-close tap reverses smoothly back to open preserving velocity when not host-dismissed
                                if (currentOnRequestDismiss == null &&
                                    phase == ContainerTransitionPhase.DISMISSING &&
                                    !hasDispatchedComplete
                                ) {
                                    animateToOpen()
                                }
                            }
                        )
                        .testTag(cardTestTag)
                ) {
                    // Temporary opaque Surface shield active during open/collapse transitions so the
                    // underlying home-screen widget (3-dots menu & icons) cannot bleed through cardBgColor.
                    Surface(
                        color = resolvedOpaqueSurfaceColor,
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = frameCalculator().occlusionAlpha
                            },
                        content = {}
                    )

                    // Compact widget layer (deferred while OPEN; composed at start of DISMISSING before
                    // compactAlpha > 0; aspect-locked so icons & 3-dots never stretch mid-transition)
                    DeferredComposition(
                        shouldCompose = shouldComposeCompact,
                        deferOneFrame = false,
                        keepAliveAfterComposed = false
                    ) {
                        val compactWidthDp = with(density) { effectiveOrigin.width.toDp() }.coerceAtLeast(32.dp)
                        val compactHeightDp = with(density) { effectiveOrigin.height.toDp() }.coerceAtLeast(32.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .requiredSize(width = compactWidthDp, height = compactHeightDp)
                                .graphicsLayer {
                                    val frame = frameCalculator()
                                    val expW = cardSlotSize.width.toFloat().coerceAtLeast(1f)
                                    val expH = cardSlotSize.height.toFloat().coerceAtLeast(1f)
                                    val baseScaleX = expW / effectiveOrigin.width.coerceAtLeast(1f)
                                    val baseScaleY = expH / effectiveOrigin.height.coerceAtLeast(1f)
                                    val netScaleX = (frame.scaleX * baseScaleX).coerceAtLeast(0.05f)
                                    val netScaleY = (frame.scaleY * baseScaleY).coerceAtLeast(0.05f)
                                    val uniformNet = minOf(netScaleX, netScaleY)
                                    scaleX = uniformNet / frame.scaleX.coerceAtLeast(0.05f)
                                    scaleY = uniformNet / frame.scaleY.coerceAtLeast(0.05f)
                                    alpha = frame.compactAlpha
                                    compositingStrategy = if (frame.compactAlpha in 0.001f..0.999f) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            compactContent()
                        }
                    }

                    // Expanded popup layer kept composed until DISMISSED so spring collapse runs
                    // purely on GPU graphicsLayer transforms with zero mid-flight disposal jank.
                    DeferredComposition(
                        shouldCompose = shouldComposeExpanded,
                        deferOneFrame = false,
                        keepAliveAfterComposed = false
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    val frame = frameCalculator()
                                    val uniformMin = minOf(frame.scaleX, frame.scaleY).coerceAtLeast(0.15f)
                                    val aspectFixX = (uniformMin / frame.scaleX).coerceIn(0.25f, 1.0f)
                                    val aspectFixY = (uniformMin / frame.scaleY).coerceIn(0.25f, 1.0f)
                                    scaleX = aspectFixX * frame.expandedInnerScale
                                    scaleY = aspectFixY * frame.expandedInnerScale
                                    alpha = frame.expandedAlpha
                                    compositingStrategy = if (frame.expandedAlpha in 0.001f..0.999f) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                }
                        ) {
                            expandedContent(
                                phase == ContainerTransitionPhase.DISMISSING,
                                {
                                    if (!hasDispatchedComplete && phase != ContainerTransitionPhase.DISMISSING) {
                                        val onRequest = currentOnRequestDismiss
                                        if (onRequest != null) {
                                            onRequest()
                                        } else {
                                            animateToClose()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (bottomSpacerWeight > 0f) {
                Spacer(modifier = Modifier.weight(bottomSpacerWeight))
            }
        }
    }
}
