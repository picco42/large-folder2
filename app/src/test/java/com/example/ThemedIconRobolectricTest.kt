package com.example

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.util.AppScanner
import com.example.util.IconStyle
import com.example.util.IconStyleManager
import com.example.util.ThemedIconTones
import com.example.util.themedIcon
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThemedIconRobolectricTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("icon_style_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        IconStyleManager.resetMemoryCacheForTests()
        AppScanner.clearBitmapCaches()
    }

    @Test
    fun `themedIcon applies tone constants when monochrome exists and preserves original otherwise`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val fgBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(24f, 24f, 84f, 84f, Paint().apply { color = Color.RED })
        }
        val monoBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(30f, 30f, 78f, 78f, Paint().apply { color = Color.BLACK })
        }

        val noMono = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), BitmapDrawable(context.resources, fgBmp))
        assertSame(noMono, themedIcon(context, noMono))

        val legacy = ColorDrawable(Color.MAGENTA)
        assertSame(legacy, themedIcon(context, legacy))

        val withMono = AdaptiveIconDrawable(
            ColorDrawable(Color.BLUE),
            BitmapDrawable(context.resources, fgBmp),
            BitmapDrawable(context.resources, monoBmp)
        )

        val darkConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val darkCtx = context.createConfigurationContext(darkConfig)
        val themedDark = themedIcon(darkCtx, withMono) as AdaptiveIconDrawable
        val expectedDarkBg = darkCtx.getColor(ThemedIconTones.DARK_BACKGROUND_COLOR_RES)
        assertEquals(expectedDarkBg, (themedDark.background as ColorDrawable).color)

        val lightConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val lightCtx = context.createConfigurationContext(lightConfig)
        val themedLight = themedIcon(lightCtx, withMono) as AdaptiveIconDrawable
        val expectedLightBg = lightCtx.getColor(ThemedIconTones.LIGHT_BACKGROUND_COLOR_RES)
        assertEquals(expectedLightBg, (themedLight.background as ColorDrawable).color)
    }

    @Test
    fun `icon style defaults to THEMED on API 33 plus and persists across restarts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(IconStyleManager.isThemedIconsSupported())
        assertEquals(IconStyle.THEMED, IconStyleManager.getIconStyle(context))

        IconStyleManager.setIconStyle(context, IconStyle.ORIGINAL)
        assertEquals(IconStyle.ORIGINAL, IconStyleManager.getIconStyle(context))

        // Simulate fresh process restart by clearing in-memory cache only
        IconStyleManager.resetMemoryCacheForTests()
        assertEquals(IconStyle.ORIGINAL, IconStyleManager.getIconStyle(context))

        IconStyleManager.setIconStyle(context, IconStyle.THEMED)
        IconStyleManager.resetMemoryCacheForTests()
        assertEquals(IconStyle.THEMED, IconStyleManager.getIconStyle(context))
    }

    @Test
    @Config(sdk = [31])
    fun `icon style forces ORIGINAL on API below 33`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(IconStyleManager.isThemedIconsSupported())
        assertEquals(IconStyle.ORIGINAL, IconStyleManager.getIconStyle(context))

        IconStyleManager.setIconStyle(context, IconStyle.THEMED)
        assertEquals(IconStyle.ORIGINAL, IconStyleManager.getIconStyle(context))
    }

    @Test
    fun `renderIconBitmap preserves device adaptive mask shape and switches cleanly between styles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fgBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(30f, 30f, 78f, 78f, Paint().apply { color = Color.RED })
        }
        val monoBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(36f, 36f, 72f, 72f, Paint().apply { color = Color.BLACK })
        }
        val icon = AdaptiveIconDrawable(
            ColorDrawable(Color.BLUE),
            BitmapDrawable(context.resources, fgBmp),
            BitmapDrawable(context.resources, monoBmp)
        )

        IconStyleManager.setIconStyle(context, IconStyle.THEMED)
        val (bg, fg) = IconStyleManager.resolveThemeColors(context)
        val themedBitmap = AppScanner.renderIconBitmap(context, icon, 120)
        // Outer corner (0,0) must remain transparent due to the device icon mask shape
        assertEquals(0, Color.alpha(themedBitmap.getPixel(0, 0)))
        assertEquals(bg, themedBitmap.getPixel(60, 12))
        assertEquals(fg, themedBitmap.getPixel(60, 60))

        IconStyleManager.setIconStyle(context, IconStyle.ORIGINAL)
        val originalBitmap = AppScanner.renderIconBitmap(context, icon, 120)
        assertEquals(0, Color.alpha(originalBitmap.getPixel(0, 0)))
        assertEquals(Color.BLUE, originalBitmap.getPixel(60, 12))
        assertEquals(Color.RED, originalBitmap.getPixel(60, 60))
    }

    @Test
    fun `cache misses automatically on night mode or style changes and prewarm populates cache`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fgBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(30f, 30f, 78f, 78f, Paint().apply { color = Color.RED })
        }
        val monoBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).drawRect(36f, 36f, 72f, 72f, Paint().apply { color = Color.BLACK })
        }
        val testDrawable = AdaptiveIconDrawable(
            ColorDrawable(Color.BLUE),
            BitmapDrawable(context.resources, fgBmp),
            BitmapDrawable(context.resources, monoBmp)
        )
        Shadows.shadowOf(context.packageManager).setApplicationIcon(context.packageName, testDrawable)

        val lightConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val darkConfig = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val lightCtx = context.createConfigurationContext(lightConfig)
        val darkCtx = context.createConfigurationContext(darkConfig)

        IconStyleManager.setIconStyle(lightCtx, IconStyle.THEMED)
        AppScanner.prewarmAppIcons(lightCtx, listOf(context.packageName to null), 96)
        val lightCached = AppScanner.getCachedAppIconBitmap(lightCtx, context.packageName, null, 96)
        assertNotNull(lightCached)

        // Switching night mode must miss light cache cleanly
        val darkCachedBefore = AppScanner.getCachedAppIconBitmap(darkCtx, context.packageName, null, 96)
        assertEquals(null, darkCachedBefore)

        // syncPaletteAndRefreshIfNeeded returns true once on change, then false on repeat
        assertTrue(IconStyleManager.syncPaletteAndRefreshIfNeeded(darkCtx, triggerWidgetUpdate = false))
        assertFalse(IconStyleManager.syncPaletteAndRefreshIfNeeded(darkCtx, triggerWidgetUpdate = false))

        IconStyleManager.setIconStyle(darkCtx, IconStyle.ORIGINAL)
        assertEquals(null, AppScanner.getCachedAppIconBitmap(darkCtx, context.packageName, null, 96))
        assertNotEquals(
            IconStyleManager.getCurrentThemeSignature(lightCtx),
            IconStyleManager.getCurrentThemeSignature(darkCtx)
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconStyleUiApi34Test {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("icon_style_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        IconStyleManager.resetMemoryCacheForTests()
    }

    @Test
    fun `settings icon opens icon style dialog and persists selection on API 34`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("icon_style_settings_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("icon_style_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("icon_style_option_original").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(IconStyle.ORIGINAL, IconStyleManager.getIconStyle(app))

        composeRule.onNodeWithTag("icon_style_option_themed").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(IconStyle.THEMED, IconStyleManager.getIconStyle(app))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31])
class IconStyleUiApi31Test {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `icon style setting option is hidden on API below 33`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("icon_style_settings_button").assertDoesNotExist()
    }
}
