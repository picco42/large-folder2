package com.example.ui.motion

import android.os.Build
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BlurLifecycleStage {
    WINDOW_ATTACHED,
    OPEN_ANIMATION,
    IDLE_OPEN,
    BACK_GESTURE_START,
    BACK_GESTURE_PROGRESS,
    BACK_GESTURE_CANCEL,
    BACK_GESTURE_RECOVERY,
    BACK_GESTURE_COMMIT,
    SWIPE_DISMISS_START,
    SWIPE_DISMISS_PROGRESS,
    SWIPE_DISMISS_END,
    CLOSE_ANIMATION,
    SYSTEM_BLUR_CHANGED,
    WINDOW_DETACHED
}

enum class BlurSkipReason {
    PRE_API_31,
    BLUR_DISABLED,
    UNCHANGED,
    SUB_2PX_THROTTLE
}

data class BlurDeviceEnvironment(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val isSamsung: Boolean,
    val oneUiSemPlatformInt: Int?,
    val oneUiVersionName: String?
) {
    fun formatBanner(): String = buildString {
        append("device=").append(manufacturer).append('/').append(model)
        append(" sdk=").append(sdkInt)
        append(" samsung=").append(isSamsung)
        if (oneUiSemPlatformInt != null) {
            append(" semPlatform=").append(oneUiSemPlatformInt)
            if (oneUiVersionName != null) {
                append(" (OneUI ").append(oneUiVersionName).append(')')
            }
        }
    }

    companion object {
        fun current(): BlurDeviceEnvironment {
            val mfg = Build.MANUFACTURER.orEmpty()
            val semInt = readSemPlatformInt()
            return BlurDeviceEnvironment(
                manufacturer = mfg,
                model = Build.MODEL.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                isSamsung = mfg.equals("samsung", ignoreCase = true),
                oneUiSemPlatformInt = semInt,
                oneUiVersionName = formatOneUiVersion(semInt)
            )
        }

        private fun readSemPlatformInt(): Int? = try {
            val field = Build.VERSION::class.java.getField("SEM_PLATFORM_INT")
            val value = field.getInt(null)
            if (value > 0) value else null
        } catch (_: Throwable) {
            null
        }

        internal fun formatOneUiVersion(semPlatformInt: Int?): String? {
            if (semPlatformInt == null || semPlatformInt < 100000) return null
            val offset = semPlatformInt - 90000
            val major = offset / 10000
            val minor = (offset % 10000) / 100
            return "$major.$minor"
        }
    }
}

data class BlurGestureFrameContext(
    val stage: BlurLifecycleStage,
    val containerProgress: Float,
    val rawGestureProgress: Float,
    val easedGestureProgress: Float,
    val gestureScale: Float,
    val gestureTranslationXPx: Float,
    val gestureTranslationYPx: Float,
    val timestampNanos: Long
)

