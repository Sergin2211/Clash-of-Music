# ProGuard rules for Clash of Music

# Keep NewPipeExtractor classes and models
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino is invoked by the extractor, including script-visible members/reflection.
# Based on TeamNewPipe/NewPipe's app/proguard-rules.pro.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
# Optional desktop JavaBeans / JSR-223 integration is unavailable on Android.
# The extractor evaluates JavaScript directly, without these desktop adapters.
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep youtubedl-android
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# yt-dlp unpacks its bundled runtime with Commons Compress. ExtraFieldUtils
# registers ZIP fields through reflective no-arg construction; R8 must retain
# their concrete classes and constructors, including on a fresh extraction.
-keep class org.apache.commons.compress.archivers.zip.** { *; }

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Domain Models
-keep class com.clashofmusic.app.domain.model.** { *; }
-keep class com.clashofmusic.app.data.database.entity.** { *; }
