package com.yuka.cutebrowser

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.yuka.cutebrowser.browser.AdBlocker
import com.yuka.cutebrowser.browser.BrowserController
import com.yuka.cutebrowser.browser.SessionStore
import com.yuka.cutebrowser.data.CuteRepository
import com.yuka.cutebrowser.dns.DohManager
import com.yuka.cutebrowser.data.SecurityPrefs
import com.yuka.cutebrowser.ui.AppProtection
import com.yuka.cutebrowser.ui.BrowserScreen
import com.yuka.cutebrowser.ui.LockScreen
import com.yuka.cutebrowser.ui.theme.CuteTheme
import com.yuka.cutebrowser.ui.theme.CuteThemeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** FragmentActivity（androidx.biometric 需要），它本身也是 ComponentActivity */
class MainActivity : FragmentActivity(), AppProtection {
    private lateinit var repo: CuteRepository
    private lateinit var controller: BrowserController

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.onStoragePermissionResult(granted)
    }

    private var runtimeCallback: ((Boolean) -> Unit)? = null
    private val runtimePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        runtimeCallback?.invoke(r.values.all { it })
        runtimeCallback = null
    }

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else null
        controller.onFileChosen(uris)
    }

    /** 主题从 DataStore 异步读出来之前为 null：启动画面一直盖着，读完再显示界面，避免先闪一下默认主题 */
    private var themeId by mutableStateOf<CuteThemeId?>(null)
    /** 上次的标签页恢复完了没（只读一个小 JSON，不创建 WebView） */
    private var sessionReady by mutableStateOf(false)
    private var sec by mutableStateOf(SecurityPrefs())

    // ---- 应用锁 ----
    override var locked by mutableStateOf(false)
        private set
    private var authInProgress = false
    private var stoppedAt = 0L

    private val clearMarker by lazy { File(filesDir, "clear_on_exit.pending") }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { themeId == null || !sessionReady }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        repo = CuteRepository(this)
        controller = BrowserController(
            activity = this,
            repo = repo,
            requestStoragePermission = { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
            launchFileChooser = { intent -> runCatching { fileChooser.launch(intent) }.isSuccess },
            requestRuntimePermissions = { perms, cb ->
                runtimeCallback?.invoke(false)
                runtimeCallback = cb
                runCatching { runtimePermissions.launch(perms) }.onFailure { runtimeCallback = null; cb(false) }
            },
        )
        // 过滤规则（约 12 万条）在低优先级后台线程加载：有缓存读缓存，没有就编译后写缓存
        val ctx = applicationContext
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            AdBlocker.load(ctx, filterCacheKey())
        }, "umu-filter").start()

        // 设置的读取全在协程里（DataStore 自己在 IO 线程），主线程不阻塞
        lifecycleScope.launch {
            combine(repo.theme, repo.webDark) { t, d -> t to d }.collect { (t, d) ->
                controller.setWebDark(t == CuteThemeId.STARRY && d)
                themeId = t
            }
        }
        lifecycleScope.launch { repo.engine.collect { controller.engine = it } }
        lifecycleScope.launch { repo.adBlock.collect { controller.adBlockEnabled = it } }
        lifecycleScope.launch { repo.adBlockOff.collect { controller.adBlockOff = it } }
        lifecycleScope.launch { repo.fpOff.collect { controller.setFpExceptions(it) } }
        lifecycleScope.launch { repo.tzLangSites.collect { controller.setTzLangSites(it) } }
        lifecycleScope.launch { repo.httpAllowed.collect { controller.httpAllowed = it } }
        lifecycleScope.launch { repo.sitePerms.collect { controller.sitePerms = it } }
        lifecycleScope.launch(Dispatchers.IO) { repo.migrateIfNeeded() }
        // App 内加密 DNS（默认关；关着时只读一次设置）
        DohManager.notice = { msg -> controller.toast(msg) }
        lifecycleScope.launch { DohManager.run(applicationContext) }

        val startIntent = intent
        lifecycleScope.launch {
            // 安全设置要在第一个 WebView 创建之前就位
            val s = repo.security.first()
            applySec(s)
            controller.setFpExceptions(repo.fpOff.first())
            controller.setTzLangSites(repo.tzLangSites.first())
            if (s.appLock && canLock()) locked = true
            var wipe = false
            val saved = withContext(Dispatchers.IO) {
                if (s.clearOnExit && clearMarker.exists()) {
                    // 上次没正常退出（比如从最近任务里划掉）：现在补清
                    SessionStore(applicationContext).clear()
                    repo.clearHistory()
                    File(cacheDir, "WebView").deleteRecursively() // WebView 还没加载，删缓存目录是安全的
                    wipe = true
                    null
                } else controller.loadSession()
            }
            if (wipe) {
                CookieManager.getInstance().removeAllCookies { clearMarker.delete() }
                WebStorage.getInstance().deleteAllData()
            }
            DohManager.awaitInitial() // 开着加密 DNS 时，等代理生效后再加载第一个网页
            controller.restore(saved)
            handleIntent(startIntent, initial = saved == null)
            sessionReady = true
            if (locked) unlock() // 冷启动：onStart 时界面还没好，这里再弹验证
            repo.security.collect { applySec(it) }
        }

        setContent {
            val theme = themeId
            if (theme != null && sessionReady) {
                val incognito = controller.current.incognito
                // 防截屏/录屏：隐身标签总是开；普通标签看设置；上锁时也开（最近任务里看不到内容）
                val secure = incognito || sec.secureScreen || locked
                SideEffect {
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                CuteTheme(theme, incognito = incognito) {
                    BrowserScreen(controller, repo, this)
                    if (locked) LockScreen { unlock() }
                }
            }
        }

        // 首帧画完、主线程空闲后再预热 WebView 内核和 DNS，不跟启动抢时间
        window.decorView.post {
            Looper.myQueue().addIdleHandler {
                controller.prewarm()
                false
            }
        }
    }

    private fun applySec(s: SecurityPrefs) {
        sec = s
        controller.applySecurity(s)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!s.appLock)
    }

    private fun filterCacheKey(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "$vc-${pi.lastUpdateTime}"
    }.getOrDefault("0")

    // ---------------- 应用锁 ----------------

    override fun canLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    override fun authenticate(title: String, onSuccess: () -> Unit) {
        if (!canLock()) { onSuccess(); return } // 设备没设锁屏：没法验证，也不能把人锁在外面
        if (authInProgress) return
        authInProgress = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInProgress = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authInProgress = false
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("用指纹、面容或锁屏密码验证一下哦 🔐")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { authInProgress = false }
    }

    private fun unlock() = authenticate("唔姆唔姆上锁啦") { locked = false }

    override fun setAppLock(on: Boolean) {
        if (on) {
            if (!canLock()) {
                Toast.makeText(this, "先在系统设置里设一个锁屏密码，才能用应用锁哦 🔐", Toast.LENGTH_LONG).show()
                return
            }
            lifecycleScope.launch { repo.updateSecurity { it.copy(appLock = true) } }
        } else {
            // 关掉应用锁也要先验证，防止别人拿到手机直接关
            authenticate("关闭应用锁") { lifecycleScope.launch { repo.updateSecurity { it.copy(appLock = false) } } }
        }
    }

    override fun onStart() {
        super.onStart()
        // 在后台超过 15 秒回来要重新解锁（验证本身弹出的系统界面不算）
        if (sec.appLock && !authInProgress && stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS) {
            locked = true
        }
        if (locked && sessionReady) unlock()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        if (sec.clearOnExit) runCatching { clearMarker.createNewFile() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, initial = false)
    }

    private fun handleIntent(intent: Intent?, initial: Boolean) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { url ->
                if (initial) controller.open(url) else controller.newTab(url)
            }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let { q ->
                if (!initial) controller.newTab()
                controller.submit(q)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        controller.onHostPause()
    }

    override fun onResume() {
        super.onResume()
        controller.onHostResume()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存紧张（或进程被放到缓存里）时回收后台标签的 WebView；单纯切到后台(UI_HIDDEN)不回收
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) controller.onLowMemory()
    }

    override fun onDestroy() {
        if (isFinishing && sec.clearOnExit) controller.clearAllDataOnExit { clearMarker.delete() }
        controller.destroyAll()
        super.onDestroy()
    }

    private companion object {
        const val LOCK_AFTER_MS = 15_000L
    }
}
