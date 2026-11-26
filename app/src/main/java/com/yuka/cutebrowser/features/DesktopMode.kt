package com.yuka.cutebrowser.features

/**
 * 桌面版网站：和「通用 UA」同一个思路，只报 Chrome 主版本号、不带设备型号；
 * 平台用桌面 Linux（和防指纹脚本里的 Linux 平台最接近，也是最不显眼的桌面 UA 之一）。
 */
object DesktopMode {
    private val CHROME = Regex("Chrome/(\\d+)")
    private const val FALLBACK_MAJOR = "131"

    fun userAgent(defaultUa: String?): String {
        val major = defaultUa?.let { CHROME.find(it)?.groupValues?.get(1) } ?: FALLBACK_MAJOR
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    /** 桌面版排版宽度（CSS 像素），和 Chrome「桌面版网站」差不多 */
    const val VIEWPORT_WIDTH = 1024

    /**
     * 文档开始时注入（只在顶层页面）：把网页的 <meta name=viewport> 改成固定 1024 宽，
     * 否则写了 width=device-width 的自适应网站换了 UA 还是手机排版。解析完就停止监听，不会一直占 CPU。
     */
    val viewportScript: String = """(function(){try{if(window.top!==window)return;var W='width=$VIEWPORT_WIDTH';
function f(m){try{if(m&&m.tagName==='META'&&String(m.getAttribute('name')||'').toLowerCase()==='viewport'&&m.getAttribute('content')!==W)m.setAttribute('content',W)}catch(e){}}
function all(){var a=document.getElementsByTagName('meta');for(var i=0;i<a.length;i++)f(a[i])}
var o=new MutationObserver(function(rs){for(var i=0;i<rs.length;i++){var n=rs[i].addedNodes;for(var j=0;j<n.length;j++)f(n[j])}});
o.observe(document,{childList:true,subtree:true});
document.addEventListener('DOMContentLoaded',function(){all();setTimeout(function(){o.disconnect()},1500)});
}catch(e){}})();"""

    /** 老 WebView 不支持文档开始脚本时，在页面加载完后补一次 */
    val viewportFallback: String = """(function(){try{var a=document.getElementsByTagName('meta');for(var i=0;i<a.length;i++){var m=a[i];if(String(m.getAttribute('name')||'').toLowerCase()==='viewport')m.setAttribute('content','width=$VIEWPORT_WIDTH')}}catch(e){}})();"""
}
