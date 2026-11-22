package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.FolderWidgetApplication
import com.example.MainActivity
import com.example.popup.PopupSessionCoordinator
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.ui.theme.FolderWidgetTheme
import com.example.util.AppScanner
import com.example.util.rememberIconThemeSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderWidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val providerClassName = AppWidgetManager.getInstance(this)
            ?.getAppWidgetInfo(appWidgetId)
            ?.provider
            ?.className
            .orEmpty()
        val isLargeIconWidget = providerClassName.contains("LargeIconWidgetProvider")

        val repository = (application as FolderWidgetApplication).repository

        setContent {
            FolderWidgetTheme {
                val folders by repository.allFolders.collectAsState(initial = emptyList())
                val largeIcons by repository.allLargeIcons.collectAsState(initial = emptyList())

                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (isLargeIconWidget) "Select Large Icon" else "Select Folder for Widget",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp)
                    ) {
                        if (isLargeIconWidget) {
                            if (largeIcons.isEmpty()) {
                                EmptyConfigureState(
                                    icon = Icons.Default.Apps,
                                    title = "No Large Icons created yet",
                                    subtitle = "Create a Large Icon in the app first, then select it here for your widget.",
                                    ctaLabel = "Create Large Icon",
                                    onOpenApp = {
                                        startActivity(Intent(this@FolderWidgetConfigureActivity, MainActivity::class.java))
                                        finish()
                                    }
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp)
                                ) {
                                    items(largeIcons, key = { it.id }) { largeIcon ->
                                        LargeIconPickCard(
                                            largeIcon = largeIcon,
                                            onSelect = {
                                                lifecycleScope.launch {
                                                    repository.bindWidgetToLargeIcon(appWidgetId, largeIcon.id)
                                                    val resultValue = Intent().apply {
                                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                                    }
                                                    setResult(Activity.RESULT_OK, resultValue)
                                                    finish()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            if (folders.isEmpty()) {
                                EmptyConfigureState(
                                    icon = Icons.Default.Folder,
                                    title = "No folders created yet",
                                    subtitle = "Create a folder in the app first, then select it here for your widget.",
                                    ctaLabel = "Create Folder",
                                    onOpenApp = {
                                        startActivity(Intent(this@FolderWidgetConfigureActivity, MainActivity::class.java))
                                        finish()
                                    }
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp)
                                ) {
                                    items(folders, key = { it.id }) { folder ->
                                        FolderPickCard(
                                            folder = folder,
                                            onSelect = {
                                                lifecycleScope.launch {
                                                    repository.bindWidgetToFolder(appWidgetId, folder.id)
                                                    val resultValue = Intent().apply {
                                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                                    }
                                                    setResult(Activity.RESULT_OK, resultValue)
                                                    finish()
                                                }
                                            }
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

    override fun onResume() {
        super.onResume()
        PopupSessionCoordinator.dismissStaleAwaitingPopup("configure_activity_resumed")
    }
}

@Composable
private fun EmptyConfigureState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    ctaLabel: String,
    onOpenApp: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onOpenApp,
                    shape = CircleShape,
                    modifier = Modifier.testTag("configure_open_app_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(ctaLabel)
                }
            }
        }
    }
}

@Composable
private fun LargeIconPickCard(
    largeIcon: LargeIconEntity,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val themeSignature = rememberIconThemeSignature()
    val iconBitmap by produceState<android.graphics.Bitmap?>(
        null,
        largeIcon.packageName,
        largeIcon.activityName,
        largeIcon.cornerRadiusDp,
        largeIcon.iconPaddingDp,
        themeSignature
    ) {
        value = withContext(Dispatchers.IO) {
            AppScanner.renderLargeIconWidgetBitmap(
                context = context,
                packageName = largeIcon.packageName,
                activityName = largeIcon.activityName,
                widthPx = 96,
                heightPx = 96,
                cornerRadiusPx = (largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * 96f,
                insetPx = (largeIcon.iconPaddingDp / 96f).coerceIn(0f, 0.30f) * 96f
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .testTag("select_large_icon_${largeIcon.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                val loadedBitmap = iconBitmap
                if (loadedBitmap != null) {
                    Image(
                        bitmap = loadedBitmap.asImageBitmap(),
                        contentDescription = largeIcon.appName,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = largeIcon.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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

            Button(
                onClick = onSelect,
                modifier = Modifier.testTag("button_choose_icon_${largeIcon.id}")
            ) {
                Text("Select")
            }
        }
    }
}

@Composable
private fun FolderPickCard(
    folder: FolderEntity,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .testTag("select_folder_${folder.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val gridLabel = when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> "2×2 Grid"
                    FolderLayouts.ROW_1X2 -> "1×2 Row"
                    FolderLayouts.ROW_1X4 -> "1×4 Row"
                    else -> "3×3 Grid"
                }
                Text(
                    text = "$gridLabel • ${folder.bgStyle.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onSelect,
                modifier = Modifier.testTag("button_choose_${folder.id}")
            ) {
                Text("Select")
            }
        }
    }
}
