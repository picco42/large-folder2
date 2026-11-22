package com.example.util

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.widget.FolderWidgetProvider
import com.example.widget.LargeIconWidgetProvider1x1
import com.example.widget.LargeIconWidgetProvider1x2
import com.example.widget.LargeIconWidgetProvider2x1
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized Material You / Samsung Color Palette tone configuration.
 * Adjust these system color resources in one place to tune the themed tile/glyph contrast.
 */
object ThemedIconTones {
    val DARK_BACKGROUND_COLOR_RES: Int = android.R.color.system_neutral1_800
    val DARK_FOREGROUND_COLOR_RES: Int = android.R.color.system_accent1_100
    val LIGHT_BACKGROUND_COLOR_RES: Int = android.R.color.system_accent1_100
    val LIGHT_FOREGROUND_COLOR_RES: Int = android.R.color.system_neutral2_700

    const val FALLBACK_DARK_BG: Int = 0xFF31302B.toInt()
    const val FALLBACK_DARK_FG: Int = 0xFFE6DE9D.toInt()
    const val FALLBACK_LIGHT_BG: Int = 0xFFE2E5EC.toInt()
    const val FALLBACK_LIGHT_FG: Int = 0xFF3E4759.toInt()
}

enum class IconStyle(val prefValue: String, val displayLabel: String) {
    ORIGINAL("original", "Original"),
    THEMED("themed", "Themed");

    companion object {
        fun fromPrefValue(value: String?): IconStyle? {
            return entries.firstOrNull { it.prefValue == value }
        }
    }
}

data class IconThemeSignature(
    val style: IconStyle,
    val isDarkMode: Boolean,
    val bgColor: Int,
    val fgColor: Int
) {
    fun serialize(): String = "${style.prefValue}|${if (isDarkMode) 1 else 0}|$bgColor|$fgColor"
}

/**
 * Converts an [AdaptiveIconDrawable] that provides a monochrome layer into a Material You /
 * Color Palette themed adaptive icon on API 33+. Icons without a monochrome layer stay original.
 */
fun themedIcon(ctx: Context, src: Drawable): Drawable {
    if (Build.VERSION.SDK_INT >= 33 && src is AdaptiveIconDrawable) {
        val mono = src.monochrome ?: return src // no monochrome layer: keep original
        val dark = IconStyleManager.isDarkMode(ctx)
        val (bg, fg) = IconStyleManager.resolveThemeColors(ctx, dark)
        val monoCopy = (mono.constantState?.newDrawable(ctx.resources) ?: mono).mutate().apply {
            setTint(fg)
        }
        return AdaptiveIconDrawable(ColorDrawable(bg), monoCopy)
    }
    return src
}

object IconStyleManager {

    private const val PREFS_NAME = "icon_style_prefs"
    private const val KEY_ICON_STYLE = "icon_style"
    private const val KEY_LAST_THEME_SIG = "last_theme_signature"

    private val _iconStyleFlow = MutableStateFlow(defaultStyleForApi())
    val iconStyleFlow: StateFlow<IconStyle> = _iconStyleFlow.asStateFlow()

    private val _paletteRevision = MutableStateFlow(0)
    val paletteRevision: StateFlow<Int> = _paletteRevision.asStateFlow()

    @Volatile
    private var cachedSelectedStyle: IconStyle? = null

    @Volatile
    private var lastAppliedSignature: String? = null

    private val stateLock = Any()

    fun isThemedIconsSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun defaultStyleForApi(): IconStyle {
        return if (isThemedIconsSupported()) IconStyle.THEMED else IconStyle.ORIGINAL
    }

    fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun resolveThemeColors(context: Context, dark: Boolean = isDarkMode(context)): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val bg = context.getColor(
                    if (dark) ThemedIconTones.DARK_BACKGROUND_COLOR_RES
                    else ThemedIconTones.LIGHT_BACKGROUND_COLOR_RES
                )
                val fg = context.getColor(
                    if (dark) ThemedIconTones.DARK_FOREGROUND_COLOR_RES
                    else ThemedIconTones.LIGHT_FOREGROUND_COLOR_RES
                )
                return bg to fg
            } catch (_: Exception) {
                // Fall back cleanly if platform resources aren't available in a host environment
            }
        }
        return if (dark) {
            ThemedIconTones.FALLBACK_DARK_BG to ThemedIconTones.FALLBACK_DARK_FG
        } else {
            ThemedIconTones.FALLBACK_LIGHT_BG to ThemedIconTones.FALLBACK_LIGHT_FG
        }
    }

    fun getIconStyle(context: Context): IconStyle {
        if (!isThemedIconsSupported()) {
            if (_iconStyleFlow.value != IconStyle.ORIGINAL) {
                _iconStyleFlow.value = IconStyle.ORIGINAL
            }
            return IconStyle.ORIGINAL
        }
        cachedSelectedStyle?.let { return it }
        synchronized(stateLock) {
            cachedSelectedStyle?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val resolved = IconStyle.fromPrefValue(prefs.getString(KEY_ICON_STYLE, null))
                ?: defaultStyleForApi()
            cachedSelectedStyle = resolved
            if (_iconStyleFlow.value != resolved) {
                _iconStyleFlow.value = resolved
            }
            return resolved
        }
    }

    fun setIconStyle(context: Context, style: IconStyle) {
        val effective = if (isThemedIconsSupported()) style else IconStyle.ORIGINAL
        val changed = synchronized(stateLock) {
            val prev = getIconStyle(context)
            cachedSelectedStyle = effective
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ICON_STYLE, effective.prefValue)
                .apply()
            _iconStyleFlow.value = effective
            val sig = getCurrentThemeSignature(context).serialize()
            lastAppliedSignature = sig
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_THEME_SIG, sig)
                .apply()
            prev != effective
        }
        if (changed) {
            AppScanner.clearBitmapCaches()
            _paletteRevision.value = _paletteRevision.value + 1
            notifyAllWidgets(context)
        }
    }

    fun getCurrentThemeSignature(context: Context): IconThemeSignature {
        val style = getIconStyle(context)
        val dark = isDarkMode(context)
        val (bg, fg) = resolveThemeColors(context, dark)
        return IconThemeSignature(
            style = style,
            isDarkMode = dark,
            bgColor = bg,
            fgColor = fg
        )
    }

    /**
     * Compares current [IconThemeSignature] (style, dark mode, bg/fg palette colors) against the
     * last recorded one. When changed, invalidates cached bitmaps and updates home-screen widgets
     * (excluding [skipProviderClass] when already inside that provider's update pass).
     */
    fun syncPaletteAndRefreshIfNeeded(
        context: Context,
        triggerWidgetUpdate: Boolean = true,
        skipProviderClass: Class<*>? = null
    ): Boolean {
        val currentSig = getCurrentThemeSignature(context)
        val currentSerialized = currentSig.serialize()
        val changed = synchronized(stateLock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previous = lastAppliedSignature ?: prefs.getString(KEY_LAST_THEME_SIG, null)
            if (previous == currentSerialized) {
                lastAppliedSignature = currentSerialized
                return@synchronized false
            }
            // Record the new signature BEFORE broadcasting widget updates to guarantee no loops.
            lastAppliedSignature = currentSerialized
            prefs.edit().putString(KEY_LAST_THEME_SIG, currentSerialized).apply()
            true
        }

        if (changed) {
            AppScanner.clearBitmapCaches()
            _paletteRevision.value = _paletteRevision.value + 1
            if (triggerWidgetUpdate) {
                notifyAllWidgets(context, skipProviderClass)
            }
        }
        return changed
    }

    fun notifyAllWidgets(context: Context, skipProviderClass: Class<*>? = null) {
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val providers = listOf(
            FolderWidgetProvider::class.java,
            LargeIconWidgetProvider1x1::class.java,
            LargeIconWidgetProvider1x2::class.java,
            LargeIconWidgetProvider2x1::class.java
        )
        for (providerClass in providers) {
            if (skipProviderClass != null && providerClass == skipProviderClass) continue
            val component = ComponentName(context, providerClass)
            val ids = try {
                appWidgetManager.getAppWidgetIds(component)
            } catch (_: Exception) {
                intArrayOf()
            }
            if (ids.isNotEmpty()) {
                val intent = Intent(context, providerClass).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    internal fun resetMemoryCacheForTests() {
        synchronized(stateLock) {
            cachedSelectedStyle = null
            lastAppliedSignature = null
            _iconStyleFlow.value = defaultStyleForApi()
        }
    }
}

@Composable
fun rememberIconThemeSignature(): IconThemeSignature {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val style by IconStyleManager.iconStyleFlow.collectAsState()
    val revision by IconStyleManager.paletteRevision.collectAsState()
    return remember(context, configuration.uiMode, style, revision) {
        IconStyleManager.syncPaletteAndRefreshIfNeeded(context, triggerWidgetUpdate = false)
        IconStyleManager.getCurrentThemeSignature(context)
    }
}
