package net.matsudamper.browser.buildlogic

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/**
 * GMD インストルメンテーションテストの失敗を CI コンソールへ出力するタスク。
 *
 * Android Gradle Managed Device の結果ディレクトリから JUnit XML を読み取り、
 * 失敗したテストのクラス名・メソッド名・メッセージ・スタックトレースを抽出して表示する。
 * アーティファクトをダウンロードしなくても CI ログ上で失敗テストが特定でき、
 * flaky テストの原因調査を容易にするのが目的。
 *
 * スタックトレース先頭は check-run 注釈（`::warning::`）にも埋め込むため、
 * ジョブログをダウンロードできない環境でも GitHub API 経由で原因を特定できる。
 *
 * レポータとして動作し、失敗の有無に関わらずタスク自体は常に成功する
 * （テストの成否は呼び出し側のテストタスクが制御する）。
 */
abstract class PrintInstrumentationFailuresTask : DefaultTask() {

    /** GMD (pixel6Api34) の JUnit 結果が出力されるディレクトリ。 */
    @get:Internal
    abstract val resultsDir: DirectoryProperty

    @TaskAction
    fun report() {
        val dir = resultsDir.get().asFile
        if (!dir.isDirectory) {
            println("::warning::テスト結果ディレクトリが見つかりません: ${dir.path}")
            println(
                "エミュレータの起動失敗やテスト実行前のクラッシュの可能性があります。" +
                    "上の Gradle コンソール出力を確認してください。",
            )
            return
        }

        val failures = dir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .sortedBy { it.path }
            .flatMap { collectFailures(it) }
            .toList()

        if (failures.isEmpty()) {
            println("::warning::${dir.path} に失敗テストの記録が見つかりませんでした。")
            println("テスト実行中のクラッシュやタイムアウトの可能性があります。")
            return
        }

        println("::group::失敗したインストルメンテーションテスト (${failures.size} 件)")
        for (failure in failures) {
            // 注釈にスタックトレース先頭を含め、ジョブログを取得できない環境でも
            // API 経由で失敗原因（例外・メッセージ・先頭フレーム）を特定できるようにする。
            val annotationLines = buildList {
                add("FAILED(${failure.kind}): ${failure.test}")
                if (failure.message.isNotEmpty()) add(failure.message)
                addAll(failure.trace.lineSequence().take(ANNOTATION_TRACE_HEAD_LINES))
            }
            println("::warning::" + ghaEscape(annotationLines.joinToString("\n")))
            if (failure.trace.isNotEmpty()) {
                printTrace(failure.trace)
            }
            findLogcatFor(failure.method, dir)?.let { printLogcatTail(it) }
        }
        println("::endgroup::")
    }

    private fun collectFailures(xml: File): List<Failure> {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        }.getOrElse {
            println("[XML パースエラー] ${xml.path}: ${it.message}")
            return emptyList()
        }
        val failures = mutableListOf<Failure>()
        val testcases = document.getElementsByTagName("testcase")
        for (i in 0 until testcases.length) {
            val testcase = testcases.item(i) as? Element ?: continue
            val classname = testcase.getAttribute("classname")
            val method = testcase.getAttribute("name")
            for (kind in PROBLEM_TAGS) {
                val problems = testcase.getElementsByTagName(kind)
                for (j in 0 until problems.length) {
                    val problem = problems.item(j) as? Element ?: continue
                    failures.add(
                        Failure(
                            test = "$classname.$method".trim('.'),
                            method = method,
                            kind = kind,
                            message = problem.getAttribute("message").trim(),
                            trace = problem.textContent.orEmpty().trim(),
                        ),
                    )
                }
            }
        }
        return failures
    }

    /** 失敗テストのメソッド名を含む logcat ファイルを探す（ベストエフォート）。 */
    private fun findLogcatFor(method: String, dir: File): File? {
        if (method.isEmpty()) return null
        return dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.contains("logcat") && it.name.contains(method) }
    }

    private fun printTrace(trace: String) {
        val lines = trace.lines()
        for (line in lines.take(STACK_TRACE_MAX_LINES)) {
            println("    $line")
        }
        val remaining = lines.size - STACK_TRACE_MAX_LINES
        if (remaining > 0) {
            println("    ... (残り $remaining 行は省略)")
        }
    }

    private fun printLogcatTail(logcat: File) {
        println("  --- logcat 末尾 (${logcat.name}) ---")
        val tail = runCatching { logcat.readLines() }.getOrElse {
            println("    (logcat 読み込み失敗: ${it.message})")
            return
        }.takeLast(LOGCAT_TAIL_LINES)
        for (line in tail) {
            println("    $line")
        }
    }

    /**
     * GitHub Actions ワークフローコマンドのメッセージ用にエスケープする。
     * 改行を %0A へ変換することで、単一の `::warning::` でも複数行の注釈を生成できる
     * （API 取得時に改行として復元される）。
     */
    private fun ghaEscape(text: String): String =
        text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    private data class Failure(
        val test: String,
        val method: String,
        val kind: String,
        val message: String,
        val trace: String,
    )

    private companion object {
        val PROBLEM_TAGS = listOf("failure", "error")

        // スタックトレース／logcat が肥大化した場合の出力上限
        const val STACK_TRACE_MAX_LINES = 60
        const val LOGCAT_TAIL_LINES = 80

        // 注釈（check-run annotation）へ埋め込むスタックトレースの先頭行数。
        const val ANNOTATION_TRACE_HEAD_LINES = 20
    }
}
