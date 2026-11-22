package com.example.util

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import kotlin.math.roundToInt

data class AppInfo(
    val packageName: String,
    val activityName: String,
    val appName: String
)

object AppScanner {

    // Cache downscaled bitmaps for widget RemoteViews (max 180 icons)
    private val iconCache = object : LruCache<String, Bitmap>(180) {}

    // Cache full-bleed Large Icon widget bitmaps by KB (max 12 MB)
    private val largeIconCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    /**
     * Returns the system's standard home screen / launcher icon size in pixels,
     * automatically reflecting system display size / density settings.
     */
    fun getSystemHomeIconSizePx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val launcherSize = am?.launcherLargeIconSize ?: 0
        val homeIconPx = (60f * metrics.density).roundToInt()
        return maxOf(launcherSize, homeIconPx).coerceAtLeast(48)
    }

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolvedList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                mainIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val apps = mutableListOf<AppInfo>()
        for (info in resolvedList) {
            val pkg = info.activityInfo.packageName
            val activity = info.activityInfo.name
            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }

            apps.add(
                AppInfo(
                    packageName = pkg,
                    activityName = activity,
                    appName = label
                )
            )
        }

        return apps
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun getAppIconDrawable(context: Context, packageName: String, activityName: String? = null): Drawable? {
        val pm = context.packageManager
        if (!activityName.isNullOrEmpty()) {
            try {
                val component = ComponentName(packageName, activityName)
                val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getActivityInfo(component, 0)
                }
                val drawable = activityInfo.loadIcon(pm)
                if (drawable != null) return drawable
            } catch (e: Exception) {
                // Fallback to application icon
            }
        }
        return try {
            pm.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildCacheKey(
        context: Context?,
        packageName: String,
        activityName: String?,
        sizePx: Int
    ): String {
        val themeSig = if (context != null) {
            val dark = if (isDarkMode(context)) "D" else "L"
            val palette = if (isColorPaletteActive(context)) "P" else "N"
            "$dark$palette"
        } else {
            "DEF"
        }
        return "$packageName:${activityName.orEmpty()}:$sizePx:$themeSig"
    }

    fun getCachedAppIconBitmap(
        packageName: String,
        activityName: String? = null,
        sizePx: Int = 120
    ): Bitmap? {
        return iconCache.get(buildCacheKey(null, packageName, activityName, sizePx))
    }

    fun getCachedAppIconBitmap(
        context: Context,
        packageName: String,
        activityName: String? = null,
        sizePx: Int = 120
    ): Bitmap? {
        return iconCache.get(buildCacheKey(context, packageName, activityName, sizePx))
    }

    fun getAppIconBitmap(
        context: Context,
        packageName: String,
        activityName: String? = null,
        sizePx: Int = 120
    ): Bitmap? {
        val cacheKey = buildCacheKey(context, packageName, activityName, sizePx)
        iconCache.get(cacheKey)?.let { return it }

        val drawable = getAppIconDrawable(context, packageName, activityName) ?: return null
        val bitmap = renderIconBitmap(context, drawable, sizePx)
        iconCache.put(cacheKey, bitmap)
        return bitmap
    }

    // Overload for backward compatibility
    fun getAppIconBitmap(context: Context, packageName: String, sizePx: Int): Bitmap? {
        return getAppIconBitmap(context, packageName, null, sizePx)
    }

    /**
     * Renders adaptive and legacy drawables safely, properly handling Samsung One UI Color Palette,
     * Material You themed icons, and monochrome layers to avoid blank icons.
     */
    fun renderIconBitmap(context: Context, drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width == sizePx && src.height == sizePx) return src
            return Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            val isColorPaletteActive = isColorPaletteActive(context)
            val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawable.monochrome
            } else null

            // If system has Material You / Color Palette active and app provides monochrome layer
            if (isColorPaletteActive && monochrome != null) {
                val themedBitmap = renderThemedAdaptiveIcon(context, monochrome, sizePx)
                if (themedBitmap != null) return themedBitmap
            }

            // Standard Adaptive Icon render with safety check
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)

            // On some Samsung One UI devices with themed icons, standard layers can draw blank
            if (isBitmapBlank(output)) {
                // Fallback 1: If monochrome layer exists, render with dynamic palette
                if (monochrome != null) {
                    val themedFallback = renderThemedAdaptiveIcon(context, monochrome, sizePx)
                    if (themedFallback != null) return themedFallback
                }

                // Fallback 2: Draw foreground explicitly
                val fg = drawable.foreground
                if (fg != null) {
                    val fallbackBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                    val fbCanvas = Canvas(fallbackBitmap)
                    fg.setBounds(0, 0, sizePx, sizePx)
                    fg.draw(fbCanvas)
                    if (!isBitmapBlank(fallbackBitmap)) {
                        return fallbackBitmap
                    }
                }
            } else {
                return output
            }
        }

        // Standard fallback for vector or legacy drawables
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return output
    }

    /**
     * Renders an enlarged app icon directly into the target [widthPx] x [heightPx] shape (1x1, 1x2, or 2x1)
     * with no outer card frame:
     * - Color Palette active + monochrome layer: monochrome glyph centered squarely over theme tint fill.
     * - Adaptive icon: background fills full rounded shape; foreground scales cleanly 1:1 and centers.
     * - Legacy / non-adaptive icon: scales 1:1 to fill short side; remainder filled with sampled edge color.
     */
    fun getCachedLargeIconWidgetBitmap(
        context: Context,
        packageName: String,
        activityName: String?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        insetPx: Float = 0f
    ): Bitmap? {
        val key = buildLargeIconCacheKey(
            context,
            packageName,
            activityName,
            widthPx,
            heightPx,
            cornerRadiusPx,
            insetPx
        )
        return largeIconCache.get(key)
    }

    fun renderLargeIconWidgetBitmap(
        context: Context,
        packageName: String,
        activityName: String?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        insetPx: Float = 0f
    ): Bitmap? {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val cacheKey = buildLargeIconCacheKey(context, packageName, activityName, w, h, cornerRadiusPx, insetPx)
        largeIconCache.get(cacheKey)?.let { return it }

        val drawable = getAppIconDrawable(context, packageName, activityName) ?: return null
        val bitmap = renderLargeIconDrawableBitmap(
            context = context,
            drawable = drawable,
            widthPx = w,
            heightPx = h,
            cornerRadiusPx = cornerRadiusPx,
            insetPx = insetPx
        )
        largeIconCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun renderLargeIconDrawableBitmap(
        context: Context,
        drawable: Drawable,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        insetPx: Float = 0f
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val shortSide = minOf(w, h).toFloat()
        val longSide = maxOf(w, h).toFloat()
        val centerX = w / 2f
        val centerY = h / 2f
        val clampedInset = insetPx.coerceIn(0f, shortSide * 0.30f)
        val effectiveSquare = (shortSide - 2f * clampedInset).coerceAtLeast(shortSide * 0.40f)

        var contentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var contentCanvas = Canvas(contentBitmap)

        var rendered = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            val isColorPalette = isColorPaletteActive(context)
            val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawable.monochrome
            } else null

            if (isColorPalette && monochrome != null) {
                val (bgFill, fgTint) = getDynamicPaletteColors(context)
                contentCanvas.drawColor(bgFill)

                val monoSide = (effectiveSquare * 1.5f).roundToInt().coerceAtLeast(1)
                val monoLeft = centerX - monoSide / 2f
                val monoTop = centerY - monoSide / 2f
                val monoCopy = monochrome.constantState?.newDrawable()?.mutate() ?: monochrome.mutate()
                monoCopy.colorFilter = PorterDuffColorFilter(fgTint, PorterDuff.Mode.SRC_IN)
                monoCopy.setBounds(0, 0, monoSide, monoSide)
                contentCanvas.save()
                contentCanvas.translate(monoLeft, monoTop)
                monoCopy.draw(contentCanvas)
                contentCanvas.restore()
                if (!isBitmapBlank(contentBitmap)) {
                    rendered = true
                }
            }

            if (!rendered) {
                contentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                contentCanvas = Canvas(contentBitmap)

                val bg = drawable.background
                if (bg != null) {
                    // Scale background uniformly to cover full width x height without distortion or bleed edge
                    val bgSide = (longSide * 1.5f).roundToInt().coerceAtLeast(1)
                    val bgLeft = centerX - bgSide / 2f
                    val bgTop = centerY - bgSide / 2f
                    val bgCopy = bg.constantState?.newDrawable()?.mutate() ?: bg.mutate()
                    bgCopy.setBounds(0, 0, bgSide, bgSide)
                    contentCanvas.save()
                    contentCanvas.translate(bgLeft, bgTop)
                    bgCopy.draw(contentCanvas)
                    contentCanvas.restore()
                }

                // Ensure any transparent outer corners/edges are backed cleanly
                val cornerAlpha = Color.alpha(contentBitmap.getPixel(1.coerceAtMost(w - 1), 1.coerceAtMost(h - 1)))
                if (bg == null || cornerAlpha < 200) {
                    val fallbackBg = sampleOpaqueEdgeColor(contentBitmap) ?: Color.WHITE
                    val backedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val backedCanvas = Canvas(backedBitmap)
                    backedCanvas.drawColor(fallbackBg)
                    backedCanvas.drawBitmap(contentBitmap, 0f, 0f, null)
                    contentBitmap = backedBitmap
                    contentCanvas = backedCanvas
                }

                val fg = drawable.foreground
                if (fg != null) {
                    val fgSide = (effectiveSquare * 1.5f).roundToInt().coerceAtLeast(1)
                    val fgLeft = centerX - fgSide / 2f
                    val fgTop = centerY - fgSide / 2f
                    val fgCopy = fg.constantState?.newDrawable()?.mutate() ?: fg.mutate()
                    fgCopy.setBounds(0, 0, fgSide, fgSide)
                    contentCanvas.save()
                    contentCanvas.translate(fgLeft, fgTop)
                    fgCopy.draw(contentCanvas)
                    contentCanvas.restore()
                }

                if (!isBitmapBlank(contentBitmap)) {
                    rendered = true
                } else if (monochrome != null) {
                    val (bgFill, fgTint) = getDynamicPaletteColors(context)
                    contentCanvas.drawColor(bgFill)
                    val monoSide = (effectiveSquare * 1.5f).roundToInt().coerceAtLeast(1)
                    val monoLeft = centerX - monoSide / 2f
                    val monoTop = centerY - monoSide / 2f
                    val monoCopy = monochrome.constantState?.newDrawable()?.mutate() ?: monochrome.mutate()
                    monoCopy.colorFilter = PorterDuffColorFilter(fgTint, PorterDuff.Mode.SRC_IN)
                    monoCopy.setBounds(0, 0, monoSide, monoSide)
                    contentCanvas.save()
                    contentCanvas.translate(monoLeft, monoTop)
                    monoCopy.draw(contentCanvas)
                    contentCanvas.restore()
                    rendered = !isBitmapBlank(contentBitmap)
                }
            }
        }

        if (!rendered) {
            contentBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            contentCanvas = Canvas(contentBitmap)
            val squareSide = effectiveSquare.roundToInt().coerceAtLeast(1)
            val squareBmp = Bitmap.createBitmap(squareSide, squareSide, Bitmap.Config.ARGB_8888)
            val squareCanvas = Canvas(squareBmp)

            val intrW = drawable.intrinsicWidth
            val intrH = drawable.intrinsicHeight
            val (drawW, drawH) = if (intrW > 0 && intrH > 0) {
                val scale = minOf(squareSide.toFloat() / intrW, squareSide.toFloat() / intrH)
                (intrW * scale).roundToInt().coerceAtLeast(1) to (intrH * scale).roundToInt().coerceAtLeast(1)
            } else {
                squareSide to squareSide
            }
            val drawLeft = (squareSide - drawW) / 2
            val drawTop = (squareSide - drawH) / 2
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            copy.setBounds(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)
            copy.draw(squareCanvas)

            val edgeColor = sampleOpaqueEdgeColor(squareBmp) ?: getDynamicPaletteColors(context).first
            contentCanvas.drawColor(edgeColor)
            contentCanvas.drawBitmap(
                squareBmp,
                centerX - squareSide / 2f,
                centerY - squareSide / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        }

        val clampedCorner = cornerRadiusPx.coerceIn(0f, shortSide / 2f)
        if (clampedCorner <= 0.5f) {
            return contentBitmap
        }

        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(output)
        val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(contentBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        outCanvas.drawRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            clampedCorner,
            clampedCorner,
            shaderPaint
        )
        return output
    }

    /**
     * Samples opaque boundary pixels moving inward from the icon edges so legacy icons with
     * transparent borders still yield their actual perimeter color.
     */
    fun sampleOpaqueEdgeColor(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val counts = HashMap<Int, Int>()
        val actualColors = HashMap<Int, Int>()
        val steps = 16
        val maxDepth = (minOf(w, h) / 4).coerceAtLeast(1)

        fun recordSample(x: Int, y: Int): Boolean {
            val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            if (Color.alpha(px) >= 180) {
                val r = (Color.red(px) shr 4)
                val g = (Color.green(px) shr 4)
                val b = (Color.blue(px) shr 4)
                val bucket = (r shl 8) or (g shl 4) or b
                counts[bucket] = (counts[bucket] ?: 0) + 1
                actualColors.putIfAbsent(bucket, Color.rgb(Color.red(px), Color.green(px), Color.blue(px)))
                return true
            }
            return false
        }

        for (i in 0 until steps) {
            val t = (i + 0.5f) / steps
            val sx = (t * w).toInt().coerceIn(0, w - 1)
            val sy = (t * h).toInt().coerceIn(0, h - 1)

            // Top edge inward
            for (d in 0 until maxDepth) { if (recordSample(sx, d)) break }
            // Bottom edge inward
            for (d in 0 until maxDepth) { if (recordSample(sx, h - 1 - d)) break }
            // Left edge inward
            for (d in 0 until maxDepth) { if (recordSample(d, sy)) break }
            // Right edge inward
            for (d in 0 until maxDepth) { if (recordSample(w - 1 - d, sy)) break }
        }

        val bestBucket = counts.maxByOrNull { it.value }?.key ?: return null
        return actualColors[bestBucket]
    }

    private fun buildLargeIconCacheKey(
        context: Context,
        packageName: String,
        activityName: String?,
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Float,
        insetPx: Float
    ): String {
        val dark = if (isDarkMode(context)) "D" else "L"
        val palette = if (isColorPaletteActive(context)) "P" else "N"
        return "$packageName:${activityName.orEmpty()}:${widthPx}x$heightPx:${cornerRadiusPx.roundToInt()}:${insetPx.roundToInt()}:$dark$palette"
    }

    /**
     * Renders a Material You / Samsung Color Palette themed icon:
     * A squircle container filled with dark/light system accent container color,
     * with the monochrome vector shape centered and tinted with system accent color.
     */
    private fun renderThemedAdaptiveIcon(context: Context, monochrome: Drawable, sizePx: Int): Bitmap? {
        try {
            val (bgColor, tintColor) = getDynamicPaletteColors(context)
            val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            // Draw Squircle Container Background
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
            }
            val cornerRadius = sizePx * 0.28f
            canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), cornerRadius, cornerRadius, paint)

            // Inset monochrome icon inside the 66% safe zone
            val inset = (sizePx * 0.18f).toInt()
            val innerSize = sizePx - (inset * 2)

            val monoCopy = monochrome.mutate()
            monoCopy.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
            monoCopy.setBounds(inset, inset, inset + innerSize, inset + innerSize)
            monoCopy.draw(canvas)

            return output
        } catch (e: Exception) {
            return null
        }
    }

    fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun isColorPaletteActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val overlay = Settings.Secure.getString(
                context.contentResolver,
                "theme_customization_overlay_packages"
            )
            !overlay.isNullOrEmpty() && (
                overlay.contains("color_palette") ||
                overlay.contains("system_palette") ||
                overlay.contains("themed_icon") ||
                overlay.contains("icon_theme") ||
                overlay.contains("android.theme.customization")
            )
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun getDynamicPaletteColors(context: Context): Pair<Int, Int> {
        val darkMode = isDarkMode(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return if (darkMode) {
                    val bg = context.getColor(android.R.color.system_accent1_800)
                    val fg = context.getColor(android.R.color.system_accent1_200)
                    Pair(bg, fg)
                } else {
                    val bg = context.getColor(android.R.color.system_accent1_100)
                    val fg = context.getColor(android.R.color.system_accent1_900)
                    Pair(bg, fg)
                }
            } catch (_: Exception) {
                // fall through to deterministic fallback pair
            }
        }
        return if (darkMode) {
            Pair(0xFF3D3617.toInt(), 0xFFDFD59C.toInt())
        } else {
            Pair(0xFFE2E8F0.toInt(), 0xFF1E293B.toInt())
        }
    }

    private fun isBitmapBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val samplePoints = listOf(
            Pair(w / 2, h / 2),
            Pair(w / 3, h / 3),
            Pair(2 * w / 3, h / 3),
            Pair(w / 3, 2 * h / 3),
            Pair(2 * w / 3, 2 * h / 3)
        )
        for ((x, y) in samplePoints) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 25) {
                return false
            }
        }
        return true
    }

    fun getLaunchIntent(context: Context, packageName: String, activityName: String? = null): Intent? {
        val pm = context.packageManager
        if (!activityName.isNullOrEmpty()) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(packageName, activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            if (intent.resolveActivity(pm) != null) {
                return intent
            }
        }
        return pm.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
    }
}
