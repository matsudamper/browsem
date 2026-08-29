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
    val scriptBody = if (persistAcrossDomChanges) {
        buildPersistentViewportZoomScriptBody(viewportContent)
    } else {
        buildResetViewportZoomScriptBody(viewportContent)
    }
    return "javascript:void($scriptBody)"
}

private fun buildApplyViewportZoomDirectFunction(): String {
    return "function applyDirect(c){" +
        "var m=document.querySelector('meta[name=\"viewport\"]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';" +
        "(document.head||document.documentElement).appendChild(m);}" +
        "if(m.content!==c){m.content=c;}" +
        "}"
}

private fun buildApplyViewportZoomFromGlobalFunction(): String {
    return "function applyFromGlobal(){" +
        "var c=window.__browserViewportZoomContent;" +
        "if(!c){return;}" +
        "applyDirect(c);" +
        "}"
}

private fun buildDisconnectViewportZoomObserverFunction(): String {
    return "function disconnect(){" +
        "if(window.__browserViewportZoomObserver){" +
        "window.__browserViewportZoomObserver.disconnect();" +
        "window.__browserViewportZoomObserver=null;" +
        "}" +
        "}"
}

private fun buildResetViewportZoomScriptBody(viewportContent: String): String {
    return "(function(){" +
        buildApplyViewportZoomDirectFunction() +
        buildDisconnectViewportZoomObserverFunction() +
        "disconnect();" +
        "window.__browserViewportZoomContent=null;" +
        "var c='$viewportContent';" +
        "applyDirect(c);" +
        "requestAnimationFrame(function(){applyDirect(c);});" +
        "setTimeout(function(){applyDirect(c);},0);" +
        "setTimeout(function(){applyDirect(c);},100);" +
        "})()"
}

private fun buildPersistentViewportZoomScriptBody(viewportContent: String): String {
    return "(function(){" +
        buildApplyViewportZoomDirectFunction() +
        buildApplyViewportZoomFromGlobalFunction() +
        buildDisconnectViewportZoomObserverFunction() +
        "window.__browserViewportZoomContent='$viewportContent';" +
        "applyFromGlobal();" +
        "requestAnimationFrame(applyFromGlobal);" +
        "setTimeout(applyFromGlobal,0);" +
        "setTimeout(applyFromGlobal,100);" +
        "if(!window.__browserViewportZoomObserver&&document.head){" +
        "window.__browserViewportZoomObserver=new MutationObserver(function(){" +
        "applyFromGlobal();" +
        "});" +
        "window.__browserViewportZoomObserver.observe(document.head," +
        "{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});" +
        "}" +
        "})()"
}
