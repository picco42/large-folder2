package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FolderBgStyles
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.ui.MainViewModel
import com.example.ui.components.DockedAppPickerSheet
import com.example.ui.components.LargeIconPreviewCard
import com.example.util.AppInfo
import com.example.util.AppScanner
import com.example.util.rememberIconThemeSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LargeIconEditorScreen(
    viewModel: MainViewModel,
    largeIconIdToEdit: Long?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberIconThemeSignature()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val allLargeIcons by viewModel.allLargeIcons.collectAsState()

    val existingIcon = remember(largeIconIdToEdit, allLargeIcons) {
        if (largeIconIdToEdit != null) allLargeIcons.find { it.id == largeIconIdToEdit } else null
    }

    var selectedAppName by remember(existingIcon) { mutableStateOf(existingIcon?.appName ?: "") }
    var selectedPkg by remember(existingIcon) { mutableStateOf(existingIcon?.packageName ?: "") }
    var selectedActivity by remember(existingIcon) { mutableStateOf(existingIcon?.activityName ?: "") }
    var orientation by remember(existingIcon) {
        mutableStateOf(existingIcon?.orientation ?: LargeIconOrientations.ICON_1X1)
    }
    var bgStyle by remember(existingIcon) {
        mutableStateOf(existingIcon?.bgStyle ?: FolderBgStyles.DYNAMIC)
    }
    var cornerRadius by remember(existingIcon) {
        mutableFloatStateOf(existingIcon?.cornerRadiusDp?.toFloat() ?: 24f)
    }
    var iconPadding by remember(existingIcon) {
        mutableFloatStateOf(existingIcon?.iconPaddingDp?.toFloat() ?: 0f)
    }

    var showAppPickerSheet by remember { mutableStateOf(false) }

    BackHandler {
        onNavigateBack()
    }

    val previewEntity = remember(
        existingIcon,
        selectedAppName,
        selectedPkg,
        selectedActivity,
        orientation,
        bgStyle,
        cornerRadius,
        iconPadding
    ) {
        LargeIconEntity(
            id = existingIcon?.id ?: 0L,
            appName = selectedAppName.ifBlank { "Large Icon" },
            packageName = selectedPkg,
            activityName = selectedActivity,
            orientation = orientation,
            bgStyle = bgStyle,
            cornerRadiusDp = cornerRadius.roundToInt(),
            iconPaddingDp = iconPadding.roundToInt(),
            showLabel = false
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (existingIcon != null) "Customize Icon" else "Create Large Icon",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("editor_icon_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (selectedPkg.isBlank()) {
                                Toast.makeText(context, "Select an app first", Toast.LENGTH_SHORT).show()
                                showAppPickerSheet = true
                            } else if (existingIcon != null) {
                                viewModel.updateLargeIcon(
                                    previewEntity.copy(
                                        id = existingIcon.id,
                                        sortOrder = existingIcon.sortOrder,
                                        createdTimestamp = existingIcon.createdTimestamp
                                    )
                                ) {
                                    viewModel.setSelectedDashboardTab(MainViewModel.TAB_ICONS)
                                    onNavigateBack()
                                }
                            } else {
                                viewModel.createLargeIcon(previewEntity) {
                                    viewModel.setSelectedDashboardTab(MainViewModel.TAB_ICONS)
                                    onNavigateBack()
                                }
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save_icon_button")
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("large_icon_editor_content"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text(
                    text = "Live Home Screen Preview",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LargeIconPreviewCard(
                        largeIcon = previewEntity,
                        onClick = { showAppPickerSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp)
                    )
                }
            }

            // App Picker Selector
            item {
                Text(
                    text = "Target App",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = { showAppPickerSheet = true },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .testTag("pick_icon_app_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (selectedPkg.isNotBlank()) {
                            val cached = remember(selectedPkg, selectedActivity, themeSignature) {
                                AppScanner.getCachedAppIconBitmap(context, selectedPkg, selectedActivity, 96)
                            }
                            val bmp by produceState(cached, selectedPkg, selectedActivity, themeSignature) {
                                value = AppScanner.getCachedAppIconBitmap(context, selectedPkg, selectedActivity, 96)
                                    ?: withContext(Dispatchers.IO) {
                                        AppScanner.getAppIconBitmap(context, selectedPkg, selectedActivity, 96)
                                    }
                            }
                            val loaded = bmp
                            if (loaded != null) {
                                Image(
                                    bitmap = loaded.asImageBitmap(),
                                    contentDescription = selectedAppName,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(36.dp))
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
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
                                text = selectedAppName.ifBlank { "Choose an App" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (selectedPkg.isBlank()) "Tap to pick which app this icon opens" else "Tap to change app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Orientation / Size Selector (1x1, 1x2, 2x1)
            item {
                Text(
                    text = "Size & Orientation",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OrientationOptionCard(
                        label = "1×1",
                        subLabel = "Square",
                        previewWidth = 26.dp,
                        previewHeight = 26.dp,
                        isSelected = orientation == LargeIconOrientations.ICON_1X1,
                        onClick = { orientation = LargeIconOrientations.ICON_1X1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("icon_size_1x1")
                    )
                    OrientationOptionCard(
                        label = "1×2",
                        subLabel = "Vertical",
                        previewWidth = 18.dp,
                        previewHeight = 32.dp,
                        isSelected = orientation == LargeIconOrientations.ICON_1X2,
                        onClick = { orientation = LargeIconOrientations.ICON_1X2 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("icon_size_1x2")
                    )
                    OrientationOptionCard(
                        label = "2×1",
                        subLabel = "Horizontal",
                        previewWidth = 34.dp,
                        previewHeight = 18.dp,
                        isSelected = orientation == LargeIconOrientations.ICON_2X1,
                        onClick = { orientation = LargeIconOrientations.ICON_2X1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("icon_size_2x1")
                    )
                }
            }

            // Corner Rounding & Foreground Inset
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Corner Rounding", fontWeight = FontWeight.Medium)
                            Text("${cornerRadius.roundToInt()} dp", style = MaterialTheme.typography.bodySmall)
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
                            Text("Icon Inset", fontWeight = FontWeight.Medium)
                            Text("${iconPadding.roundToInt()} dp", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = iconPadding,
                            onValueChange = { iconPadding = it },
                            valueRange = 0f..20f,
                            steps = 3
                        )
                    }
                }
            }
        }
    }

        DockedAppPickerSheet(
            visible = showAppPickerSheet,
            onDismiss = { showAppPickerSheet = false },
            sheetTestTag = "icon_select_app_sheet"
        ) {
            SelectSingleAppSheet(
                installedApps = installedApps,
                isLoading = isLoadingApps,
                selectedPackage = selectedPkg,
                onSelectApp = { appInfo ->
                    selectedAppName = appInfo.appName
                    selectedPkg = appInfo.packageName
                    selectedActivity = appInfo.activityName
                    showAppPickerSheet = false
                },
                onDismiss = { showAppPickerSheet = false }
            )
        }
    }
}

@Composable
private fun OrientationOptionCard(
    label: String,
    subLabel: String,
    previewWidth: androidx.compose.ui.unit.Dp,
    previewHeight: androidx.compose.ui.unit.Dp,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 84.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.height(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = previewWidth, height = previewHeight)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun SelectSingleAppSheet(
    installedApps: List<AppInfo>,
    isLoading: Boolean,
    selectedPackage: String,
    onSelectApp: (AppInfo) -> Unit,
    onDismiss: () -> Unit
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
            Text(
                text = "Select App",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = onDismiss,
                shape = CircleShape,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancel")
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
                    val isSelected = app.packageName == selectedPackage
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
                        onClick = { onSelectApp(app) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
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

                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectApp(app) }
                            )
                        }
                    }
                }
            }
        }
    }
}
