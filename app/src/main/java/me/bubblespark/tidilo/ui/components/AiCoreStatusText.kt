package me.bubblespark.tidilo.ui.components

import androidx.annotation.StringRes
import com.google.mlkit.genai.common.FeatureStatus
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.AiCoreReadiness

@StringRes
internal fun aiCoreStatusText(readiness: AiCoreReadiness?): Int = when (readiness) {
    null -> R.string.settings_ai_analysis_checking
    AiCoreReadiness.Failed -> R.string.settings_ai_analysis_check_failed
    is AiCoreReadiness.Status -> when (readiness.code) {
        FeatureStatus.AVAILABLE -> R.string.ai_model_ready
        FeatureStatus.DOWNLOADABLE -> R.string.ai_model_download_required
        FeatureStatus.DOWNLOADING -> R.string.ai_model_downloading
        else -> R.string.settings_ai_analysis_unavailable
    }
}
