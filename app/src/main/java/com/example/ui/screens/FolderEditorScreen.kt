package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.*
import com.example.ui.MainViewModel
import com.example.ui.components.DockedAppPickerSheet
import com.example.ui.components.WidgetPreviewCard
import com.example.util.AppInfo
import com.example.util.AppScanner
import com.example.util.rememberIconThemeSignature
import com.example.util.rememberSystemAnimationScale
import com.example.util.scaledTweenSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderEditorScreen(
    viewModel: MainViewModel,
    folderIdToEdit: Long?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val animScale = rememberSystemAnimationScale()
    val themeSignature = rememberIconThemeSignature()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val allFolders by viewModel.allFolders.collectAsState()

    val existingFolder = remember(folderIdToEdit, allFolders) {
        if (folderIdToEdit != null) allFolders.find { it.id == folderIdToEdit } else null
    }

    var name by remember(existingFolder) { mutableStateOf(existingFolder?.name ?: "New Folder") }
    var layoutType by remember(existingFolder) {
        mutableStateOf(
            when (existingFolder?.layoutType) {
                FolderLayouts.GRID_2X2 -> FolderLayouts.GRID_2X2
                FolderLayouts.ROW_1X2 -> FolderLayouts.ROW_1X2
                FolderLayouts.ROW_1X4 -> FolderLayouts.ROW_1X4
                else -> FolderLayouts.GRID_3X3
            }
        )
    }
    var bgStyle by remember(existingFolder) { mutableStateOf(existingFolder?.bgStyle ?: FolderBgStyles.DYNAMIC) }
    var cornerRadius by remember(existingFolder) { mutableFloatStateOf(existingFolder?.cornerRadiusDp?.toFloat() ?: 24f) }
    var showLabels by remember(existingFolder) { mutableStateOf(existingFolder?.showLabels ?: true) }
    var showPopupLabels by remember(existingFolder) { mutableStateOf(existingFolder?.showPopupLabels ?: false) }

    var selectedApps by remember { mutableStateOf<List<FolderAppEntity>>(emptyList()) }
    var showAddAppsSheet by remember { mutableStateOf(false) }
    var showSelectedAppsList by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var draggedListPkg by remember { mutableStateOf<String?>(null) }
    var draggedListDeltaY by remember { mutableFloatStateOf(0f) }
    val currentSelectedApps by rememberUpdatedState(selectedApps)

    LaunchedEffect(folderIdToEdit) {
        if (folderIdToEdit != null) {
            viewModel.getAppsForFolder(folderIdToEdit).collect { apps ->
                selectedApps = apps
            }
        }
    }

    BackHandler {
        onNavigateBack()
    }

    // Temporary entity for live preview
    val previewFolder = remember(name, layoutType, bgStyle, cornerRadius, showLabels, showPopupLabels) {
        FolderEntity(
            id = existingFolder?.id ?: 0L,
            name = name.ifBlank { "Folder" },
            layoutType = layoutType,
            bgStyle = bgStyle,
            cornerRadiusDp = cornerRadius.toInt(),
            showLabels = showLabels,
            showPopupLabels = showPopupLabels
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingFolder != null) "Customize Folder" else "Create Folder", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("editor_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (existingFolder != null) {
                                viewModel.updateFolder(
                                    folder = previewFolder,
                                    apps = selectedApps
                                ) {
                                    viewModel.setSelectedDashboardTab(MainViewModel.TAB_FOLDERS)
                                    onNavigateBack()
                                }
                            } else {
                                viewModel.createFolder(
                                    name = name.ifBlank { "Folder" },
                                    layoutType = layoutType,
                                    bgStyle = bgStyle,
                                    bgAlpha = 0.85f,
                                    cornerRadiusDp = cornerRadius.toInt(),
                                    showLabels = showLabels,
                                    showPopupLabels = showPopupLabels,
                                    selectedPackages = selectedApps.map { it.packageName },
                                    onDone = {
                                        viewModel.setSelectedDashboardTab(MainViewModel.TAB_FOLDERS)
                                        onNavigateBack()
                                    }
                                )
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save_folder_button")
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            userScrollEnabled = draggedListPkg == null,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(listState) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val pkgSet = currentSelectedApps.map { it.packageName }.toSet()
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                val keyStr = item.key as? String
                                keyStr != null &&
                                    keyStr in pkgSet &&
                                    startOffset.y >= item.offset &&
                                    startOffset.y <= item.offset + item.size
                            }
                            if (hit != null) {
                                draggedListPkg = hit.key as String
                                draggedListDeltaY = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val activePkg = draggedListPkg ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            draggedListDeltaY += dragAmount.y

                            val pkgSet = currentSelectedApps.map { it.packageName }.toSet()
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val currentItem = visible.firstOrNull { it.key == activePkg }
                                ?: return@detectDragGesturesAfterLongPress

                            val centerY = currentItem.offset + currentItem.size / 2f + draggedListDeltaY
                            val targetItem = visible.firstOrNull { target ->
                                val keyStr = target.key as? String
                                keyStr != null &&
                                    keyStr != activePkg &&
                                    keyStr in pkgSet &&
                                    centerY >= target.offset &&
                                    centerY <= target.offset + target.size
                            }

                            if (targetItem != null) {
                                val fromIdx = currentSelectedApps.indexOfFirst { it.packageName == activePkg }
                                val toIdx = currentSelectedApps.indexOfFirst { it.packageName == targetItem.key }
                                if (fromIdx in currentSelectedApps.indices && toIdx in currentSelectedApps.indices) {
                                    val updated = currentSelectedApps.toMutableList()
                                    val moved = updated.removeAt(fromIdx)
                                    updated.add(toIdx, moved)
                                    selectedApps = updated
                                    draggedListDeltaY -= (targetItem.offset - currentItem.offset).toFloat()
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                        onDragEnd = {
                            draggedListPkg = null
                            draggedListDeltaY = 0f
                        },
                        onDragCancel = {
                            draggedListPkg = null
                            draggedListDeltaY = 0f
                        }
                    )
                }
                .testTag("folder_editor_content"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Live Preview Card
            item(key = "header_preview") {
                Text(
                    text = "Live Home Screen Preview",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                WidgetPreviewCard(
                    folder = previewFolder,
                    apps = selectedApps,
                    onReorderApps = { reordered ->
                        selectedApps = reordered
                    }
                )
            }

            // App Selection Card directly below preview (matching LargeIconEditorScreen)
            item(key = "folder_apps_selector") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Folder Apps",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedApps.isNotEmpty()) {
                            TextButton(
                                onClick = { showSelectedAppsList = !showSelectedAppsList },
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("toggle_reorder_apps_button"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (showSelectedAppsList) "Hide list" else "Reorder (${selectedApps.size})",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showSelectedAppsList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        onClick = { showAddAppsSheet = true },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .testTag("add_apps_button")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            if (selectedApps.isNotEmpty()) {
                                val mosaicApps = selectedApps.take(4)
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        for (row in 0..1) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                for (col in 0..1) {
                                                    val idx = row * 2 + col
                                                    val appItem = mosaicApps.getOrNull(idx)
                                                    if (appItem != null) {
                                                        val cached = remember(appItem.packageName, appItem.activityName, themeSignature) {
                                                            AppScanner.getCachedAppIconBitmap(context, appItem.packageName, appItem.activityName, 48)
                                                        }
                                                        val bmp by produceState(cached, appItem.packageName, appItem.activityName, themeSignature) {
                                                            value = AppScanner.getCachedAppIconBitmap(context, appItem.packageName, appItem.activityName, 48)
                                                                ?: withContext(Dispatchers.IO) {
                                                                    AppScanner.getAppIconBitmap(context, appItem.packageName, appItem.activityName, 48)
                                                                }
                                                        }
                                                        val loaded = bmp
                                                        if (loaded != null) {
                                                            Image(
                                                                bitmap = loaded.asImageBitmap(),
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        } else {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(16.dp)
                                                                    .clip(RoundedCornerShape(4.dp))
                                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                            )
                                                        }
                                                    } else {
                                                        Spacer(modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (selectedApps.isEmpty()) {
                                        "Choose Apps"
                                    } else {
                                        "${selectedApps.size} app${if (selectedApps.size == 1) "" else "s"} selected"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val subtitleText = if (selectedApps.isEmpty()) {
                                    "Tap to pick apps for this folder"
                                } else {
                                    val previewNames = selectedApps.take(3).joinToString(", ") { it.appName }
                                    val extraCount = selectedApps.size - 3
                                    if (extraCount > 0) "$previewNames +$extraCount more" else previewNames
                                }
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showSelectedAppsList && selectedApps.isNotEmpty()) {
                itemsIndexed(selectedApps, key = { _, app -> app.packageName }) { index, app ->
                    val isDraggingRow = draggedListPkg == app.packageName
                    val rowScale by animateFloatAsState(
                        targetValue = if (isDraggingRow) 1.03f else 1f,
                        animationSpec = scaledTweenSpec(140, animScale),
                        label = "editorRowDragScale"
                    )
                    val cachedBitmap = remember(app.packageName, app.activityName, themeSignature) {
                        AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 96)
                    }
                    val iconBitmap by produceState(cachedBitmap, app.packageName, app.activityName, themeSignature) {
                        value = AppScanner.getCachedAppIconBitmap(context, app.packageName, app.activityName, 96)
                            ?: withContext(Dispatchers.IO) {
                                AppScanner.getAppIconBitmap(context, app.packageName, app.activityName, 96)
                            }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!isDraggingRow) Modifier.animateItem() else Modifier)
                            .zIndex(if (isDraggingRow) 10f else 0f)
                            .graphicsLayer {
                                if (isDraggingRow) {
                                    translationY = draggedListDeltaY
                                }
                                scaleX = rowScale
                                scaleY = rowScale
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDraggingRow) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = if (isDraggingRow) 8.dp else 1.dp
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Long-press and drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )

                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(20.dp)
                            )

                            val loadedBitmap = iconBitmap
                            if (loadedBitmap != null) {
                                Image(
                                    bitmap = loadedBitmap.asImageBitmap(),
                                    contentDescription = app.appName,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Text(
                                text = app.appName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    selectedApps = selectedApps.filterNot { it.packageName == app.packageName }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Folder Name
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("editor_folder_name_input"),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )
            }

            // Layout Style (Home screen widget display only)
            item {
                Text(
                    text = "Home Screen Widget Grid",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Controls widget preview slots only — add as many apps as you want inside the folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LayoutChoiceChip(
                        title = "3×3 Grid",
                        isSelected = layoutType == FolderLayouts.GRID_3X3,
                        onClick = { layoutType = FolderLayouts.GRID_3X3 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_chip_3x3")
                    )
                    LayoutChoiceChip(
                        title = "2×2 Grid",
                        isSelected = layoutType == FolderLayouts.GRID_2X2,
                        onClick = { layoutType = FolderLayouts.GRID_2X2 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_chip_2x2")
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LayoutChoiceChip(
                        title = "1×4 Row",
                        isSelected = layoutType == FolderLayouts.ROW_1X4,
                        onClick = { layoutType = FolderLayouts.ROW_1X4 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_chip_1x4")
                    )
                    LayoutChoiceChip(
                        title = "1×2 Row",
                        isSelected = layoutType == FolderLayouts.ROW_1X2,
                        onClick = { layoutType = FolderLayouts.ROW_1X2 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("layout_chip_1x2")
                    )
                }
            }

            // Folder Background Style
            item {
                Text(
                    text = "Folder Color & Style",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StyleChoiceChip(
                        label = "System",
                        isSelected = bgStyle == FolderBgStyles.DYNAMIC,
                        onClick = { bgStyle = FolderBgStyles.DYNAMIC },
                        modifier = Modifier.weight(1f)
                    )
                    StyleChoiceChip(
                        label = "Dark",
                        isSelected = bgStyle == FolderBgStyles.FROSTED_DARK,
                        onClick = { bgStyle = FolderBgStyles.FROSTED_DARK },
                        modifier = Modifier.weight(1f)
                    )
                    StyleChoiceChip(
                        label = "Light",
                        isSelected = bgStyle == FolderBgStyles.FROSTED_LIGHT,
                        onClick = { bgStyle = FolderBgStyles.FROSTED_LIGHT },
                        modifier = Modifier.weight(1f)
                    )
                    StyleChoiceChip(
                        label = "Clear",
                        isSelected = bgStyle == FolderBgStyles.CLEAR,
                        onClick = { bgStyle = FolderBgStyles.CLEAR },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Corner Radius & Labels
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Corner Rounding", fontWeight = FontWeight.Medium)
                            Text("${cornerRadius.toInt()} dp", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = cornerRadius,
                            onValueChange = { cornerRadius = it },
                            valueRange = 12f..36f,
                            steps = 5
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Show Folder Name", fontWeight = FontWeight.Medium)
                                Text("Display folder title underneath widget card", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = showLabels,
                                onCheckedChange = { showLabels = it }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("App Names in Pop-up View", fontWeight = FontWeight.Medium)
                                Text("Show labels below icons in expanded folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = showPopupLabels,
                                onCheckedChange = { showPopupLabels = it }
                            )
                        }
                    }
                }
            }
        }
    }

        DockedAppPickerSheet(
            visible = showAddAppsSheet,
            onDismiss = { showAddAppsSheet = false },
            sheetTestTag = "folder_add_apps_sheet"
        ) {
            AddAppsSheetContent(
                installedApps = installedApps,
                isLoading = isLoadingApps,
                alreadySelectedPackages = selectedApps.map { it.packageName }.toSet(),
                onAddApp = { appInfo ->
                    if (selectedApps.none { it.packageName == appInfo.packageName }) {
                        val newApp = FolderAppEntity(
                            folderId = existingFolder?.id ?: 0L,
                            packageName = appInfo.packageName,
                            activityName = appInfo.activityName,
                            appName = appInfo.appName,
                            positionIndex = selectedApps.size
                        )
                        selectedApps = selectedApps + newApp
                    }
                },
                onRemoveApp = { pkg ->
                    selectedApps = selectedApps.filterNot { it.packageName == pkg }
                },
                onDismiss = { showAddAppsSheet = false }
            )
        }
    }
}

@Composable
private fun LayoutChoiceChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StyleChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddAppsSheetContent(
    installedApps: List<AppInfo>,
    isLoading: Boolean,
    alreadySelectedPackages: Set<String>,
    onAddApp: (AppInfo) -> Unit,
    onRemoveApp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberIconThemeSignature()
    var query by remember { mutableStateOf("") }
    val filtered = remember(installedApps, query) {
        if (query.isBlank()) installedApps
        else installedApps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
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
                    text = "Select Apps",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${alreadySelectedPackages.size} selected • Add as many as you want",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onDismiss,
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
                    val isSelected = alreadySelectedPackages.contains(app.packageName)
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
                        onClick = {
                            if (isSelected) onRemoveApp(app.packageName)
                            else onAddApp(app)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
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
                                onCheckedChange = { checked ->
                                    if (checked) onAddApp(app) else onRemoveApp(app.packageName)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
