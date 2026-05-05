# R8 Compatibility Mode 配合 proguard-android-optimize.txt 使用
# 保留行号方便排查崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson / Retrofit 依赖泛型与匿名内部类签名做运行时反射
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes Exceptions
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

# Retrofit — 保留 API 接口的全部方法及泛型签名
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class com.tutu.myblbl.network.api.** { *; }

# Network response wrappers（泛型类型信息不能被 R8 优化掉）
-keep class com.tutu.myblbl.network.response.** { *; }

# Gson custom TypeAdapters
-keep class com.tutu.myblbl.model.adapter.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Model classes
-keep class com.tutu.myblbl.model.** { *; }

# SponsorBlock / 空降助手
-keep class com.tutu.myblbl.feature.player.sponsor.** { *; }

# Koin DI
-keep class com.tutu.myblbl.di.** { *; }
-keep class com.tutu.myblbl.repository.** { *; }
-keep class com.tutu.myblbl.event.** { *; }
-keep class com.tutu.myblbl.core.common.** { *; }
-keep class com.tutu.myblbl.network.** { *; }

# ViewModel (Koin 反射创建)
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.tutu.myblbl.feature.**ViewModel { *; }

# AkDanmaku（内嵌弹幕引擎，Ashley ECS 使用反射创建组件）
-keep class com.kuaishou.akdanmaku.** { *; }
-keep class com.badlogicgames.ashley.** { *; }
-keep class com.badlogicgames.gdx.** { *; }

# 通用 Android 优化
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.**

# 移除日志（release 构建）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom views
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.content.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.content.AttributeSet, int);
}

# Keep enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Coil 3.x
-keep class coil3.** { *; }
-keep interface coil3.** { *; }
-dontwarn coil3.**
-keep class okio.** { *; }
-dontwarn okio.**
