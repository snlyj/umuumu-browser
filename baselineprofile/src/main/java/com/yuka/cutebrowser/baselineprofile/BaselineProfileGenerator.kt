package com.yuka.cutebrowser.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真机/模拟器上录制 Baseline Profile：
 *   ./gradlew :app:generateBaselineProfile
 * 结果写到 app/src/release/generated/baselineProfiles/baseline-prof.txt（saveInSrc = true），
 * 构建时会和手写的 app/src/main/baseline-prof.txt 合并。
 *
 * 这几段"旅程"覆盖启动、首页、地址栏、标签页切换、菜单面板、网页加载（WebViewClient 回调 + FilterEngine 匹配）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = targetAppId,
        includeInStartupProfile = true, // 启动路径的方法同时进 startup profile，R8 会据此重排 dex
    ) {
        startToHome()
        homeScrollJourney()
        menuJourney()
        openUrlFromAddressBar("example.com")
        openUrlFromAddressBar("https://www.wikipedia.org")
        tabSwitchJourney()
    }
}