data class BlurGestureFrameRecord(
    val sequence: Int,
    val timestampNanos: Long,
    val deltaMs: Float,
    val stage: BlurLifecycleStage,
    val containerProgress: Float,
    val rawGestureProgress: Float,
    val easedGestureProgress: Float,
    val gestureScale: Float,
    val gestureTranslationXPx: Float,
    val gestureTranslationYPx: Float,
    val expectedBlurProgress: Float,
    val dispatchedBlurProgress: Float,
    val maxWindowBlurPx: Int,
    val requestedBlurPx: Int,
    val appliedBlurPx: Int,
    val wasAppliedToWindow: Boolean,
    val skipReason: BlurSkipReason?,
    val blurVsGestureDrift: Float,
    val deltaAppliedPx: Int,
    val visualRenderLagMs: Float,
    val lastRenderedScaleX: Float,
    val lastRenderedTranslationY: Float,
    val isMonotonicViolation: Boolean
) {
    fun toLogLine(sessionId: Int): String {
        val status = if (wasAppliedToWindow) {
            "APPLIED(dPx=%+d)".format(Locale.US, deltaAppliedPx)
        } else {
            "SKIPPED:%s".format(Locale.US, skipReason?.name ?: "UNKNOWN")
        }
        val monoFlag = if (isMonotonicViolation) " [NON_MONOTONIC]" else ""
        val lagText = if (visualRenderLagMs >= 0f) {
            "%.1fms".format(Locale.US, visualRenderLagMs)
        } else {
            "n/a"
        }
        return String.format(
            Locale.US,
            "[s=%d #%03d dt=%.1fms] %-22s | gesture(raw=%.3f eased=%.3f p=%.3f sc=%.3f ty=%.1fpx) | render(sc=%.3f ty=%.1fpx lag=%s) | blur(exp=%.3f disp=%.3f req=%dpx->app=%dpx/%dpx drift=%+.3f) | %s%s",
            sessionId,
            sequence,
            deltaMs,
            stage.name,
            rawGestureProgress,
            easedGestureProgress,
            containerProgress,
            gestureScale,
            gestureTranslationYPx,
            lastRenderedScaleX,
            lastRenderedTranslationY,
            lagText,
            expectedBlurProgress,
            dispatchedBlurProgress,
            requestedBlurPx,
            appliedBlurPx,
            maxWindowBlurPx,
            blurVsGestureDrift,
            status,
            monoFlag
        )
    }
}

data class BlurSessionSummary(
    val sessionId: Int,
    val endReason: String,
    val device: BlurDeviceEnvironment,
    val folderId: Long,
    val isSystemBlurEnabledAtStart: Boolean,
    val maxWindowBlurPx: Int,
    val totalDurationMs: Float,
    val totalFrames: Int,
    val appliedWindowUpdates: Int,
    val skippedBySub2PxThrottle: Int,
    val skippedByUnchanged: Int,
    val skippedByBlurDisabled: Int,
    val openFrameCount: Int,
    val backDragFrameCount: Int,
    val swipeDragFrameCount: Int,
    val closeFrameCount: Int,
    val maxAbsBlurDrift: Float,
    val meanAbsBlurDrift: Float,
    val maxAppliedStepPx: Int,
    val monotonicViolationCount: Int,
    val diagnosticFlags: List<String>
) {
    fun toLogBlock(): String {
        val verdict = if (diagnosticFlags.isEmpty()) "SYNC_OK" else diagnosticFlags.joinToString(",")
        return String.format(
            Locale.US,
            "[SESSION_SUMMARY s=%d folder=%d reason=%s] %s | blurOn=%b maxPx=%d dur=%.1fms | frames=%d (applied=%d sub2pxSkip=%d unchangedSkip=%d disabledSkip=%d) | phases(open=%d backDrag=%d swipe=%d close=%d) | maxDrift=%.3f meanDrift=%.3f maxStep=%dpx monotonicViolations=%d | verdict=%s",
            sessionId,
            folderId,
            endReason,
            device.formatBanner(),
            isSystemBlurEnabledAtStart,
            maxWindowBlurPx,
            totalDurationMs,
            totalFrames,
            appliedWindowUpdates,
            skippedBySub2PxThrottle,
            skippedByUnchanged,
            skippedByBlurDisabled,
            openFrameCount,
            backDragFrameCount,
            swipeDragFrameCount,
            closeFrameCount,
            maxAbsBlurDrift,
            meanAbsBlurDrift,
            maxAppliedStepPx,
            monotonicViolationCount,
            verdict
        )
    }
}

/**
 * Logging interceptor sitting at the boundary between gesture/container animation frames and
 * Android `Window.setBackgroundBlurRadius(px)`. Captures aligned gesture movement coordinates,
 * expected blur progression from [FolderBlurSpec], actual window blur commits, `<2px` throttle skips,
 * render-layer timing, and monotonic violations so Samsung device behavior can be diagnosed directly
 * from Logcat (`FolderBlurLifecycle`) or inspected programmatically in tests.
 */
