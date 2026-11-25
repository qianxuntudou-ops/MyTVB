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
-keep class com.mytvb.network.api.** { *; }

# Network response wrappers（泛型类型信息不能被 R8 优化掉）
-keep class com.mytvb.network.response.** { *; }

# Gson custom TypeAdapters
-keep class com.mytvb.model.adapter.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Model classes
-keep class com.mytvb.model.** { *; }

# SponsorBlock / 空降助手
-keep class com.mytvb.feature.player.sponsor.** { *; }

# TV 直播（marmot）：CloudConfig/CloudRes/MarmotModels/HzItem 由 Gson 反射解析，
# 字段名/构造器被 R8 优化掉会导致 fromJson 静默置 null（曾引发「频道数据加载失败」：
# DataWrapper.data 与 CloudConfig.res 都解析为 null）。必须保留类、字段、构造器。
-keep class com.mytvb.feature.marmot.domain.** { *; }
-keepclassmembers class com.mytvb.feature.marmot.domain.** {
    <init>(...);
}

# 投屏（NVA）：整体保留，不混淆不优化。
# 实测 Fengmi 小明投影（Android 9, API 28）装 R8 release 包后，nva-boot 线程加载投屏类抛
# NoClassDefFoundError: Invalid descriptor: .（ART 解析混淆产物的描述符判非法），该线程
# 未捕获异常直接杀进程 → 每次冷启动秒退、首页看不到；覆盖安装可临时恢复但会复发。
# 与 core.common/network 同级整体 keep，规避 R8 产物在该 ART 版本上的兼容问题。
-keep class com.mytvb.feature.cast.** { *; }

# Koin DI
-keep class com.mytvb.di.** { *; }
-keep class com.mytvb.repository.** { *; }
-keep class com.mytvb.event.** { *; }
-keep class com.mytvb.core.common.** { *; }
-keep class com.mytvb.network.** { *; }

# ViewModel (Koin 反射创建)
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.mytvb.feature.**ViewModel { *; }

# 通用 Android 优化
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.**
# gRPC 引用的 JNDI 类在 Android 上不存在（DNS 解析不走 JNDI）
-dontwarn javax.naming.**

# 移除日志（release 构建）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# protobuf-javalite：jar 依赖不自带 consumer 规则，生成类的字段必须手动保留。
# 运行时 MessageSchema 按字段名反射访问生成类字段（如热门 gRPC 请求的 idx_），
# 字段被 R8 改名即抛 "Field idx_ for x4.c not found. Known fields are [...]"
# （release 包热门 tab 必现；debug 不混淆所以开发期无感知）。
-keep class com.google.protobuf.** { *; }
-keep class bilibili.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
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

-keep class okio.** { *; }
-dontwarn okio.**

# 腾讯 X5 / TBS 内核
# dalvik.system.VMStack 为 Android 运行时隐藏类，不在 compile SDK 中，TBS 的 DexLoader 在运行时通过反射调用
-dontwarn dalvik.system.VMStack
# X5 内核核心 API（com.tencent.smtt.*）通过反射加载，不能被混淆或裁剪
-keep class com.tencent.smtt.** { *; }
-keep interface com.tencent.smtt.** { *; }
# TBS 下载/加载入口
-keep class com.tencent.tbs.** { *; }
-keep interface com.tencent.tbs.** { *; }
# X5 附属动态加载类（含 DexLoader 等内部实现）
-keep class com.tencent.smtt.export.external.** { *; }
-keep class com.tencent.smtt.sdk.** { *; }
-keep class com.tencent.smtt.utils.** { *; }
# X5 运行时依赖的隐藏类
-dontwarn com.tencent.smtt.**
-dontwarn com.tencent.tbs.**
