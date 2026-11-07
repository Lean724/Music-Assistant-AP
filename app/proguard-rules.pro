# ProGuard / R8 Rules for MMAC (Minimal Music Assistant Companion)

# Android Application Components
-keep public class com.example.MainActivity
-keep public class com.example.MusicApplication
-keep public class com.example.playback.PlaybackService

# Kotlin Reflection & Metadata
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keepclassmembers class **$WhenMappings {
    int[] $EnumSwitchMapping$*;
}

# Keep line numbers for stack traces in Play Console crash reports
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Moshi JSON serialization
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
-keep class com.squareup.moshi.** { *; }
-keep interface com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
    @com.squareup.moshi.JsonClass *;
}
-keep class *JsonAdapter {
    <init>(...);
}
-keep class **$$JsonObjectAdapter { *; }

# Keep domain data models and DTOs that are serialized/deserialized
-keep class com.example.data.** { *; }
-keep class com.example.playback.sendspin.** { *; }
-keep class com.example.api.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlinx Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Android Jetpack Compose & Lifecycle
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }

# AndroidX Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# AndroidX Media & Playback Service
-keep class androidx.media.** { *; }
-keep class androidx.core.app.NotificationCompat** { *; }
-keep class com.example.playback.PlaybackService { *; }
