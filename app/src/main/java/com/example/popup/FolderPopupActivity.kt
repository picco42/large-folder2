package com.example.popup

import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import com.example.FolderWidgetApplication
import com.example.data.FolderAppEntity
import com.example.data.FolderEntity
import com.example.data.FolderLayouts
import com.example.ui.components.SamsungFolderPopupOverlay
import com.example.ui.motion.FolderBlurSpec
import com.example.ui.motion.WindowBlurLifecycleInterceptor
import com.example.ui.theme.FolderWidgetTheme
import com.example.util.AppScanner
import com.example.util.IconStyleManager
import com.example.util.OriginSourceKind
import com.example.util.WidgetBoundsCache
import com.example.widget.FolderWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.function.Consumer
import kotlin.math.roundToInt

class FolderPopupActivity : ComponentActivity() {

    private var folderIdState by mutableLongStateOf(-1L)
    private var appWidgetIdState by mutableIntStateOf(AppWidgetManager.INVALID_APPWIDGET_ID)
    private var preloadedFolderState by mutableStateOf<FolderEntity?>(null)
    private var preloadedAppsState by mutableStateOf<List<FolderAppEntity>>(emptyList())
    private var sourceBoundsState by mutableStateOf<Rect?>(null)
    private var hasConcreteOriginState by mutableStateOf(false)
    private var originConfigKeyState by mutableStateOf("")
    private var externalDismissTrigger by mutableIntStateOf(0)
    private var returnDismissTrigger by mutableIntStateOf(0)
    private var openGeneration by mutableIntStateOf(0)
    private var isSystemBlurEnabledState by mutableStateOf(false)
    private var lastAppliedBlurRadiusPx = -1
    private var lastBlurProgress = 0f
    private var maxWindowBlurRadiusPx = 120
    private var blurEnabledListener: Consumer<Boolean>? = null
    private var isLaunchingApp = false
    private var awaitingAppReturn = false
    private var isDismissInFlight = false
    private var hasFinished = false
    private var isPopupComposed = false
    private val blurInterceptor = WindowBlurLifecycleInterceptor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureTranslucentBlurWindow()
        disableSystemActivityTransitions()
        PopupSessionCoordinator.registerActivePopup(this)

