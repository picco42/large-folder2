package com.example.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.util.AppScanner
import com.example.widget.BaseLargeIconWidgetProvider
import com.example.widget.FolderWidgetProvider
import com.example.widget.LargeIconWidgetProvider1x1
import com.example.widget.LargeIconWidgetProvider1x2
import com.example.widget.LargeIconWidgetProvider2x1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val folderDao = database.folderDao()
    private val largeIconDao = database.largeIconDao()
    private val folderAppDao = database.folderAppDao()
    private val widgetBindingDao = database.widgetBindingDao()

    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()
    val allLargeIcons: Flow<List<LargeIconEntity>> = largeIconDao.getAllLargeIcons()

    suspend fun purgeLegacyDefaultFoldersIfNeeded() {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("folder_widget_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("purged_legacy_defaults_v1", false)) return@withContext

            val legacyNames = setOf("Popular Apps", "Tools", "Media Hub")
            val existing = folderDao.getAllFoldersList()
            for (folder in existing) {
                if (folder.name in legacyNames) {
                    val bindings = widgetBindingDao.getBindingsForFolder(folder.id)
                    for (b in bindings) {
                        widgetBindingDao.deleteBinding(b.appWidgetId)
                    }
                    folderDao.deleteFolder(folder)
                }
            }
            prefs.edit().putBoolean("purged_legacy_defaults_v1", true).apply()
            notifyAllWidgets()
        }
    }

    fun getAppsForFolder(folderId: Long): Flow<List<FolderAppEntity>> {
        return folderAppDao.getAppsForFolder(folderId)
    }

    suspend fun getFolderById(folderId: Long): FolderEntity? {
        return withContext(Dispatchers.IO) {
            folderDao.getFolderById(folderId)
        }
    }

    suspend fun getLargeIconById(largeIconId: Long): LargeIconEntity? {
        return withContext(Dispatchers.IO) {
            largeIconDao.getLargeIconById(largeIconId)
        }
    }

    suspend fun getAppsForFolderSync(folderId: Long): List<FolderAppEntity> {
        return withContext(Dispatchers.IO) {
            folderAppDao.getAppsForFolderSync(folderId)
        }
    }

    suspend fun createFolder(
        name: String,
        layoutType: String = FolderLayouts.GRID_3X3,
        bgStyle: String = FolderBgStyles.DYNAMIC,
        bgAlpha: Float = 0.85f,
        cornerRadiusDp: Int = 24,
        showLabels: Boolean = true,
        showPopupLabels: Boolean = false,
        initialApps: List<String> = emptyList()
    ): Long {
        return withContext(Dispatchers.IO) {
            val folder = FolderEntity(
                name = name,
                layoutType = layoutType,
                bgStyle = bgStyle,
                bgAlpha = bgAlpha,
                cornerRadiusDp = cornerRadiusDp,
                showLabels = showLabels,
                showPopupLabels = showPopupLabels
            )
            val folderId = folderDao.insertFolder(folder)

            if (initialApps.isNotEmpty()) {
                val installed = AppScanner.getInstalledApps(context).associateBy { it.packageName }
                val entities = initialApps.mapIndexedNotNull { index, pkg ->
                    val info = installed[pkg]
                    if (info != null) {
                        FolderAppEntity(
                            folderId = folderId,
                            packageName = info.packageName,
                            activityName = info.activityName,
                            appName = info.appName,
                            positionIndex = index
                        )
                    } else null
                }
                folderAppDao.insertApps(entities)
            }

            notifyWidgetsUpdatedForFolder(folderId)
            folderId
        }
    }

    suspend fun updateFolder(folder: FolderEntity) {
        withContext(Dispatchers.IO) {
            folderDao.updateFolder(folder)
            notifyWidgetsUpdatedForFolder(folder.id)
        }
    }

    suspend fun renameFolder(folderId: Long, newName: String) {
        withContext(Dispatchers.IO) {
            val existing = folderDao.getFolderById(folderId) ?: return@withContext
            val trimmed = newName.trim()
            if (trimmed.isNotEmpty() && trimmed != existing.name) {
                folderDao.updateFolder(existing.copy(name = trimmed))
                notifyWidgetsUpdatedForFolder(folderId)
            }
        }
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        withContext(Dispatchers.IO) {
            val bindings = widgetBindingDao.getBindingsForFolder(folder.id)
            for (b in bindings) {
                widgetBindingDao.deleteBinding(b.appWidgetId)
            }
            folderDao.deleteFolder(folder)
            notifyWidgetsUpdatedForFolder(folder.id)
        }
    }

    suspend fun setAppsForFolder(folderId: Long, apps: List<FolderAppEntity>) {
        withContext(Dispatchers.IO) {
            folderAppDao.clearAppsForFolder(folderId)
            val reindexed = apps.mapIndexed { index, app ->
                app.copy(id = 0, folderId = folderId, positionIndex = index)
            }
            folderAppDao.insertApps(reindexed)
            notifyWidgetsUpdatedForFolder(folderId)
        }
    }

    suspend fun createLargeIcon(largeIcon: LargeIconEntity): Long {
        return withContext(Dispatchers.IO) {
            val id = largeIconDao.insertLargeIcon(largeIcon)
            notifyWidgetsUpdatedForLargeIcon(id)
            id
        }
    }

    suspend fun updateLargeIcon(largeIcon: LargeIconEntity) {
        withContext(Dispatchers.IO) {
            largeIconDao.updateLargeIcon(largeIcon)
            notifyWidgetsUpdatedForLargeIcon(largeIcon.id)
        }
    }

    suspend fun deleteLargeIcon(largeIcon: LargeIconEntity) {
        withContext(Dispatchers.IO) {
            val bindings = widgetBindingDao.getBindingsForLargeIcon(largeIcon.id)
            for (b in bindings) {
                widgetBindingDao.deleteBinding(b.appWidgetId)
            }
            largeIconDao.deleteLargeIcon(largeIcon)
            notifyWidgetsUpdatedForLargeIcon(largeIcon.id)
        }
    }

    suspend fun bindWidgetToFolder(appWidgetId: Int, folderId: Long) {
        withContext(Dispatchers.IO) {
            widgetBindingDao.insertBinding(
                WidgetBindingEntity(
                    appWidgetId = appWidgetId,
                    folderId = folderId,
                    itemType = WidgetItemTypes.FOLDER,
                    largeIconId = -1L
                )
            )
            notifyWidgetUpdated(appWidgetId)
        }
    }

    suspend fun bindWidgetToLargeIcon(appWidgetId: Int, largeIconId: Long) {
        withContext(Dispatchers.IO) {
            widgetBindingDao.insertBinding(
                WidgetBindingEntity(
                    appWidgetId = appWidgetId,
                    folderId = -1L,
                    itemType = WidgetItemTypes.ICON,
                    largeIconId = largeIconId
                )
            )
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@withContext
            BaseLargeIconWidgetProvider.updateLargeIconWidget(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetId = appWidgetId,
                largeIconId = largeIconId
            )
        }
    }

    fun notifyWidgetsUpdatedForFolder(folderId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val bindings = widgetBindingDao.getBindingsForFolder(folderId)
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
            for (binding in bindings) {
                FolderWidgetProvider.updateAppWidget(context, appWidgetManager, binding.appWidgetId, folderId)
            }
        }
    }

    fun notifyWidgetsUpdatedForLargeIcon(largeIconId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val bindings = widgetBindingDao.getBindingsForLargeIcon(largeIconId)
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
            for (binding in bindings) {
                BaseLargeIconWidgetProvider.updateLargeIconWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = binding.appWidgetId,
                    largeIconId = largeIconId
                )
            }
        }
    }

    fun notifyWidgetUpdated(appWidgetId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val binding = widgetBindingDao.getBinding(appWidgetId)
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return@launch
            if (binding != null && binding.itemType == WidgetItemTypes.ICON && binding.largeIconId != -1L) {
                BaseLargeIconWidgetProvider.updateLargeIconWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = appWidgetId,
                    largeIconId = binding.largeIconId
                )
            } else if (binding != null && binding.folderId != -1L) {
                FolderWidgetProvider.updateAppWidget(context, appWidgetManager, appWidgetId, binding.folderId)
            } else {
                FolderWidgetProvider.updateAppWidget(context, appWidgetManager, appWidgetId, null)
            }
        }
    }

    fun notifyAllWidgets() {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val providers = listOf(
            FolderWidgetProvider::class.java,
            LargeIconWidgetProvider1x1::class.java,
            LargeIconWidgetProvider1x2::class.java,
            LargeIconWidgetProvider2x1::class.java
        )
        for (providerClass in providers) {
            val component = ComponentName(context, providerClass)
            val ids = appWidgetManager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, providerClass).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
