package net.matsudamper.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoSession

/**
 * mozilla/readability を使って現在のページのメインコンテンツを抽出し、
 * シンプルな読みやすいビューに置き換える。
 *
 * readability.js は app/src/main/assets/readability.js に同梱されている。
 */
internal class ReadabilitySimpleView(
    private val session: GeckoSession,
    private val context: Context,
) {
    suspend fun execute() {
        val readabilityJs = withContext(Dispatchers.IO) {
            context.assets.open("readability.js").bufferedReader().readText()
        }
        val script = buildScript(readabilityJs)
        session.loadUri(script)
    }

    private fun buildScript(readabilityJs: String): String {
        // CSSスタイル（単引用符・バックスラッシュを含まない文字のみ使用）
        val styles = "body{font-family:sans-serif;max-width:720px;margin:0 auto;padding:1em 1.2em;line-height:1.8;color:#222;background:#fafafa}" +
            "h1{font-size:1.6em;line-height:1.3;margin-bottom:0.4em}" +
            "h2,h3{line-height:1.3}" +
            "img{max-width:100%;height:auto}" +
            "pre{overflow-x:auto;background:#eee;padding:0.8em;border-radius:4px}" +
            "a{color:#0066cc}" +
            ".byline{color:#666;font-size:0.9em;margin-top:0}"

        // Readability.js を埋め込み、記事を解析してDOMを置き換えるスクリプト
        val wrapperScript =
            "var doc=document.cloneNode(true);" +
            "var article=new Readability(doc).parse();" +
            "if(!article)return;" +
            "var head=document.head;" +
            "var removes=head.querySelectorAll('style,link[rel=\"stylesheet\"]');" +
            "for(var i=0;i<removes.length;i++){removes[i].parentNode.removeChild(removes[i]);}" +
            "var style=document.createElement('style');" +
            "style.textContent='$styles';" +
            "head.appendChild(style);" +
            "document.title=article.title||'';" +
            "document.body.innerHTML='';" +
            "var h1=document.createElement('h1');" +
            "h1.textContent=article.title||'';" +
            "document.body.appendChild(h1);" +
            "if(article.byline){" +
            "var bl=document.createElement('p');" +
            "bl.className='byline';" +
            "bl.textContent=article.byline;" +
            "document.body.appendChild(bl);}" +
            "var content=document.createElement('div');" +
            "content.innerHTML=article.content||'';" +
            "document.body.appendChild(content);" +
            "window.scrollTo(0,0);"

        return "javascript:void((function(){\n$readabilityJs\n$wrapperScript\n})())"
    }
}
