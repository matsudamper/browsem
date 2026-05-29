import net.matsudamper.browser.buildlogic.PrintInstrumentationFailuresTask

// GMD (pixel6Api34) の JUnit 結果が出力される build 配下の既定ディレクトリ
val gmdResultsRelativePath = "outputs/androidTest-results/managedDevice/debug/pixel6Api34"

// 失敗した GMD インストルメンテーションテストの詳細を CI コンソール/注釈へ出力するタスク。
// CI ではテスト失敗時に明示的に呼び出す（テストタスクには依存させない）。
tasks.register<PrintInstrumentationFailuresTask>("printInstrumentationFailures") {
    group = "verification"
    description = "失敗した GMD インストルメンテーションテストの詳細を CI コンソール/注釈へ出力する"
    resultsDir.convention(layout.buildDirectory.dir(gmdResultsRelativePath))
}
