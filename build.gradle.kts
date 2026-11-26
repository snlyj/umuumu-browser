plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("io.github.takahirom.roborazzi") version "1.39.0" apply false
    // 生成 Baseline Profile 的 Gradle 插件 + 对应的 com.android.test 模块（:baselineprofile）
    id("androidx.baselineprofile") version "1.3.3" apply false
    id("com.android.test") version "8.7.3" apply false
}
