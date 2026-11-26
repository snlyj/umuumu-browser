# 唔姆唔姆 · Baseline Profile / Macrobenchmark 模块

目前 `app/src/main/baseline-prof.txt` 是**手写**的（没有设备可以录制）。有真机或能跑的模拟器之后：

```bash
export JAVA_HOME=/workspace/tools/jdk17 ANDROID_HOME=/workspace/tools/android
adb devices                                  # 确认有设备（API 28+，需要装好系统 WebView）
./gradlew :app:generateBaselineProfile       # 录制 → app/src/release/generated/baselineProfiles/baseline-prof.txt
./gradlew :app:assembleRelease               # 手写的 + 录到的 profile 会合并进 APK
```

量化效果（和 `CompilationMode.None` 对比冷启动、交互帧耗时）：

```bash
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

- 录制用的"旅程"在 `Journeys.kt`：启动 → 首页滚动 → 菜单面板 → 地址栏输入网址 → 标签页切换。选择器用的是界面上的无障碍描述（"菜单""地址栏…""标签页…""新建标签页""刷新"），改了界面文字要同步。
- 录出真实 profile 后，手写那份可以只留 `HSPL…` 的兜底行，或者直接删掉。
- `profiling` 构建类型（不混淆、可录制）是旧的手动录制方式，仍然保留。
