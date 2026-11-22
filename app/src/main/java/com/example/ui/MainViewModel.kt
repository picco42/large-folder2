package com.example.ui

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.FolderWidgetApplication
import com.example.data.FolderAppEntity
import com.example.data.FolderEntity
import com.example.data.LargeIconEntity
import com.example.util.AppInfo
import com.example.util.AppScanner
import com.example.widget.BaseLargeIconWidgetProvider
import com.example.widget.FolderWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    companion object {
        const val TAB_FOLDERS = 0
        const val TAB_ICONS = 1
        private const val KEY_SELECTED_DASHBOARD_TAB = "selected_dashboard_tab"
    }

    private val repository = (application as FolderWidgetApplication).repository

    val selectedDashboardTab: StateFlow<Int> = savedStateHandle.getStateFlow(
        KEY_SELECTED_DASHBOARD_TAB,
        TAB_FOLDERS
    )

    fun setSelectedDashboardTab(tab: Int) {
        savedStateHandle[KEY_SELECTED_DASHBOARD_TAB] = tab.coerceIn(TAB_FOLDERS, TAB_ICONS)
    }

    val allFolders: StateFlow<List<FolderEntity>> = repository.allFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allLargeIcons: StateFlow<List<LargeIconEntity>> = repository.allLargeIcons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(true)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _isLoadingApps.value = true
            val apps = withContext(Dispatchers.IO) {
                AppScanner.getInstalledApps(getApplication())
            }
            _installedApps.value = apps
            _isLoadingApps.value = false
        }
    }

    fun getAppsForFolder(folderId: Long): Flow<List<FolderAppEntity>> {
        return repository.getAppsForFolder(folderId)
    }

    fun createFolder(
        name: String,
        layoutType: String,
        bgStyle: String,
        bgAlpha: Float,
        cornerRadiusDp: Int,
        showLabels: Boolean,
        showPopupLabels: Boolean,
        selectedPackages: List<String>,
        onDone: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = repository.createFolder(
                name = name,
                layoutType = layoutType,
                bgStyle = bgStyle,
                bgAlpha = bgAlpha,
                cornerRadiusDp = cornerRadiusDp,
                showLabels = showLabels,
                showPopupLabels = showPopupLabels,
                initialApps = selectedPackages
            )
            onDone(id)
        }
    }

    fun updateFolder(
        folder: FolderEntity,
        apps: List<FolderAppEntity>,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            repository.updateFolder(folder)
            repository.setAppsForFolder(folder.id, apps)
            onDone()
        }
    }

    fun renameFolder(folderId: Long, newName: String) {
        viewModelScope.launch {
            repository.renameFolder(folderId, newName)
        }
    }

    fun reorderFolderApps(folderId: Long, apps: List<FolderAppEntity>) {
        viewModelScope.launch {
            repository.setAppsForFolder(folderId, apps)
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
        }
    }

    fun createLargeIcon(largeIcon: LargeIconEntity, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createLargeIcon(largeIcon)
            onDone(id)
        }
    }

    fun updateLargeIcon(largeIcon: LargeIconEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.updateLargeIcon(largeIcon)
            onDone()
        }
    }

    fun deleteLargeIcon(largeIcon: LargeIconEntity) {
        viewModelScope.launch {
            repository.deleteLargeIcon(largeIcon)
        }
    }

    fun requestPinWidget(context: Context, folder: FolderEntity, apps: List<FolderAppEntity> = emptyList()): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val myProvider = ComponentName(context, FolderWidgetProvider::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val pinSuccessIntent = Intent(context, FolderWidgetProvider::class.java).apply {
                    action = FolderWidgetProvider.ACTION_WIDGET_PINNED
                    putExtra(FolderWidgetProvider.EXTRA_FOLDER_ID, folder.id)
                }
                val successCallback = android.app.PendingIntent.getBroadcast(
                    context,
                    folder.id.toInt(),
                    pinSuccessIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                val previewViews = FolderWidgetProvider.buildPinPreviewRemoteViews(context, folder, apps)
                val extras = previewViews?.let { rv ->
                    Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, rv) }
                }
                return try {
                    appWidgetManager.requestPinAppWidget(myProvider, extras, successCallback)
                    true
                } catch (_: Exception) {
                    appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
                    true
                }
            }
        }
        return false
    }

    fun requestPinLargeIcon(context: Context, largeIcon: LargeIconEntity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val providerClass = BaseLargeIconWidgetProvider.providerClassForOrientation(largeIcon.orientation)
            val myProvider = ComponentName(context, providerClass)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val pinSuccessIntent = Intent(context, providerClass).apply {
                    action = BaseLargeIconWidgetProvider.ACTION_LARGE_ICON_PINNED
                    putExtra(BaseLargeIconWidgetProvider.EXTRA_LARGE_ICON_ID, largeIcon.id)
                }
                val successCallback = android.app.PendingIntent.getBroadcast(
                    context,
                    500_000 + largeIcon.id.toInt(),
                    pinSuccessIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                )
                val previewViews = BaseLargeIconWidgetProvider.buildPinPreviewRemoteViews(context, largeIcon)
                val extras = previewViews?.let { rv ->
                    Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, rv) }
                }
                return try {
                    appWidgetManager.requestPinAppWidget(myProvider, extras, successCallback)
                    true
                } catch (_: Exception) {
                    appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
                    true
                }
            }
        }
        return false
    }
}
