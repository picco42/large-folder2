package com.example.popup

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * Coordinates in-process popup sessions so that:
 * 1) Immediate Back return from a launched app collapses the popup cleanly into its widget.
 * 2) Stale detached popups left behind after the user swipes Home are silently torn down
 *    whenever widget or main app lifecycles resume, preventing ghost popups or stale collapses.
 */
object PopupSessionCoordinator {

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activePopupRef: WeakReference<FolderPopupActivity>? = null
    private var awaitingAppReturn: Boolean = false
    private var launchElapsedMs: Long = 0L
    private var launchConfigKey: String = ""

    fun registerActivePopup(activity: FolderPopupActivity) {
        synchronized(lock) {
            val previous = activePopupRef?.get()
            if (previous != null && previous !== activity && !previous.isFinishing && !previous.isDestroyed) {
                finishOnMain(previous)
            }
            activePopupRef = WeakReference(activity)
        }
    }

    fun unregisterPopup(activity: FolderPopupActivity) {
        synchronized(lock) {
            if (activePopupRef?.get() === activity) {
                activePopupRef = null
                awaitingAppReturn = false
                launchElapsedMs = 0L
                launchConfigKey = ""
            }
        }
    }

    fun markLaunchingExternalApp(
        activity: FolderPopupActivity,
        configKey: String,
        elapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        synchronized(lock) {
            activePopupRef = WeakReference(activity)
            awaitingAppReturn = true
            launchElapsedMs = elapsedMs
            launchConfigKey = configKey
        }
    }

    fun clearAwaitingAppReturn(activity: FolderPopupActivity) {
        synchronized(lock) {
            if (activePopupRef?.get() === activity) {
                awaitingAppReturn = false
                launchElapsedMs = 0L
            }
        }
    }

    fun isAwaitingAppReturn(): Boolean {
        synchronized(lock) {
            val popup = activePopupRef?.get()
            return popup != null && !popup.isFinishing && !popup.isDestroyed && awaitingAppReturn
        }
    }

    fun getLaunchConfigKey(): String {
        synchronized(lock) {
            return launchConfigKey
        }
    }

    fun getLaunchElapsedMs(): Long {
        synchronized(lock) {
            return launchElapsedMs
        }
    }

    /**
     * Finishes any stopped popup waiting on a launched app when an external boundary event
     * (e.g., widget lifecycle update or MainActivity resume) indicates the user left that flow.
     */
    fun dismissStaleAwaitingPopup(@Suppress("UNUSED_PARAMETER") reason: String): Boolean {
        val target = synchronized(lock) {
            val popup = activePopupRef?.get()
            if (popup != null && awaitingAppReturn && !popup.isFinishing && !popup.isDestroyed) {
                awaitingAppReturn = false
                launchElapsedMs = 0L
                popup
            } else {
                null
            }
        } ?: return false

        finishOnMain(target)
        return true
    }

    internal fun resetForTest() {
        synchronized(lock) {
            activePopupRef = null
            awaitingAppReturn = false
            launchElapsedMs = 0L
            launchConfigKey = ""
        }
    }

    private fun finishOnMain(activity: FolderPopupActivity) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.finishStalePopupSilently()
            }
        } else {
            mainHandler.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.finishStalePopupSilently()
                }
            }
        }
    }
}
