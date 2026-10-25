# Proguard rules for Tidilo

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class me.bubblespark.tidilo.data.db.** { *; }

# Jsoup HTML Parser
-dontwarn org.jsoup.**

# Readability4J Content Extractor & SLF4J Logging
-dontwarn net.dankito.readability4j.**
-dontwarn org.slf4j.**

# ML Kit GenAI (AICore)
-dontwarn com.google.mlkit.genai.**

# ML Kit discovers this registrar through manifest metadata and reflection.
# Retain its constructor so SharedPrefManager is registered before client creation.
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
