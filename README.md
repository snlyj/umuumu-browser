# 唔姆唔姆（UmuUmu）

A cute, privacy-minded Android browser built on System WebView. Speed and smoothness come first, then security and privacy. Latest release on `main` is **1.6.4** (`versionCode` 11). Almost all automated checks were run on a desktop JVM or desktop Chrome, not on a phone — only the original author's own device testing has exercised a real Android WebView.

唔姆唔姆是一款可爱、偏少女风格的安卓浏览器。核心追求是快、流畅，其次是安全和隐私。内核使用系统 WebView（没有换成 GeckoView），在应用层做加固：广告与跟踪过滤、加密 DNS、防指纹、时区/语言伪装、无痕模式、应用锁等。当前 `main` 分支对应 **1.6.4**。

更细的设计说明、历次版本记录和测量数据见 [NOTES.md](NOTES.md)。

> **还没有选择开源许可证。** 仓库里没有 `LICENSE` 文件。在作者选定许可证之前，默认保留所有权利。If you are taking this project over, please agree a licence with the owner before redistributing.

## 功能（1.6.4）

- 标签页浏览，主页、收藏、历史；最多同时保留 3 个活的 WebView，其余会保存状态
- 广告与跟踪过滤（EasyList、EasyPrivacy、AdAway）
- 仅 HTTPS、拦截第三方 Cookie、WebRTC 防护、每站权限
- 加密 DNS（DoH，默认关闭；Cloudflare / Google / Quad9 / 阿里 / DNSPod / 自定义；走系统 HTTP 代理）
- 防指纹脚本（默认开启，标准/严格，可按站例外）
- 伪造时区与语言（含西雅图等预设；`Accept-Language` 会尽量跟着改）
- 无痕模式（菜单里是开关：再点一次会结束隐身并清理数据）
- 应用锁、离开时清除、`FLAG_SECURE`
- 从剪贴板识别并打开链接；中文错误页；本地崩溃记录（设置页长按版本号可复制，不上传）

## 技术栈

- Kotlin 2.0.21，Jetpack Compose（BOM 2024.12.01），Material 3
- Android Gradle Plugin 8.7.3，Gradle 8.10.2（wrapper），JDK 17
- `minSdk` 24，`compileSdk` / `targetSdk` 35
- 包名 `com.yuka.cutebrowser`
- 系统 WebView（`androidx.webkit` 1.12.1）
- DataStore 存设置，OkHttp 4.12 用于 DoH
- 测试：JUnit 4、Robolectric 4.14、Roborazzi 截图

## 如何编译

需要 **JDK 17** 和 **Android SDK**（platform / build-tools 35 即可）。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后：

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

发布包：

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

没有配置签名时，Release 构建会打出未签名包。调试包使用系统默认的 debug keystore。

### 给 Release 签名

仓库**不包含**签名密钥。`keystore/` 已写入 `.gitignore`。接手的人需要**自己生成** keystore 和 `keystore/keystore.properties`，不要使用或猜测原来的密码。

用 `keytool` 生成（示例，请自己选文件名、别名和密码）：

```bash
mkdir -p keystore
keytool -genkeypair -keystore keystore/your-release.jks -alias yourAlias \
  -keyalg RSA -keysize 2048 -validity 10000
```

然后新建 `keystore/keystore.properties`（把这一文件权限设为仅自己可读），格式如下，**不要把真实密码写进仓库**：

```properties
storeFile=your-release.jks
storePassword=your-store-password
keyAlias=yourAlias
keyPassword=your-key-password
```

`storeFile` 可以是文件名（相对 `keystore/`）或路径，构建脚本只会取文件名，并在 `keystore/` 目录下解析。`app/build.gradle.kts` 在这个文件存在且非空时，会自动把 Release 签上名。

**重要：** 原来已经装在用户手机上的 1.6.x 安装包，是用作者本机那份密钥签的。那份密钥不在本仓库里。如果要给现有用户做覆盖安装升级，必须向作者要回并妥善备份原来的 `keystore/`（含 `.jks` 和 `keystore.properties`）。换一套新密钥只能用于新安装，或用户先卸载再装。

## 测试

```bash
# 单元测试（含 Robolectric）
./gradlew testDebugUnitTest

# 同时录 Compose 截图（Roborazzi）
./gradlew testDebugUnitTest -Proborazzi.test.record=true
```

1.6.4 时，`main` 上这些测试是通过的。部分需要真网的用例（例如 `DohLiveTest`）会被跳过。

### 防指纹脚本（fp-js）

源码在 `fp-js/fingerprint.js`，压缩后放到 `app/src/main/assets/fingerprint.min.js`。

```bash
# 需要 terser（npm i -g terser，或设置 TERSER 指向可执行文件）
./fp-js/build.sh

# 在桌面 Chrome 里跑脚本测试（不是 Android WebView）
node fp-js/chrome-test.mjs
node fp-js/chrome-locale-test.mjs
```

## 目录结构

```
app/                  安卓应用（Kotlin + Compose）
  src/main/assets/    过滤规则、压缩后的防指纹脚本
  src/test/           单元测试和截图测试
fp-js/                防指纹脚本源码、构建脚本和 Chrome 测试
gradle/               Gradle Wrapper
NOTES.md              完整的项目笔记（设计、版本历史、已知限制）
keystore/             签名密钥（被 git 忽略，不会出现在仓库里）
```

