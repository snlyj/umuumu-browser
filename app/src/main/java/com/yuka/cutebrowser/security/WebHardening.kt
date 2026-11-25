package com.yuka.cutebrowser.security

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yuka.cutebrowser.data.SecurityPrefs

/** WebView 能力探测（Robolectric 里会抛异常，所以都包一层） */
object WebFeatures {
    private fun has(f: String) = runCatching { WebViewFeature.isFeatureSupported(f) }.getOrDefault(false)
    val multiProfile by lazy { has(WebViewFeature.MULTI_PROFILE) }
    val documentStartScript by lazy { has(WebViewFeature.DOCUMENT_START_SCRIPT) }
    val uaMetadata by lazy { has(WebViewFeature.USER_AGENT_METADATA) }
    val webMessageListener by lazy { has(WebViewFeature.WEB_MESSAGE_LISTENER) }
    val safeBrowsing by lazy { has(WebViewFeature.SAFE_BROWSING_ENABLE) }
    val attribution by lazy { has(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR) }

    fun webViewVersion(ctx: android.content.Context): String = runCatching {
        WebViewCompat.getCurrentWebViewPackage(ctx)?.let { "${it.packageName} ${it.versionName}" }
    }.getOrNull() ?: "未知"
}

object WebHardening {
    /** 每个页面最开始执行（在网页自己的脚本之前）：GPC/DNT 信号 + 可选关闭 WebRTC */
    fun privacyScript(gpc: Boolean, noWebRtc: Boolean): String = buildString {
        append("(function(){try{")
        if (gpc) {
            append("var N=Navigator.prototype;")
            append("Object.defineProperty(N,'globalPrivacyControl',{get:function(){return true},configurable:true});")
            append("Object.defineProperty(N,'doNotTrack',{get:function(){return '1'},configurable:true});")
        }
        if (noWebRtc) {
            // 删掉 WebRTC 构造函数，网页就拿不到 ICE 候选地址（本机/公网 IP）
            append("['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCIceCandidate','RTCSessionDescription','RTCRtpSender','RTCRtpReceiver','RTCRtpTransceiver','RTCDtlsTransport','RTCIceTransport','RTCSctpTransport','RTCCertificate','RTCEncodedAudioFrame','RTCEncodedVideoFrame','RTCPeerConnectionIceEvent','RTCDTMFSender'].forEach(function(n){")
            append("try{Object.defineProperty(window,n,{value:undefined,writable:false,configurable:false})}catch(e){}});")
        }
        append("}catch(e){}})();")
    }

    /** 一次性设置（创建 WebView 时）。javaScript 的 interface 在别处只暴露 blob 桥 */
    @SuppressLint("SetJavaScriptEnabled")
    fun lockDown(wv: WebView, sec: SecurityPrefs) {
        wv.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(true) // 只是允许"询问"；每个网站都要用户在我们的弹窗里同意
            @Suppress("DEPRECATION") saveFormData = false
            @Suppress("DEPRECATION") savePassword = false
            // 网页弹窗：只有用户点击触发的 window.open 才会走 onCreateWindow 变成新标签
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        runCatching { if (WebFeatures.safeBrowsing) WebSettingsCompat.setSafeBrowsingEnabled(wv.settings, true) }
        runCatching {
            if (WebFeatures.attribution) {
                WebSettingsCompat.setAttributionRegistrationBehavior(wv.settings, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.importantForAutofill = if (sec.autofill) View.IMPORTANT_FOR_AUTOFILL_AUTO else View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (sec.genericUa) {
            val def = runCatching { WebSettings.getDefaultUserAgent(wv.context) }.getOrNull() ?: wv.settings.userAgentString
            wv.settings.userAgentString = UserAgents.generic(def)
            runCatching {
                if (WebFeatures.uaMetadata) {
                    val md = WebSettingsCompat.getUserAgentMetadata(wv.settings)
                    val brands = md.brandVersionList.filterNot { it.brand.contains("WebView", ignoreCase = true) }
                    WebSettingsCompat.setUserAgentMetadata(
                        wv.settings,
                        UserAgentMetadata.Builder(md).setModel("").setPlatformVersion("10.0.0").setBrandVersionList(brands).build(),
                    )
                }
            }
        }
        if (WebFeatures.documentStartScript && (sec.dntGpc || sec.webrtcProtect)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, privacyScript(sec.dntGpc, sec.webrtcProtect), setOf("*")) }
        }
    }

    fun cookieManager(profile: String?): CookieManager =
        if (profile != null) runCatching { ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager }.getOrNull()
            ?: CookieManager.getInstance()
        else CookieManager.getInstance()

    /** 必须在 WebView 刚创建、还没做任何事时调用 */
    fun useProfile(wv: WebView, profile: String): Boolean = runCatching {
        ProfileStore.getInstance().getOrCreateProfile(profile)
        WebViewCompat.setProfile(wv, profile)
        true
    }.getOrDefault(false)

    /** 删掉隐身用过的 profile（Cookie、缓存、存储一起删）。正在用的删不掉，返回 false */
    fun deleteProfile(name: String): Boolean = runCatching { ProfileStore.getInstance().deleteProfile(name) }.getOrDefault(false)

    fun leftoverIncognitoProfiles(): List<String> = runCatching {
        ProfileStore.getInstance().allProfileNames.filter { it.startsWith(INCOGNITO_PREFIX) }
    }.getOrDefault(emptyList())

    const val INCOGNITO_PREFIX = "incognito-"

    /**
     * blob 下载桥：优先用 WebMessageListener（能拿到消息来源 origin 和是否主框架，检查后才收），
     * 旧 WebView 退回 addJavascriptInterface（只靠一次性 token 防伪造）。
     * 两种方式在 JS 里都是 UmuBlobBridge.postMessage(string)。
     */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun installBlobBridge(wv: WebView, name: String, onMessage: (json: String, origin: String?) -> Unit, fallback: Any) {
        if (WebFeatures.webMessageListener) {
            val ok = runCatching {
                WebViewCompat.addWebMessageListener(wv, name, setOf("*"), object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                        if (!isMainFrame) return // 只接受顶层页面的消息
                        val data = message.data ?: return
                        onMessage(data, sourceOrigin.toString())
                    }
                })
                true
            }.getOrDefault(false)
            if (ok) return
        }
        wv.addJavascriptInterface(fallback, name)
    }
}
