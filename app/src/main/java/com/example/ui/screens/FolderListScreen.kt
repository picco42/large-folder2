package com.example.ui.screens

import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.FolderAppEntity
import com.example.data.FolderEntity
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.ui.MainViewModel
import com.example.ui.components.InAppLargeIconPreviewOverlay
import com.example.ui.components.LargeIconPreviewCard
import com.example.ui.components.SamsungFolderPopupOverlay
import com.example.ui.components.WidgetPreviewCard
import com.example.util.AppScanner
import com.example.util.IconStyle
import com.example.util.IconStyleManager
import com.example.util.WidgetBoundsCache
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    viewModel: MainViewModel,
    onCreateFolder: () -> Unit,
    onEditFolder: (FolderEntity) -> Unit,
    onCreateLargeIcon: () -> Unit,
    onEditLargeIcon: (LargeIconEntity) -> Unit
) {
    val context = LocalContext.current
    val hostView: View = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val folders by viewModel.allFolders.collectAsState()
    val largeIcons by viewModel.allLargeIcons.collectAsState()
    val selectedTab by viewModel.selectedDashboardTab.collectAsState()

    var folderToDelete by remember { mutableStateOf<FolderEntity?>(null) }
    var folderPendingPin by remember { mutableStateOf<FolderEntity?>(null) }
    var largeIconToDelete by remember { mutableStateOf<LargeIconEntity?>(null) }
    var showNewChoiceSheet by remember { mutableStateOf(false) }
    var showIconStyleDialog by remember { mutableStateOf(false) }
    val showIconStyleSetting = remember { IconStyleManager.isThemedIconsSupported() }
    val currentIconStyle by IconStyleManager.iconStyleFlow.collectAsState()
    LaunchedEffect(context) {
        IconStyleManager.getIconStyle(context)
    }

    // Expanded Folder & Large Icon in-app overlay states
    var expandedPopupFolderId by remember { mutableStateOf<Long?>(null) }
    var awaitingFolderAppReturn by remember { mutableStateOf(false) }
    var folderReturnDismissTrigger by remember { mutableIntStateOf(0) }
    var expandedLargeIconId by remember { mutableStateOf<Long?>(null) }
    var awaitingIconAppReturn by remember { mutableStateOf(false) }
    var iconReturnDismissTrigger by remember { mutableIntStateOf(0) }
    var staticOverlayBackdrop by remember { mutableStateOf<Bitmap?>(null) }
    val previewBoundsMap = remember { mutableStateMapOf<Long, Rect>() }
    val iconBoundsMap = remember { mutableStateMapOf<Long, Rect>() }

    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { 2 })
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (viewModel.selectedDashboardTab.value != page) {
                viewModel.setSelectedDashboardTab(page)
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(selectedTab)
        }
    }

    DisposableEffect(lifecycleOwner, expandedPopupFolderId, expandedLargeIconId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (expandedPopupFolderId != null && awaitingFolderAppReturn) {
                    awaitingFolderAppReturn = false
                    folderReturnDismissTrigger++
                }
                if (expandedLargeIconId != null && awaitingIconAppReturn) {
                    awaitingIconAppReturn = false
                    iconReturnDismissTrigger++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = if (pagerState.currentPage == 0) "Folders" else "Icons",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (showIconStyleSetting) {
                            IconButton(
                                onClick = { showIconStyleDialog = true },
                                modifier = Modifier.testTag("icon_style_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.icon_style_title)
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
                PrimaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            viewModel.setSelectedDashboardTab(MainViewModel.TAB_FOLDERS)
                            coroutineScope.launch { pagerState.animateScrollToPage(MainViewModel.TAB_FOLDERS) }
                        },
                        text = { Text("Folders", fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("tab_folders")
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            viewModel.setSelectedDashboardTab(MainViewModel.TAB_ICONS)
                            coroutineScope.launch { pagerState.animateScrollToPage(MainViewModel.TAB_ICONS) }
                        },
                        text = { Text("Icons", fontWeight = FontWeight.SemiBold) },
                        icon = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("tab_icons")
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewChoiceSheet = true },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.testTag("fab_new")
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { pageIndex ->
            if (pageIndex == 0) {
                if (folders.isEmpty()) {
                    DashboardEmptyCard(
                        icon = Icons.Default.CreateNewFolder,
                        title = "No folders yet",
                        description = "Pick as many apps as you like and pin your folder straight to your home screen.",
                        ctaLabel = "Create Folder",
                        ctaTestTag = "empty_create_folder_button",
                        onCtaClick = onCreateFolder
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("folder_list"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            val apps by viewModel.getAppsForFolder(folder.id).collectAsState(initial = emptyList())

                            FolderCardItem(
                                folder = folder,
                                apps = apps,
                                onPreviewPositioned = { rect -> previewBoundsMap[folder.id] = rect },
                                onOpenFolder = {
                                    staticOverlayBackdrop = WidgetBoundsCache.captureDownscaledBackdropBitmap(hostView)
                                    expandedPopupFolderId = folder.id
                                },
                                onEdit = { onEditFolder(folder) },
                                onDelete = { folderToDelete = folder },
                                onPin = {
                                    folderPendingPin = folder
                                }
                            )
                        }
                    }
                }
            } else {
                if (largeIcons.isEmpty()) {
                    DashboardEmptyCard(
                        icon = Icons.Default.Apps,
                        title = "No large icons yet",
                        description = "Create customizable 1×1, 1×2, or 2×1 app icon widgets with automatic Color Palette support.",
                        ctaLabel = "Create Large Icon",
                        ctaTestTag = "empty_create_icon_button",
                        onCtaClick = onCreateLargeIcon
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("large_icon_list"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(largeIcons, key = { it.id }) { largeIcon ->
                            LargeIconCardItem(
                                largeIcon = largeIcon,
                                onPreviewPositioned = { rect -> iconBoundsMap[largeIcon.id] = rect },
                                onLaunchPreview = {
                                    staticOverlayBackdrop = WidgetBoundsCache.captureDownscaledBackdropBitmap(hostView)
                                    expandedLargeIconId = largeIcon.id
                                },
                                onEdit = { onEditLargeIcon(largeIcon) },
                                onDelete = { largeIconToDelete = largeIcon },
                                onPin = {
                                    val success = viewModel.requestPinLargeIcon(context, largeIcon)
                                    if (success) {
                                        Toast.makeText(context, "Adding '${largeIcon.appName}' widget to home screen", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Drag 'Large Icon' from your widget drawer", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // In-App Folder Pin Preview Dialog before system requestPinAppWidget
        folderPendingPin?.let { pendingFolder ->
            val pendingApps by viewModel.getAppsForFolder(pendingFolder.id).collectAsState(initial = emptyList())
            AlertDialog(
                onDismissRequest = { folderPendingPin = null },
                modifier = Modifier.testTag("pin_folder_preview_dialog"),
                title = {
                    Text(
                        text = "Add to Home screen",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF2D1B4E),
                                            Color(0xFF1A2238)
                                        )
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WidgetPreviewCard(
                                folder = pendingFolder,
                                apps = pendingApps
                            )
                        }
                        Text(
                            text = "Widget preview (${pendingApps.size} apps)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetFolder = pendingFolder
                            val targetApps = pendingApps
                            folderPendingPin = null
                            val success = viewModel.requestPinWidget(context, targetFolder, targetApps)
                            if (success) {
                                Toast.makeText(context, "Adding '${targetFolder.name}' widget to home screen", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Drag 'Large Folder' from your widget drawer", Toast.LENGTH_LONG).show()
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("pin_preview_confirm_button")
                    ) {
                        Text("Add Widget")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { folderPendingPin = null },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("pin_preview_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Folder Confirmation Dialog
        folderToDelete?.let { folder ->
            AlertDialog(
                onDismissRequest = { folderToDelete = null },
                title = { Text("Delete '${folder.name}'?") },
                text = { Text("This will remove the folder and any associated home screen widgets.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteFolder(folder)
                            folderToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { folderToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Delete Large Icon Confirmation Dialog
        largeIconToDelete?.let { icon ->
            AlertDialog(
                onDismissRequest = { largeIconToDelete = null },
                title = { Text("Delete '${icon.appName}'?") },
                text = { Text("This will remove the large icon and any associated home screen widgets.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteLargeIcon(icon)
                            largeIconToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { largeIconToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Icon Style Setting Dialog (API 33+ only; hidden on lower APIs)
        if (showIconStyleSetting && showIconStyleDialog) {
            AlertDialog(
                onDismissRequest = { showIconStyleDialog = false },
                modifier = Modifier.testTag("icon_style_dialog"),
                title = {
                    Text(
                        text = stringResource(R.string.icon_style_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.icon_style_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        IconStyleOptionRow(
                            title = stringResource(R.string.icon_style_themed),
                            subtitle = "Use device color palette for monochrome icons",
                            isSelected = currentIconStyle == IconStyle.THEMED,
                            onClick = {
                                IconStyleManager.setIconStyle(context, IconStyle.THEMED)
                            },
                            modifier = Modifier.testTag("icon_style_option_themed")
                        )
                        IconStyleOptionRow(
                            title = stringResource(R.string.icon_style_original),
                            subtitle = "Always show standard app icon artwork",
                            isSelected = currentIconStyle == IconStyle.ORIGINAL,
                            onClick = {
                                IconStyleManager.setIconStyle(context, IconStyle.ORIGINAL)
                            },
                            modifier = Modifier.testTag("icon_style_option_original")
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showIconStyleDialog = false },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Done")
                    }
                }
            )
        }
    }

    // "New" Creation Category Bottom Sheet (New Folder vs New Icon)
    if (showNewChoiceSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChoiceSheet = false },
            modifier = Modifier.testTag("new_widget_choice_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "New widget",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    onClick = {
                        showNewChoiceSheet = false
                        onCreateFolder()
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .testTag("choice_new_folder")
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Folder",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Group apps in a 3×3, 2×2, 1×4, or 1×2 expandable folder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    onClick = {
                        showNewChoiceSheet = false
                        onCreateLargeIcon()
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .testTag("choice_new_icon")
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "New Icon",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Large 1×1, 1×2, or 2×1 app icon with Color Palette theming",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // In-app expanded folder view (uses SharedContainerTransformOverlay + static blur backdrop)
    val popupFolder = expandedPopupFolderId?.let { id -> folders.find { it.id == id } }
    if (popupFolder != null) {
        val popupApps by viewModel.getAppsForFolder(popupFolder.id).collectAsState(initial = emptyList())
        val folderOrigin = previewBoundsMap[popupFolder.id]
        SamsungFolderPopupOverlay(
            folder = popupFolder,
            apps = popupApps,
            sourceBounds = folderOrigin,
            hasExactOrCachedOrigin = folderOrigin != null && !folderOrigin.isEmpty,
            returnDismissTrigger = folderReturnDismissTrigger,
            keepAliveOnAppLaunch = true,
            staticBackdropBitmap = staticOverlayBackdrop,
            onDismiss = {
                awaitingFolderAppReturn = false
                folderReturnDismissTrigger = 0
                staticOverlayBackdrop = null
                expandedPopupFolderId = null
            },
            onLaunchApp = { app, iconRect ->
                val launchIntent = AppScanner.getLaunchIntent(context, app.packageName, app.activityName)
                if (launchIntent != null) {
                    awaitingFolderAppReturn = true
                    val options = if (iconRect != null && !iconRect.isEmpty && context is Activity) {
                        ActivityOptions.makeClipRevealAnimation(
                            context.window.decorView,
                            iconRect.left,
                            iconRect.top,
                            iconRect.width().coerceAtLeast(1),
                            iconRect.height().coerceAtLeast(1)
                        ).toBundle()
                    } else null
                    context.startActivity(launchIntent, options)
                } else {
                    awaitingFolderAppReturn = false
                    folderReturnDismissTrigger = 0
                    staticOverlayBackdrop = null
                    expandedPopupFolderId = null
                }
            },
            onReorderApps = { reordered ->
                viewModel.reorderFolderApps(popupFolder.id, reordered)
            },
            onRenameFolder = { newName ->
                viewModel.renameFolder(popupFolder.id, newName)
            }
        )
    }

    // In-app expanded Large Icon preview (1x1, 1x2, 2x1) sharing exact same container-transform controller
    val popupLargeIcon = expandedLargeIconId?.let { id -> largeIcons.find { it.id == id } }
    if (popupLargeIcon != null) {
        InAppLargeIconPreviewOverlay(
            largeIcon = popupLargeIcon,
            originBounds = iconBoundsMap[popupLargeIcon.id],
            staticBackdropBitmap = staticOverlayBackdrop,
            returnDismissTrigger = iconReturnDismissTrigger,
            onDismiss = {
                awaitingIconAppReturn = false
                iconReturnDismissTrigger = 0
                staticOverlayBackdrop = null
                expandedLargeIconId = null
            },
            onLaunchApp = { icon, iconRect ->
                val launchIntent = AppScanner.getLaunchIntent(context, icon.packageName, icon.activityName)
                if (launchIntent != null) {
                    awaitingIconAppReturn = true
                    val options = if (iconRect != null && !iconRect.isEmpty && context is Activity) {
                        ActivityOptions.makeClipRevealAnimation(
                            context.window.decorView,
                            iconRect.left,
                            iconRect.top,
                            iconRect.width().coerceAtLeast(1),
                            iconRect.height().coerceAtLeast(1)
                        ).toBundle()
                    } else null
                    context.startActivity(launchIntent, options)
                } else {
                    awaitingIconAppReturn = false
                    Toast.makeText(context, "App not installed", Toast.LENGTH_SHORT).show()
                }
            },
            onPin = { icon ->
                val success = viewModel.requestPinLargeIcon(context, icon)
                if (success) {
                    Toast.makeText(context, "Adding '${icon.appName}' widget to home screen", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Drag 'Large Icon' from your widget drawer", Toast.LENGTH_LONG).show()
                }
            },
            onCustomize = { icon ->
                onEditLargeIcon(icon)
            }
        )
    }
}

@Composable
private fun IconStyleOptionRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = null
            )
        }
    }
}

@Composable
private fun DashboardEmptyCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    ctaLabel: String,
    ctaTestTag: String,
    onCtaClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onCtaClick,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    modifier = Modifier.testTag(ctaTestTag)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(ctaLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FolderCardItem(
    folder: FolderEntity,
    apps: List<FolderAppEntity>,
    onPreviewPositioned: (Rect) -> Unit,
    onOpenFolder: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_card_${folder.id}"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Single clean Title + Delete action
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Borderless Card Preview (measures exact card bounds for 3x3, 2x2, 1x4, and 1x2 morphs)
            WidgetPreviewCard(
                folder = folder.copy(showLabels = false),
                apps = apps,
                onHeaderClick = onOpenFolder,
                onAppClick = { onOpenFolder() },
                onCardPositioned = onPreviewPositioned
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Expressive Pill Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPin,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("pin_widget_button_${folder.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddHome,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Pin Widget",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = onEdit,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("edit_folder_button_${folder.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Customize",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LargeIconCardItem(
    largeIcon: LargeIconEntity,
    onPreviewPositioned: (Rect) -> Unit,
    onLaunchPreview: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("large_icon_card_${largeIcon.id}"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = largeIcon.appName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    val sizeLabel = when (largeIcon.orientation) {
                        LargeIconOrientations.ICON_1X2 -> "1×2 Vertical"
                        LargeIconOrientations.ICON_2X1 -> "2×1 Horizontal"
                        else -> "1×1 Square"
                    }
                    Text(
                        text = sizeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete large icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LargeIconPreviewCard(
                largeIcon = largeIcon,
                onClick = onLaunchPreview,
                onCardPositioned = onPreviewPositioned
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPin,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("pin_icon_widget_button_${largeIcon.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddHome,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Pin Widget",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = onEdit,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("edit_icon_button_${largeIcon.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Customize",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