class WindowBlurLifecycleInterceptor(
    private val clockNanos: () -> Long = System::nanoTime,
    private val logger: (priority: Int, tag: String, message: String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
    },
    private val device: BlurDeviceEnvironment = BlurDeviceEnvironment.current(),
    private val maxHistoryFrames: Int = DEFAULT_MAX_HISTORY_FRAMES
) {
    private val lock = Any()
    private val frameRecords = ArrayDeque<BlurGestureFrameRecord>()

    private var currentSessionId = 0
    private var currentFolderId = -1L
    private var blurEnabledAtStart = false
    private var sessionMaxBlurPx = 0
    private var sessionStartNanos = 0L
    private var prevFrameNanos = 0L
    private var sequenceCounter = 0
    private var sessionEnded = true

    private var pendingGestureContext: BlurGestureFrameContext? = null
    private var lastAppliedPxInSession = -1
    private var lastRecordedBlurEnabled: Boolean? = null

    // Zero-allocation primitive snapshot updated by Compose graphicsLayer draw traversal
    @Volatile
    private var lastVisualRenderNanos: Long = 0L
    @Volatile
    private var lastVisualScaleX: Float = 1f
    @Volatile
    private var lastVisualTranslationY: Float = 0f

    fun beginSession(
        folderId: Long,
        isSystemBlurEnabled: Boolean,
        maxBlurPx: Int,
        apiLevel: Int = Build.VERSION.SDK_INT
    ): Int {
        val now = clockNanos()
        val id: Int
        synchronized(lock) {
            id = globalSessionCounter.incrementAndGet()
            currentSessionId = id
            currentFolderId = folderId
            blurEnabledAtStart = isSystemBlurEnabled && apiLevel >= Build.VERSION_CODES.S
            sessionMaxBlurPx = maxBlurPx.coerceAtLeast(1)
            sessionStartNanos = now
            prevFrameNanos = now
            sequenceCounter = 0
            pendingGestureContext = null
            lastAppliedPxInSession = -1
            lastRecordedBlurEnabled = isSystemBlurEnabled
            sessionEnded = false
            frameRecords.clear()
        }
        lastVisualRenderNanos = 0L
        lastVisualScaleX = 1f
        lastVisualTranslationY = 0f

        logger(
            Log.INFO,
            LOG_TAG,
            "[SESSION_START s=$id folder=$folderId] ${device.formatBanner()} | crossWindowBlur=$isSystemBlurEnabled maxBlurPx=$maxBlurPx blurSpecDp=${FolderBlurSpec.MAX_BLUR_RADIUS_DP.value}dp"
        )
        publishLatestState(this, emptyList(), null)
        return id
    }

    fun recordGestureFrame(context: BlurGestureFrameContext) {
        synchronized(lock) {
            if (sessionEnded) return
            pendingGestureContext = context
        }
    }

    fun recordVisualLayerRender(
        scaleX: Float,
        translationY: Float,
        timestampNanos: Long = clockNanos()
    ) {
        lastVisualScaleX = scaleX
        lastVisualTranslationY = translationY
        lastVisualRenderNanos = timestampNanos
    }

    fun interceptWindowBlurUpdate(
        dispatchedBlurProgress: Float,
        maxBlurPx: Int,
        isSystemBlurEnabled: Boolean,
        apiLevel: Int = Build.VERSION.SDK_INT,
        currentAppliedPx: Int,
        minimumDeltaPx: Int = DEFAULT_MIN_INTERMEDIATE_DELTA_PX,
        commitToWindow: (Int) -> Unit
    ): Int {
        val now = clockNanos()
        val safeMaxPx = maxBlurPx.coerceAtLeast(1)
        val clampedDispatched = dispatchedBlurProgress.coerceIn(0f, 1f)

        val record: BlurGestureFrameRecord
        val shouldEmitLog: Boolean
        val targetApplyPx: Int?
        val snapshotList: List<BlurGestureFrameRecord>

        synchronized(lock) {
            if (sessionEnded) {
                // Still safely apply endpoint/fallback if called outside an open session
                val directTarget = if (apiLevel >= Build.VERSION_CODES.S && isSystemBlurEnabled) {
                    (FolderBlurSpec.computeBlurProgress(clampedDispatched) * safeMaxPx)
                        .roundToInt()
                        .coerceIn(0, safeMaxPx)
                } else {
                    0
                }
                if (directTarget != currentAppliedPx) {
                    commitToWindow(directTarget)
                    return directTarget
                }
                return currentAppliedPx
            }

            sessionMaxBlurPx = safeMaxPx
            val gCtx = pendingGestureContext ?: BlurGestureFrameContext(
                stage = if (lastAppliedPxInSession < 0) BlurLifecycleStage.WINDOW_ATTACHED else BlurLifecycleStage.IDLE_OPEN,
                containerProgress = clampedDispatched,
                rawGestureProgress = 0f,
                easedGestureProgress = 0f,
                gestureScale = lastVisualScaleX,
                gestureTranslationXPx = 0f,
                gestureTranslationYPx = lastVisualTranslationY,
                timestampNanos = now
            )

            val expectedProgress = FolderBlurSpec.computeEffectiveBlurProgress(
                containerProgress = gCtx.containerProgress,
                backDragProgress = gCtx.easedGestureProgress
            )
            val requestedPx = if (isSystemBlurEnabled) {
                (FolderBlurSpec.computeBlurProgress(clampedDispatched) * safeMaxPx)
                    .roundToInt()
                    .coerceIn(0, safeMaxPx)
            } else {
                0
            }

            val skipReason: BlurSkipReason? = when {
                apiLevel < Build.VERSION_CODES.S -> BlurSkipReason.PRE_API_31
                !isSystemBlurEnabled && currentAppliedPx == 0 -> BlurSkipReason.BLUR_DISABLED
                requestedPx == currentAppliedPx -> BlurSkipReason.UNCHANGED
                currentAppliedPx >= 0 &&
                    requestedPx != 0 &&
                    requestedPx != safeMaxPx &&
                    abs(requestedPx - currentAppliedPx) < minimumDeltaPx -> BlurSkipReason.SUB_2PX_THROTTLE
                else -> null
            }

            val appliedPx = if (skipReason == null) requestedPx else currentAppliedPx.coerceAtLeast(0)
            val wasApplied = skipReason == null
            targetApplyPx = if (wasApplied) appliedPx else null

            val prevRecord = frameRecords.lastOrNull()
            val deltaApplied = if (lastAppliedPxInSession >= 0) appliedPx - lastAppliedPxInSession else 0
            val monotonicViolation = detectMonotonicViolation(
                stage = gCtx.stage,
                containerProgress = gCtx.containerProgress,
                rawGestureProgress = gCtx.rawGestureProgress,
                appliedPx = appliedPx,
                wasApplied = wasApplied,
                prevRecord = prevRecord
            )

            val actualRatio = if (isSystemBlurEnabled && apiLevel >= Build.VERSION_CODES.S) {
                appliedPx.toFloat() / safeMaxPx.toFloat()
            } else {
                0f
            }
            val drift = if (isSystemBlurEnabled && apiLevel >= Build.VERSION_CODES.S) {
                actualRatio - expectedProgress
            } else {
                0f
            }

            val dtMs = if (prevFrameNanos > 0L) ((now - prevFrameNanos).coerceAtLeast(0L) / 1_000_000f) else 0f
            prevFrameNanos = now

            val visualLagMs = if (lastVisualRenderNanos > 0L) {
                ((now - lastVisualRenderNanos) / 1_000_000f)
            } else {
                -1f
            }

            val seq = ++sequenceCounter
            record = BlurGestureFrameRecord(
                sequence = seq,
                timestampNanos = now,
                deltaMs = dtMs,
                stage = gCtx.stage,
                containerProgress = gCtx.containerProgress,
                rawGestureProgress = gCtx.rawGestureProgress,
                easedGestureProgress = gCtx.easedGestureProgress,
                gestureScale = gCtx.gestureScale,
                gestureTranslationXPx = gCtx.gestureTranslationXPx,
                gestureTranslationYPx = gCtx.gestureTranslationYPx,
                expectedBlurProgress = expectedProgress,
                dispatchedBlurProgress = clampedDispatched,
                maxWindowBlurPx = safeMaxPx,
                requestedBlurPx = requestedPx,
                appliedBlurPx = appliedPx,
                wasAppliedToWindow = wasApplied,
                skipReason = skipReason,
                blurVsGestureDrift = drift,
                deltaAppliedPx = deltaApplied,
                visualRenderLagMs = visualLagMs,
                lastRenderedScaleX = lastVisualScaleX,
                lastRenderedTranslationY = lastVisualTranslationY,
                isMonotonicViolation = monotonicViolation
            )

            appendRecordLocked(record)
            if (wasApplied) {
                lastAppliedPxInSession = appliedPx
            }

            val isInteractiveStage = gCtx.stage == BlurLifecycleStage.BACK_GESTURE_PROGRESS ||
                gCtx.stage == BlurLifecycleStage.BACK_GESTURE_START ||
                gCtx.stage == BlurLifecycleStage.BACK_GESTURE_CANCEL ||
                gCtx.stage == BlurLifecycleStage.BACK_GESTURE_COMMIT ||
                gCtx.stage == BlurLifecycleStage.SWIPE_DISMISS_START ||
                gCtx.stage == BlurLifecycleStage.SWIPE_DISMISS_PROGRESS ||
                gCtx.stage == BlurLifecycleStage.SWIPE_DISMISS_END ||
                gCtx.stage == BlurLifecycleStage.SYSTEM_BLUR_CHANGED ||
                gCtx.stage == BlurLifecycleStage.WINDOW_ATTACHED ||
                gCtx.stage == BlurLifecycleStage.WINDOW_DETACHED
            shouldEmitLog = wasApplied ||
                monotonicViolation ||
                skipReason == BlurSkipReason.SUB_2PX_THROTTLE ||
                (isInteractiveStage && skipReason == BlurSkipReason.UNCHANGED && prevRecord?.stage != gCtx.stage)

            snapshotList = frameRecords.toList()
        }

        if (targetApplyPx != null) {
            commitToWindow(targetApplyPx)
        }
        if (shouldEmitLog) {
            val priority = if (record.isMonotonicViolation) Log.WARN else Log.DEBUG
            logger(priority, LOG_TAG, record.toLogLine(currentSessionId))
        }
        publishLatestState(this, snapshotList, null)
        return targetApplyPx ?: currentAppliedPx
    }

    fun recordSystemBlurAvailabilityChanged(
        enabled: Boolean,
        lastBlurProgress: Float,
        maxBlurPx: Int,
        apiLevel: Int = Build.VERSION.SDK_INT,
        currentAppliedPx: Int,
        commitToWindow: (Int) -> Unit
    ): Int {
        val now = clockNanos()
        val prevEnabled: Boolean?
        synchronized(lock) {
            prevEnabled = lastRecordedBlurEnabled
            lastRecordedBlurEnabled = enabled
            if (!sessionEnded) {
                pendingGestureContext = BlurGestureFrameContext(
                    stage = BlurLifecycleStage.SYSTEM_BLUR_CHANGED,
                    containerProgress = lastBlurProgress.coerceIn(0f, 1f),
                    rawGestureProgress = 0f,
                    easedGestureProgress = 0f,
                    gestureScale = lastVisualScaleX,
                    gestureTranslationXPx = 0f,
                    gestureTranslationYPx = lastVisualTranslationY,
                    timestampNanos = now
                )
            }
        }
        if (prevEnabled != null && prevEnabled != enabled) {
            logger(
                Log.INFO,
                LOG_TAG,
                "[SYSTEM_BLUR_TOGGLE s=$currentSessionId] $prevEnabled -> $enabled (lastProgress=$lastBlurProgress maxPx=$maxBlurPx)"
            )
        }
        return interceptWindowBlurUpdate(
            dispatchedBlurProgress = if (enabled) lastBlurProgress else 0f,
            maxBlurPx = maxBlurPx,
            isSystemBlurEnabled = enabled,
            apiLevel = apiLevel,
            currentAppliedPx = currentAppliedPx,
            commitToWindow = commitToWindow
        )
    }

    fun endSession(reason: String = "DISMISSED"): BlurSessionSummary? {
        val now = clockNanos()
        val summary: BlurSessionSummary
        val recordsCopy: List<BlurGestureFrameRecord>
        synchronized(lock) {
            if (sessionEnded || currentSessionId == 0) return null
            sessionEnded = true
            recordsCopy = frameRecords.toList()
            val durationMs = ((now - sessionStartNanos).coerceAtLeast(0L)) / 1_000_000f

            val appliedCount = recordsCopy.count { it.wasAppliedToWindow }
            val sub2pxCount = recordsCopy.count { it.skipReason == BlurSkipReason.SUB_2PX_THROTTLE }
            val unchangedCount = recordsCopy.count { it.skipReason == BlurSkipReason.UNCHANGED }
            val disabledCount = recordsCopy.count {
                it.skipReason == BlurSkipReason.BLUR_DISABLED || it.skipReason == BlurSkipReason.PRE_API_31
            }

            val openFrames = recordsCopy.count { it.stage == BlurLifecycleStage.OPEN_ANIMATION }
            val backDragFrames = recordsCopy.count {
                it.stage == BlurLifecycleStage.BACK_GESTURE_PROGRESS ||
                    it.stage == BlurLifecycleStage.BACK_GESTURE_START ||
                    it.stage == BlurLifecycleStage.BACK_GESTURE_CANCEL ||
                    it.stage == BlurLifecycleStage.BACK_GESTURE_RECOVERY ||
                    it.stage == BlurLifecycleStage.BACK_GESTURE_COMMIT
            }
            val swipeFrames = recordsCopy.count {
                it.stage == BlurLifecycleStage.SWIPE_DISMISS_START ||
                    it.stage == BlurLifecycleStage.SWIPE_DISMISS_PROGRESS ||
                    it.stage == BlurLifecycleStage.SWIPE_DISMISS_END
            }
            val closeFrames = recordsCopy.count { it.stage == BlurLifecycleStage.CLOSE_ANIMATION }

            val maxAbsDrift = recordsCopy.maxOfOrNull { abs(it.blurVsGestureDrift) } ?: 0f
            val meanAbsDrift = if (recordsCopy.isNotEmpty()) {
                recordsCopy.sumOf { abs(it.blurVsGestureDrift).toDouble() }.toFloat() / recordsCopy.size
            } else {
                0f
            }
            val maxStepPx = recordsCopy.filter { it.wasAppliedToWindow }.maxOfOrNull { abs(it.deltaAppliedPx) } ?: 0
            val monotonicViolations = recordsCopy.count { it.isMonotonicViolation }

            val flags = buildList {
                if (maxAbsDrift > HIGH_DRIFT_THRESHOLD) add("HIGH_DRIFT")
                if (monotonicViolations > 0) add("NON_MONOTONIC")
                if (sub2pxCount > 0) add("SUB2PX_SKIPPED_FRAMES")
                if (blurEnabledAtStart && appliedCount <= 2 && (openFrames + closeFrames) > 4) {
                    add("SPARSE_BLUR_UPDATES")
                }
            }

            summary = BlurSessionSummary(
                sessionId = currentSessionId,
                endReason = reason,
                device = device,
                folderId = currentFolderId,
                isSystemBlurEnabledAtStart = blurEnabledAtStart,
                maxWindowBlurPx = sessionMaxBlurPx,
                totalDurationMs = durationMs,
                totalFrames = recordsCopy.size,
                appliedWindowUpdates = appliedCount,
                skippedBySub2PxThrottle = sub2pxCount,
                skippedByUnchanged = unchangedCount,
                skippedByBlurDisabled = disabledCount,
                openFrameCount = openFrames,
                backDragFrameCount = backDragFrames,
                swipeDragFrameCount = swipeFrames,
                closeFrameCount = closeFrames,
                maxAbsBlurDrift = maxAbsDrift,
                meanAbsBlurDrift = meanAbsDrift,
                maxAppliedStepPx = maxStepPx,
                monotonicViolationCount = monotonicViolations,
                diagnosticFlags = flags
            )
        }

        logger(Log.INFO, LOG_TAG, summary.toLogBlock())
        publishLatestState(this, recordsCopy, summary)
        return summary
    }

    fun getSessionRecords(): List<BlurGestureFrameRecord> = synchronized(lock) {
        frameRecords.toList()
    }

    private fun appendRecordLocked(record: BlurGestureFrameRecord) {
        while (frameRecords.size >= maxHistoryFrames) {
            frameRecords.removeFirst()
        }
        frameRecords.addLast(record)
    }

    private fun detectMonotonicViolation(
        stage: BlurLifecycleStage,
        containerProgress: Float,
        rawGestureProgress: Float,
        appliedPx: Int,
        wasApplied: Boolean,
        prevRecord: BlurGestureFrameRecord?
    ): Boolean {
        if (!wasApplied || prevRecord == null) return false
        return when (stage) {
            BlurLifecycleStage.CLOSE_ANIMATION -> {
                prevRecord.stage == BlurLifecycleStage.CLOSE_ANIMATION &&
                    appliedPx > prevRecord.appliedBlurPx + 1
            }
            BlurLifecycleStage.BACK_GESTURE_PROGRESS -> {
                prevRecord.stage == BlurLifecycleStage.BACK_GESTURE_PROGRESS &&
                    rawGestureProgress > prevRecord.rawGestureProgress + 0.01f &&
                    appliedPx > prevRecord.appliedBlurPx + 1
            }
            BlurLifecycleStage.OPEN_ANIMATION -> {
                prevRecord.stage == BlurLifecycleStage.OPEN_ANIMATION &&
                    containerProgress > prevRecord.containerProgress + 0.01f &&
                    appliedPx < prevRecord.appliedBlurPx - 1
            }
            else -> false
        }
    }

    companion object {
        const val LOG_TAG = "FolderBlurLifecycle"
        const val DEFAULT_MAX_HISTORY_FRAMES = 360
        const val DEFAULT_MIN_INTERMEDIATE_DELTA_PX = 2
        const val HIGH_DRIFT_THRESHOLD = 0.06f

        private val globalSessionCounter = AtomicInteger(0)
        private val latestLock = Any()
        private var latestRecords: List<BlurGestureFrameRecord> = emptyList()
        private var latestSummary: BlurSessionSummary? = null

        private fun publishLatestState(
            @Suppress("UNUSED_PARAMETER") source: WindowBlurLifecycleInterceptor,
            records: List<BlurGestureFrameRecord>,
            summary: BlurSessionSummary?
        ) {
            synchronized(latestLock) {
                latestRecords = records
                if (summary != null) {
                    latestSummary = summary
                }
            }
        }

        fun latestSessionRecords(): List<BlurGestureFrameRecord> = synchronized(latestLock) {
            latestRecords
        }

        fun latestSessionSummary(): BlurSessionSummary? = synchronized(latestLock) {
            latestSummary
        }
    }
}
