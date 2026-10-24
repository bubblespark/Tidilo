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
