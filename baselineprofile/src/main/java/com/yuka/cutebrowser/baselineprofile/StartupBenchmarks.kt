package com.yuka.cutebrowser.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 量化 Baseline Profile 的效果（需要真机/模拟器）：
 *   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   （用 benchmarkRelease 构建类型）
 * 对比 CompilationMode.None（没有任何预编译）和装了 profile 的冷启动 / 页面交互帧耗时。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun coldStartNoCompilation() = coldStart(CompilationMode.None())

    @Test fun coldStartWithBaselineProfile() = coldStart(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun coldStart(mode: CompilationMode) = rule.measureRepeated(
        packageName = targetAppId,
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = mode,
    ) {
        startToHome()
    }

    /** 打开菜单、标签页、滚动首页期间的帧耗时 / 卡顿 */
    @Test fun interactionFramesWithBaselineProfile() = rule.measureRepeated(
        packageName = targetAppId,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
    ) {
        startToHome()
        homeScrollJourney()
        menuJourney()
        tabSwitchJourney()
    }
}
