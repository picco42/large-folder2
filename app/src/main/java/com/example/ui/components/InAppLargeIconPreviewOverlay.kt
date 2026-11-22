package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddHome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.ui.motion.FolderBlurSpec
import com.example.ui.motion.SharedContainerTransformOverlay
import com.example.util.AppScanner
import com.example.util.rememberIconThemeSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * In-app Large Icon preview surface (1x1, 1x2, 2x1) sharing the exact same
 * SharedContainerTransformOverlay choreography, springs, predictive back, and swipe-to-dismiss
 * as folder popups. Home-screen Large Icon widgets continue launching directly via PendingIntent.getActivity.
 */
@Composable
fun InAppLargeIconPreviewOverlay(
    largeIcon: LargeIconEntity,
    originBounds: Rect?,
    staticBackdropBitmap: Bitmap? = null,
    returnDismissTrigger: Int = 0,
    onDismiss: () -> Unit,
    onLaunchApp: (LargeIconEntity, AndroidRect?) -> Unit,
    onPin: (LargeIconEntity) -> Unit,
    onCustomize: (LargeIconEntity) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val (compactW, compactH) = when (largeIcon.orientation) {
        LargeIconOrientations.ICON_1X2 -> 92.dp to 184.dp
        LargeIconOrientations.ICON_2X1 -> 184.dp to 92.dp
        else -> 96.dp to 96.dp
    }
    val shortDp = minOf(compactW, compactH)
    val originCornerDp = ((largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * shortDp.value)

    val compactWpx = with(density) { compactW.toPx() }.roundToInt().coerceAtLeast(160)
    val compactHpx = with(density) { compactH.toPx() }.roundToInt().coerceAtLeast(160)
    val compactInsetPx = remember(largeIcon.iconPaddingDp, compactWpx, compactHpx) {
        val shortPx = minOf(compactWpx, compactHpx).toFloat()
        (largeIcon.iconPaddingDp / 96f).coerceIn(0f, 0.30f) * shortPx
    }

    val themeSignature = rememberIconThemeSignature()
    val cachedBitmap = remember(
        largeIcon.packageName,
        largeIcon.activityName,
        compactWpx,
        compactHpx,
        compactInsetPx,
        themeSignature
    ) {
        AppScanner.getCachedLargeIconWidgetBitmap(
            context,
            largeIcon.packageName,
            largeIcon.activityName,
            compactWpx,
            compactHpx,
            0f,
            compactInsetPx
        )
    }
    val compactBitmap by produceState<Bitmap?>(
        initialValue = cachedBitmap,
        largeIcon.packageName,
        largeIcon.activityName,
        compactWpx,
        compactHpx,
        compactInsetPx,
        themeSignature
    ) {
        value = withContext(Dispatchers.IO) {
            AppScanner.renderLargeIconWidgetBitmap(
                context = context,
                packageName = largeIcon.packageName,
                activityName = largeIcon.activityName,
                widthPx = compactWpx,
                heightPx = compactHpx,
                cornerRadiusPx = 0f,
                insetPx = compactInsetPx
            )
        }
    }

    var launchOriginRect by remember { mutableStateOf<AndroidRect?>(null) }
    var pendingPostCloseAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val isBlurActive = staticBackdropBitmap != null
    val blurColors = FolderBlurSpec.resolveCardColors(
        bgStyle = largeIcon.bgStyle,
        isSystemDark = isSystemInDarkTheme(),
        isBlurEnabled = isBlurActive,
        dynamicSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dynamicOnSurface = MaterialTheme.colorScheme.onSurface,
        dynamicPrimary = MaterialTheme.colorScheme.primary
    )

    SharedContainerTransformOverlay(
        originBounds = originBounds,
        originBoundsAreScreenCoords = false,
        originCornerRadiusDp = originCornerDp,
        hasExactOrCachedOrigin = originBounds != null && !originBounds.isEmpty,
        expandedCornerRadiusDp = FolderBlurSpec.EXPANDED_CARD_CORNER_RADIUS_DP,
        expandedElevationDp = 0.dp,
        cardBgColor = blurColors.cardTint,
        cardBorderColor = blurColors.highlightEdge,
        opaqueSurfaceColor = blurColors.opaqueCollapseShield,
        cardWidthFraction = 0.88f,
        cardMaxWidth = 380.dp,
        returnDismissTrigger = returnDismissTrigger,
        isSystemBlurEnabled = isBlurActive,
        staticBackdropBitmap = staticBackdropBitmap,
        onDismissCompleted = {
            val postAction = pendingPostCloseAction
            pendingPostCloseAction = null
            onDismiss()
            postAction?.invoke()
        },
        overlayTestTag = "large_icon_preview_overlay",
        cardTestTag = "large_icon_expanded_card",
        topSpacerWeight = 0.20f,
        bottomSpacerWeight = 0.20f,
        compactContent = {
            val bmp = compactBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
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
        },
        expandedContent = { isDismissing, triggerClose ->
            val (previewW, previewH) = when (largeIcon.orientation) {
                LargeIconOrientations.ICON_1X2 -> 108.dp to 216.dp
                LargeIconOrientations.ICON_2X1 -> 216.dp to 108.dp
                else -> 124.dp to 124.dp
            }
            val heroCornerDp = ((largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * minOf(previewW, previewH).value).dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when (largeIcon.orientation) {
                            LargeIconOrientations.ICON_1X2 -> "1×2 Vertical"
                            LargeIconOrientations.ICON_2X1 -> "2×1 Horizontal"
                            else -> "1×1 Square"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    IconButton(
                        onClick = { if (!isDismissing) triggerClose() },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(48.dp)
                            .testTag("large_icon_overlay_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close preview",
                            tint = blurColors.contentColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .size(width = previewW, height = previewH)
                        .clip(RoundedCornerShape(heroCornerDp))
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                val b = coords.boundsInWindow()
                                launchOriginRect = AndroidRect(
                                    b.left.roundToInt(),
                                    b.top.roundToInt(),
                                    b.right.roundToInt(),
                                    b.bottom.roundToInt()
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = compactBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = largeIcon.appName,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x33888888))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = largeIcon.appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = blurColors.contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (!isDismissing) {
                            onLaunchApp(largeIcon, launchOriginRect)
                        }
                    },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("large_icon_overlay_open_app")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open ${largeIcon.appName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (!isDismissing) {
                                onPin(largeIcon)
                                triggerClose()
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("large_icon_overlay_pin")
                    ) {
                        Icon(Icons.Default.AddHome, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pin", maxLines = 1)
                    }

                    FilledTonalButton(
                        onClick = {
                            if (!isDismissing) {
                                pendingPostCloseAction = { onCustomize(largeIcon) }
                                triggerClose()
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("large_icon_overlay_customize")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Customize", maxLines = 1)
                    }
                }
            }
        }
    )
}
