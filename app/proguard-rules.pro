# WebView 的 JS 接口等目前没用到；Compose/AndroidX 自带 consumer 规则。
# 保留行号，方便崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# blob: 下载用的 JS 桥（旧 WebView 的退路）——方法名 postMessage 必须保留，否则网页里 window.UmuBlobBridge.postMessage 调不到
-keepattributes JavascriptInterface
-keep class com.yuka.cutebrowser.browser.BlobBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp 4.12（App 内 DoH 用）：自带 consumer 规则；这里再兜底几个可选的 TLS 提供者（Android 上没有）
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# 测速/DoH 才会第一次碰到 OkHttp。R8 full mode 若裁掉 okio 扩展（toByteString / toHttpUrl）
# 或 PublicSuffixDatabase，release 包一点「测一测」就会 ExceptionInInitializerError / NoSuchMethodError。
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**
