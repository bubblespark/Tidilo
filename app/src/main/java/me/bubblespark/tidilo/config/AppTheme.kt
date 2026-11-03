package me.bubblespark.tidilo.config

import androidx.annotation.StringRes
import me.bubblespark.tidilo.R

/**
 * Application visual theme definition.
 */
enum class AppTheme(
    val code: String,
    @StringRes val labelRes: Int
) {
    DEFAULT(
        code = "default",
        labelRes = R.string.settings_theme_default
    ),
    MODERN(
        code = "modern",
        labelRes = R.string.settings_theme_modern
    );

    companion object {
        fun fromCode(code: String?): AppTheme =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}
