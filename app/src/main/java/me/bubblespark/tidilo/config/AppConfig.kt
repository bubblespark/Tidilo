package me.bubblespark.tidilo.config

import android.content.Context
import android.content.SharedPreferences
import me.bubblespark.tidilo.TidiloApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application display language.
 * Default base language is English (ENGLISH).
 */
enum class AppLanguage(val code: String, val displayName: String) {
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語");

    val isJapanese: Boolean
        get() = this == JAPANESE

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}

/**
 * App-wide configuration and feature flag management.
 */
object AppConfig {
    private const val PREFS_NAME = "tidilo_app_config_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_APP_THEME = "app_theme"
    private const val KEY_OVERLAY_BLUR_ENABLED = "overlay_blur_enabled"
    private const val KEY_PRIVATE_CONTENT_VISIBLE = "private_content_visible"
    private const val KEY_LEGACY_NSFW_CONTENT_VISIBLE = "nsfw_content_visible"
    private const val KEY_PRIVATE_CONTENT_BOOKMARKABLE = "private_content_bookmarkable"
    private const val KEY_LEGACY_NSFW_CONTENT_BOOKMARKABLE = "nsfw_content_bookmarkable"
    private const val KEY_AI_ANALYSIS_MODE = "ai_analysis_mode"

    private val prefs by lazy {
        TidiloApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Language setting (Base language: English)
    private val _currentLanguage = MutableStateFlow(
        AppLanguage.fromCode(
            // Safely initialize with fallback when Application context is not yet attached (e.g., unit tests)
            runCatching { prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code) }
                .getOrNull() ?: AppLanguage.ENGLISH.code
        )
    )
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    fun setLanguage(language: AppLanguage) {
        _currentLanguage.value = language
        runCatching {
            prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
        }
    }

    // Theme setting (Default: DEFAULT)
    private val _currentTheme = MutableStateFlow(
        AppTheme.fromCode(
            runCatching { prefs.getString(KEY_APP_THEME, AppTheme.DEFAULT.code) }
                .getOrNull() ?: AppTheme.DEFAULT.code
        )
    )
    val currentTheme: StateFlow<AppTheme> = _currentTheme.asStateFlow()

    fun setTheme(theme: AppTheme) {
        _currentTheme.value = theme
        runCatching {
            prefs.edit().putString(KEY_APP_THEME, theme.code).apply()
        }
    }

    private val _overlayBlurEnabled = MutableStateFlow(
        runCatching { readOverlayBlurPreference(prefs) }.getOrDefault(true)
    )
    val overlayBlurEnabled: StateFlow<Boolean> = _overlayBlurEnabled.asStateFlow()

    fun setOverlayBlurEnabled(enabled: Boolean) {
        _overlayBlurEnabled.value = enabled
        runCatching { prefs.edit().putBoolean(KEY_OVERLAY_BLUR_ENABLED, enabled).apply() }
    }

    internal fun readOverlayBlurPreference(preferences: SharedPreferences): Boolean =
        runCatching { preferences.getBoolean(KEY_OVERLAY_BLUR_ENABLED, true) }.getOrDefault(true)

    internal fun readBooleanPreferenceWithLegacyFallback(
        preferences: SharedPreferences,
        key: String,
        legacyKey: String,
        defaultValue: Boolean = true
    ): Boolean = runCatching {
        when {
            preferences.contains(key) -> preferences.getBoolean(key, defaultValue)
            preferences.contains(legacyKey) -> {
                val migrated = preferences.getBoolean(legacyKey, defaultValue)
                preferences.edit().putBoolean(key, migrated).remove(legacyKey).apply()
                migrated
            }
            else -> defaultValue
        }
    }.getOrDefault(defaultValue)

    // Private content visibility flag (default: true).
    // Outer runCatching guards lazy `prefs` evaluation when TidiloApplication.instance is uninitialized in JVM tests.
    private val _isPrivateContentVisible = MutableStateFlow(
        runCatching {
            readBooleanPreferenceWithLegacyFallback(
                prefs,
                KEY_PRIVATE_CONTENT_VISIBLE,
                KEY_LEGACY_NSFW_CONTENT_VISIBLE,
                true
            )
        }.getOrDefault(true)
    )
    val isPrivateContentVisible: StateFlow<Boolean> = _isPrivateContentVisible.asStateFlow()

    fun setPrivateContentVisible(visible: Boolean) {
        _isPrivateContentVisible.value = visible
        runCatching {
            prefs.edit().putBoolean(KEY_PRIVATE_CONTENT_VISIBLE, visible).apply()
        }
    }

    // Private content bookmarking allowed flag (default: true).
    // Outer runCatching guards lazy `prefs` evaluation when TidiloApplication.instance is uninitialized in JVM tests.
    private val _isPrivateContentBookmarkable = MutableStateFlow(
        runCatching {
            readBooleanPreferenceWithLegacyFallback(
                prefs,
                KEY_PRIVATE_CONTENT_BOOKMARKABLE,
                KEY_LEGACY_NSFW_CONTENT_BOOKMARKABLE,
                true
            )
        }.getOrDefault(true)
    )
    val isPrivateContentBookmarkable: StateFlow<Boolean> = _isPrivateContentBookmarkable.asStateFlow()

    fun setPrivateContentBookmarkable(bookmarkable: Boolean) {
        _isPrivateContentBookmarkable.value = bookmarkable
        runCatching {
            prefs.edit().putBoolean(KEY_PRIVATE_CONTENT_BOOKMARKABLE, bookmarkable).apply()
        }
    }

    private val aiAnalysisPreference = AiAnalysisPreference(
        runCatching { prefs.getString(KEY_AI_ANALYSIS_MODE, null) }.getOrNull()?.let(AiAnalysisMode::fromCode)
    ) { mode -> runCatching { prefs.edit().putString(KEY_AI_ANALYSIS_MODE, mode.code).apply() } }
    val aiAnalysisMode: StateFlow<AiAnalysisMode> = aiAnalysisPreference.mode

    fun setAiAnalysisMode(mode: AiAnalysisMode) {
        aiAnalysisPreference.setMode(mode)
    }

    internal suspend fun initializeAiAnalysisMode(isFreshInstallation: Boolean) {
        aiAnalysisPreference.initialize(isFreshInstallation) { me.bubblespark.tidilo.data.AiCoreAvailability.check() }
    }
}
