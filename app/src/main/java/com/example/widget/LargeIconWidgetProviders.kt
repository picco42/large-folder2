package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.R
import com.example.data.AppDatabase
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.data.WidgetBindingEntity
import com.example.data.WidgetItemTypes
import com.example.util.AppScanner
import com.example.util.IconStyleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

abstract class BaseLargeIconWidgetProvider(
    private val defaultOrientation: String
) : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LARGE_ICON_PINNED) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val largeIconId = intent.getLongExtra(EXTRA_LARGE_ICON_ID, -1L)
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID && largeIconId != -1L) {
                val db = AppDatabase.getDatabase(context)
                CoroutineScope(Dispatchers.IO).launch {
                    db.widgetBindingDao().insertBinding(
                        WidgetBindingEntity(
                            appWidgetId = widgetId,
                            folderId = -1L,
                            itemType = WidgetItemTypes.ICON,
                            largeIconId = largeIconId
                        )
                    )
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateLargeIconWidget(context, appWidgetManager, widgetId, largeIconId, defaultOrientation)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        IconStyleManager.syncPaletteAndRefreshIfNeeded(
            context = context,
            triggerWidgetUpdate = true,
            skipProviderClass = this::class.java
        )
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            for (widgetId in appWidgetIds) {
                val binding = db.widgetBindingDao().getBinding(widgetId)
                val targetIconId = if (binding?.itemType == WidgetItemTypes.ICON && binding.largeIconId != -1L) {
                    binding.largeIconId
                } else {
                    null
                }
                updateLargeIconWidget(context, appWidgetManager, widgetId, targetIconId, defaultOrientation)
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
        IconStyleManager.syncPaletteAndRefreshIfNeeded(
            context = context,
            triggerWidgetUpdate = true,
            skipProviderClass = this::class.java
        )
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            val binding = db.widgetBindingDao().getBinding(appWidgetId)
            val targetIconId = if (binding?.itemType == WidgetItemTypes.ICON && binding.largeIconId != -1L) {
                binding.largeIconId
            } else {
                null
            }
            updateLargeIconWidget(context, appWidgetManager, appWidgetId, targetIconId, defaultOrientation)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            for (widgetId in appWidgetIds) {
                db.widgetBindingDao().deleteBinding(widgetId)
            }
        }
    }

    companion object {
        const val EXTRA_LARGE_ICON_ID = "com.example.EXTRA_LARGE_ICON_ID"
        const val ACTION_LARGE_ICON_PINNED = "com.example.ACTION_LARGE_ICON_PINNED"

        fun providerClassForOrientation(orientation: String): Class<out AppWidgetProvider> {
            return when (orientation) {
                LargeIconOrientations.ICON_1X2 -> LargeIconWidgetProvider1x2::class.java
                LargeIconOrientations.ICON_2X1 -> LargeIconWidgetProvider2x1::class.java
                else -> LargeIconWidgetProvider1x1::class.java
            }
        }

        private fun resolveWidgetBitmapDimensions(
            context: Context,
            options: Bundle?,
            orientation: String
        ): Pair<Int, Int> {
            val baseShort = 224
            if (options != null) {
                val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val widthDp = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                )
                val heightDp = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                )
                if (widthDp > 0 && heightDp > 0) {
                    val ratio = widthDp.toFloat() / heightDp.toFloat()
                    return if (ratio >= 1f) {
                        val w = (baseShort * ratio).roundToInt().coerceIn(baseShort, 560)
                        w to baseShort
                    } else {
                        val h = (baseShort / ratio).roundToInt().coerceIn(baseShort, 560)
                        baseShort to h
                    }
                }
            }
            return when (orientation) {
                LargeIconOrientations.ICON_1X2 -> baseShort to (baseShort * 2)
                LargeIconOrientations.ICON_2X1 -> (baseShort * 2) to baseShort
                else -> baseShort to baseShort
            }
        }

        fun updateLargeIconWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            largeIconId: Long?,
            fallbackOrientation: String = LargeIconOrientations.ICON_1X1
        ) {
            val db = AppDatabase.getDatabase(context)
            CoroutineScope(Dispatchers.IO).launch {
                val largeIcon: LargeIconEntity? = if (largeIconId != null && largeIconId != -1L) {
                    db.largeIconDao().getLargeIconById(largeIconId)
                } else {
                    val all = db.largeIconDao().getAllLargeIconsList()
                    val matched = all.firstOrNull { it.orientation == fallbackOrientation } ?: all.firstOrNull()
                    matched?.also {
                        db.widgetBindingDao().insertBinding(
                            WidgetBindingEntity(
                                appWidgetId = appWidgetId,
                                folderId = -1L,
                                itemType = WidgetItemTypes.ICON,
                                largeIconId = it.id
                            )
                        )
                    }
                }

                if (largeIcon == null) {
                    renderUnconfiguredLargeIcon(context, appWidgetManager, appWidgetId)
                    return@launch
                }

                val views = RemoteViews(context.packageName, R.layout.widget_large_icon)
                val options = try {
                    appWidgetManager.getAppWidgetOptions(appWidgetId)
                } catch (_: Exception) {
                    null
                }
                val (bmpW, bmpH) = resolveWidgetBitmapDimensions(context, options, largeIcon.orientation)
                val shortSide = minOf(bmpW, bmpH).toFloat()
                val cornerPx = (largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * shortSide
                val insetPx = (largeIcon.iconPaddingDp / 96f).coerceIn(0f, 0.30f) * shortSide

                val widgetBitmap = AppScanner.renderLargeIconWidgetBitmap(
                    context = context,
                    packageName = largeIcon.packageName,
                    activityName = largeIcon.activityName,
                    widthPx = bmpW,
                    heightPx = bmpH,
                    cornerRadiusPx = cornerPx,
                    insetPx = insetPx
                )
                if (widgetBitmap != null) {
                    views.setImageViewBitmap(R.id.large_icon_image, widgetBitmap)
                    views.setViewVisibility(R.id.large_icon_image, View.VISIBLE)
                } else {
                    views.setImageViewResource(R.id.large_icon_image, R.mipmap.ic_launcher)
                }

                // Direct PendingIntent to the target app allows Android 12+ launchers to run native
                // AppWidgetHostView morph/reveal transitions straight from @android:id/background bounds
                // with no intermediate trampoline flash or double-icon jump.
                val launchIntent = AppScanner.getLaunchIntent(
                    context = context,
                    packageName = largeIcon.packageName,
                    activityName = largeIcon.activityName
                )
                val clickPendingIntent = if (launchIntent != null) {
                    PendingIntent.getActivity(
                        context,
                        900_000 + appWidgetId,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    val configIntent = Intent(context, FolderWidgetConfigureActivity::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    PendingIntent.getActivity(
                        context,
                        900_000 + appWidgetId,
                        configIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                views.setOnClickPendingIntent(android.R.id.background, clickPendingIntent)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }

        private fun renderUnconfiguredLargeIcon(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_large_icon)
            views.setImageViewResource(R.id.large_icon_image, R.mipmap.ic_launcher)

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
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun buildPinPreviewRemoteViews(context: Context, largeIcon: LargeIconEntity): RemoteViews? {
            return try {
                val (previewW, previewH) = when (largeIcon.orientation) {
                    LargeIconOrientations.ICON_1X2 -> 72 to 144
                    LargeIconOrientations.ICON_2X1 -> 144 to 72
                    else -> 96 to 96
                }
                val views = RemoteViews(context.packageName, R.layout.widget_large_icon)
                val shortSide = minOf(previewW, previewH).toFloat()
                val cornerPx = (largeIcon.cornerRadiusDp / 96f).coerceIn(0f, 0.5f) * shortSide
                val insetPx = (largeIcon.iconPaddingDp / 96f).coerceIn(0f, 0.30f) * shortSide

                val bmp = AppScanner.renderLargeIconWidgetBitmap(
                    context = context,
                    packageName = largeIcon.packageName,
                    activityName = largeIcon.activityName,
                    widthPx = previewW,
                    heightPx = previewH,
                    cornerRadiusPx = cornerPx,
                    insetPx = insetPx
                )
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.large_icon_image, bmp)
                } else {
                    views.setImageViewResource(R.id.large_icon_image, R.mipmap.ic_launcher)
                }
                views.setViewVisibility(R.id.large_icon_image, View.VISIBLE)
                views
            } catch (_: Exception) {
                null
            }
        }
    }
}

class LargeIconWidgetProvider1x1 : BaseLargeIconWidgetProvider(LargeIconOrientations.ICON_1X1)
class LargeIconWidgetProvider1x2 : BaseLargeIconWidgetProvider(LargeIconOrientations.ICON_1X2)
class LargeIconWidgetProvider2x1 : BaseLargeIconWidgetProvider(LargeIconOrientations.ICON_2X1)

