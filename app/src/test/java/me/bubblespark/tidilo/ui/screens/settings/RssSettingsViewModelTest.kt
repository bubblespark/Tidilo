package me.bubblespark.tidilo.ui.screens.settings

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RssSettingsViewModelTest {

    @Test
    fun verifyConstructorSupportsDefaultSavedState() {
        val constructors = RssSettingsViewModel::class.java.declaredConstructors
        val hasDefaultConstructor = constructors.any { constructor ->
            val types = constructor.parameterTypes
            types.size == 4 &&
                types[0] == Application::class.java &&
                types[1] == SavedStateHandle::class.java &&
                types[2] == java.lang.Integer.TYPE &&
                types[3].name == "kotlin.jvm.internal.DefaultConstructorMarker"
        }
        assertTrue(
            "RssSettingsViewModel should have a synthetic constructor supporting default parameters",
            hasDefaultConstructor
        )

        val hasTwoArgConstructor = constructors.any { constructor ->
            val types = constructor.parameterTypes
            types.size == 2 &&
                types[0] == Application::class.java &&
                types[1] == SavedStateHandle::class.java
        }
        assertTrue(
            "RssSettingsViewModel should have (Application, SavedStateHandle) constructor",
            hasTwoArgConstructor
        )
    }

    @Test
    fun verifyProguardRulesRetainViewModels() {
        val proguardCandidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro")
        )
        val proguardFile = proguardCandidates.firstOrNull { it.exists() }
            ?: throw AssertionError("Could not find proguard-rules.pro in $proguardCandidates")
        val content = proguardFile.readText()

        assertTrue(
            "Proguard rules must keep ViewModel class members with <init>(...)",
            content.contains("-keepclassmembers class * extends androidx.lifecycle.ViewModel")
        )
        assertTrue(
            "Proguard rules must keep ViewModel classes",
            content.contains("-keep class * extends androidx.lifecycle.ViewModel")
        )
    }
}
