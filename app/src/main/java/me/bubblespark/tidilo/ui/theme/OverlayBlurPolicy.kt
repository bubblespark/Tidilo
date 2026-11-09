package me.bubblespark.tidilo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

enum class OverlayBlurPolicy { USER_CHOICE, FORCE_ON, FORCE_OFF }

data class OverlayBlurSetting(val enabled: Boolean, val userEditable: Boolean)

fun resolveOverlayBlur(policy: OverlayBlurPolicy, userEnabled: Boolean): OverlayBlurSetting = when (policy) {
    OverlayBlurPolicy.USER_CHOICE -> OverlayBlurSetting(userEnabled, true)
    OverlayBlurPolicy.FORCE_ON -> OverlayBlurSetting(true, false)
    OverlayBlurPolicy.FORCE_OFF -> OverlayBlurSetting(false, false)
}

val LocalOverlayBlurSetting = staticCompositionLocalOf { OverlayBlurSetting(true, true) }