        maxWindowBlurRadiusPx = FolderBlurSpec.maxBlurRadiusPx(Density(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isSystemBlurEnabledState = windowManager.isCrossWindowBlurEnabled
            Log.d(TAG, "onCreate: windowManager.isCrossWindowBlurEnabled=$isSystemBlurEnabledState")
            if (isSystemBlurEnabledState) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            applyWindowBackgroundBlur(0)
        }

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_AWAITING_APP_RETURN, false)) {
                // Process was killed/recreated after user left to an external app or swiped Home:
                // tear down silently rather than resurrecting a ghost popup.
                finishStalePopupSilently()
                return
            }
            restoreFromSavedState(savedInstanceState)
        } else {
            resolveAndApplyIntent(intent)
        }

        blurInterceptor.beginSession(
            folderId = folderIdState,
            isSystemBlurEnabled = isSystemBlurEnabledState,
            maxBlurPx = maxWindowBlurRadiusPx,
            apiLevel = Build.VERSION.SDK_INT
        )

        val repository = (application as FolderWidgetApplication).repository

        setContent {
            FolderWidgetTheme {
                BackHandler(enabled = true) {
                    dismiss()
                }

                val activeFolderId = folderIdState
                val maxBlurRadiusPx = FolderBlurSpec.maxBlurRadiusPx(LocalDensity.current)
                SideEffect {
                    maxWindowBlurRadiusPx = maxBlurRadiusPx
                }

                var folder by remember(activeFolderId, openGeneration) {
                    mutableStateOf(preloadedFolderState)
                }
                val apps by repository.getAppsForFolder(activeFolderId)
                    .collectAsState(initial = preloadedAppsState)

                LaunchedEffect(activeFolderId, openGeneration) {
                    if (activeFolderId != -1L) {
                        val latest = repository.getFolderById(activeFolderId)
                        if (latest != null) {
                            folder = latest
                        }
                    }
                }

                folder?.let { currentFolder ->
                    key(currentFolder.id, appWidgetIdState, openGeneration) {
                        DisposableEffect(currentFolder.id, appWidgetIdState, openGeneration) {
                            isPopupComposed = true
                            onDispose {
                                isPopupComposed = false
                            }
                        }

                        SamsungFolderPopupOverlay(
                            folder = currentFolder,
                            apps = apps,
                            onDismiss = { finishAfterAnimation() },
                            onLaunchApp = { app, iconRect ->
                                val launchIntent = AppScanner.getLaunchIntent(
                                    this@FolderPopupActivity,
                                    app.packageName,
                                    app.activityName
                                )
                                if (launchIntent != null) {
                                    // Keep popup suspended at full scale behind target app so immediate Back
                                    // collapses cleanly into the home-screen widget; PopupSessionCoordinator
                                    // tears down stale sessions if the user swipes Home instead.
                                    isLaunchingApp = true
                                    awaitingAppReturn = true
                                    PopupSessionCoordinator.markLaunchingExternalApp(
                                        activity = this@FolderPopupActivity,
                                        configKey = originConfigKeyState
                                    )
                                    val options = if (iconRect != null && !iconRect.isEmpty) {
                                        ActivityOptions.makeClipRevealAnimation(
                                            window.decorView,
                                            iconRect.left,
                                            iconRect.top,
                                            iconRect.width().coerceAtLeast(1),
                                            iconRect.height().coerceAtLeast(1)
                                        ).toBundle()
                                    } else null
                                    startActivity(launchIntent, options)
                                } else {
                                    awaitingAppReturn = false
                                    PopupSessionCoordinator.clearAwaitingAppReturn(this@FolderPopupActivity)
                                    dismiss()
                                }
                            },
                            onReorderApps = { reorderedApps ->
                                lifecycleScope.launch {
                                    repository.setAppsForFolder(currentFolder.id, reorderedApps)
                                }
                            },
                            onRenameFolder = { newName ->
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) {
                                    folder = currentFolder.copy(name = trimmed)
                                    lifecycleScope.launch {
                                        repository.renameFolder(currentFolder.id, trimmed)
                                    }
                                }
                            },
                            sourceBounds = sourceBoundsState,
                            hasExactOrCachedOrigin = hasConcreteOriginState,
                            sourceBoundsAreScreenCoords = true,
                            externalDismissTrigger = externalDismissTrigger,
                            returnDismissTrigger = returnDismissTrigger,
                            keepAliveOnAppLaunch = true,
                            isSystemBlurEnabled = isSystemBlurEnabledState,
                            onBlurProgressChanged = { progress ->
                                lastBlurProgress = progress
                                lastAppliedBlurRadiusPx = blurInterceptor.interceptWindowBlurUpdate(
                                    dispatchedBlurProgress = progress,
                                    maxBlurPx = maxWindowBlurRadiusPx,
                                    isSystemBlurEnabled = isSystemBlurEnabledState,
                                    apiLevel = Build.VERSION.SDK_INT,
                                    currentAppliedPx = lastAppliedBlurRadiusPx,
                                    commitToWindow = ::commitRawWindowBackgroundBlur
                                )
                            },
                            blurLifecycleInterceptor = blurInterceptor,
                            onRequestDismiss = { dismiss() },
                            keepComposedUntilHostFinishes = true
                        )
                    }
                } ?: Box(modifier = Modifier.fillMaxSize())
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_FOLDER_ID, folderIdState)
        outState.putInt(STATE_APPWIDGET_ID, appWidgetIdState)
        outState.putString(STATE_CONFIG_KEY, originConfigKeyState)
        outState.putBoolean(STATE_HAS_CONCRETE_ORIGIN, hasConcreteOriginState)
        outState.putBoolean(STATE_AWAITING_APP_RETURN, awaitingAppReturn)
        sourceBoundsState?.let { rect ->
            outState.putFloatArray(
                STATE_BOUNDS_ARRAY,
                floatArrayOf(rect.left, rect.top, rect.right, rect.bottom)
            )
        }
    }

    private fun restoreFromSavedState(savedState: Bundle) {
        folderIdState = savedState.getLong(STATE_FOLDER_ID, -1L)
        appWidgetIdState = savedState.getInt(STATE_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        awaitingAppReturn = savedState.getBoolean(STATE_AWAITING_APP_RETURN, false)

        if (folderIdState == -1L) {
            folderIdState = intent?.getLongExtra(FolderWidgetProvider.EXTRA_FOLDER_ID, -1L) ?: -1L
        }
        if (appWidgetIdState == AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetIdState = intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        }

        loadFolderDataSynchronously(folderIdState)

        val currentConfigKey = WidgetBoundsCache.buildDisplayConfigKey(this)
        val savedConfigKey = savedState.getString(STATE_CONFIG_KEY).orEmpty()
        val savedArray = savedState.getFloatArray(STATE_BOUNDS_ARRAY)
        val screen = WidgetBoundsCache.getScreenBoundsPx(this)

        if (savedConfigKey == currentConfigKey && savedArray != null && savedArray.size == 4) {
            val restored = WidgetBoundsCache.clampToScreen(
                Rect(savedArray[0], savedArray[1], savedArray[2], savedArray[3]),
                screen
            )
            sourceBoundsState = restored
            hasConcreteOriginState = savedState.getBoolean(STATE_HAS_CONCRETE_ORIGIN, false)
            originConfigKeyState = currentConfigKey
        } else {
            // Config (orientation/display size) changed across recreation: re-resolve & discard stale origin
            resolveOriginBounds(intentSourceBounds = null)
        }
    }

    override fun onRestart() {
        super.onRestart()
        if (awaitingAppReturn && !isFinishing && !isDestroyed) {
            val currentConfigKey = WidgetBoundsCache.buildDisplayConfigKey(this)
            if (originConfigKeyState.isNotEmpty() && originConfigKeyState != currentConfigKey) {
                // Orientation/display changed while in external app: invalidate stale origin so collapse
                // safely centers at the widget's known size instead of shrinking into wrong coordinates.
                resolveOriginBounds(intentSourceBounds = null)
            }
            dismiss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        disableSystemActivityTransitions()
        PopupSessionCoordinator.registerActivePopup(this)
        PopupSessionCoordinator.clearAwaitingAppReturn(this)
        isLaunchingApp = false
        awaitingAppReturn = false
        isDismissInFlight = false
        hasFinished = false
        externalDismissTrigger = 0
        returnDismissTrigger = 0
        lastBlurProgress = 0f
        blurInterceptor.endSession("NEW_INTENT")
        applyWindowBackgroundBlur(0)
        resolveAndApplyIntent(intent)
        blurInterceptor.beginSession(
            folderId = folderIdState,
            isSystemBlurEnabled = isSystemBlurEnabledState,
            maxBlurPx = maxWindowBlurRadiusPx,
            apiLevel = Build.VERSION.SDK_INT
        )
        openGeneration++
    }

    private fun resolveAndApplyIntent(intent: Intent?) {
        folderIdState = intent?.getLongExtra(FolderWidgetProvider.EXTRA_FOLDER_ID, -1L) ?: -1L
        appWidgetIdState = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        loadFolderDataSynchronously(folderIdState)
        val fromLabel = intent?.getBooleanExtra(FolderWidgetProvider.EXTRA_FROM_FOLDER_LABEL, false) == true
        resolveOriginBounds(intentSourceBounds = if (fromLabel) null else intent?.sourceBounds)
    }

    private fun resolveOriginBounds(intentSourceBounds: android.graphics.Rect?) {
        val folder = preloadedFolderState
        val resolved = WidgetBoundsCache.resolveWidgetOrigin(
            context = this,
            appWidgetId = appWidgetIdState,
            intentSourceBounds = intentSourceBounds,
            layoutType = folder?.layoutType ?: FolderLayouts.GRID_3X3,
            showLabels = folder?.showLabels ?: true
        )
        sourceBoundsState = resolved.bounds
        hasConcreteOriginState = resolved.sourceKind != OriginSourceKind.CENTER_KNOWN_SIZE_FALLBACK
        originConfigKeyState = resolved.configKey
    }

    private fun loadFolderDataSynchronously(folderId: Long) {
        IconStyleManager.syncPaletteAndRefreshIfNeeded(this, triggerWidgetUpdate = true)
        val repository = (application as FolderWidgetApplication).repository
        if (folderId != -1L) {
            val iconSizePx = AppScanner.getSystemHomeIconSizePx(this)
            val pair = runBlocking(Dispatchers.IO) {
                val folderAndApps = repository.getFolderById(folderId) to repository.getAppsForFolderSync(folderId)
                val firstPageApps = folderAndApps.second.take(16)
                if (firstPageApps.isNotEmpty()) {
                    withTimeoutOrNull(60L) {
                        firstPageApps.map { app ->
                            async(Dispatchers.IO) {
                                AppScanner.getAppIconBitmap(
                                    this@FolderPopupActivity,
                                    app.packageName,
                                    app.activityName,
                                    iconSizePx
                                )
                            }
                        }.awaitAll()
                    }
                }
                folderAndApps
            }
            preloadedFolderState = pair.first
            preloadedAppsState = pair.second
            val allApps = pair.second
            if (allApps.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    for (app in allApps) {
                        AppScanner.getAppIconBitmap(
                            this@FolderPopupActivity,
                            app.packageName,
                            app.activityName,
                            iconSizePx
                        )
                    }
                }
            }
        } else {
            preloadedFolderState = null
            preloadedAppsState = emptyList()
        }
    }

    override fun onStart() {
        super.onStart()
        IconStyleManager.syncPaletteAndRefreshIfNeeded(this, triggerWidgetUpdate = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isSystemBlurEnabledState = windowManager.isCrossWindowBlurEnabled
            Log.d(TAG, "onStart: windowManager.isCrossWindowBlurEnabled=$isSystemBlurEnabledState")
            lastAppliedBlurRadiusPx = blurInterceptor.recordSystemBlurAvailabilityChanged(
                enabled = isSystemBlurEnabledState,
                lastBlurProgress = lastBlurProgress,
                maxBlurPx = maxWindowBlurRadiusPx,
                apiLevel = Build.VERSION.SDK_INT,
                currentAppliedPx = lastAppliedBlurRadiusPx,
                commitToWindow = ::commitRawWindowBackgroundBlur
            )
            if (blurEnabledListener == null) {
                val listener = Consumer<Boolean> { enabled ->
                    Log.d(TAG, "onCrossWindowBlurEnabledChanged: isCrossWindowBlurEnabled=$enabled")
                    isSystemBlurEnabledState = enabled
                    lastAppliedBlurRadiusPx = blurInterceptor.recordSystemBlurAvailabilityChanged(
                        enabled = enabled,
                        lastBlurProgress = lastBlurProgress,
                        maxBlurPx = maxWindowBlurRadiusPx,
                        apiLevel = Build.VERSION.SDK_INT,
                        currentAppliedPx = lastAppliedBlurRadiusPx,
                        commitToWindow = ::commitRawWindowBackgroundBlur
                    )
                }
                blurEnabledListener = listener
                windowManager.addCrossWindowBlurEnabledListener(listener)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isLaunchingApp && !awaitingAppReturn && !isFinishing && !isDestroyed) {
            dismiss()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurEnabledListener?.let { listener ->
                windowManager.removeCrossWindowBlurEnabledListener(listener)
                blurEnabledListener = null
            }
        }
        if (isDismissInFlight && !awaitingAppReturn && !hasFinished && !isFinishing && !isDestroyed) {
            finishAfterAnimation()
        }
    }

    override fun onDestroy() {
        PopupSessionCoordinator.unregisterPopup(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurEnabledListener?.let { listener ->
                windowManager.removeCrossWindowBlurEnabledListener(listener)
                blurEnabledListener = null
            }
            applyWindowBackgroundBlur(0)
        }
        blurInterceptor.endSession("DESTROY")
        super.onDestroy()
    }

    private fun configureTranslucentBlurWindow() {
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(0)
        }
    }

    private fun commitRawWindowBackgroundBlur(clampedPx: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val lp = window.attributes
            val shouldKeepBehindFlag = isSystemBlurEnabledState && !isFinishing && !isDestroyed
            val wantFlag = clampedPx > 0 || shouldKeepBehindFlag
            val hasFlag = (lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0
            if (wantFlag != hasFlag || lp.blurBehindRadius != clampedPx) {
                if (wantFlag) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                }
                lp.blurBehindRadius = clampedPx
                window.attributes = lp
            }
        }
    }

    private fun applyWindowBackgroundBlur(radiusPx: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val progress = if (maxWindowBlurRadiusPx > 0) {
            radiusPx.toFloat() / maxWindowBlurRadiusPx.toFloat()
        } else {
            0f
        }
        lastAppliedBlurRadiusPx = blurInterceptor.interceptWindowBlurUpdate(
            dispatchedBlurProgress = progress,
            maxBlurPx = maxWindowBlurRadiusPx,
            isSystemBlurEnabled = isSystemBlurEnabledState,
            apiLevel = Build.VERSION.SDK_INT,
            currentAppliedPx = lastAppliedBlurRadiusPx,
            commitToWindow = ::commitRawWindowBackgroundBlur
        )
    }

    private fun disableSystemActivityTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    internal fun dismiss() {
        if (isDismissInFlight || hasFinished || isFinishing || isDestroyed) return
        isDismissInFlight = true
        isLaunchingApp = false
        val wasAwaitingAppReturn = awaitingAppReturn
        awaitingAppReturn = false
        PopupSessionCoordinator.clearAwaitingAppReturn(this)

        if (!isPopupComposed && preloadedFolderState == null) {
            finishAfterAnimation()
            return
        }
        if (wasAwaitingAppReturn) {
            returnDismissTrigger++
        } else {
            externalDismissTrigger++
        }
    }

    internal fun finishStalePopupSilently() {
        if (hasFinished || isFinishing || isDestroyed) return
        hasFinished = true
        isDismissInFlight = true
        isLaunchingApp = false
        awaitingAppReturn = false
        PopupSessionCoordinator.unregisterPopup(this)
        lastBlurProgress = 0f
        applyWindowBackgroundBlur(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        blurInterceptor.endSession("STALE_RESTORE")
        finish()
        disableSystemActivityTransitions()
    }

    private fun finishAfterAnimation() {
        if (hasFinished || isFinishing || isDestroyed) return
        hasFinished = true
        isDismissInFlight = true
        isLaunchingApp = false
        awaitingAppReturn = false
        PopupSessionCoordinator.unregisterPopup(this)
        lastBlurProgress = 0f
        applyWindowBackgroundBlur(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        blurInterceptor.endSession("DISMISSED")
        finish()
        disableSystemActivityTransitions()
    }

    companion object {
        private const val TAG = "FolderPopupActivity"
        private const val STATE_FOLDER_ID = "state_folder_id"
        private const val STATE_APPWIDGET_ID = "state_appwidget_id"
        private const val STATE_CONFIG_KEY = "state_config_key"
        private const val STATE_HAS_CONCRETE_ORIGIN = "state_has_concrete_origin"
        private const val STATE_AWAITING_APP_RETURN = "state_awaiting_app_return"
        private const val STATE_BOUNDS_ARRAY = "state_bounds_array"
    }
}
