package me.bubblespark.tidilo.config

import android.content.Context
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
    private const val KEY_NSFW_CONTENT_VISIBLE = "nsfw_content_visible"
    private const val KEY_NSFW_CONTENT_BOOKMARKABLE = "nsfw_content_bookmarkable"
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

    // NSFW content visibility flag (default: true)
    private val _isNsfwContentVisible = MutableStateFlow(
        runCatching { prefs.getBoolean(KEY_NSFW_CONTENT_VISIBLE, true) }
            .getOrDefault(true)
    )
    val isNsfwContentVisible: StateFlow<Boolean> = _isNsfwContentVisible.asStateFlow()

    fun setNsfwContentVisible(visible: Boolean) {
        _isNsfwContentVisible.value = visible
        runCatching {
            prefs.edit().putBoolean(KEY_NSFW_CONTENT_VISIBLE, visible).apply()
        }
    }

    // NSFW content bookmarking allowed flag (default: true)
    private val _isNsfwContentBookmarkable = MutableStateFlow(
        runCatching { prefs.getBoolean(KEY_NSFW_CONTENT_BOOKMARKABLE, true) }
            .getOrDefault(true)
    )
    val isNsfwContentBookmarkable: StateFlow<Boolean> = _isNsfwContentBookmarkable.asStateFlow()

    fun setNsfwContentBookmarkable(bookmarkable: Boolean) {
        _isNsfwContentBookmarkable.value = bookmarkable
        runCatching {
            prefs.edit().putBoolean(KEY_NSFW_CONTENT_BOOKMARKABLE, bookmarkable).apply()
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