未合入 `main` 的功能分支上还有：`smoketest/`、`scripts/`（`feature/stability`），`baselineprofile/`（`feature/perf`），`reader-js/`（`feature/features`）。

## 进行中的工作（Work in progress）

下面这些 `feature/*` 分支是在 1.6.4 上分开做的，做到一半就停了，**没有合进 main，也没有在真机上验证**。可以继续在分支上做完再合并，不要直接当作发布版。

### `feature/stability` — 稳定性和自动冒烟测试

本打算在可重复的安卓环境里把常用操作自动跑一遍。已有 `:smoketest` 模块（UiAutomator，12 个流程）和 `scripts/emu-up.sh`、`scripts/smoke.sh`（面向 TCG API 30 ATD 模拟器）。这个环境在原来的开发机上并不稳定（嵌套虚拟化、没有 KVM），所以这些流程很可能还没真正跑通。分支是干净的，没有未提交改动。

### `feature/perf` — 启动和流畅度

更早在后台启动过滤引擎和 WebView、并行读取会话、预备空闲 WebView、预热 DNS、标签缩略图、分级内存回收、启动 trace。另外加了 Macrobenchmark / Baseline Profile 生成模块（`:baselineprofile`），并细化了手写的 baseline profile。还有按 URL 后缀判断资源类型、必要时再读 `Accept` 头的小优化。分支是干净的。

### `feature/features` — 常用功能

已提交：页面内查找、按标签的桌面版网站、阅读模式（Mozilla Readability 0.6.0，Apache-2.0）、可选的网页翻译（Google / Yandex，每次确认，局域网地址不会送出）、下载管理页。最后一笔未完成的工作已打成 WIP 提交：把当前页面添加到主屏幕（隐身模式不可用）。这些功能都还没合进 1.6.4，也没有真机验证。

### `feature/themes` — 可爱主题和动画

七套配色、跟随系统、外观设置、壁纸、吉祥物（兔子 / 猫 / 丝带）、进度条样式、动效和减少动效、触感与音效开关，以及对比度和截图测试。WIP 提交里补了测试之间恢复默认外观、截图等待超时加长，避免 DataStore 单例把状态漏到下一个测试。

### `feature/privacy-backup` — 隐私加强，以及书签/设置备份

把防指纹注入到 Worker（含 blob / data / module / Shared Worker 等）、无痕在没有 multi-profile API 时的隔离、清除单个网站数据、不记住 http 权限、下载跳转时的 Cookie 防护，以及过滤规则补丁（`$badfilter`、`$match-case`）。备份/恢复走 SAF，可选 AES-GCM 密码。WIP 提交补了下载跳转、过滤规则和 Worker 脚本的单元测试。

### 已合入、仅作历史保留的分支

`feature/antifp`、`feature/doh`、`review/core`、`review/security`、`review/ui` 都已经完全合进 `main`。仓库里仍保留这些分支，方便对照当时的提交，不必再基于它们开发。

## 已知未修复的问题

1.6.4 里仍然存在、或尚未在真机上核实的限制：

- 较老的系统 WebView 没有 multi-profile 时，无痕模式会和普通模式共用 Cookie（隐私分支在尝试改进，但还没合并）
- 使用系统下载器时，下载不走加密 DNS；跨站跳转时 Cookie 处理也不够理想（隐私分支有防护，未合并）
- 主分支上，网页后台线程（Worker / Service Worker）还没有覆盖防指纹和时区/语言伪装
- 本地 DoH 代理本身没有再做一次应用内鉴权
- 没有完整的 Public Suffix List，站点范围用的是近似的 eTLD+1
- WebView 无法提供精确的每请求资源类型，部分过滤规则只能近似匹配
- 没有在本仓库维护者这边的模拟器或真机上做过完整运行时验收

**除作者本人用自己的手机试过之外，没有在其他真机或可用的模拟器上验证过。** 1.6.1–1.6.4 的多项修复（隐身开关、DoH 测速、设置页滑动关闭、粘贴链接、DoH 走系统代理等）都来自作者在真机上的反馈；后续改动同样需要在手机上再测一遍。

## 第三方许可

请保留资源文件头和设置页里的署名。

- **EasyList** 和 **EasyPrivacy**（https://easylist.to）：作者为 The EasyList authors，以 **CC BY-SA 3.0** 使用（原作为 GPLv3 或 CC BY-SA 3.0 双许可）。列表经过修改（去掉注释和通用元素隐藏规则）。ShareAlike 约束的是这些列表（以及由它们生成、再分发的编译缓存），不是整个应用代码。
- **AdAway** hosts 列表：CC BY 3.0。
- 应用依赖的 AndroidX / Jetpack Compose / OkHttp 等库为 Apache License 2.0。
- 阅读模式（仅 `feature/features` 分支）使用 Mozilla Readability 0.6.0，Apache License 2.0，许可文本在该分支的 `reader-js/` 和 `app/src/main/assets/reader/`。

## 标签

已打的版本标签：`v1.3-baseline`、`v1.4`、`v1.5`、`v1.6`、`v1.6.1`、`v1.6.2`、`v1.6.3`、`v1.6.4`。
