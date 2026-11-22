package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import com.example.data.FolderAppEntity
import com.example.data.FolderBgStyles
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.util.AppScanner
import com.example.util.IconThemeSignature
import com.example.util.rememberIconThemeSignature
import com.example.util.rememberSystemAnimationScale
import com.example.util.scaledTweenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WidgetPreviewCard(
    folder: FolderEntity,
    apps: List<FolderAppEntity>,
    modifier: Modifier = Modifier,
    onHeaderClick: () -> Unit = {},
    onAppClick: (FolderAppEntity) -> Unit = {},
    onReorderApps: ((List<FolderAppEntity>) -> Unit)? = null,
    onCardPositioned: ((Rect) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current
    val animScale = rememberSystemAnimationScale()
    val themeSignature = rememberIconThemeSignature()
    val cornerRadius = folder.cornerRadiusDp.dp
    val systemIconSizePx = remember(configuration.densityDpi, configuration.screenLayout) {
        AppScanner.getSystemHomeIconSizePx(context)
    }
    val systemIconSizeDp = with(density) { systemIconSizePx.toDp() }

    // Drag-to-reorder state for live widget preview
    var localApps by remember(apps) { mutableStateOf(apps) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val slotCoordsMap = remember { mutableStateMapOf<Int, LayoutCoordinates>() }

    val currentLocalApps by rememberUpdatedState(localApps)
    val currentSourceApps by rememberUpdatedState(apps)
    val currentOnReorder by rememberUpdatedState(onReorderApps)
    val currentLayoutType by rememberUpdatedState(folder.layoutType)

    // Frosted glass & Material You dynamic color scheme
    val bgColor: Color
    val borderColor: Color
    val iconTintColor: Color
    val overflowCircleColor: Color
    when (folder.bgStyle) {
        FolderBgStyles.FROSTED_LIGHT -> {
            bgColor = Color(0xE6F1F5F9)
            borderColor = Color(0x80FFFFFF)
            iconTintColor = Color(0xFF0F172A)
            overflowCircleColor = Color(0x1A0F172A)
        }
        FolderBgStyles.DYNAMIC -> {
            bgColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            iconTintColor = MaterialTheme.colorScheme.onSurface
            overflowCircleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        }
        FolderBgStyles.CLEAR -> {
            bgColor = Color(0x800F172A)
            borderColor = Color(0x2EFFFFFF)
            iconTintColor = Color.White
            overflowCircleColor = Color(0x26FFFFFF)
        }
        else -> {
            bgColor = Color(0xD9161E2E)
            borderColor = Color(0x40FFFFFF)
            iconTintColor = Color.White
            overflowCircleColor = Color(0x26FFFFFF)
        }
    }

    fun slotRectInContainer(slotIdx: Int): Rect? {
        val parent = containerCoords ?: return null
        val child = slotCoordsMap[slotIdx] ?: return null
        if (!parent.isAttached || !child.isAttached) return null
        val topLeft = parent.localPositionOf(child, Offset.Zero)
        return Rect(topLeft, child.size.toSize())
    }

    fun editableAppCount(totalApps: Int, layoutType: String): Int {
        return when (layoutType) {
            FolderLayouts.GRID_2X2 -> if (totalApps > 4) 3 else totalApps
            FolderLayouts.ROW_1X2 -> if (totalApps > 2) 1 else totalApps
            FolderLayouts.ROW_1X4 -> if (totalApps > 4) 3 else totalApps
            else -> if (totalApps > 9) 8 else totalApps
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val (targetWidth, targetHeight) = when (folder.layoutType) {
                FolderLayouts.GRID_2X2 -> {
                    val side = maxOf(maxWidth * 0.66f, systemIconSizeDp * 2 + 44.dp).coerceAtMost(maxWidth)
                    side to side
                }
                FolderLayouts.ROW_1X2 -> {
                    val rowWidth = maxOf(maxWidth * 0.56f, systemIconSizeDp * 2 + 48.dp).coerceAtMost(maxWidth)
                    val rowHeight = (systemIconSizeDp + 32.dp).coerceAtLeast(80.dp)
                    rowWidth to rowHeight
                }
                FolderLayouts.ROW_1X4 -> {
                    val rowHeight = (systemIconSizeDp + 32.dp).coerceAtLeast(80.dp)
                    maxWidth to rowHeight
                }
                else -> maxWidth to maxWidth
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
                label = "previewCardWidth"
            )
            val animHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = sizeAnimSpec,
                label = "previewCardHeight"
            )
            val animCornerRadius by animateDpAsState(
                targetValue = cornerRadius,
                animationSpec = sizeAnimSpec,
                label = "previewCardCorner"
            )

            // Squircle Card Container (morphs cleanly between 3x3, 2x2, 1x4, and 1x2)
            Box(
                modifier = Modifier
                    .size(width = animWidth, height = animHeight)
                    .clip(RoundedCornerShape(animCornerRadius))
                    .background(bgColor)
                    .border(1.2.dp, borderColor, RoundedCornerShape(animCornerRadius))
                    .onGloballyPositioned { coords ->
                        containerCoords = coords
                        if (coords.isAttached) {
                            onCardPositioned?.invoke(coords.boundsInWindow())
                        }
                    }
                .then(
                    if (onReorderApps != null) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val maxCount = editableAppCount(currentLocalApps.size, currentLayoutType)
                                    val hitSlot = (0 until maxCount).firstOrNull { idx ->
                                        slotRectInContainer(idx)?.contains(startOffset) == true
                                    }
                                    if (hitSlot != null) {
                                        draggedIndex = hitSlot
                                        dragDelta = Offset.Zero
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    val activeIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    dragDelta += dragAmount

                                    val activeRect = slotRectInContainer(activeIdx)
                                        ?: return@detectDragGesturesAfterLongPress
                                    val activeCenter = activeRect.center + dragDelta
                                    val maxCount = editableAppCount(currentLocalApps.size, currentLayoutType)

                                    val targetIdx = (0 until maxCount).firstOrNull { idx ->
                                        idx != activeIdx &&
                                            slotRectInContainer(idx)?.contains(activeCenter) == true
                                    }

                                    if (targetIdx != null) {
                                        val targetRect = slotRectInContainer(targetIdx)
                                        if (targetRect != null && activeIdx in currentLocalApps.indices && targetIdx in currentLocalApps.indices) {
                                            val updated = currentLocalApps.toMutableList()
                                            val moved = updated.removeAt(activeIdx)
                                            updated.add(targetIdx, moved)
                                            localApps = updated
                                            dragDelta -= (targetRect.center - activeRect.center)
                                            draggedIndex = targetIdx
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (draggedIndex != null) {
                                        draggedIndex = null
                                        dragDelta = Offset.Zero
                                        currentOnReorder?.invoke(currentLocalApps)
                                    }
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragDelta = Offset.Zero
                                    localApps = currentSourceApps
                                }
                            )
                        }
                    } else {
                        Modifier.clickable(onClick = onHeaderClick)
                    }
                )
                .padding(10.dp)
            ) {
                when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> PreviewGrid2x2(
                        apps = localApps,
                        systemIconSizePx = systemIconSizePx,
                        systemIconSizeDp = systemIconSizeDp,
                        themeSignature = themeSignature,
                        animScale = animScale,
                        iconTintColor = iconTintColor,
                        overflowCircleColor = overflowCircleColor,
                        draggedIndex = draggedIndex,
                        dragDelta = dragDelta,
                        onSlotPositioned = { idx, coords -> slotCoordsMap[idx] = coords },
                        onMoreClick = onHeaderClick,
                        onAppClick = { app -> if (draggedIndex == null) onAppClick(app) }
                    )
                    FolderLayouts.ROW_1X2 -> PreviewRowGrid(
                        columns = 2,
                        apps = localApps,
                        systemIconSizePx = systemIconSizePx,
                        systemIconSizeDp = systemIconSizeDp,
                        themeSignature = themeSignature,
                        animScale = animScale,
                        iconTintColor = iconTintColor,
                        overflowCircleColor = overflowCircleColor,
                        draggedIndex = draggedIndex,
                        dragDelta = dragDelta,
                        onSlotPositioned = { idx, coords -> slotCoordsMap[idx] = coords },
                        onMoreClick = onHeaderClick,
                        onAppClick = { app -> if (draggedIndex == null) onAppClick(app) }
                    )
                    FolderLayouts.ROW_1X4 -> PreviewRowGrid(
                        columns = 4,
                        apps = localApps,
                        systemIconSizePx = systemIconSizePx,
                        systemIconSizeDp = systemIconSizeDp,
                        themeSignature = themeSignature,
                        animScale = animScale,
                        iconTintColor = iconTintColor,
                        overflowCircleColor = overflowCircleColor,
                        draggedIndex = draggedIndex,
                        dragDelta = dragDelta,
                        onSlotPositioned = { idx, coords -> slotCoordsMap[idx] = coords },
                        onMoreClick = onHeaderClick,
                        onAppClick = { app -> if (draggedIndex == null) onAppClick(app) }
                    )
                    else -> PreviewGrid3x3(
                        apps = localApps,
                        themeSignature = themeSignature,
                        animScale = animScale,
                        iconTintColor = iconTintColor,
                        overflowCircleColor = overflowCircleColor,
                        draggedIndex = draggedIndex,
                        dragDelta = dragDelta,
                        onSlotPositioned = { idx, coords -> slotCoordsMap[idx] = coords },
                        onMoreClick = onHeaderClick,
                        onAppClick = { app -> if (draggedIndex == null) onAppClick(app) }
                    )
                }
            }
        }

        if (folder.showLabels) {
            Spacer(modifier = Modifier.height(6.dp))

            // Folder Label Centered Under the Widget Card (Native Home Screen Style)
            Text(
                text = folder.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onHeaderClick)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PreviewGrid3x3(
    apps: List<FolderAppEntity>,
    themeSignature: IconThemeSignature,
    animScale: Float,
    iconTintColor: Color,
    overflowCircleColor: Color,
    draggedIndex: Int?,
    dragDelta: Offset,
    onSlotPositioned: (Int, LayoutCoordinates) -> Unit,
    onMoreClick: () -> Unit,
    onAppClick: (FolderAppEntity) -> Unit
) {
    val context = LocalContext.current
    val hasOverflow = apps.size > 9

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        for (row in 0 until 3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val isSlot9 = index == 8

                    if (isSlot9 && hasOverflow) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(onClick = onMoreClick),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(overflowCircleColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreHoriz,
                                    contentDescription = "More apps",
                                    tint = iconTintColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    } else {
                        val app = apps.getOrNull(index)
                        val isDragging = draggedIndex == index
                        val scale by animateFloatAsState(
                            targetValue = if (isDragging) 1.18f else 1f,
                            animationSpec = scaledTweenSpec(140, animScale),
                            label = "previewDragScale3x3"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .onGloballyPositioned { onSlotPositioned(index, it) }
                                .zIndex(if (isDragging) 10f else 0f)
                                .graphicsLayer {
                                    if (isDragging) {
                                        translationX = dragDelta.x
                                        translationY = dragDelta.y
                                    }
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .then(
                                    if (app != null) Modifier.clickable { onAppClick(app) }
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (app != null) {
                                val cached = remember(app.packageName, app.activityName, themeSignature) {
                                    AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 120)
                                }
                                val bitmap by produceState(cached, app.packageName, app.activityName, themeSignature) {
                                    value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 120)
                                        ?: withContext(Dispatchers.IO) {
                                            AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, 120)
                                        }
                                }
                                val loadedBitmap = bitmap
                                if (loadedBitmap != null) {
                                    Image(
                                        bitmap = loadedBitmap.asImageBitmap(),
                                        contentDescription = app.appName,
                                        modifier = Modifier.size(44.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0x33888888))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewGrid2x2(
    apps: List<FolderAppEntity>,
    systemIconSizePx: Int,
    systemIconSizeDp: androidx.compose.ui.unit.Dp,
    themeSignature: IconThemeSignature,
    animScale: Float,
    iconTintColor: Color,
    overflowCircleColor: Color,
    draggedIndex: Int?,
    dragDelta: Offset,
    onSlotPositioned: (Int, LayoutCoordinates) -> Unit,
    onMoreClick: () -> Unit,
    onAppClick: (FolderAppEntity) -> Unit
) {
    val context = LocalContext.current
    val hasOverflow = apps.size > 4

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxCellDim = minOf(maxWidth / 2f, maxHeight / 2f)
        val buttonSize = minOf(systemIconSizeDp, (maxCellDim - 4.dp).coerceAtLeast(24.dp))
        val overflowIconSize = buttonSize * 0.52f
        val fallbackCorner = (buttonSize * 0.22f).coerceAtLeast(10.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (row in 0 until 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (col in 0 until 2) {
                        val index = row * 2 + col
                        val isSlot4 = index == 3

                        if (isSlot4 && hasOverflow) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(onClick = onMoreClick),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(buttonSize)
                                        .clip(CircleShape)
                                        .background(overflowCircleColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreHoriz,
                                        contentDescription = "More apps",
                                        tint = iconTintColor,
                                        modifier = Modifier.size(overflowIconSize)
                                    )
                                }
                            }
                        } else {
                            val app = apps.getOrNull(index)
                            val isDragging = draggedIndex == index
                            val scale by animateFloatAsState(
                                targetValue = if (isDragging) 1.18f else 1f,
                                animationSpec = scaledTweenSpec(140, animScale),
                                label = "previewDragScale2x2"
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { onSlotPositioned(index, it) }
                                    .zIndex(if (isDragging) 10f else 0f)
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationX = dragDelta.x
                                            translationY = dragDelta.y
                                        }
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .then(
                                        if (app != null) Modifier.clickable { onAppClick(app) }
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (app != null) {
                                    val cached = remember(app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                        AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                    }
                                    val bitmap by produceState(cached, app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                        value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            ?: withContext(Dispatchers.IO) {
                                                AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            }
                                    }
                                    val loadedBitmap = bitmap
                                    if (loadedBitmap != null) {
                                        Image(
                                            bitmap = loadedBitmap.asImageBitmap(),
                                            contentDescription = app.appName,
                                            modifier = Modifier.size(buttonSize)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(buttonSize)
                                                .clip(RoundedCornerShape(fallbackCorner))
                                                .background(Color(0x33888888))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewRowGrid(
    columns: Int,
    apps: List<FolderAppEntity>,
    systemIconSizePx: Int,
    systemIconSizeDp: androidx.compose.ui.unit.Dp,
    themeSignature: IconThemeSignature,
    animScale: Float,
    iconTintColor: Color,
    overflowCircleColor: Color,
    draggedIndex: Int?,
    dragDelta: Offset,
    onSlotPositioned: (Int, LayoutCoordinates) -> Unit,
    onMoreClick: () -> Unit,
    onAppClick: (FolderAppEntity) -> Unit
) {
    val context = LocalContext.current
    val hasOverflow = apps.size > columns

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxCellDim = minOf(maxWidth / columns.toFloat(), maxHeight)
        val buttonSize = minOf(systemIconSizeDp, (maxCellDim - 4.dp).coerceAtLeast(24.dp))
        val overflowIconSize = buttonSize * 0.52f
        val fallbackCorner = (buttonSize * 0.22f).coerceAtLeast(10.dp)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (col in 0 until columns) {
                val isLastSlot = col == columns - 1

                if (isLastSlot && hasOverflow) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(onClick = onMoreClick),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(buttonSize)
                                .clip(CircleShape)
                                .background(overflowCircleColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More apps",
                                tint = iconTintColor,
                                modifier = Modifier.size(overflowIconSize)
                            )
                        }
                    }
                } else {
                    val app = apps.getOrNull(col)
                    val isDragging = draggedIndex == col
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.18f else 1f,
                        animationSpec = scaledTweenSpec(140, animScale),
                        label = "previewDragScaleRow"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { onSlotPositioned(col, it) }
                            .zIndex(if (isDragging) 10f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationX = dragDelta.x
                                    translationY = dragDelta.y
                                }
                                scaleX = scale
                                scaleY = scale
                            }
                            .then(
                                if (app != null) Modifier.clickable { onAppClick(app) }
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (app != null) {
                            val cached = remember(app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                            }
                            val bitmap by produceState(cached, app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                    ?: withContext(Dispatchers.IO) {
                                        AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                    }
                            }
                            val loadedBitmap = bitmap
                            if (loadedBitmap != null) {
                                Image(
                                    bitmap = loadedBitmap.asImageBitmap(),
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(buttonSize)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(buttonSize)
                                        .clip(RoundedCornerShape(fallbackCorner))
                                        .background(Color(0x33888888))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
