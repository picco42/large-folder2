package com.example

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect as AndroidRect
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.FolderAppEntity
import com.example.data.FolderBgStyles
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.data.LargeIconEntity
import com.example.data.LargeIconOrientations
import com.example.popup.FolderPopupActivity
import com.example.popup.PopupSessionCoordinator
import com.example.ui.MainViewModel
import com.example.ui.components.InAppLargeIconPreviewOverlay
import com.example.ui.components.SamsungFolderPopupOverlay
import com.example.ui.motion.BlurDeviceEnvironment
import com.example.ui.motion.BlurGestureFrameContext
import com.example.ui.motion.BlurLifecycleStage
import com.example.ui.motion.BlurSkipReason
import com.example.ui.motion.ContainerTransformSpec
import com.example.ui.motion.DeferredComposition
import com.example.ui.motion.FolderBlurSpec
import com.example.ui.motion.SharedContainerTransformOverlay
import com.example.ui.motion.WindowBlurLifecycleInterceptor
import com.example.ui.motion.computeCompactContentAlpha
import com.example.ui.motion.computeContainerAlpha
import com.example.ui.motion.computeContainerVisualFrame
import com.example.ui.motion.computeExpandedContentAlpha
import com.example.ui.motion.computeGeometryProgress
import com.example.ui.motion.computeOcclusionAlpha
import com.example.ui.motion.computePredictiveBackState
import com.example.ui.screens.FolderEditorScreen
import com.example.ui.screens.LargeIconEditorScreen
import com.example.ui.theme.FolderWidgetTheme
import com.example.util.OriginSourceKind
import com.example.util.WidgetBoundsCache
import com.example.widget.FolderWidgetProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ContainerTransformMotionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetBoundsCache.clearAll(context)
        WidgetBoundsCache.clearRecentDebugEvents()
        PopupSessionCoordinator.resetForTest()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
    }

    @Test
    fun `bounds cache stores separate instances clamps to screen and discards on config change`() {
        val screen = WidgetBoundsCache.getScreenBoundsPx(context)

        // Two widget instances pointing to the same folder keep independent bounds
        val resolvedA = WidgetBoundsCache.resolveWidgetOrigin(
            context = context,
            appWidgetId = 101,
            intentSourceBounds = AndroidRect(20, 40, 220, 240),
            layoutType = FolderLayouts.GRID_2X2,
            showLabels = true
        )
        val resolvedB = WidgetBoundsCache.resolveWidgetOrigin(
            context = context,
            appWidgetId = 202,
            intentSourceBounds = AndroidRect(-50, 300, screen.right.toInt() + 100, 460),
            layoutType = FolderLayouts.GRID_2X2,
            showLabels = true
        )
        assertEquals(OriginSourceKind.LIVE_SOURCE_BOUNDS, resolvedA.sourceKind)
        assertEquals(Rect(20f, 40f, 220f, 240f), resolvedA.bounds)
        assertTrue(resolvedB.bounds.left >= screen.left)
        assertTrue(resolvedB.bounds.right <= screen.right)

        // Subsequent launch for 101 with null sourceBounds uses cached bounds & logs event
        val cachedA = WidgetBoundsCache.resolveWidgetOrigin(
            context = context,
            appWidgetId = 101,
            intentSourceBounds = null,
            layoutType = FolderLayouts.GRID_2X2,
            showLabels = true
        )
        assertEquals(OriginSourceKind.CACHED_BOUNDS, cachedA.sourceKind)
        assertEquals(resolvedA.bounds, cachedA.bounds)
        assertTrue(WidgetBoundsCache.getRecentDebugEvents().any { it.appWidgetId == 101 && it.sourceKind == OriginSourceKind.CACHED_BOUNDS })

        // Orientation/config change invalidates stale cached bounds cleanly
        val landscapeCfg = Configuration(context.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        val landscapeCtx = context.createConfigurationContext(landscapeCfg)
        val afterRotate = WidgetBoundsCache.resolveWidgetOrigin(
            context = landscapeCtx,
            appWidgetId = 101,
            intentSourceBounds = null,
            layoutType = FolderLayouts.GRID_2X2,
            showLabels = true
        )
        assertEquals(OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK, afterRotate.sourceKind)
        assertNull(WidgetBoundsCache.getValidCachedBounds(landscapeCtx, 101))
    }

    @Test
    fun `center fallback uses AppWidgetManager options and never collapses to a dot`() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(appWidgetManager).createWidget(
            FolderWidgetProvider::class.java,
            R.layout.widget_folder_1x4
        )
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        }
        appWidgetManager.updateAppWidgetOptions(widgetId, opts)

        val resolved = WidgetBoundsCache.resolveWidgetOrigin(
            context = context,
            appWidgetId = widgetId,
            intentSourceBounds = null,
            layoutType = FolderLayouts.ROW_1X4,
            showLabels = false
        )

        assertEquals(OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK, resolved.sourceKind)
        assertFalse(resolved.hasConcreteWidgetOrigin)

        val density = context.resources.displayMetrics.density
        assertEquals(200f * density, resolved.bounds.width, 4f)
        assertEquals(90f * density, resolved.bounds.height, 4f)

        val screen = WidgetBoundsCache.getScreenBoundsPx(context)
        assertEquals(screen.center.x, resolved.bounds.center.x, 2f)
        assertEquals(screen.center.y, resolved.bounds.center.y, 2f)
    }

    @Test
    fun `choreography keeps concrete container opaque through end crossfades early and late and close spring has zero overshoot`() {
        val openSpring = ContainerTransformSpec.openSpatialSpring(1f) as SpringSpec
        val closeSpring = ContainerTransformSpec.closeSpatialSpring(1f) as SpringSpec
        assertTrue(closeSpring.stiffness > openSpring.stiffness)
        assertEquals(1.0f, closeSpring.dampingRatio, 0.001f)

        assertEquals(1f, computeContainerAlpha(progress = 0.0f, hasOriginBounds = true, hasMeasuredLayout = true), 0.001f)
        assertEquals(1f, computeContainerAlpha(progress = 0.5f, hasOriginBounds = true, hasMeasuredLayout = true), 0.001f)
        assertEquals(1f, computeCompactContentAlpha(progress = 0.0f, hasOriginBounds = true), 0.001f)
        assertEquals(0f, computeExpandedContentAlpha(progress = 0.28f), 0.001f)
        assertEquals(1f, computeExpandedContentAlpha(progress = 0.85f), 0.001f)

        for (step in 0..100) {
            val p = step / 100f
            val expA = computeExpandedContentAlpha(p)
            val compExactA = computeCompactContentAlpha(p, hasOriginBounds = true)
            val compFallbackA = computeCompactContentAlpha(p, hasOriginBounds = false)
            assertTrue(
                "Crossfade overlap at p=$p: expanded=$expA compactExact=$compExactA",
                expA == 0f || compExactA == 0f
            )
            assertTrue(
                "Fallback crossfade overlap at p=$p: expanded=$expA compactFallback=$compFallbackA",
                expA == 0f || compFallbackA == 0f
            )
        }

        val origin = Rect(60f, 800f, 300f, 920f)
        val endFrame = computeContainerVisualFrame(
            progress = 0f,
            originBounds = origin,
            originCornerRadiusPx = 24f,
            expandedCenter = Offset(540f, 1000f),
            expandedSize = IntSize(900, 1000),
            expandedCornerRadiusPx = 72f,
            expandedElevationPx = 40f,
            hasOriginBounds = true,
            hasMeasuredLayout = true,
            preCollapseScale = 1f,
            preCollapseOffset = Offset.Zero
        )
        assertEquals(1f, endFrame.containerAlpha, 0.001f)
        assertEquals(1f, endFrame.compactAlpha, 0.001f)
        assertEquals(0f, endFrame.expandedAlpha, 0.001f)
        assertEquals(24f, endFrame.cornerRadiusRxPx * endFrame.scaleX, 0.01f)
        assertEquals(24f, endFrame.cornerRadiusRyPx * endFrame.scaleY, 0.01f)

        assertEquals(0f, computeGeometryProgress(0.20f, hasOriginBounds = false), 0.001f)
        assertEquals(1f, computeContainerAlpha(0.30f, hasOriginBounds = false, hasMeasuredLayout = true), 0.001f)
        assertEquals(0f, computeContainerAlpha(0.0f, hasOriginBounds = false, hasMeasuredLayout = true), 0.001f)

        // Temporary Surface occlusion shield stays transparent for frosted glass above 0.55f,
        // solidifies for compact handoff between 0.55f..0.40f, and releases at 0.0f
        assertEquals(0f, computeOcclusionAlpha(progress = 1.0f, isTransitioning = false), 0.001f)
        assertEquals(0f, computeOcclusionAlpha(progress = 0.60f, isTransitioning = true), 0.001f)
        assertEquals(1f, computeOcclusionAlpha(progress = 0.30f, isTransitioning = true), 0.001f)
        assertEquals(1f, computeOcclusionAlpha(progress = 0.10f, isTransitioning = true), 0.001f)
        assertEquals(0f, computeOcclusionAlpha(progress = 0.0f, isTransitioning = true), 0.001f)
    }

    @Test
    fun `predictive back keeps scale at least 90 percent and maintains 8dp margin from all screen edges`() {
        val density = 3f
        val edgeMarginPx = 8f * density
        val screenW = 1080f
        val screenH = 2400f
        val cardSize = IntSize(984, 1200)
        val cardCenter = Offset(540f, 1200f)

        val fullBack = computePredictiveBackState(
            rawProgress = 1f,
            swipeEdge = BackEventCompat.EDGE_LEFT,
            touchDeltaYRatio = 1f,
            cardCenter = cardCenter,
            cardSize = cardSize,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            edgeMarginPx = edgeMarginPx,
            density = density
        )
        assertTrue(fullBack.scale >= 0.90f - 0.0001f)

        val halfW = (cardSize.width * fullBack.scale) / 2f
        val halfH = (cardSize.height * fullBack.scale) / 2f
        val left = cardCenter.x + fullBack.offset.x - halfW
        val right = cardCenter.x + fullBack.offset.x + halfW
        val top = cardCenter.y + fullBack.offset.y - halfH
        val bottom = cardCenter.y + fullBack.offset.y + halfH

        assertTrue("Left edge $left violated 8dp margin", left >= edgeMarginPx - 0.1f)
        assertTrue("Right edge $right violated 8dp margin", right <= screenW - edgeMarginPx + 0.1f)
        assertTrue("Top edge $top violated 8dp margin", top >= edgeMarginPx - 0.1f)
        assertTrue("Bottom edge $bottom violated 8dp margin", bottom <= screenH - edgeMarginPx + 0.1f)
    }

    @Test
    fun `shared overlay supports predictive back cancel and commit without jump`() {
        var dismissedCount = 0
        composeRule.setContent {
            FolderWidgetTheme {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides composeRule.activity) {
                    SharedContainerTransformOverlay(
                        originBounds = Rect(40f, 100f, 200f, 260f),
                        originBoundsAreScreenCoords = false,
                        originCornerRadiusDp = 24f,
                        hasExactOrCachedOrigin = true,
                        cardBgColor = Color(0xFF1E293B),
                        cardBorderColor = Color.White.copy(alpha = 0.2f),
                        onDismissCompleted = { dismissedCount++ },
                        compactContent = { Text("Compact") },
                        expandedContent = { _, _ -> Text("Expanded") }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shared_container_card").assertIsDisplayed()

        // Predictive back start -> progress -> cancel must NOT dismiss
        composeRule.runOnUiThread {
            val dispatcher: OnBackPressedDispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 220f, 0.65f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()
        assertEquals(0, dismissedCount)

        // Commit back from started gesture -> collapses smoothly and invokes onDismissCompleted
        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(90f, 210f, 0.45f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, dismissedCount)
    }

    @Test
    fun `reduced motion scale 0 completes open and scrim dismiss cleanly`() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var dismissed = false
        val folder = FolderEntity(id = 99, name = "Work", layoutType = FolderLayouts.ROW_1X4, bgStyle = FolderBgStyles.DYNAMIC)
        val apps = listOf(
            FolderAppEntity(folderId = 99, packageName = "com.test.one", activityName = "A", appName = "One")
        )

        composeRule.setContent {
            FolderWidgetTheme {
                SamsungFolderPopupOverlay(
                    folder = folder,
                    apps = apps,
                    sourceBounds = Rect(20f, 300f, 280f, 380f),
                    hasExactOrCachedOrigin = true,
                    onDismiss = { dismissed = true },
                    onLaunchApp = { _, _ -> }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("samsung_expanded_card").assertIsDisplayed()
        // Click exposed top-left scrim margin (outside centered card)
        composeRule.onNodeWithTag("samsung_popup_surface_layer_scrim").performTouchInput {
            click(Offset(12f, 12f))
        }
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `in-app large icon overlay shares same controller for 1x1 1x2 and 2x1`() {
        var activeIcon by mutableStateOf<LargeIconEntity?>(
            LargeIconEntity(
                id = 5L,
                appName = "Notes 1x1",
                packageName = "com.example.notes",
                activityName = "Main",
                orientation = LargeIconOrientations.ICON_1X1
            )
        )
        var dismissCount = 0

        composeRule.setContent {
            FolderWidgetTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    activeIcon?.let { icon ->
                        InAppLargeIconPreviewOverlay(
                            largeIcon = icon,
                            originBounds = Rect(80f, 160f, 180f, 260f),
                            onDismiss = {
                                dismissCount++
                                activeIcon = null
                            },
                            onLaunchApp = { _, _ -> },
                            onPin = {},
                            onCustomize = {}
                        )
                    }
                }
            }
        }

        for (orientation in listOf(
            LargeIconOrientations.ICON_1X1,
            LargeIconOrientations.ICON_1X2,
            LargeIconOrientations.ICON_2X1
        )) {
            activeIcon = LargeIconEntity(
                id = 5L,
                appName = "Notes $orientation",
                packageName = "com.example.notes",
                activityName = "Main",
                orientation = orientation
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("large_icon_expanded_card").assertIsDisplayed()
            composeRule.onNodeWithTag("large_icon_overlay_close").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(3, dismissCount)
    }

    @Test
    fun `FolderPopupActivity saves and restores sourceBounds and appWidgetId across state recreation`() {
        val db = AppDatabase.getDatabase(context)
        val folderId = runBlocking {
            db.folderDao().insertFolder(
                FolderEntity(name = "Media", layoutType = FolderLayouts.GRID_2X2)
            )
        }
        val intent = Intent(context, FolderPopupActivity::class.java).apply {
            putExtra("com.example.EXTRA_FOLDER_ID", folderId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 777)
            sourceBounds = AndroidRect(40, 100, 200, 260)
        }

        val controller = Robolectric.buildActivity(FolderPopupActivity::class.java, intent)
            .create()
            .start()
            .resume()

        val cached = WidgetBoundsCache.getValidCachedBounds(context, 777)
        assertNotNull(cached)
        assertEquals(Rect(40f, 100f, 200f, 260f), cached)

        val outState = Bundle()
        controller.saveInstanceState(outState)
        assertEquals(777, outState.getInt("state_appwidget_id"))
        assertEquals(folderId, outState.getLong("state_folder_id"))
        val restoredArr = outState.getFloatArray("state_bounds_array")
        assertNotNull(restoredArr)
        assertEquals(4, restoredArr!!.size)
        assertEquals(40f, restoredArr[0], 0.1f)
    }

    @Test
    fun `all four folder widget layouts expose system background id and non-clickable overflow_slot`() {
        val inflater = LayoutInflater.from(context)
        val layouts = listOf(
            R.layout.widget_folder_3x3,
            R.layout.widget_folder_2x2,
            R.layout.widget_folder_1x2,
            R.layout.widget_folder_1x4
        )
        for (layoutId in layouts) {
            val root = inflater.inflate(layoutId, null)
            assertNotNull("Layout $layoutId must expose android.R.id.background", root.findViewById(android.R.id.background))
            assertNotNull("Layout $layoutId must expose slot_1", root.findViewById(R.id.slot_1))
            val overflowSlot = root.findViewById<android.view.View>(R.id.overflow_slot)
            val overflowIcon = root.findViewById<android.view.View>(R.id.overflow_slot_icon)
            assertNotNull("Layout $layoutId must expose overflow_slot", overflowSlot)
            assertNotNull("Layout $layoutId must expose overflow_slot_icon", overflowIcon)
            assertFalse("overflow_slot must not be clickable so taps bubble to card background", overflowSlot.isClickable)
            assertFalse("overflow_slot_icon must not be clickable so taps bubble to card background", overflowIcon.isClickable)
        }
    }

    @Test
    fun `FolderPopupActivity launched from folder label preserves cached full-card bounds`() {
        val db = AppDatabase.getDatabase(context)
        val folderId = runBlocking {
            db.folderDao().insertFolder(
                FolderEntity(name = "Social", layoutType = FolderLayouts.GRID_3X3)
            )
        }
        val fullCardBounds = Rect(20f, 100f, 280f, 360f)
        WidgetBoundsCache.putBounds(context, 555, fullCardBounds)

        val labelIntent = Intent(context, FolderPopupActivity::class.java).apply {
            putExtra(FolderWidgetProvider.EXTRA_FOLDER_ID, folderId)
            putExtra(FolderWidgetProvider.EXTRA_FROM_FOLDER_LABEL, true)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 555)
            // Simulate launcher passing tiny label strip rect
            sourceBounds = AndroidRect(100, 364, 200, 390)
        }

        Robolectric.buildActivity(FolderPopupActivity::class.java, labelIntent)
            .create()
            .start()
            .resume()

        val cached = WidgetBoundsCache.getValidCachedBounds(context, 555)
        assertEquals(fullCardBounds, cached)
    }

    @Test
    fun `PopupSessionCoordinator cleans up stale awaiting popup on widget or app boundary`() {
        val db = AppDatabase.getDatabase(context)
        val folderId = runBlocking {
            db.folderDao().insertFolder(
                FolderEntity(name = "Work", layoutType = FolderLayouts.ROW_1X4)
            )
        }
        val intent = Intent(context, FolderPopupActivity::class.java).apply {
            putExtra("com.example.EXTRA_FOLDER_ID", folderId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 888)
            sourceBounds = AndroidRect(20, 120, 280, 200)
        }

        val controller = Robolectric.buildActivity(FolderPopupActivity::class.java, intent)
            .create()
            .start()
            .resume()
        val activity = controller.get()

        PopupSessionCoordinator.markLaunchingExternalApp(
            activity = activity,
            configKey = WidgetBoundsCache.buildDisplayConfigKey(context)
        )
        assertTrue(PopupSessionCoordinator.isAwaitingAppReturn())

        // Simulates user swiping Home and a widget lifecycle or app resume event firing
        val dismissed = PopupSessionCoordinator.dismissStaleAwaitingPopup("widget_update")
        assertTrue(dismissed)
        assertTrue(activity.isFinishing)
        assertFalse(PopupSessionCoordinator.isAwaitingAppReturn())
    }

    @Test
    fun `recreating FolderPopupActivity with awaitingAppReturn true finishes silently without ghost popup`() {
        val savedState = Bundle().apply {
            putLong("state_folder_id", 12L)
            putInt("state_appwidget_id", 999)
            putBoolean("state_awaiting_app_return", true)
        }
        val controller = Robolectric.buildActivity(FolderPopupActivity::class.java)
            .create(savedState)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp")
    fun `FolderEditorScreen places app selection card at top under preview above folder name`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = MainViewModel(app, SavedStateHandle())
        composeRule.setContent {
            FolderWidgetTheme {
                FolderEditorScreen(
                    viewModel = vm,
                    folderIdToEdit = null,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()
        val appsBounds = composeRule.onNodeWithTag("add_apps_button").fetchSemanticsNode().boundsInRoot
        val nameBounds = composeRule.onNodeWithTag("editor_folder_name_input").fetchSemanticsNode().boundsInRoot
        assertTrue("App selector bounds must be laid out: $appsBounds", appsBounds.height > 0f)
        assertTrue("Folder Name bounds must be laid out: $nameBounds", nameBounds.height > 0f)
        assertTrue(
            "App selector ($appsBounds) must appear above Folder Name ($nameBounds)",
            appsBounds.bottom <= nameBounds.top + 1f
        )
    }

    @Test
    fun `DeferredComposition defers mount until enabled and disposes on exit`() {
        var shouldCompose by mutableStateOf(false)
        composeRule.setContent {
            FolderWidgetTheme {
                DeferredComposition(shouldCompose = shouldCompose) {
                    Text("DeferredChild", modifier = Modifier.testTag("deferred_child"))
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("deferred_child")).fetchSemanticsNodes().size)

        shouldCompose = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("deferred_child").assertIsDisplayed()

        shouldCompose = false
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("deferred_child")).fetchSemanticsNodes().size)
    }

    @Test
    fun `WidgetBoundsCache rejects tiny sub-slot sourceBounds when AppWidgetOptions defines full widget size`() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(appWidgetManager).createWidget(
            FolderWidgetProvider::class.java,
            R.layout.widget_folder_3x3
        )
        val opts = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 240)
        }
        appWidgetManager.updateAppWidgetOptions(widgetId, opts)

        val fullCardBounds = Rect(20f, 60f, 260f, 300f)
        WidgetBoundsCache.putBounds(context, widgetId, fullCardBounds)

        // Simulate sub-slot rect (<55% of 240dp widget width) sent by a launcher for the 3-dots corner
        val resolved = WidgetBoundsCache.resolveWidgetOrigin(
            context = context,
            appWidgetId = widgetId,
            intentSourceBounds = AndroidRect(180, 220, 240, 280),
            layoutType = FolderLayouts.GRID_3X3,
            showLabels = false
        )

        assertEquals(OriginSourceKind.CACHED_BOUNDS, resolved.sourceKind)
        assertEquals(fullCardBounds, resolved.bounds)
    }

    @Test
    fun `FolderBlurSpec matches Samsung One UI blur bounds, frosted tints, and stronger no-blur fallback`() {
        assertTrue(FolderBlurSpec.MAX_BLUR_RADIUS_DP.value in 30f..60f)
        assertTrue(FolderBlurSpec.maxBlurRadiusPx(androidx.compose.ui.unit.Density(3f)) >= 120)
        assertTrue(FolderBlurSpec.BLUR_ENABLED_SCRIM_ALPHA in 0.15f..0.25f)
        assertTrue(FolderBlurSpec.CARD_TINT_ALPHA_LIGHT in 0.25f..0.40f)
        assertTrue(FolderBlurSpec.CARD_TINT_ALPHA_DARK in 0.25f..0.40f)
        assertTrue(FolderBlurSpec.HIGHLIGHT_EDGE_ALPHA_DARK <= 0.10f)

        assertEquals(0f, FolderBlurSpec.computeBlurProgress(0f), 0.001f)
        assertEquals(1f, FolderBlurSpec.computeBlurProgress(1f), 0.001f)
        assertTrue(
            FolderBlurSpec.scrimAlpha(isBlurEnabled = false) >
                FolderBlurSpec.scrimAlpha(isBlurEnabled = true)
        )

        for (style in listOf(
            FolderBgStyles.DYNAMIC,
            FolderBgStyles.FROSTED_LIGHT,
            FolderBgStyles.FROSTED_DARK,
            FolderBgStyles.CLEAR
        )) {
            for (dark in listOf(false, true)) {
                val blurOn = FolderBlurSpec.cardTintAlpha(style, dark, isBlurEnabled = true)
                val blurOff = FolderBlurSpec.cardTintAlpha(style, dark, isBlurEnabled = false)
                assertTrue(
                    "Fallback tint alpha ($blurOff) must exceed blur-on tint ($blurOn) for $style dark=$dark",
                    blurOff > blurOn
                )
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp")
    fun `FolderEditorScreen removes reorder helper text and opens docked non-floating app sheet`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = MainViewModel(app, SavedStateHandle())
        composeRule.setContent {
            FolderWidgetTheme {
                FolderEditorScreen(
                    viewModel = vm,
                    folderIdToEdit = null,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(
            0,
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("Long-press & drag icons to reorder"))
                .fetchSemanticsNodes().size
        )

        composeRule.onNodeWithTag("add_apps_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("folder_add_apps_sheet").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp")
    fun `LargeIconEditorScreen opens docked non-floating app picker sheet`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = MainViewModel(app, SavedStateHandle())
        composeRule.setContent {
            FolderWidgetTheme {
                LargeIconEditorScreen(
                    viewModel = vm,
                    largeIconIdToEdit = null,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pick_icon_app_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("icon_select_app_sheet").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp")
    fun `predictive back gesture on multi-row SamsungFolderPopupOverlay completes smoothly without layout jump`() {
        val folder = FolderEntity(
            id = 42L,
            name = "Daily",
            layoutType = FolderLayouts.GRID_3X3,
            bgStyle = FolderBgStyles.FROSTED_DARK
        )
        val apps = (1..16).map { i ->
            FolderAppEntity(
                folderId = 42L,
                packageName = "com.example.app$i",
                activityName = "MainActivity",
                appName = "App $i",
                positionIndex = i - 1
            )
        }
        var dismissCount = 0

        composeRule.setContent {
            FolderWidgetTheme {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides composeRule.activity) {
                    SamsungFolderPopupOverlay(
                        folder = folder,
                        apps = apps,
                        sourceBounds = Rect(40f, 160f, 320f, 440f),
                        hasExactOrCachedOrigin = true,
                        onDismiss = { dismissCount++ },
                        onLaunchApp = { _, _ -> }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val expandedCard = composeRule.onNodeWithTag("samsung_expanded_card")
        expandedCard.assertIsDisplayed()
        val initialBounds = expandedCard.fetchSemanticsNode().boundsInRoot
        assertTrue("Multi-row card height must be taller than compact origin: $initialBounds", initialBounds.height > 300f)

        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(8f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(60f, 305f, 0.25f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(140f, 310f, 0.65f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }

        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun `effective blur progress stays continuous and monotonic through predictive back and collapse`() {
        assertEquals(0f, FolderBlurSpec.computeEffectiveBlurProgress(0f, 0f), 0.001f)
        assertEquals(1f, FolderBlurSpec.computeEffectiveBlurProgress(1f, 0f), 0.001f)

        val midBack = FolderBlurSpec.computeEffectiveBlurProgress(1f, 0.5f)
        val fullBack = FolderBlurSpec.computeEffectiveBlurProgress(1f, 1f)
        assertTrue("Mid back blur ($midBack) must sit strictly between fullBack ($fullBack) and 1f", midBack in fullBack..1f)
        assertEquals(FolderBlurSpec.PREDICTIVE_BACK_BLUR_MIN_FACTOR, fullBack, 0.001f)

        // Committing close from full back drag must decrease monotonically without snapping back to 1.0f
        var prev = fullBack
        for (step in 9 downTo 0) {
            val p = step / 10f
            val cur = FolderBlurSpec.computeEffectiveBlurProgress(containerProgress = p, backDragProgress = 1f)
            assertTrue("Collapse blur at p=$p ($cur) must not jump above previous ($prev)", cur <= prev + 0.0001f)
            prev = cur
        }
        assertEquals(0f, prev, 0.001f)
    }

    @Test
    fun `SharedContainerTransformOverlay synchronizes onBlurProgressChanged with predictive back drag, cancel, and commit`() {
        val blurUpdates = mutableListOf<Float>()
        var dismissCount = 0

        composeRule.setContent {
            FolderWidgetTheme {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides composeRule.activity) {
                    SharedContainerTransformOverlay(
                        originBounds = Rect(40f, 100f, 200f, 260f),
                        originBoundsAreScreenCoords = false,
                        originCornerRadiusDp = 24f,
                        hasExactOrCachedOrigin = true,
                        cardBgColor = Color(0xFF1E293B),
                        cardBorderColor = Color.White.copy(alpha = 0.2f),
                        isSystemBlurEnabled = true,
                        onBlurProgressChanged = { blurUpdates.add(it) },
                        onDismissCompleted = { dismissCount++ },
                        compactContent = { Text("Compact") },
                        expandedContent = { _, _ -> Text("Expanded") }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(1f, blurUpdates.last(), 0.01f)

        blurUpdates.clear()
        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 210f, 1.0f, BackEventCompat.EDGE_LEFT))
        }
        assertTrue(
            "Predictive back progress must synchronously drop blur proportionally (~0.80f), never 0f: $blurUpdates",
            blurUpdates.any { it in 0.70f..0.95f }
        )

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()
        assertEquals(1f, blurUpdates.last(), 0.01f)

        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 210f, 0.7f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
        assertEquals(0f, blurUpdates.last(), 0.001f)
    }

    @Test
    fun `WindowBlurLifecycleInterceptor logs Samsung device banner, sub-2px skips, and monotonic violations`() {
        val logLines = mutableListOf<String>()
        var fakeClockNs = 1_000_000_000L
        val samsungDevice = BlurDeviceEnvironment(
            manufacturer = "samsung",
            model = "SM-S928B",
            sdkInt = 34,
            isSamsung = true,
            oneUiSemPlatformInt = 150100,
            oneUiVersionName = BlurDeviceEnvironment.formatOneUiVersion(150100)
        )
        assertEquals("6.1", samsungDevice.oneUiVersionName)

        val interceptor = WindowBlurLifecycleInterceptor(
            clockNanos = { fakeClockNs },
            logger = { _, _, msg -> logLines.add(msg) },
            device = samsungDevice
        )
        var currentAppliedPx = -1
        val appliedHistory = mutableListOf<Int>()

        interceptor.beginSession(
            folderId = 7L,
            isSystemBlurEnabled = true,
            maxBlurPx = 174,
            apiLevel = 34
        )
        assertTrue(logLines.first().contains("samsung=true"))
        assertTrue(logLines.first().contains("OneUI 6.1"))

        // Opening reach 174px cleanly
        interceptor.recordGestureFrame(
            BlurGestureFrameContext(
                stage = BlurLifecycleStage.OPEN_ANIMATION,
                containerProgress = 1f,
                rawGestureProgress = 0f,
                easedGestureProgress = 0f,
                gestureScale = 1f,
                gestureTranslationXPx = 0f,
                gestureTranslationYPx = 0f,
                timestampNanos = fakeClockNs
            )
        )
        currentAppliedPx = interceptor.interceptWindowBlurUpdate(
            dispatchedBlurProgress = 1f,
            maxBlurPx = 174,
            isSystemBlurEnabled = true,
            apiLevel = 34,
            currentAppliedPx = currentAppliedPx
        ) { px -> appliedHistory.add(px) }
        assertEquals(174, currentAppliedPx)

        // Small back movement (<2px delta -> 173px) should be intercepted & logged as SUB_2PX_THROTTLE
        fakeClockNs += 16_600_000L
        interceptor.recordGestureFrame(
            BlurGestureFrameContext(
                stage = BlurLifecycleStage.BACK_GESTURE_PROGRESS,
                containerProgress = 1f,
                rawGestureProgress = 0.015f,
                easedGestureProgress = 0.03f,
                gestureScale = 0.997f,
                gestureTranslationXPx = 1.2f,
                gestureTranslationYPx = 0.4f,
                timestampNanos = fakeClockNs
            )
        )
        currentAppliedPx = interceptor.interceptWindowBlurUpdate(
            dispatchedBlurProgress = 173f / 174f,
            maxBlurPx = 174,
            isSystemBlurEnabled = true,
            apiLevel = 34,
            currentAppliedPx = currentAppliedPx
        ) { px -> appliedHistory.add(px) }
        assertEquals(174, currentAppliedPx)
        assertEquals(BlurSkipReason.SUB_2PX_THROTTLE, interceptor.getSessionRecords().last().skipReason)
        assertTrue(logLines.last().contains("SKIPPED:SUB_2PX_THROTTLE"))

        // Simulated non-monotonic blur spike during CLOSE_ANIMATION should be flagged
        fakeClockNs += 16_600_000L
        interceptor.recordGestureFrame(
            BlurGestureFrameContext(
                stage = BlurLifecycleStage.CLOSE_ANIMATION,
                containerProgress = 0.6f,
                rawGestureProgress = 0f,
                easedGestureProgress = 0f,
                gestureScale = 0.9f,
                gestureTranslationXPx = 0f,
                gestureTranslationYPx = 0f,
                timestampNanos = fakeClockNs
            )
        )
        currentAppliedPx = interceptor.interceptWindowBlurUpdate(
            dispatchedBlurProgress = 0.6f,
            maxBlurPx = 174,
            isSystemBlurEnabled = true,
            apiLevel = 34,
            currentAppliedPx = currentAppliedPx
        ) { px -> appliedHistory.add(px) }

        fakeClockNs += 16_600_000L
        interceptor.recordGestureFrame(
            BlurGestureFrameContext(
                stage = BlurLifecycleStage.CLOSE_ANIMATION,
                containerProgress = 0.4f,
                rawGestureProgress = 0f,
                easedGestureProgress = 0f,
                gestureScale = 0.9f,
                gestureTranslationXPx = 0f,
                gestureTranslationYPx = 0f,
                timestampNanos = fakeClockNs
            )
        )
        currentAppliedPx = interceptor.interceptWindowBlurUpdate(
            dispatchedBlurProgress = 0.75f,
            maxBlurPx = 174,
            isSystemBlurEnabled = true,
            apiLevel = 34,
            currentAppliedPx = currentAppliedPx
        ) { px -> appliedHistory.add(px) }

        val summary = interceptor.endSession("DISMISSED")!!
        assertEquals(1, summary.skippedBySub2PxThrottle)
        assertEquals(1, summary.monotonicViolationCount)
        assertTrue(summary.diagnosticFlags.contains("NON_MONOTONIC"))
        assertTrue(logLines.last().contains("[SESSION_SUMMARY"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h915dp")
    fun `SharedContainerTransformOverlay correlates window blur levels with predictive back and close frames`() {
        val logLines = mutableListOf<String>()
        val interceptor = WindowBlurLifecycleInterceptor(
            logger = { _, _, msg -> logLines.add(msg) }
        )
        val maxBlurPx = 174
        var currentAppliedPx = -1
        var dismissCount = 0

        interceptor.beginSession(
            folderId = 99L,
            isSystemBlurEnabled = true,
            maxBlurPx = maxBlurPx,
            apiLevel = 34
        )

        composeRule.setContent {
            FolderWidgetTheme {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides composeRule.activity) {
                    SharedContainerTransformOverlay(
                        originBounds = Rect(40f, 160f, 220f, 340f),
                        originBoundsAreScreenCoords = false,
                        originCornerRadiusDp = 24f,
                        hasExactOrCachedOrigin = true,
                        cardBgColor = Color(0xFF1E293B),
                        cardBorderColor = Color.White.copy(alpha = 0.1f),
                        isSystemBlurEnabled = true,
                        onBlurProgressChanged = { progress ->
                            currentAppliedPx = interceptor.interceptWindowBlurUpdate(
                                dispatchedBlurProgress = progress,
                                maxBlurPx = maxBlurPx,
                                isSystemBlurEnabled = true,
                                apiLevel = 34,
                                currentAppliedPx = currentAppliedPx
                            ) { _ -> }
                        },
                        blurLifecycleInterceptor = interceptor,
                        onDismissCompleted = {
                            interceptor.endSession("DISMISSED")
                            dismissCount++
                        },
                        compactContent = { Text("Compact") },
                        expandedContent = { _, _ -> Text("Expanded") }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(maxBlurPx, currentAppliedPx)

        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 205f, 0.35f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(150f, 210f, 0.85f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        composeRule.waitForIdle()
        assertEquals(maxBlurPx, currentAppliedPx)

        composeRule.runOnUiThread {
            val dispatcher = composeRule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 210f, 0.70f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
        assertEquals(0, currentAppliedPx)

        val records = interceptor.getSessionRecords()
        assertTrue(records.any { it.stage == BlurLifecycleStage.OPEN_ANIMATION || it.stage == BlurLifecycleStage.IDLE_OPEN })
        assertTrue(records.any { it.stage == BlurLifecycleStage.BACK_GESTURE_PROGRESS && it.rawGestureProgress >= 0.8f })
        assertTrue(records.any { it.stage == BlurLifecycleStage.BACK_GESTURE_CANCEL })
        assertTrue(records.any { it.stage == BlurLifecycleStage.CLOSE_ANIMATION || it.stage == BlurLifecycleStage.WINDOW_DETACHED })

        val backDragRecords = records.filter { it.stage == BlurLifecycleStage.BACK_GESTURE_PROGRESS }
        assertTrue(
            "Back drag blur drift must stay tightly synchronized (<= 0.02f): $backDragRecords",
            backDragRecords.all { kotlin.math.abs(it.blurVsGestureDrift) <= 0.02f }
        )
        val summary = WindowBlurLifecycleInterceptor.latestSessionSummary()!!
        assertEquals(0, summary.monotonicViolationCount)
        assertTrue(summary.backDragFrameCount >= 3)
        assertTrue(logLines.any { it.contains("BACK_GESTURE_PROGRESS") })
        assertTrue(logLines.any { it.contains("[SESSION_SUMMARY") })
    }
}
