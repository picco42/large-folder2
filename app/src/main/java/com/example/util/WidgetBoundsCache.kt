package com.example.util

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Rect
import com.example.BuildConfig
import com.example.data.FolderLayouts
import kotlin.math.roundToInt

enum class OriginSourceKind {
    LIVE_SOURCE_BOUNDS,
    CACHED_BOUNDS,
    CENTER_KNOWN_SIZE_FALLBACK
}

data class ResolvedWidgetOrigin(
    val bounds: Rect,
    val sourceKind: OriginSourceKind,
    val configKey: String
) {
    val hasConcreteWidgetOrigin: Boolean
        get() = sourceKind != OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK
}

data class BoundsFallbackLogEvent(
    val appWidgetId: Int,
    val sourceKind: OriginSourceKind,
    val configKey: String,
    val detail: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Tracks last-known on-screen bounds per `appWidgetId`, keyed by display size / orientation / DPI.
 * Cached entries are clamped to the current display and discarded whenever display config changes.
 * When both live `intent.sourceBounds` and valid cached bounds are unavailable, falls back to the
 * widget's known size from `AppWidgetManager` options centered on screen (never collapsing to a dot).
 */
object WidgetBoundsCache {

    private const val TAG = "WidgetBoundsCache"
    private const val PREFS_NAME = "widget_bounds_cache_prefs"
    private const val KEY_PREFIX = "bounds_v2_"
    private const val MAX_LOG_EVENTS = 64

    private val lock = Any()
    private val recentEvents = ArrayDeque<BoundsFallbackLogEvent>()

    fun getRecentDebugEvents(): List<BoundsFallbackLogEvent> = synchronized(lock) {
        recentEvents.toList()
    }

    fun clearRecentDebugEvents() = synchronized(lock) {
        recentEvents.clear()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun getScreenBoundsPx(context: Context): Rect {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels
        var height = dm.heightPixels
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = context.getSystemService(WindowManager::class.java)
                val b = wm?.maximumWindowMetrics?.bounds
                if (b != null && b.width() > 0 && b.height() > 0) {
                    width = b.width()
                    height = b.height()
                }
            } catch (_: Exception) {
                // Keep DisplayMetrics fallback
            }
        }
        return Rect(
            left = 0f,
            top = 0f,
            right = width.coerceAtLeast(1).toFloat(),
            bottom = height.coerceAtLeast(1).toFloat()
        )
    }

    fun buildDisplayConfigKey(context: Context): String {
        val cfg = context.resources.configuration
        val dm = context.resources.displayMetrics
        val screen = getScreenBoundsPx(context)
        return "${screen.width.roundToInt()}x${screen.height.roundToInt()}_o${cfg.orientation}_d${dm.densityDpi}"
    }

    fun clampToScreen(bounds: Rect, screenBounds: Rect): Rect {
        val maxW = screenBounds.width.coerceAtLeast(1f)
        val maxH = screenBounds.height.coerceAtLeast(1f)
        val w = bounds.width.coerceIn(1f, maxW)
        val h = bounds.height.coerceIn(1f, maxH)
        val left = bounds.left.coerceIn(
            screenBounds.left,
            (screenBounds.right - w).coerceAtLeast(screenBounds.left)
        )
        val top = bounds.top.coerceIn(
            screenBounds.top,
            (screenBounds.bottom - h).coerceAtLeast(screenBounds.top)
        )
        return Rect(left = left, top = top, right = left + w, bottom = top + h)
    }

    fun putBounds(
        context: Context,
        appWidgetId: Int,
        bounds: Rect,
        configKey: String = buildDisplayConfigKey(context)
    ) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || bounds.isEmpty) return
        val clamped = clampToScreen(bounds, getScreenBoundsPx(context))
        val encoded = "$configKey|${clamped.left}|${clamped.top}|${clamped.right}|${clamped.bottom}"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("$KEY_PREFIX$appWidgetId", encoded)
            .apply()
    }

    fun getValidCachedBounds(
        context: Context,
        appWidgetId: Int,
        currentConfigKey: String = buildDisplayConfigKey(context)
    ): Rect? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "$KEY_PREFIX$appWidgetId"
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 5) {
            prefs.edit().remove(key).apply()
            return null
        }
        val storedConfig = parts[0]
        if (storedConfig != currentConfigKey) {
            prefs.edit().remove(key).apply()
            recordDebugLog(
                appWidgetId = appWidgetId,
                kind = OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK,
                configKey = currentConfigKey,
                detail = "Discarded stale cached bounds ($storedConfig != $currentConfigKey)"
            )
            return null
        }
        val l = parts[1].toFloatOrNull()
        val t = parts[2].toFloatOrNull()
        val r = parts[3].toFloatOrNull()
        val b = parts[4].toFloatOrNull()
        if (l == null || t == null || r == null || b == null || r <= l || b <= t) {
            prefs.edit().remove(key).apply()
            return null
        }
        return clampToScreen(Rect(l, t, r, b), getScreenBoundsPx(context))
    }

    fun computeCenteredFallbackBounds(
        context: Context,
        appWidgetId: Int,
        layoutType: String,
        showLabels: Boolean
    ): Rect {
        val screen = getScreenBoundsPx(context)
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        var widthPx = 0f
        var heightPx = 0f

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
                val wDp = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                )
                val hDp = options.getInt(
                    if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                )
                if (wDp > 0 && hDp > 0) {
                    widthPx = wDp * density
                    heightPx = hDp * density
                }
            } catch (_: Exception) {
                // Fallback to default dp footprint below
            }
        }

        if (widthPx <= 0f || heightPx <= 0f) {
            val (defaultWdp, defaultHdp) = when (layoutType) {
                FolderLayouts.GRID_2X2 -> 168f to 168f
                FolderLayouts.ROW_1X2 -> 168f to 84f
                FolderLayouts.ROW_1X4 -> 300f to 84f
                else -> 240f to 240f
            }
            val labelExtraDp = if (showLabels) 18f else 0f
            widthPx = defaultWdp * density
            heightPx = (defaultHdp + labelExtraDp) * density
        }

        val minSidePx = 48f * density
        val clampedW = widthPx.coerceIn(minSidePx.coerceAtMost(screen.width), screen.width * 0.90f)
        val clampedH = heightPx.coerceIn(minSidePx.coerceAtMost(screen.height), screen.height * 0.78f)
        val left = screen.center.x - clampedW / 2f
        val top = screen.center.y - clampedH / 2f
        return clampToScreen(Rect(left, top, left + clampedW, top + clampedH), screen)
    }

    private fun isSubSlotRectForKnownWidgetSize(
        context: Context,
        appWidgetId: Int,
        liveRect: Rect
    ): Boolean {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        return try {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val widthTooNarrow = minWidthDp > 0 && liveRect.width < (minWidthDp * density * 0.55f)
            val heightTooShort = minHeightDp > 0 && liveRect.height < (minHeightDp * density * 0.40f)
            widthTooNarrow || heightTooShort
        } catch (_: Exception) {
            false
        }
    }

    fun resolveWidgetOrigin(
        context: Context,
        appWidgetId: Int,
        intentSourceBounds: android.graphics.Rect?,
        layoutType: String,
        showLabels: Boolean
    ): ResolvedWidgetOrigin {
        val configKey = buildDisplayConfigKey(context)
        val screen = getScreenBoundsPx(context)

        if (intentSourceBounds != null && !intentSourceBounds.isEmpty) {
            val liveRect = clampToScreen(
                Rect(
                    left = intentSourceBounds.left.toFloat(),
                    top = intentSourceBounds.top.toFloat(),
                    right = intentSourceBounds.right.toFloat(),
                    bottom = intentSourceBounds.bottom.toFloat()
                ),
                screen
            )
            val isSuspiciousSubSlot = isSubSlotRectForKnownWidgetSize(context, appWidgetId, liveRect)
            if (!isSuspiciousSubSlot) {
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    putBounds(context, appWidgetId, liveRect, configKey)
                }
                return ResolvedWidgetOrigin(
                    bounds = liveRect,
                    sourceKind = OriginSourceKind.LIVE_SOURCE_BOUNDS,
                    configKey = configKey
                )
            }
            recordDebugLog(
                appWidgetId = appWidgetId,
                kind = OriginSourceKind.CACHED_BOUNDS,
                configKey = configKey,
                detail = "Rejected tiny sub-slot intent.sourceBounds=$liveRect; using full-card fallback"
            )
        }

        val cached = getValidCachedBounds(context, appWidgetId, configKey)
        if (cached != null && !cached.isEmpty) {
            recordDebugLog(
                appWidgetId = appWidgetId,
                kind = OriginSourceKind.CACHED_BOUNDS,
                configKey = configKey,
                detail = "intent.sourceBounds null; used cached bounds for widgetId=$appWidgetId ($cached)"
            )
            return ResolvedWidgetOrigin(
                bounds = cached,
                sourceKind = OriginSourceKind.CACHED_BOUNDS,
                configKey = configKey
            )
        }

        val centered = computeCenteredFallbackBounds(context, appWidgetId, layoutType, showLabels)
        recordDebugLog(
            appWidgetId = appWidgetId,
            kind = OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK,
            configKey = configKey,
            detail = "intent.sourceBounds and cache missing; using known-size centered fallback ($centered)"
        )
        return ResolvedWidgetOrigin(
            bounds = centered,
            sourceKind = OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK,
            configKey = configKey
        )
    }

    fun removeBounds(context: Context, appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("$KEY_PREFIX$appWidgetId")
            .apply()
    }

    /**
     * Captures a small 1/8-resolution snapshot of an in-app root view so overlays can fade a static
     * blurred layer cleanly via GPU alpha instead of animating blur radius every frame.
     */
    fun captureDownscaledBackdropBitmap(view: View?): Bitmap? {
        if (view == null || view.width <= 8 || view.height <= 8) return null
        return try {
            val scale = 0.125f
            val w = (view.width * scale).roundToInt().coerceIn(32, 240)
            val h = (view.height * scale).roundToInt().coerceIn(32, 420)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(w.toFloat() / view.width, h.toFloat() / view.height)
            view.draw(canvas)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun recordDebugLog(
        appWidgetId: Int,
        kind: OriginSourceKind,
        configKey: String,
        detail: String
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "[$kind] widgetId=$appWidgetId config=$configKey :: $detail")
        synchronized(lock) {
            if (recentEvents.size >= MAX_LOG_EVENTS) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(
                BoundsFallbackLogEvent(
                    appWidgetId = appWidgetId,
                    sourceKind = kind,
                    configKey = configKey,
                    detail = detail
                )
            )
        }
    }
}
