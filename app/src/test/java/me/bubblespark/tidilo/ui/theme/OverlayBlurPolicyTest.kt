package me.bubblespark.tidilo.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayBlurPolicyTest {
    @Test fun resolvesEveryPolicyForBothSavedChoices() {
        for (saved in listOf(false, true)) {
            assertEquals(OverlayBlurSetting(saved, true), resolveOverlayBlur(OverlayBlurPolicy.USER_CHOICE, saved))
            assertEquals(OverlayBlurSetting(true, false), resolveOverlayBlur(OverlayBlurPolicy.FORCE_ON, saved))
            assertEquals(OverlayBlurSetting(false, false), resolveOverlayBlur(OverlayBlurPolicy.FORCE_OFF, saved))
        }
    }

    @Test fun existingPresetsAllowUserChoice() {
        assertEquals(OverlayBlurPolicy.USER_CHOICE, AppThemeStyle.Default.overlayBlurPolicy)
        assertEquals(OverlayBlurPolicy.USER_CHOICE, AppThemeStyle.Modern.overlayBlurPolicy)
    }

    @Test fun fixedPolicyDoesNotReplaceSavedChoiceWhenReturning() {
        val saved = false
        assertEquals(true, resolveOverlayBlur(OverlayBlurPolicy.FORCE_ON, saved).enabled)
        assertEquals(false, resolveOverlayBlur(OverlayBlurPolicy.FORCE_OFF, saved).enabled)
        assertEquals(OverlayBlurSetting(false, true), resolveOverlayBlur(OverlayBlurPolicy.USER_CHOICE, saved))
    }
}
