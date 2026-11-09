plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val overlayBlurDependency = libs.haze.blur
project(":app") {
    pluginManager.withPlugin("com.android.application") {
        dependencies.add("implementation", overlayBlurDependency)
    }
}
