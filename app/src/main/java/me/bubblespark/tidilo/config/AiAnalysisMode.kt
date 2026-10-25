package me.bubblespark.tidilo.config

/** Controls whether article imports use on-device inference. */
enum class AiAnalysisMode(val code: String) {
    AICORE("aicore"),
    DISABLE("disable");

    companion object {
        fun fromCode(code: String?): AiAnalysisMode =
            entries.firstOrNull { it.code == code } ?: AICORE
    }
}
