package net.matsudamper.browser

/**
 * ページズーム用の viewport content を生成する。
 * percent=100 のときは width=device-width に戻す。
 */
internal fun viewportContentForPageZoom(screenWidthDp: Int, percent: Int): String {
    return if (percent == 100) {
        "width=device-width,initial-scale=1"
    } else {
        val viewportWidth = screenWidthDp * 100 / percent
        "width=$viewportWidth,initial-scale=1"
    }
}

/**
 * SPA 遷移（pushState 等）後にページズームを再適用すべきかを判定する。
 * フルページロードは onPageStop で再適用するため、ここでは false を返す。
 */
internal fun shouldReapplyPageZoomOnSpaLocationChange(
    pageZoomPercent: Int,
    isFullPageLoad: Boolean,
): Boolean {
    return !isFullPageLoad && pageZoomPercent != 100
}

/**
 * viewport meta を書き換えてページズームを適用する javascript: URI を生成する。
 *
 * SPA は遷移後に viewport meta を差し替えることがあるため、
 * persistAcrossDomChanges=true のときは MutationObserver で維持する。
 */
internal fun buildViewportZoomInjectionScript(
    viewportContent: String,
    persistAcrossDomChanges: Boolean,
): String {
    val persistFlag = if (persistAcrossDomChanges) "true" else "false"
    val scriptBody = "(function(){" +
        "var c='$viewportContent';" +
        "var persist=$persistFlag;" +
        "function apply(){" +
        "var m=document.querySelector('meta[name=\"viewport\"]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';" +
        "(document.head||document.documentElement).appendChild(m);}" +
        "if(m.content!==c){m.content=c;}" +
        "}" +
        "function disconnect(){" +
        "if(window.__browserViewportZoomObserver){" +
        "window.__browserViewportZoomObserver.disconnect();" +
        "window.__browserViewportZoomObserver=null;" +
        "}" +
        "}" +
        "apply();" +
        "requestAnimationFrame(apply);" +
        "setTimeout(apply,0);" +
        "setTimeout(apply,100);" +
        "if(!persist){disconnect();window.__browserViewportZoomContent=null;return;}" +
        "window.__browserViewportZoomContent=c;" +
        "if(!window.__browserViewportZoomObserver&&document.head){" +
        "window.__browserViewportZoomObserver=new MutationObserver(function(){" +
        "if(!window.__browserViewportZoomContent)return;" +
        "apply();" +
        "});" +
        "window.__browserViewportZoomObserver.observe(document.head," +
        "{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});" +
        "}" +
        "})()"
    return "javascript:void($scriptBody)"
}
