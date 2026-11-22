package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.R
import com.example.data.AppDatabase
import com.example.data.FolderBgStyles
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.popup.FolderPopupActivity
import com.example.popup.PopupSessionCoordinator
import com.example.util.AppScanner
import com.example.util.IconStyleManager
import com.example.util.WidgetBoundsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FolderWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_PINNED) {
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val folderId = intent.getLongExtra(EXTRA_FOLDER_ID, -1L)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && folderId != -1L) {
                val db = AppDatabase.getDatabase(context)
                CoroutineScope(Dispatchers.IO).launch {
                    db.widgetBindingDao().insertBinding(com.example.data.WidgetBindingEntity(widgetId, folderId))
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateAppWidget(context, appWidgetManager, widgetId, folderId)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        PopupSessionCoordinator.dismissStaleAwaitingPopup("widget_update")
        IconStyleManager.syncPaletteAndRefreshIfNeeded(
            context = context,
            triggerWidgetUpdate = true,
            skipProviderClass = FolderWidgetProvider::class.java
        )
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            for (widgetId in appWidgetIds) {
                val binding = db.widgetBindingDao().getBinding(widgetId)
                updateAppWidget(context, appWidgetManager, widgetId, binding?.folderId)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        PopupSessionCoordinator.dismissStaleAwaitingPopup("widget_options_changed")
        IconStyleManager.syncPaletteAndRefreshIfNeeded(
            context = context,
            triggerWidgetUpdate = true,
            skipProviderClass = FolderWidgetProvider::class.java
        )
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val binding = db.widgetBindingDao().getBinding(appWidgetId)
            updateAppWidget(context, appWidgetManager, appWidgetId, binding?.folderId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        PopupSessionCoordinator.dismissStaleAwaitingPopup("widget_deleted")
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            for (widgetId in appWidgetIds) {
                db.widgetBindingDao().deleteBinding(widgetId)
                WidgetBoundsCache.removeBounds(context, widgetId)
            }
        }
    }

    companion object {
        const val EXTRA_FOLDER_ID = "com.example.EXTRA_FOLDER_ID"
        const val EXTRA_FROM_FOLDER_LABEL = "com.example.EXTRA_FROM_FOLDER_LABEL"
        const val ACTION_WIDGET_PINNED = "com.example.ACTION_WIDGET_PINNED"

        /**
         * Returns [desiredPx] limited to what fits in one slot of the widget at its current size.
         * Falls back to [desiredPx] when the launcher reports no size.
         */
        private fun fitIconSizePx(
            context: Context,
            options: Bundle,
            layoutType: String,
            showLabels: Boolean,
            desiredPx: Int
        ): Int {
            val density = context.resources.displayMetrics.density
            val landscape = context.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            )
            val heightDp = options.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            )
            if (widthDp <= 0 || heightDp <= 0) return desiredPx

            // cols, rows, horizontal padding dp, vertical padding dp (matches the layout XMLs)
            val (cols, rows, padX, padY) = when (layoutType) {
                FolderLayouts.GRID_2X2 -> listOf(2, 2, 16, 16)
                FolderLayouts.ROW_1X2 -> listOf(2, 1, 16, 16)
                FolderLayouts.ROW_1X4 -> listOf(4, 1, 16, 16)
                else -> listOf(3, 3, 24, 24)
            }
            val labelDp = if (showLabels) 24 else 0

            val slotWidthDp = (widthDp - padX) / cols.toFloat()
            val slotHeightDp = (heightDp - padY - labelDp) / rows.toFloat()
            val fitPx = (minOf(slotWidthDp, slotHeightDp) * density).toInt()
            return minOf(desiredPx, fitPx).coerceAtLeast((32 * density).toInt())
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            folderId: Long?
        ) {
            val db = AppDatabase.getDatabase(context)
            CoroutineScope(Dispatchers.IO).launch {
                val folder: FolderEntity? = if (folderId != null) {
                    db.folderDao().getFolderById(folderId)
                } else {
                    // Fall back to first folder if available
                    db.folderDao().getAllFoldersList().firstOrNull()?.also { fallbackFolder ->
                        db.widgetBindingDao().insertBinding(com.example.data.WidgetBindingEntity(appWidgetId, fallbackFolder.id))
                    }
                }

                if (folder == null) {
                    withContext(Dispatchers.Main) {
                        renderUnconfiguredWidget(context, appWidgetManager, appWidgetId)
                    }
                    return@launch
                }

                val apps = db.folderAppDao().getAppsForFolderSync(folder.id)

                val layoutRes = when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> R.layout.widget_folder_2x2
                    FolderLayouts.ROW_1X2 -> R.layout.widget_folder_1x2
                    FolderLayouts.ROW_1X4 -> R.layout.widget_folder_1x4
                    else -> R.layout.widget_folder_3x3
                }

                val maxSlots = when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> 4
                    FolderLayouts.ROW_1X2 -> 2
                    FolderLayouts.ROW_1X4 -> 4
                    else -> 9
                }
                val hasOverflow = apps.size > maxSlots
                val useSystemHomeIconSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val systemIconSizePx = fitIconSizePx(
                    context = context,
                    options = appWidgetManager.getAppWidgetOptions(appWidgetId),
                    layoutType = folder.layoutType,
                    showLabels = folder.showLabels,
                    desiredPx = AppScanner.getSystemHomeIconSizePx(context)
                )
                val iconSizePx = if (useSystemHomeIconSize) {
                    systemIconSizePx
                } else {
                    if (folder.layoutType == FolderLayouts.GRID_3X3) 120
                    else AppScanner.getSystemHomeIconSizePx(context)
                }

                val preloadedBitmaps = HashMap<Int, android.graphics.Bitmap?>(maxSlots)
                for (i in 0 until maxSlots) {
                    val isOverflowSlot = hasOverflow && (i + 1) == maxSlots
                    if (!isOverflowSlot && i < apps.size) {
                        val app = apps[i]
                        preloadedBitmaps[i] = AppScanner.getAppIconBitmap(
                            context,
                            app.packageName,
                            app.activityName,
                            iconSizePx
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    val views = RemoteViews(context.packageName, layoutRes)

                    // Background drawable for the card squircle
                    val bgRes = when (folder.bgStyle) {
                        FolderBgStyles.FROSTED_LIGHT -> R.drawable.widget_bg_frosted_light
                        FolderBgStyles.DYNAMIC -> R.drawable.widget_bg_dynamic
                        FolderBgStyles.CLEAR -> R.drawable.widget_bg_clear
                        else -> R.drawable.widget_bg_frosted_dark
                    }
                    views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

                    // Folder Name centered underneath the card
                    if (folder.showLabels) {
                        views.setViewVisibility(R.id.widget_folder_name, View.VISIBLE)
                        views.setTextViewText(R.id.widget_folder_name, folder.name)
                    } else {
                        views.setViewVisibility(R.id.widget_folder_name, View.GONE)
                    }

                    // Intent for opening the floating popup drawer (unique data + EXTRA_APPWIDGET_ID keeps
                    // multiple instances of the same folder isolated; FLAG_MUTABLE lets launcher fill sourceBounds)
                    val popupIntent = Intent(context, FolderPopupActivity::class.java).apply {
                        data = Uri.parse("folderwidget://popup/$appWidgetId/${folder.id}")
                        putExtra(EXTRA_FOLDER_ID, folder.id)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val popupPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId * 1000,
                        popupIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    // Tapping the card container (or the non-clickable 3-dots overflow slot whose clicks
                    // bubble up) opens the popup with full-card sourceBounds for clean collapse animation.
                    views.setOnClickPendingIntent(android.R.id.background, popupPendingIntent)

                    // Tapping the bottom folder label uses a separate PendingIntent flagged as label-origin
                    // so it falls back to cached card bounds instead of overwriting them with label strip bounds.
                    val labelPopupIntent = Intent(context, FolderPopupActivity::class.java).apply {
                        data = Uri.parse("folderwidget://popup/$appWidgetId/${folder.id}/label")
                        putExtra(EXTRA_FOLDER_ID, folder.id)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        putExtra(EXTRA_FROM_FOLDER_LABEL, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val labelPopupPendingIntent = PendingIntent.getActivity(
                        context,
                        appWidgetId * 1000 + 900,
                        labelPopupIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_folder_name, labelPopupPendingIntent)

                    views.setViewVisibility(R.id.overflow_slot, View.GONE)

                    for (i in 0 until maxSlots) {
                        val slotIndex = i + 1
                        val slotId = context.resources.getIdentifier("slot_$slotIndex", "id", context.packageName)
                        val iconId = context.resources.getIdentifier("slot_icon_$slotIndex", "id", context.packageName)

                        if (slotId == 0 || iconId == 0) continue

                        if (useSystemHomeIconSize && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            views.setViewLayoutWidth(iconId, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                            views.setViewLayoutHeight(iconId, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                        }

                        // Check if this slot should be the 3-dots overflow indicator (Samsung One UI style)
                        val isOverflowSlot = hasOverflow && slotIndex == maxSlots

                        if (isOverflowSlot) {
                            val dotsRes = when (folder.bgStyle) {
                                FolderBgStyles.DYNAMIC -> R.drawable.ic_more_dots_dynamic
                                FolderBgStyles.FROSTED_LIGHT -> R.drawable.ic_more_dots_dark
                                else -> R.drawable.ic_more_dots
                            }
                            views.setViewVisibility(slotId, View.GONE)
                            if (useSystemHomeIconSize && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                views.setViewLayoutWidth(R.id.overflow_slot_icon, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                                views.setViewLayoutHeight(R.id.overflow_slot_icon, systemIconSizePx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                            }
                            views.setImageViewResource(R.id.overflow_slot_icon, dotsRes)
                            views.setViewVisibility(R.id.overflow_slot_icon, View.VISIBLE)
                            views.setViewVisibility(R.id.overflow_slot, View.VISIBLE)
                            continue
                        }

                        if (i < apps.size) {
                            val app = apps[i]
                            val bitmap = preloadedBitmaps[i]
                            if (bitmap != null) {
                                views.setImageViewBitmap(iconId, bitmap)
                                views.setViewVisibility(iconId, View.VISIBLE)
                            } else {
                                views.setViewVisibility(iconId, View.INVISIBLE)
                            }

                            views.setViewVisibility(slotId, View.VISIBLE)

                            // Launch App directly with single tap
                            val launchIntent = AppScanner.getLaunchIntent(context, app.packageName, app.activityName)
                            if (launchIntent != null) {
                                val slotPendingIntent = PendingIntent.getActivity(
                                    context,
                                    appWidgetId * 1000 + slotIndex,
                                    launchIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                views.setOnClickPendingIntent(slotId, slotPendingIntent)
                            }
                        } else {
                            // Empty slot
                            views.setViewVisibility(slotId, View.INVISIBLE)
                        }
                    }

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        fun buildPinPreviewRemoteViews(
            context: Context,
            folder: FolderEntity,
            apps: List<com.example.data.FolderAppEntity>
        ): RemoteViews? {
            return try {
                val layoutRes = when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> R.layout.widget_folder_2x2
                    FolderLayouts.ROW_1X2 -> R.layout.widget_folder_1x2
                    FolderLayouts.ROW_1X4 -> R.layout.widget_folder_1x4
                    else -> R.layout.widget_folder_3x3
                }
                val maxSlots = when (folder.layoutType) {
                    FolderLayouts.GRID_2X2 -> 4
                    FolderLayouts.ROW_1X2 -> 2
                    FolderLayouts.ROW_1X4 -> 4
                    else -> 9
                }
                val views = RemoteViews(context.packageName, layoutRes)

                val bgRes = when (folder.bgStyle) {
                    FolderBgStyles.FROSTED_LIGHT -> R.drawable.widget_bg_frosted_light
                    FolderBgStyles.DYNAMIC -> R.drawable.widget_bg_dynamic
                    FolderBgStyles.CLEAR -> R.drawable.widget_bg_clear
                    else -> R.drawable.widget_bg_frosted_dark
                }
                views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

                if (folder.showLabels) {
                    views.setViewVisibility(R.id.widget_folder_name, View.VISIBLE)
                    views.setTextViewText(R.id.widget_folder_name, folder.name)
                } else {
                    views.setViewVisibility(R.id.widget_folder_name, View.GONE)
                }

                views.setViewVisibility(R.id.overflow_slot, View.GONE)

                // Small bitmaps only so Binder IPC Parcel stays safely within launcher limits
                val smallIconPx = if (folder.layoutType == FolderLayouts.GRID_3X3) 48 else 64
                val hasOverflow = apps.size > maxSlots

                for (i in 0 until maxSlots) {
                    val slotIndex = i + 1
                    val slotId = context.resources.getIdentifier("slot_$slotIndex", "id", context.packageName)
                    val iconId = context.resources.getIdentifier("slot_icon_$slotIndex", "id", context.packageName)
                    if (slotId == 0 || iconId == 0) continue

                    val isOverflowSlot = hasOverflow && slotIndex == maxSlots
                    if (isOverflowSlot) {
                        val dotsRes = when (folder.bgStyle) {
                            FolderBgStyles.DYNAMIC -> R.drawable.ic_more_dots_dynamic
                            FolderBgStyles.FROSTED_LIGHT -> R.drawable.ic_more_dots_dark
                            else -> R.drawable.ic_more_dots
                        }
                        views.setViewVisibility(slotId, View.GONE)
                        views.setImageViewResource(R.id.overflow_slot_icon, dotsRes)
                        views.setViewVisibility(R.id.overflow_slot_icon, View.VISIBLE)
                        views.setViewVisibility(R.id.overflow_slot, View.VISIBLE)
                        continue
                    }

                    if (i < apps.size) {
                        val app = apps[i]
                        val bmp = AppScanner.getAppIconBitmap(
                            context,
                            app.packageName,
                            app.activityName,
                            smallIconPx
                        )
                        if (bmp != null) {
                            views.setImageViewBitmap(iconId, bmp)
                            views.setViewVisibility(iconId, View.VISIBLE)
                        } else {
                            views.setImageViewResource(iconId, R.mipmap.ic_launcher)
                            views.setViewVisibility(iconId, View.VISIBLE)
                        }
                        views.setViewVisibility(slotId, View.VISIBLE)
                    } else {
                        views.setViewVisibility(slotId, View.INVISIBLE)
                    }
                }
                views
            } catch (_: Exception) {
                null
            }
        }

        private fun renderUnconfiguredWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_folder_2x2)
            views.setTextViewText(R.id.widget_folder_name, "Tap to configure")
            val configIntent = Intent(context, FolderWidgetConfigureActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context,
                appWidgetId,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(android.R.id.background, pi)
            views.setOnClickPendingIntent(R.id.widget_folder_name, pi)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
