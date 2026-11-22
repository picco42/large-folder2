package com.example.ui.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.FolderBgStyles
import kotlin.math.roundToInt

data class FolderCardBlurColors(
    val cardTint: Color,
    val highlightEdge: Color,
    val opaqueCollapseShield: Color,
    val contentColor: Color,
    val overflowCircleColor: Color
)

/**
 * Single tunable specification for Samsung One UI-style system-level backdrop blur,
 * dimming scrim, frosted milky card tint (light/dark & blur/no-blur fallback),
 * faint 1dp highlight edge, and card corner radius shared by every folder size
 * and every Large Icon surface.
 */
object FolderBlurSpec {
    // Android 12+ system cross-window backdrop blur tuned to One UI (dissolves home icons into broad gradients)
    val MAX_BLUR_RADIUS_DP = 58.dp
    val IN_APP_STATIC_BLUR_DP = 38.dp

    // Scrim dimming behind the frosted card
    const val BLUR_ENABLED_SCRIM_ALPHA = 0.24f
    const val FALLBACK_NO_BLUR_SCRIM_ALPHA = 0.60f

    // Frosted ("milky") card tint alpha when cross-window blur is active
    const val CARD_TINT_ALPHA_LIGHT = 0.38f
    const val CARD_TINT_ALPHA_DARK = 0.38f
    const val CARD_TINT_ALPHA_CLEAR = 0.26f

    // Opaque fallback card tint alpha when cross-window blur is disabled / pre-Android 12
    const val FALLBACK_CARD_TINT_ALPHA_LIGHT = 0.92f
    const val FALLBACK_CARD_TINT_ALPHA_DARK = 0.88f
    const val FALLBACK_CARD_TINT_ALPHA_CLEAR = 0.84f

    // Faint 1dp highlight edge (One UI dark folder card is virtually borderless)
    const val HIGHLIGHT_EDGE_ALPHA_LIGHT = 0.22f
    const val HIGHLIGHT_EDGE_ALPHA_DARK = 0.06f
    val HIGHLIGHT_EDGE_WIDTH = 1.dp

    // Shared expanded card corner radius & blur ramp bounds
    const val EXPANDED_CARD_CORNER_RADIUS_DP = 36f
    const val BLUR_RAMP_START = 0f
    const val BLUR_RAMP_END = 1f
    const val PREDICTIVE_BACK_BLUR_MIN_FACTOR = 0.80f

    fun maxBlurRadiusPx(density: Density): Int =
        with(density) { MAX_BLUR_RADIUS_DP.toPx().roundToInt().coerceIn(48, 200) }

    /**
     * Animates blur, scrim, and tint identically with the container-transform [progress],
     * reaching `0f` the exact frame the collapsing card lands on the home-screen widget.
     */
    fun computeBlurProgress(progress: Float): Float {
        val span = (BLUR_RAMP_END - BLUR_RAMP_START).coerceAtLeast(0.01f)
        return ((progress.coerceIn(0f, 1f) - BLUR_RAMP_START) / span).coerceIn(0f, 1f)
    }

    /**
     * Synchronizes cross-window blur & scrim progress across both container open/close progress
     * and active predictive-back gesture progress so WindowManager blur never freezes or snaps
     * when transitioning from back swipe into collapse/cancel.
     */
    fun computeEffectiveBlurProgress(
        containerProgress: Float,
        backDragProgress: Float = 0f
    ): Float {
        val container = computeBlurProgress(containerProgress)
        val backFactor = 1f - (1f - PREDICTIVE_BACK_BLUR_MIN_FACTOR) * backDragProgress.coerceIn(0f, 1f)
        return (container * backFactor).coerceIn(0f, 1f)
    }

    fun scrimAlpha(isBlurEnabled: Boolean): Float =
        if (isBlurEnabled) BLUR_ENABLED_SCRIM_ALPHA else FALLBACK_NO_BLUR_SCRIM_ALPHA

    fun cardTintAlpha(bgStyle: String, isSystemDark: Boolean, isBlurEnabled: Boolean): Float {
        val lightTone = when (bgStyle) {
            FolderBgStyles.FROSTED_LIGHT -> true
            FolderBgStyles.FROSTED_DARK, FolderBgStyles.CLEAR -> false
            else -> !isSystemDark
        }
        return when {
            bgStyle == FolderBgStyles.CLEAR -> {
                if (isBlurEnabled) CARD_TINT_ALPHA_CLEAR else FALLBACK_CARD_TINT_ALPHA_CLEAR
            }
            lightTone -> {
                if (isBlurEnabled) CARD_TINT_ALPHA_LIGHT else FALLBACK_CARD_TINT_ALPHA_LIGHT
            }
            else -> {
                if (isBlurEnabled) CARD_TINT_ALPHA_DARK else FALLBACK_CARD_TINT_ALPHA_DARK
            }
        }
    }

    fun resolveCardColors(
        bgStyle: String,
        isSystemDark: Boolean,
        isBlurEnabled: Boolean,
        dynamicSurfaceColor: Color,
        dynamicOnSurface: Color,
        dynamicPrimary: Color
    ): FolderCardBlurColors {
        val tintAlpha = cardTintAlpha(bgStyle, isSystemDark, isBlurEnabled)
        return when (bgStyle) {
            FolderBgStyles.FROSTED_LIGHT -> FolderCardBlurColors(
                cardTint = Color(0xFFF8FAFC).copy(alpha = tintAlpha),
                highlightEdge = Color.White.copy(alpha = HIGHLIGHT_EDGE_ALPHA_LIGHT),
                opaqueCollapseShield = Color(0xFFF5F8FA),
                contentColor = Color(0xFF0F172A),
                overflowCircleColor = Color(0x1A0F172A)
            )
            FolderBgStyles.DYNAMIC -> {
                val baseColor = if (isSystemDark) Color(0xFF1C2029) else Color(0xFFF4F6FA)
                val blendedBase = Color(
                    red = (dynamicSurfaceColor.red * 0.45f) + (baseColor.red * 0.55f),
                    green = (dynamicSurfaceColor.green * 0.45f) + (baseColor.green * 0.55f),
                    blue = (dynamicSurfaceColor.blue * 0.45f) + (baseColor.blue * 0.55f),
                    alpha = 1f
                )
                FolderCardBlurColors(
                    cardTint = blendedBase.copy(alpha = tintAlpha),
                    highlightEdge = Color.White.copy(
                        alpha = if (isSystemDark) HIGHLIGHT_EDGE_ALPHA_DARK else HIGHLIGHT_EDGE_ALPHA_LIGHT
                    ),
                    opaqueCollapseShield = dynamicSurfaceColor.copy(alpha = 1f),
                    contentColor = dynamicOnSurface,
                    overflowCircleColor = dynamicPrimary.copy(alpha = 0.16f)
                )
            }
            FolderBgStyles.CLEAR -> FolderCardBlurColors(
                cardTint = Color(0xFF181C24).copy(alpha = tintAlpha),
                highlightEdge = Color.White.copy(alpha = HIGHLIGHT_EDGE_ALPHA_DARK),
                opaqueCollapseShield = Color(0xFF141B29),
                contentColor = Color.White,
                overflowCircleColor = Color(0x26FFFFFF)
            )
            else -> FolderCardBlurColors(
                cardTint = Color(0xFF1A1E28).copy(alpha = tintAlpha),
                highlightEdge = Color.White.copy(alpha = HIGHLIGHT_EDGE_ALPHA_DARK),
                opaqueCollapseShield = Color(0xFF161E2E),
                contentColor = Color.White,
                overflowCircleColor = Color(0x26FFFFFF)
            )
        }
    }
}
