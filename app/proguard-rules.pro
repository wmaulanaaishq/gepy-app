# R8 / ProGuard Targeted Rules - Gepy Native (v6.5.2)

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# BuildConfig flags & version info
-keep class com.teknologigawai.gepy.BuildConfig { *; }

# kotlinx.serialization (models in core.memory.backup & features.voice)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.teknologigawai.gepy.**$$serializer { *; }
-keepclassmembers class com.teknologigawai.gepy.** {
    *** Companion;
}
-keepclasseswithmembers class com.teknologigawai.gepy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.teknologigawai.gepy.** { *; }

# Room v6 Database, Entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class com.teknologigawai.gepy.** { *; }
-keep @androidx.room.Dao interface com.teknologigawai.gepy.** { *; }
-keep class com.teknologigawai.gepy.data.database.GepyDatabase_Impl { *; }

# Note: OkHttp, kotlinx-coroutines, androidx.credentials, GoogleID, Sentry, Lottie, & ML Kit
# menyertakan aturan R8 consumer masing-masing di dalam AAR/JAR resmi.
