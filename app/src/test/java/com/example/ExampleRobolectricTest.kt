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
import android.provider.Settings
import android.view.LayoutInflater
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.lifecycle.SavedStateHandle
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.data.WidgetItemTypes
import com.example.ui.MainViewModel
import com.example.util.AppScanner
import com.example.util.IconStyle
import com.example.util.IconStyleManager
import com.example.util.getSystemAnimationScale
import com.example.util.scaledDurationMs
import com.example.util.scaledSpringSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Folder Widget", appName)
  }

  @Test
  fun `folder and large icon entity defaults`() {
    val folder = FolderEntity(name = "Tools")
    assertEquals("Tools", folder.name)
    assertEquals(FolderLayouts.GRID_3X3, folder.layoutType)
    assertEquals(24, folder.cornerRadiusDp)
    assertEquals(true, folder.showLabels)
    assertEquals(false, folder.showPopupLabels)
    assertEquals("ROW_1X2", FolderLayouts.ROW_1X2)
    assertEquals("ROW_1X4", FolderLayouts.ROW_1X4)

    val largeIcon = LargeIconEntity(
      appName = "Calculator",
      packageName = "com.example.calc",
      activityName = "com.example.calc.MainActivity"
    )
    assertEquals(LargeIconOrientations.ICON_1X1, largeIcon.orientation)
    assertEquals(24, largeIcon.cornerRadiusDp)
    assertEquals(0, largeIcon.iconPaddingDp)
    assertEquals(false, largeIcon.showLabel)
    assertEquals("ICON_1X2", LargeIconOrientations.ICON_1X2)
    assertEquals("ICON_2X1", LargeIconOrientations.ICON_2X1)
  }

  @Test
  fun `room migration 2 to 3 preserves existing bindings as FOLDER type`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val helper = FrameworkSQLiteOpenHelperFactory().create(
      SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(null)
        .callback(object : SupportSQLiteOpenHelper.Callback(2) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              """
              CREATE TABLE `widget_bindings` (
                `appWidgetId` INTEGER NOT NULL,
                `folderId` INTEGER NOT NULL,
                `lastUpdatedMillis` INTEGER NOT NULL,
                PRIMARY KEY(`appWidgetId`)
              )
              """.trimIndent()
            )
            db.execSQL(
              "INSERT INTO `widget_bindings` (`appWidgetId`, `folderId`, `lastUpdatedMillis`) VALUES (42, 7, 123456)"
            )
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        })
        .build()
    )

    val db = helper.writableDatabase
    AppDatabase.MIGRATION_2_3.migrate(db)

    val cursor = db.query("SELECT appWidgetId, folderId, itemType, largeIconId FROM widget_bindings WHERE appWidgetId = 42")
    assertTrue(cursor.moveToFirst())
    assertEquals(42, cursor.getInt(0))
    assertEquals(7L, cursor.getLong(1))
    assertEquals(WidgetItemTypes.FOLDER, cursor.getString(2))
    assertEquals(-1L, cursor.getLong(3))
    cursor.close()

    val iconTableCursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='large_icons'")
    assertTrue(iconTableCursor.moveToFirst())
    iconTableCursor.close()
    helper.close()
  }

  @Test
  fun `selectedDashboardTab persists across SavedStateHandle recreation`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val handle1 = SavedStateHandle()
    val vm1 = MainViewModel(app, handle1)
    assertEquals(MainViewModel.TAB_FOLDERS, vm1.selectedDashboardTab.value)

    vm1.setSelectedDashboardTab(MainViewModel.TAB_ICONS)
    assertEquals(MainViewModel.TAB_ICONS, vm1.selectedDashboardTab.value)

    // Simulate process death restoration via saved map
    val savedMap = handle1.keys().associateWith { key -> handle1.get<Any?>(key) }
    val handle2 = SavedStateHandle(savedMap)
    val vm2 = MainViewModel(app, handle2)
    assertEquals(MainViewModel.TAB_ICONS, vm2.selectedDashboardTab.value)

    vm2.setSelectedDashboardTab(MainViewModel.TAB_FOLDERS)
    assertEquals(MainViewModel.TAB_FOLDERS, vm2.selectedDashboardTab.value)
  }

  @Test
  fun `large icon renderer fills widget cleanly for adaptive monochrome and legacy icons`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    IconStyleManager.resetMemoryCacheForTests()

    // 1. Adaptive icon (2x1) without monochrome (or Original style): background fills whole width; centered foreground stays 1:1 unstretched
    IconStyleManager.setIconStyle(context, IconStyle.ORIGINAL)
    val fgBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
      Canvas(bmp).drawRect(24f, 24f, 84f, 84f, Paint().apply { color = Color.RED })
    }
    val fgDrawable = BitmapDrawable(context.resources, fgBmp)
    val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), fgDrawable)
    val bmp2x1 = AppScanner.renderLargeIconDrawableBitmap(
      context = context,
      drawable = adaptive,
      widthPx = 192,
      heightPx = 96,
      cornerRadiusPx = 0f,
      insetPx = 0f
    )
    assertEquals(192, bmp2x1.width)
    assertEquals(96, bmp2x1.height)
    assertEquals(Color.BLUE, bmp2x1.getPixel(4, 48))
    assertEquals(Color.BLUE, bmp2x1.getPixel(187, 48))
    assertEquals(Color.RED, bmp2x1.getPixel(96, 48))

    // Verify 1:1 foreground glyph stays unstretched (vertical & horizontal span match)
    var redWidth = 0
    for (x in 0 until bmp2x1.width) {
      if (bmp2x1.getPixel(x, 48) == Color.RED) redWidth++
    }
    var redHeight = 0
    for (y in 0 until bmp2x1.height) {
      if (bmp2x1.getPixel(96, y) == Color.RED) redHeight++
    }
    assertTrue("Foreground width ($redWidth) and height ($redHeight) must stay 1:1", redWidth > 30 && kotlin.math.abs(redWidth - redHeight) <= 2)

    // 2. Themed style + monochrome layer fills entire 1x2 widget with theme colors
    IconStyleManager.setIconStyle(context, IconStyle.THEMED)
    val monoBmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bmp ->
      Canvas(bmp).drawRect(28f, 28f, 80f, 80f, Paint().apply { color = Color.BLACK })
    }
    val monoAdaptive = AdaptiveIconDrawable(
      ColorDrawable(Color.BLUE),
      fgDrawable,
      BitmapDrawable(context.resources, monoBmp)
    )
    val (paletteBg, paletteFg) = AppScanner.getDynamicPaletteColors(context)
    val themed1x2 = AppScanner.renderLargeIconDrawableBitmap(
      context = context,
      drawable = monoAdaptive,
      widthPx = 96,
      heightPx = 192,
      cornerRadiusPx = 0f,
      insetPx = 0f
    )
    assertEquals(paletteBg, themed1x2.getPixel(48, 4))
    assertEquals(paletteBg, themed1x2.getPixel(48, 187))
    assertEquals(paletteFg, themed1x2.getPixel(48, 96))

    // 3. Legacy BitmapDrawable fills short side and extends sampled edge color across 2x1 wings
    val legacySrc = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { bmp ->
      val c = Canvas(bmp)
      c.drawColor(Color.GREEN)
      c.drawRect(14f, 14f, 34f, 34f, Paint().apply { color = Color.YELLOW })
    }
    val legacyRendered = AppScanner.renderLargeIconDrawableBitmap(
      context = context,
      drawable = BitmapDrawable(context.resources, legacySrc),
      widthPx = 192,
      heightPx = 96,
      cornerRadiusPx = 0f,
      insetPx = 0f
    )
    assertEquals(Color.GREEN, legacyRendered.getPixel(4, 48))
    assertEquals(Color.GREEN, legacyRendered.getPixel(187, 48))
    assertEquals(Color.YELLOW, legacyRendered.getPixel(96, 48))
  }

  @Test
  fun `widget_large_icon uses system background id for launcher morph transitions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = LayoutInflater.from(context).inflate(R.layout.widget_large_icon, null)
    assertEquals(android.R.id.background, root.id)
    assertNotNull(root.findViewById(R.id.large_icon_image))
  }

  @Test
  fun `color palette tones differ across dark and light modes and keep non-monochrome icons original`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    IconStyleManager.resetMemoryCacheForTests()
    IconStyleManager.setIconStyle(context, IconStyle.THEMED)

    val lightConfig = Configuration(context.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    val darkConfig = Configuration(context.resources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    }
    val lightCtx = context.createConfigurationContext(lightConfig)
    val darkCtx = context.createConfigurationContext(darkConfig)
    assertFalse(AppScanner.isDarkMode(lightCtx))
    assertTrue(AppScanner.isDarkMode(darkCtx))
    assertNotEquals(
      AppScanner.getDynamicPaletteColors(lightCtx),
      AppScanner.getDynamicPaletteColors(darkCtx)
    )

    // Non-monochrome AdaptiveIconDrawable must render full color cleanly even when Themed style is enabled
    val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED))
    val rendered = AppScanner.renderIconBitmap(darkCtx, adaptive, 96)
    assertEquals(96, rendered.width)
    assertEquals(96, rendered.height)
    assertEquals(Color.RED, rendered.getPixel(48, 48))
  }

  @Test
  fun `animation scale helpers respect disabled and scaled settings`() {
    assertEquals(0, scaledDurationMs(200, 0f))
    assertEquals(100, scaledDurationMs(200, 0.5f))
    assertEquals(400, scaledDurationMs(200, 2.0f))

    assertTrue(scaledSpringSpec(0f) is SnapSpec)
    val fastSpring = scaledSpringSpec(0.5f, baseStiffness = 460f) as SpringSpec
    val slowSpring = scaledSpringSpec(2.0f, baseStiffness = 460f) as SpringSpec
    assertTrue(fastSpring.stiffness > slowSpring.stiffness)

    val context = ApplicationProvider.getApplicationContext<Context>()
    Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
    assertEquals(0.5f, getSystemAnimationScale(context), 0.01f)
  }

  @Test
  fun `widget preview layouts inflate non-empty filled cards and buildPinPreviewRemoteViews uses real layouts`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val inflater = LayoutInflater.from(context)

    val folderPreview = inflater.inflate(R.layout.widget_folder_preview, null)
    assertNotNull(folderPreview.background)
    assertNotNull(folderPreview.findViewById(R.id.preview_slot_1))
    assertNotNull(folderPreview.findViewById(R.id.preview_slot_4))

    for (layoutId in listOf(
      R.layout.widget_icon_preview_1x1,
      R.layout.widget_icon_preview_1x2,
      R.layout.widget_icon_preview_2x1
    )) {
      val root = inflater.inflate(layoutId, null)
      assertNotNull("Preview $layoutId must have non-transparent card background", root.background)
      assertNotNull(root.findViewById(R.id.large_icon_preview_image))
    }

    val expectedLayouts = mapOf(
      FolderLayouts.GRID_3X3 to R.layout.widget_folder_3x3,
      FolderLayouts.GRID_2X2 to R.layout.widget_folder_2x2,
      FolderLayouts.ROW_1X2 to R.layout.widget_folder_1x2,
      FolderLayouts.ROW_1X4 to R.layout.widget_folder_1x4
    )
    val apps = listOf(
      com.example.data.FolderAppEntity(
        folderId = 1L,
        packageName = context.packageName,
        activityName = "MainActivity",
        appName = "Test"
      )
    )
    for ((layoutType, expectedRes) in expectedLayouts) {
      val rv = com.example.widget.FolderWidgetProvider.buildPinPreviewRemoteViews(
        context = context,
        folder = FolderEntity(id = 1L, name = "Pinned", layoutType = layoutType),
        apps = apps
      )
      assertNotNull(rv)
      assertEquals(expectedRes, rv!!.layoutId)
    }

    val iconRv = com.example.widget.BaseLargeIconWidgetProvider.buildPinPreviewRemoteViews(
      context = context,
      largeIcon = LargeIconEntity(
        id = 2L,
        appName = "App",
        packageName = context.packageName,
        activityName = "MainActivity",
        orientation = LargeIconOrientations.ICON_2X1
      )
    )
    assertNotNull(iconRv)
    assertEquals(R.layout.widget_large_icon, iconRv!!.layoutId)
  }
}
