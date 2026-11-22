package com.example

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.popup.PopupSessionCoordinator
import com.example.ui.MainViewModel
import com.example.ui.screens.FolderEditorScreen
import com.example.ui.screens.FolderListScreen
import com.example.ui.screens.LargeIconEditorScreen
import com.example.ui.theme.FolderWidgetTheme
import com.example.util.IconStyleManager
import com.example.util.rememberSystemAnimationScale
import com.example.util.scaledDurationMs

private sealed interface MainRoute {
    data object Home : MainRoute
    data class FolderEditor(val folderId: Long?) : MainRoute
    data class LargeIconEditor(val largeIconId: Long?) : MainRoute
}

private val MainRouteSaver = listSaver<MainRoute, Any>(
    save = { route ->
        when (route) {
            MainRoute.Home -> listOf(0)
            is MainRoute.FolderEditor -> listOf(1, route.folderId ?: -1L)
            is MainRoute.LargeIconEditor -> listOf(2, route.largeIconId ?: -1L)
        }
    },
    restore = { saved ->
        val kind = (saved.getOrNull(0) as? Number)?.toInt() ?: 0
        val id = (saved.getOrNull(1) as? Number)?.toLong()?.takeIf { it != -1L }
        when (kind) {
            1 -> MainRoute.FolderEditor(id)
            2 -> MainRoute.LargeIconEditor(id)
            else -> MainRoute.Home
        }
    }
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FolderWidgetTheme {
                val viewModel: MainViewModel = viewModel()
                val animScale = rememberSystemAnimationScale()

                var currentRoute by rememberSaveable(stateSaver = MainRouteSaver) {
                    val initialFolderId = intent.getLongExtra("EDIT_FOLDER_ID", -1L)
                    val initialIconId = intent.getLongExtra("EDIT_LARGE_ICON_ID", -1L)
                    mutableStateOf<MainRoute>(
                        when {
                            initialFolderId != -1L -> MainRoute.FolderEditor(initialFolderId)
                            initialIconId != -1L -> MainRoute.LargeIconEditor(initialIconId)
                            else -> MainRoute.Home
                        }
                    )
                }

                AnimatedContent(
                    targetState = currentRoute,
                    transitionSpec = {
                        if (animScale <= 0.05f) {
                            fadeIn(snap()) togetherWith fadeOut(snap())
                        } else {
                            val duration = scaledDurationMs(220, animScale)
                            fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                        }
                    },
                    label = "mainContentTransition"
                ) { route ->
                    when (route) {
                        MainRoute.Home -> FolderListScreen(
                            viewModel = viewModel,
                            onCreateFolder = { currentRoute = MainRoute.FolderEditor(null) },
                            onEditFolder = { folder -> currentRoute = MainRoute.FolderEditor(folder.id) },
                            onCreateLargeIcon = { currentRoute = MainRoute.LargeIconEditor(null) },
                            onEditLargeIcon = { icon -> currentRoute = MainRoute.LargeIconEditor(icon.id) }
                        )
                        is MainRoute.FolderEditor -> FolderEditorScreen(
                            viewModel = viewModel,
                            folderIdToEdit = route.folderId,
                            onNavigateBack = { currentRoute = MainRoute.Home }
                        )
                        is MainRoute.LargeIconEditor -> LargeIconEditorScreen(
                            viewModel = viewModel,
                            largeIconIdToEdit = route.largeIconId,
                            onNavigateBack = { currentRoute = MainRoute.Home }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PopupSessionCoordinator.dismissStaleAwaitingPopup("main_activity_resumed")
        IconStyleManager.syncPaletteAndRefreshIfNeeded(this, triggerWidgetUpdate = true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        IconStyleManager.syncPaletteAndRefreshIfNeeded(this, triggerWidgetUpdate = true)
    }
}

