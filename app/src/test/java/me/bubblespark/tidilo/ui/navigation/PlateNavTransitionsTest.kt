package me.bubblespark.tidilo.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import org.junit.Assert.assertEquals
import org.junit.Test

class PlateNavTransitionsTest {
    private val effects = tween<Float>(150)

    @Test fun detailShrinksOnPopWithNormalMotion() {
        val transitions = PlateNavTransitions(effects, reducedMotion = false)
        assertEquals(scaleOut(targetScale = 0.9f) + fadeOut(effects), transitions.popExit)
        assertEquals(fadeIn(effects), transitions.enter)
        assertEquals(fadeOut(effects), transitions.exit)
    }

    @Test fun reducedMotionPopIsCrossfadeOnly() {
        assertEquals(fadeOut(effects), PlateNavTransitions(effects, reducedMotion = true).popExit)
    }
}
