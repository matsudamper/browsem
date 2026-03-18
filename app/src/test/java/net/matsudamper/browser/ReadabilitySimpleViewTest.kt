package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadabilitySimpleViewTest {

    // テスト用の最小限の readability.js スタブ
    private val stubReadabilityJs = """
        function Readability(doc, options) { this._doc = doc; }
        Readability.prototype = {
            parse: function() {
                return { title: 'Test', byline: null, content: '<p>body</p>' };
            }
        };
    """.trimIndent()

    @Test
    fun scriptStartsWithJavascriptVoidIIFE() {
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "javascript: URI の形式が正しくない: ${ script.take(50) }",
            script.startsWith("javascript:void((function(){"),
        )
    }

    @Test
    fun scriptEndsWithIIFEClose() {
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "IIFE の閉じ括弧がない",
            script.endsWith("})())"),
        )
    }

    @Test
    fun scriptContainsReadabilityInstantiation() {
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "new Readability(doc).parse() がスクリプトに含まれていない",
            script.contains("new Readability(doc).parse()"),
        )
    }

    @Test
    fun scriptContainsEarlyReturnWhenArticleIsNull() {
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "article が null のときの早期 return がない",
            script.contains("if(!article)return;"),
        )
    }

    @Test
    fun stylesDoNotContainSingleQuote() {
        // style.textContent='$styles' という形でシングルクォートを使うため、
        // styles 自体にシングルクォートが含まれると JavaScript 構文エラーになる
        val script = buildReadabilityScript(stubReadabilityJs)
        // style.textContent= の後の文字列を取り出してチェック
        val styleStart = script.indexOf("style.textContent='")
        assertTrue("style.textContent 代入が見つからない", styleStart >= 0)
        val afterAssign = script.substring(styleStart + "style.textContent='".length)
        val styleEnd = afterAssign.indexOf("';")
        assertTrue("style の終端が見つからない", styleEnd >= 0)
        val stylesContent = afterAssign.substring(0, styleEnd)
        assertFalse(
            "styles にシングルクォートが含まれている: $stylesContent",
            stylesContent.contains("'"),
        )
    }

    @Test
    fun scriptContainsStubReadabilityJs() {
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "渡した readabilityJs がスクリプトに埋め込まれていない",
            script.contains(stubReadabilityJs),
        )
    }

    /**
     * 実際の readability.js を使ったスクリプト生成テスト。
     * URI の長さが極端に大きい場合は GeckoView が拒否する可能性があるため記録する。
     */
    @Test
    fun scriptWithRealReadabilityJsHasReasonableLength() {
        val assetsPath = File("src/main/assets/readability.js")
        if (!assetsPath.exists()) {
            // CI 環境でアセットが存在しない場合はスキップ
            return
        }
        val realJs = assetsPath.readText()
        val script = buildReadabilityScript(realJs)

        // javascript: URI の長さを確認
        // GeckoView には URI 長の制限はないが、あまりにも長い場合は問題になりうる
        val lengthKb = script.length / 1024
        println("生成スクリプト長: ${script.length} 文字 (${lengthKb} KB)")

        // スクリプト構造の検証
        assertTrue("javascript:void( で始まらない", script.startsWith("javascript:void((function(){"))
        assertTrue("new Readability(doc).parse() がない", script.contains("new Readability(doc).parse()"))

        // 実際の readability.js が function Readability として定義されているか
        assertTrue(
            "readability.js に function Readability 定義がない",
            realJs.contains("function Readability("),
        )
    }

    @Test
    fun scriptDoesNotContainUnencodedBackslash() {
        // スタイル文字列にバックスラッシュが混入すると JS 文字列エスケープ問題になる
        val script = buildReadabilityScript(stubReadabilityJs)
        val styleStart = script.indexOf("style.textContent='")
        val afterAssign = script.substring(styleStart + "style.textContent='".length)
        val stylesContent = afterAssign.substring(0, afterAssign.indexOf("';"))
        assertFalse(
            "styles にバックスラッシュが含まれている",
            stylesContent.contains("\\"),
        )
    }

    @Test
    fun documentCloneNodeIsUsedInsteadOfDirectDocument() {
        // Readability は document を直接渡すと DOM を破壊するため cloneNode が必要
        val script = buildReadabilityScript(stubReadabilityJs)
        assertTrue(
            "document.cloneNode(true) がない (直接 document を渡すと DOM が破壊される)",
            script.contains("document.cloneNode(true)"),
        )
        // Readability に渡しているのが cloneNode した doc であることを確認
        val cloneIndex = script.indexOf("document.cloneNode(true)")
        val readabilityIndex = script.indexOf("new Readability(doc)")
        assertTrue(
            "cloneNode より前に Readability が呼ばれている",
            cloneIndex < readabilityIndex,
        )
    }

    @Test
    fun wrapperScriptIsAfterReadabilityJs() {
        // wrapperScript (new Readability...) が readabilityJs の後に来ることを確認
        val marker = "MARKER_END_OF_READABILITY"
        val jsWithMarker = "$stubReadabilityJs\n// $marker"
        val script = buildReadabilityScript(jsWithMarker)
        val markerIndex = script.indexOf(marker)
        val readabilityCallIndex = script.indexOf("new Readability(doc).parse()")
        assertTrue(
            "wrapperScript が readabilityJs より前に挿入されている",
            markerIndex < readabilityCallIndex,
        )
    }
}
