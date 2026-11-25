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
