package me.bubblespark.tidilo.config

import android.content.Context
import androidx.annotation.StringRes
import me.bubblespark.tidilo.R
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
 * AICore error fallback modes for bookmark ingestion.
 * Initial default setting is DISABLE (無効).
 */
enum class AiCoreFallbackMode(
    val code: String,
    val jaLabel: String,
    val enLabel: String,
    @StringRes val labelRes: Int = 0,
    @StringRes val descRes: Int = 0
) {
    DISABLE(
        "disable",
        "無効",
        "Disable",
        labelRes = R.string.settings_fallback_mode_disable,
        descRes = R.string.settings_fallback_mode_disable_desc
    ),
    QUOTA_EXCEEDED_ONLY(
        "quota_only",
        "クォータ上限に達した場合のみ",
        "If the quota is exceeded",
        labelRes = R.string.settings_fallback_mode_quota,
        descRes = R.string.settings_fallback_mode_quota_desc
    ),
    ALL(
        "all",
        "すべてのAICoreエラー",
        "All AICore errors",
        labelRes = R.string.settings_fallback_mode_all,
        descRes = R.string.settings_fallback_mode_all_desc
    );

    fun getLabel(isJapanese: Boolean): String = if (isJapanese) jaLabel else enLabel

    companion object {
        fun fromCode(code: String): AiCoreFallbackMode =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DISABLE
    }
}

/**
 * App-wide configuration and feature flag management.
 */
object AppConfig {
    private const val PREFS_NAME = "tidilo_app_config_prefs"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_NSFW_CONTENT_VISIBLE = "nsfw_content_visible"
    private const val KEY_NSFW_CONTENT_BOOKMARKABLE = "nsfw_content_bookmarkable"
    private const val KEY_AUTO_RUN_AI_ON_ACTIVE = "auto_run_ai_on_active"
    private const val KEY_AICORE_FALLBACK_MODE = "aicore_fallback_mode"
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

    // Auto-run AI tagging when app becomes active (default: false)
    private val _isAutoRunAiOnActive = MutableStateFlow(
        runCatching { prefs.getBoolean(KEY_AUTO_RUN_AI_ON_ACTIVE, false) }
            .getOrDefault(false)
    )
    val isAutoRunAiOnActive: StateFlow<Boolean> = _isAutoRunAiOnActive.asStateFlow()

    fun setAutoRunAiOnActive(enabled: Boolean) {
        _isAutoRunAiOnActive.value = enabled
        runCatching {
            prefs.edit().putBoolean(KEY_AUTO_RUN_AI_ON_ACTIVE, enabled).apply()
        }
    }

    // AICore error fallback mode (default: DISABLE)
    private val _aiCoreFallbackMode = MutableStateFlow(
        AiCoreFallbackMode.fromCode(
            runCatching { prefs.getString(KEY_AICORE_FALLBACK_MODE, AiCoreFallbackMode.DISABLE.code) }
                .getOrNull() ?: AiCoreFallbackMode.DISABLE.code
        )
    )
    val aiCoreFallbackMode: StateFlow<AiCoreFallbackMode> = _aiCoreFallbackMode.asStateFlow()

    fun setAiCoreFallbackMode(mode: AiCoreFallbackMode) {
        _aiCoreFallbackMode.value = mode
        runCatching {
            prefs.edit().putString(KEY_AICORE_FALLBACK_MODE, mode.code).apply()
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
