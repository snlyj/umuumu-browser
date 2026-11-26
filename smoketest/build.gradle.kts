// 真机/模拟器黑盒冒烟测试（UiAutomator），自我插桩：不需要和被测 App 同签名，
// 所以同一套测试可以跑 debug 包，也可以跑 R8 混淆过的 release 包。
// 用法见 scripts/smoke.sh 和 NOTES.md「运行时测试」。
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuka.cutebrowser.smoke"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test:rules:1.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
