package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Rect as AndroidRect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.FolderAppEntity
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.ui.motion.FolderBlurSpec
import com.example.ui.motion.SharedContainerTransformOverlay
import com.example.ui.motion.WindowBlurLifecycleInterceptor
import com.example.util.AppInfo
import com.example.util.AppScanner
import com.example.util.IconThemeSignature
import com.example.util.rememberIconThemeSignature
import com.example.util.rememberSystemAnimationScale
import com.example.util.scaledTweenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungFolderPopupOverlay(
    folder: FolderEntity,
    apps: List<FolderAppEntity>,
    onDismiss: () -> Unit,
    onLaunchApp: (FolderAppEntity, AndroidRect?) -> Unit,
    onReorderApps: (List<FolderAppEntity>) -> Unit = {},
    onRenameFolder: (String) -> Unit = {},
    sourceBounds: Rect? = null,
    hasExactOrCachedOrigin: Boolean = sourceBounds != null && !sourceBounds.isEmpty,
    sourceBoundsAreScreenCoords: Boolean = false,
    externalDismissTrigger: Int = 0,
    returnDismissTrigger: Int = 0,
    keepAliveOnAppLaunch: Boolean = true,
    isSystemBlurEnabled: Boolean = false,
    staticBackdropBitmap: Bitmap? = null,
    onBlurProgressChanged: ((Float) -> Unit)? = null,
    blurLifecycleInterceptor: WindowBlurLifecycleInterceptor? = null,
    onRequestDismiss: (() -> Unit)? = null,
    keepComposedUntilHostFinishes: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptic = LocalHapticFeedback.current

    val systemIconSizePx = remember(configuration.densityDpi, configuration.screenLayout) {
        AppScanner.getSystemHomeIconSizePx(context)
    }
    val systemIconSizeDp = with(density) { systemIconSizePx.toDp() }
    val displayIconDp = minOf(systemIconSizeDp, 60.dp).coerceAtLeast(52.dp)
    val iconCornerRadius = (displayIconDp * 0.25f).coerceAtLeast(12.dp)
    val themeSignature = rememberIconThemeSignature()

    // Inline folder title rename state
    var isEditingTitle by remember { mutableStateOf(false) }
    var titleField by remember(folder.name) {
        mutableStateOf(TextFieldValue(folder.name, TextRange(folder.name.length)))
    }
    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) {
            titleField = TextFieldValue(folder.name, TextRange(folder.name.length))
            delay(60)
            titleFocusRequester.requestFocus()
        }
    }

    fun commitRename() {
        val trimmed = titleField.text.trim()
        if (trimmed.isNotEmpty() && trimmed != folder.name) {
            onRenameFolder(trimmed)
        }
        isEditingTitle = false
    }

    // Quick-add "+" bottom sheet state (lazily scans apps on IO only when opened)
    var showQuickAddSheet by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoadingInstalled by remember { mutableStateOf(false) }

    LaunchedEffect(showQuickAddSheet) {
        if (showQuickAddSheet && installedApps.isEmpty()) {
            isLoadingInstalled = true
            installedApps = withContext(Dispatchers.IO) {
                AppScanner.getInstalledApps(context)
            }
            isLoadingInstalled = false
        }
    }

    // Drag-to-reorder & pagination state (Samsung 4x4 grid, 16 apps per page)
    var localApps by remember(apps) { mutableStateOf(apps) }
    var draggedPkg by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    val currentApps by rememberUpdatedState(localApps)
    val currentSourceApps by rememberUpdatedState(apps)
    val currentOnReorder by rememberUpdatedState(onReorderApps)
    val animScale = rememberSystemAnimationScale()

    val isSystemDark = isSystemInDarkTheme()
    val blurColors = FolderBlurSpec.resolveCardColors(
        bgStyle = folder.bgStyle,
        isSystemDark = isSystemDark,
        isBlurEnabled = isSystemBlurEnabled || staticBackdropBitmap != null,
        dynamicSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dynamicOnSurface = MaterialTheme.colorScheme.onSurface,
        dynamicPrimary = MaterialTheme.colorScheme.primary
    )
    val cardBg = blurColors.cardTint
    val cardBorder = blurColors.highlightEdge
    val contentColor = blurColors.contentColor
    val overflowCircleColor = blurColors.overflowCircleColor
    val opaqueSurfaceColor = blurColors.opaqueCollapseShield

    // Ensure compact snapshot and popup icons are warm in LruCache off main thread
    LaunchedEffect(localApps, systemIconSizePx, themeSignature) {
        val pairs = localApps.map { it.packageName to it.activityName }
        AppScanner.prewarmAppIcons(context, pairs, systemIconSizePx)
    }

    val titleSlidePx = with(density) { 14.dp.toPx() }
    val pages = remember(localApps) {
        if (localApps.isEmpty()) listOf(emptyList()) else localApps.chunked(16)
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    SharedContainerTransformOverlay(
        originBounds = sourceBounds,
        originBoundsAreScreenCoords = sourceBoundsAreScreenCoords,
        originCornerRadiusDp = folder.cornerRadiusDp.toFloat(),
        hasExactOrCachedOrigin = hasExactOrCachedOrigin,
        expandedCornerRadiusDp = FolderBlurSpec.EXPANDED_CARD_CORNER_RADIUS_DP,
        expandedElevationDp = 0.dp,
        cardBgColor = cardBg,
        cardBorderColor = cardBorder,
        opaqueSurfaceColor = opaqueSurfaceColor,
        cardWidthFraction = 0.91f,
        cardMaxWidth = 420.dp,
        externalDismissTrigger = externalDismissTrigger,
        returnDismissTrigger = returnDismissTrigger,
        isGestureDisabled = draggedPkg != null,
        isSystemBlurEnabled = isSystemBlurEnabled,
        staticBackdropBitmap = staticBackdropBitmap,
        onBlurProgressChanged = onBlurProgressChanged,
        blurLifecycleInterceptor = blurLifecycleInterceptor,
        onDismissRequestedBeforeClose = {
            when {
                showQuickAddSheet -> {
                    showQuickAddSheet = false
                    true
                }
                isEditingTitle -> {
                    commitRename()
                    true
                }
                else -> false
            }
        },
        onRequestDismiss = onRequestDismiss,
        keepComposedUntilHostFinishes = keepComposedUntilHostFinishes,
        onDismissCompleted = onDismiss,
        overlayTestTag = "samsung_popup_surface_layer",
        cardTestTag = "samsung_expanded_card",
        topSpacerWeight = 0.14f,
        bottomSpacerWeight = 0.08f,
        headerContent = { isDismissing, expandedAlphaProvider ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // High Centered Folder Title (32.sp SemiBold, fades out early on collapse in graphicsLayer only)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .graphicsLayer {
                            val expandedAlpha = expandedAlphaProvider()
                            alpha = expandedAlpha
                            translationY = titleSlidePx * (1f - expandedAlpha)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isEditingTitle) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.16f))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                                .padding(start = 20.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = titleField,
                                onValueChange = { titleField = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(Color.White),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Done,
                                    capitalization = KeyboardCapitalization.Words
                                ),
                                keyboardActions = KeyboardActions(onDone = { commitRename() }),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(titleFocusRequester)
                                    .testTag("popup_rename_input")
                            )
                            IconButton(
                                onClick = { commitRename() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("popup_rename_confirm")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Save folder name",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { isEditingTitle = true }
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag("popup_folder_title"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = folder.name,
                                fontSize = 32.sp,
                                lineHeight = 38.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick-Add "+" Button Row aligned above Card's top-right edge
                Row(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(0.91f)
                        .padding(end = 6.dp, bottom = 4.dp)
                        .graphicsLayer {
                            val expandedAlpha = expandedAlphaProvider()
                            alpha = expandedAlpha
                            translationY = titleSlidePx * 0.6f * (1f - expandedAlpha)
                        },
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (!isDismissing) showQuickAddSheet = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("popup_quick_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add or remove apps",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        },
        compactContent = {
            CompactFolderWidgetSnapshot(
                layoutType = folder.layoutType,
                apps = localApps,
                systemIconSizePx = systemIconSizePx,
                systemIconSizeDp = systemIconSizeDp,
                themeSignature = themeSignature,
                iconTintColor = contentColor,
                overflowCircleColor = overflowCircleColor
            )
        },
        expandedContent = { isDismissing, triggerClose ->
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val viewportHeight = maxHeight.takeIf { it > 0.dp } ?: 640.dp
                val rowSlotHeight = displayIconDp + 6.dp + 18.dp
                val idealFourRowHeight = (rowSlotHeight * 4) + (18.dp * 3) + 24.dp + 14.dp
                val maxGridHeight = (viewportHeight * 0.68f).coerceIn(280.dp, idealFourRowHeight)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (localApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No apps in this folder",
                                    color = contentColor.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                FilledTonalButton(onClick = { showQuickAddSheet = true }) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Apps")
                                }
                            }
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = draggedPkg == null && !isDismissing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(maxGridHeight)
                                .testTag("popup_horizontal_pager")
                        ) { pageIndex ->
                            val pageApps = pages.getOrElse(pageIndex) { emptyList() }
                            val pageBaseIndex = pageIndex * 16
                            val gridState = rememberLazyGridState()

                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(4),
                                userScrollEnabled = false,
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 24.dp,
                                    bottom = 14.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(gridState, pageBaseIndex) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { startOffset ->
                                                if (isDismissing) return@detectDragGesturesAfterLongPress
                                                val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                                    startOffset.x >= item.offset.x &&
                                                        startOffset.x <= item.offset.x + item.size.width &&
                                                        startOffset.y >= item.offset.y &&
                                                        startOffset.y <= item.offset.y + item.size.height
                                                }
                                                if (hit != null) {
                                                    draggedPkg = hit.key as? String
                                                    dragDelta = Offset.Zero
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                val activePkg = draggedPkg ?: return@detectDragGesturesAfterLongPress
                                                change.consume()
                                                dragDelta += dragAmount

                                                val visibleItems = gridState.layoutInfo.visibleItemsInfo
                                                val currentItem = visibleItems.firstOrNull { it.key == activePkg }
                                                    ?: return@detectDragGesturesAfterLongPress

                                                val centerX = currentItem.offset.x + currentItem.size.width / 2f + dragDelta.x
                                                val centerY = currentItem.offset.y + currentItem.size.height / 2f + dragDelta.y

                                                val targetItem = visibleItems.firstOrNull { target ->
                                                    target.key != activePkg &&
                                                        centerX >= target.offset.x &&
                                                        centerX <= target.offset.x + target.size.width &&
                                                        centerY >= target.offset.y &&
                                                        centerY <= target.offset.y + target.size.height
                                                }

                                                if (targetItem != null) {
                                                    val fromGlobal = pageBaseIndex + currentItem.index
                                                    val toGlobal = pageBaseIndex + targetItem.index
                                                    if (fromGlobal in currentApps.indices && toGlobal in currentApps.indices) {
                                                        val updated = currentApps.toMutableList()
                                                        val moved = updated.removeAt(fromGlobal)
                                                        updated.add(toGlobal, moved)
                                                        localApps = updated
                                                        dragDelta -= Offset(
                                                            x = (targetItem.offset.x - currentItem.offset.x).toFloat(),
                                                            y = (targetItem.offset.y - currentItem.offset.y).toFloat()
                                                        )
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                if (draggedPkg != null) {
                                                    draggedPkg = null
                                                    dragDelta = Offset.Zero
                                                    currentOnReorder(currentApps)
                                                }
                                            },
                                            onDragCancel = {
                                                draggedPkg = null
                                                dragDelta = Offset.Zero
                                                localApps = currentSourceApps
                                            }
                                        )
                                    }
                            ) {
                                itemsIndexed(pageApps, key = { _, app -> app.packageName }) { _, app ->
                                    val isDragging = draggedPkg == app.packageName
                                    val dragScale by animateFloatAsState(
                                        targetValue = if (isDragging) 1.16f else 1.0f,
                                        animationSpec = scaledTweenSpec(140, animScale),
                                        label = "dragScale"
                                    )
                                    val cachedBitmap = remember(app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                        AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                    }
                                    val iconBitmap by produceState(
                                        initialValue = cachedBitmap,
                                        app.packageName,
                                        app.activityName,
                                        systemIconSizePx,
                                        themeSignature
                                    ) {
                                        value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            ?: withContext(Dispatchers.IO) {
                                                AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            }
                                    }
                                    var iconWindowRect by remember { mutableStateOf<AndroidRect?>(null) }

                                    Column(
                                        modifier = Modifier
                                            .then(if (!isDragging && !isDismissing) Modifier.animateItem() else Modifier)
                                            .zIndex(if (isDragging) 10f else 0f)
                                            .graphicsLayer {
                                                if (isDragging) {
                                                    translationX = dragDelta.x
                                                    translationY = dragDelta.y
                                                }
                                                scaleX = dragScale
                                                scaleY = dragScale
                                            }
                                            .clip(RoundedCornerShape(16.dp))
                                            .then(
                                                if (isDragging) {
                                                    Modifier.background(contentColor.copy(alpha = 0.12f))
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .clickable(enabled = draggedPkg == null && !isDismissing) {
                                                if (isEditingTitle) {
                                                    commitRename()
                                                }
                                                // Strategy A: keep popup visible at 1f underneath target app while open;
                                                // returning via Back reveals popup cleanly and collapses into widget.
                                                onLaunchApp(app, iconWindowRect)
                                                if (!keepAliveOnAppLaunch) {
                                                    triggerClose()
                                                }
                                            }
                                            .padding(vertical = 2.dp, horizontal = 2.dp)
                                            .testTag("popup_app_item_${app.packageName}"),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(displayIconDp)
                                                .onGloballyPositioned { coords ->
                                                    if (coords.isAttached) {
                                                        val b = coords.boundsInWindow()
                                                        iconWindowRect = AndroidRect(
                                                            b.left.roundToInt(),
                                                            b.top.roundToInt(),
                                                            b.right.roundToInt(),
                                                            b.bottom.roundToInt()
                                                        )
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val loadedBitmap = iconBitmap
                                            if (loadedBitmap != null) {
                                                Image(
                                                    bitmap = loadedBitmap.asImageBitmap(),
                                                    contentDescription = app.appName,
                                                    modifier = Modifier.size(displayIconDp)
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(displayIconDp)
                                                        .clip(RoundedCornerShape(iconCornerRadius))
                                                        .background(Color(0x33888888))
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = app.appName,
                                            color = contentColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Page Indicator Dots when > 16 apps
                        if (pages.size > 1) {
                            Row(
                                modifier = Modifier
                                    .padding(top = 8.dp, bottom = 14.dp)
                                    .testTag("popup_page_dots"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(pages.size) { index ->
                                    val isSelected = pagerState.currentPage == index
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) contentColor
                                                else contentColor.copy(alpha = 0.35f)
                                            )
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    )

    DockedAppPickerSheet(
        visible = showQuickAddSheet,
        onDismiss = { showQuickAddSheet = false },
        sheetTestTag = "popup_add_apps_sheet"
    ) {
        QuickAddAppsSheet(
            installedApps = installedApps,
            isLoading = isLoadingInstalled,
            selectedPackages = localApps.map { it.packageName }.toSet(),
            onToggleApp = { appInfo ->
                val latest = currentApps
                val updated = if (latest.any { it.packageName == appInfo.packageName }) {
                    latest.filterNot { it.packageName == appInfo.packageName }
                } else {
                    latest + FolderAppEntity(
                        folderId = folder.id,
                        packageName = appInfo.packageName,
                        activityName = appInfo.activityName,
                        appName = appInfo.appName,
                        positionIndex = latest.size
                    )
                }
                localApps = updated
                onReorderApps(updated)
            },
            onDone = { showQuickAddSheet = false }
        )
    }
}

/**
 * Lightweight, interaction-free compact widget representation rendered inside the collapsing
 * container during final collapse frames (p = 0.36 -> 0.0) so the last frame aligns visually
 * with the home-screen widget across 3x3, 2x2, 1x4, and 1x2 layouts.
 */
@Composable
private fun CompactFolderWidgetSnapshot(
    layoutType: String,
    apps: List<FolderAppEntity>,
    systemIconSizePx: Int,
    systemIconSizeDp: androidx.compose.ui.unit.Dp,
    themeSignature: IconThemeSignature,
    iconTintColor: Color,
    overflowCircleColor: Color
) {
    val context = LocalContext.current
    val (cols, rows) = when (layoutType) {
        FolderLayouts.GRID_2X2 -> 2 to 2
        FolderLayouts.ROW_1X2 -> 2 to 1
        FolderLayouts.ROW_1X4 -> 4 to 1
        else -> 3 to 3
    }
    val maxSlots = cols * rows
    val hasOverflow = apps.size > maxSlots

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .testTag("compact_folder_widget_snapshot")
    ) {
        val cellW = maxWidth / cols.toFloat()
        val cellH = maxHeight / rows.toFloat()
        val iconSize = minOf(systemIconSizeDp, minOf(cellW, cellH) * 0.84f).coerceAtLeast(18.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            for (r in 0 until rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        val isOverflowSlot = hasOverflow && idx == maxSlots - 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isOverflowSlot) {
                                Box(
                                    modifier = Modifier
                                        .size(iconSize)
                                        .clip(CircleShape)
                                        .background(overflowCircleColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreHoriz,
                                        contentDescription = null,
                                        tint = iconTintColor,
                                        modifier = Modifier.size(iconSize * 0.54f)
                                    )
                                }
                            } else {
                                val app = apps.getOrNull(idx)
                                if (app != null) {
                                    val cached = remember(app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                        AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                    }
                                    val bmp by produceState(cached, app.packageName, app.activityName, systemIconSizePx, themeSignature) {
                                        value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            ?: withContext(Dispatchers.IO) {
                                                AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, systemIconSizePx)
                                            }
                                    }
                                    val loaded = bmp
                                    if (loaded != null) {
                                        Image(
                                            bitmap = loaded.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(iconSize)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(iconSize)
                                                .clip(RoundedCornerShape((iconSize * 0.22f).coerceAtLeast(6.dp)))
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
private fun QuickAddAppsSheet(
    installedApps: List<AppInfo>,
    isLoading: Boolean,
    selectedPackages: Set<String>,
    onToggleApp: (AppInfo) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberIconThemeSignature()
    var query by remember { mutableStateOf("") }
    val filtered = remember(installedApps, query) {
        if (query.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Folder Apps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${selectedPackages.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onDone,
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Done")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search installed apps...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading && installedApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = app.packageName in selectedPackages
                    val cachedBitmap = remember(app.packageName, app.activityName, themeSignature) {
                        AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 96)
                    }
                    val iconBitmap by produceState(cachedBitmap, app.packageName, app.activityName, themeSignature) {
                        value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 96)
                            ?: withContext(Dispatchers.IO) {
                                AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, 96)
                            }
                    }

                    Surface(
                        onClick = { onToggleApp(app) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val loadedBitmap = iconBitmap
                            if (loadedBitmap != null) {
                                Image(
                                    bitmap = loadedBitmap.asImageBitmap(),
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }

                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )

                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleApp(app) }
                            )
                        }
                    }
                }
            }
        }
    }
}
