package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.util.AppScanner
import com.example.util.rememberIconThemeSignature
import com.example.util.rememberSystemAnimationScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LargeIconPreviewCard(
    largeIcon: LargeIconEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onCardPositioned: ((Rect) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val animScale = rememberSystemAnimationScale()
    val themeSignature = rememberIconThemeSignature()

    val (targetWidth, targetHeight) = when (largeIcon.orientation) {
        LargeIconOrientations.ICON_1X2 -> 92.dp to 184.dp
        LargeIconOrientations.ICON_2X1 -> 184.dp to 92.dp
        else -> 96.dp to 96.dp
    }

    val sizeAnimSpec = remember(animScale) {
        if (animScale > 0.05f) {
            spring<Dp>(
                dampingRatio = 0.88f,
                stiffness = (440f / (animScale * animScale)).coerceIn(30f, 4000f)
            )
        } else {
            snap()
        }
    }

    val animWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = sizeAnimSpec,
        label = "largeIconWidth"
    )
    val animHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = sizeAnimSpec,
        label = "largeIconHeight"
    )
    val shortDp = minOf(targetWidth, targetHeight)
    val targetCornerDp = ((largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * shortDp.value).dp
    val animCorner by animateDpAsState(
        targetValue = targetCornerDp,
        animationSpec = sizeAnimSpec,
        label = "largeIconCorner"
    )
    val shape = RoundedCornerShape(animCorner)

    val targetWpx = remember(targetWidth, density) {
        with(density) { targetWidth.toPx() }.roundToInt().coerceAtLeast(160)
    }
    val targetHpx = remember(targetHeight, density) {
        with(density) { targetHeight.toPx() }.roundToInt().coerceAtLeast(160)
    }
    val insetPx = remember(largeIcon.iconPaddingDp, targetWpx, targetHpx) {
        val shortPx = minOf(targetWpx, targetHpx).toFloat()
        (largeIcon.iconPaddingDp / 96f).coerceIn(0f, 0.30f) * shortPx
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = animWidth, height = animHeight)
                .clip(shape)
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        onCardPositioned?.invoke(coords.boundsInWindow())
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (largeIcon.packageName.isNotEmpty()) {
                val cached = remember(
                    largeIcon.packageName,
                    largeIcon.activityName,
                    targetWpx,
                    targetHpx,
                    insetPx,
                    themeSignature
                ) {
                    AppScanner.getCachedLargeIconWidgetBitmap(
                        context = context,
                        packageName = largeIcon.packageName,
                        activityName = largeIcon.activityName,
                        widthPx = targetWpx,
                        heightPx = targetHpx,
                        cornerRadiusPx = 0f,
                        insetPx = insetPx
                    )
                }
                val renderedBitmap by produceState(
                    cached,
                    largeIcon.packageName,
                    largeIcon.activityName,
                    targetWpx,
                    targetHpx,
                    insetPx,
                    themeSignature
                ) {
                    value = withContext(Dispatchers.IO) {
                        AppScanner.renderLargeIconWidgetBitmap(
                            context = context,
                            packageName = largeIcon.packageName,
                            activityName = largeIcon.activityName,
                            widthPx = targetWpx,
                            heightPx = targetHpx,
                            cornerRadiusPx = 0f,
                            insetPx = insetPx
                        )
                    }
                }
                val loadedBitmap = renderedBitmap
                if (loadedBitmap != null) {
                    Image(
                        bitmap = loadedBitmap.asImageBitmap(),
                        contentDescription = largeIcon.appName,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = "Choose app",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}

