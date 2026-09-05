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
 * セッション復元後など onPageStop が発火しない経路で、描画準備完了時に
 * 永続化済みズームを document へ反映すべきかを判定する。
 * フルページロード中は onPageStop 側で再適用するため、ここでは false を返す。
 */
internal fun shouldApplyPersistedPageZoomAfterRender(
    pageZoomPercent: Int,
    isFullPageLoadPending: Boolean,
): Boolean {
    return pageZoomPercent != 100 && !isFullPageLoadPending
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
    // SPA は既存 meta の content 変更ではなく新しい viewport meta を追加することがある。
    // querySelector だけだと先頭要素しか更新できず、後から追加された meta が優先される。
    return "function applyDirect(c){" +
        "var metas=document.querySelectorAll('meta[name=\"viewport\"]');" +
        "if(metas.length===0){" +
        "var m=document.createElement('meta');m.name='viewport';m.content=c;" +
        "(document.head||document.documentElement).appendChild(m);" +
        "return;}" +
        "for(var i=0;i<metas.length;i++){" +
        "if(metas[i].content!==c){metas[i].content=c;}" +
        "}" +
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
        "setTimeout(applyFromGlobal,300);" +
        "setTimeout(applyFromGlobal,500);" +
        "if(!window.__browserViewportZoomObserver&&document.head){" +
        "window.__browserViewportZoomObserver=new MutationObserver(function(){" +
        "applyFromGlobal();" +
        "});" +
        "window.__browserViewportZoomObserver.observe(document.head," +
        "{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});" +
        "}" +
        "})()"
}
