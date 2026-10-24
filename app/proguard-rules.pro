# Proguard rules for Tidilo

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class me.bubblespark.tidilo.data.db.** { *; }

# Jsoup HTML Parser
-keep public class org.jsoup.** { public *; }
-dontwarn org.jsoup.**

# Readability4J Content Extractor & SLF4J Logging
-keep class net.dankito.readability4j.** { *; }
-dontwarn net.dankito.readability4j.**
-dontwarn org.slf4j.**

# ML Kit GenAI (AICore)
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**
