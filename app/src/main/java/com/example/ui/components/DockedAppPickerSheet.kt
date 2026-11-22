package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.util.rememberSystemAnimationScale

/**
 * Bottom-docked, non-floating, non-draggable app selection sheet.
 * Opens smoothly from the bottom edge, stays flush with the bottom of the screen,
 * and keeps the sheet card completely stationary while the user swipes through the app list.
 */
@Composable
fun DockedAppPickerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    sheetTestTag: String = "docked_app_picker_sheet",
    content: @Composable ColumnScope.() -> Unit
) {
    val animScale = rememberSystemAnimationScale()
    val durationMs = if (animScale <= 0.05f) 0 else (260 * animScale).toInt().coerceIn(80, 400)

    BackHandler(enabled = visible) {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1200f)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = durationMs)),
            exit = fadeOut(animationSpec = tween(durationMillis = durationMs))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = durationMs),
                initialOffsetY = { fullHeight -> fullHeight }
            ),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = durationMs),
                targetOffsetY = { fullHeight -> fullHeight }
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag(sheetTestTag),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 18.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    content = content
                )
            }
        }
    }
}
