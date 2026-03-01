Paparazzi で `@Preview` コンポーザブルのスクリーンショットを撮影するスキルです。

ユーザーが「スクリーンショットを撮って」「Preview を撮影して」「snapshot を実行して」のように依頼したときに使用してください。フィルター対象（クラス名・パッケージ名・Preview の name）が指定された場合は絞り込みを行い、指定がなければ全件撮影します。

**Agent ツール（subagent_type=general-purpose）を使って以下のタスクをサブエージェントに委譲すること。**

サブエージェントへの指示内容:

1. `$ARGUMENTS` が空かどうかを確認する。
2. 以下のコマンドを Bash ツールで実行する（引数が空の場合は `-Dpaparazzi.filter` を省略）:
   - 引数あり: `./gradlew recordPaparazziDebug -Dpaparazzi.filter=$ARGUMENTS`
   - 引数なし: `./gradlew recordPaparazziDebug`
3. 実行結果を返す。
   - 成功時: `app/src/test/snapshots/images/` 以下に保存されたファイル一覧を含める。
   - 失敗時: エラーログの該当箇所を含める。

サブエージェントの結果を受け取ったら、ユーザーに成否と保存先を伝える。

## フィルター仕様

- クラスの完全修飾名（パッケージ名を含む）または Preview の `name` パラメータに対して大文字小文字を区別せず部分一致
- 例: `BrowserToolBar` → `BrowserToolBarKt` クラスの Preview のみ、`settings` → settings パッケージ配下のみ
