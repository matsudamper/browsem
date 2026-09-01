# CLAUDE.md

## プロジェクト概要

GeckoView ベースの Android ブラウザアプリ。
Kotlin / Jetpack Compose / Material 3 / Navigation 3 / Koin DI。

## 開発コマンド

```bash
# デバッグビルド
./gradlew :app:assembleDebug

# ユニットテスト (Paparazzi 以外)
./gradlew test

# Paparazzi スクリーンショットテスト
./gradlew :app:verifyPaparazziDebug

# 特定 Preview のスナップショット記録
./gradlew :app:recordPaparazziDebug -Dpaparazzi.filter="PreviewName"

# Lint
./gradlew :app:lintDebug detekt

# Android Instrumentation テスト (Gradle Managed Device)
./gradlew :app:pixel6Api34DebugAndroidTest

# 特定クラスのみ実行 (PowerShell は -P 引数全体をダブルクオート)
./gradlew :app:pixel6Api34DebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=net.matsudamper.browser.MainActivityCustomTabLaunchTest"
```


## アーキテクチャ方針
ViewModelとUIのやり取りにはクリックイベントも表示もUiStateを経由して行う。これがUIとの唯一の接点。ActivityやOneShotのイベントを行うにはChannelを使ったeventを使用する。

### データフロー

```
Proto → DataStore/Room → Repository → ViewModel(ViewModelStateFlow) → UiState -> Compose
```

### GeckoRuntime

- **プロセスに1つ**。`GeckoRuntime.getDefault(context)` で取得
- ViewModel・Controller 経由で各タブに配布

### GeckoView の不具合調査

GeckoView に関連する不具合・制限・既知の問題を調査する際は、Mozilla の Bugzilla を参照すること。
- URL: https://bugzilla.mozilla.org/
- コンポーネント: `Core :: GeckoView` や `GeckoView :: General` などで検索する

## Coding Agent
すべての応答、説明、およびコミットメッセージは日本語で行ってください。

## コーディング規約

### 言語・スタイル

- コメントは日本語で書く
- import はワイルドカード不使用。個別 import
- FQCN ではなく import して短縮名を使う（名前衝突時を除く）

### Compose パターン

- 画面 Composable は `internal` visibility
- State Holder クラスには `@Stable` アノテーション

### その他
Compose Material Icons ExtendedはDeprecatedなので使用禁止。

## デグレ防止

コードを変更・削除する前に、必ず `git log -p` で対象コードが追加されたコミットを確認し、その目的と経緯を把握してから作業すること。

## テスト方針

- 単体テスト: JUnit 4。`./gradlew test` で実行 (Paparazzi テストは自動除外)
- Instrumentation テスト: Managed Device (Pixel 7, API 34)。`app/src/androidTest/`
  - 接続デバイス (実機・通常エミュレータ) の使用は禁止。Gradle Managed Deviceを使用すること
  - 長時間化を避けるため、通常は全件実行せず `-Pandroid.testInstrumentationRunnerArguments.class=...` で対象を絞ること
- Lint: `./gradlew :app:lintDebug`

### Instrumentation テストの操作ルール

- UI 操作は `performClick()` など Compose セマンティクス API を使う
  - `UiAutomation.injectInputEvent()` や `MotionEvent` による生のタッチイベント注入は禁止
  - `pressBack()` など物理ボタンは `onBackPressedDispatcher.onBackPressed()` を使う
- repository等のデータを直接データをいじらない。全てUI操作で行う
- テキストを監視する場合を除いて`hasText`を使わない。コンポーネントを特定するには`hasTestTag`を使用する
- TestTagは直接stringを使用せず、他のtestTagのパターンに合わせる
- GMDデバイスを使用する。起動に失敗した場合、名前、IDを一時的に変えて再実行する

## 作業フロー

UIに関連した実装はPaparazziで差分を取って作業するのが良い

### 変更後

1. `./gradlew :app:assembleDebug` でビルド確認
2. `./gradlew detekt` でlintを確認
3. `./gradlew test` でユニットテスト通過を確認
4. UI 変更を含む場合は `@Preview` を追加/更新し、Paparazzi スナップショットを撮影
   1. コミットしない。PRに貼り付ける
   2. 撮影した画像は必ずチャットにも貼り付けて（送付して）ユーザーが見た目を確認できるようにする。UI 変更の報告に画像添付がない状態で作業を完了しない

## Git

- 1 コミット = 1 論理的変更を厳守する。異なる目的の変更を同じコミットに混ぜない。コミットは分けられる最小単位で分ける。
- 複数の論理変更を 1 つのコミットにまとめない。`git commit --amend` や squash による統合も禁止する
- コミットメッセージに具体的なことを書く。GitHubのコメントに書かない。「修正」のみのようなコミットメッセージにしない
- 機能実装・レビュー対応・テスト修正・ドキュメント更新は、目的が異なればそれぞれ別コミットにする
- ビルド時にネットワークエラーになった場合は原因を調べなくて良い。作業を完了し、通らなかったエラーを知らせるだけで良い
- ビルド、テスト、lint 等のタスクを並列実行しない。必ず逐次実行すること

## GitHub（レビュー）

- レビューに対応したらpush後にそのhashを含めて返信する。resolveはユーザーがする
- PR へのコメントは、レビューコメントへの返信以外では書かない。修正内容の説明はコミットメッセージに書く
- クリティカルなセキュリティ問題以外は深く対応しすぎない。ホビープロジェクトという事を意識する。
  - 設計、読みやすさは最優先

### レビュー対応時の必須手順（Cloud Agent）

「レビューに対応して」と依頼されたら、コード修正と各レビューコメントへの返信をセットで完了する。返信なしで作業完了にしない。

1. gh api repos/<owner>/<repo>/pulls/<number>/comments で未返信の inline レビューコメントをすべて列挙する
2. 指摘ごとにコードを修正し、コミット・git push する
3. push したコミット hash を含め、指摘コメントごとに ManagePullRequest の post_comment（in_reply_to にコメント ID）で返信する
   - 返信は短く。何をどう直したかとコミット hash だけ書く
   - Resolve conversation はしない（ユーザーが行う）
4. ユーザーへのサマリーでも返信済みとコミット hash を伝える

push の直後に手順 1 と 3 を必ず実行する（テスト完了後の最終手順に含める）。

## PRの更新

このルールはAIリモート実行環境では自動的に適用する。ローカル CLI や IDE 拡張ではユーザーから指示があった場合にのみ適用する。

ファイルを編集・コミットした後、現在のブランチに紐づく既存の PR があるかを確認する。PR が存在する場合、以下の手順でタイトルと本文の更新要否を判断する。

1. 現在のブランチの PR を取得し、タイトルと本文を読む
2. 今回の変更内容（コミット群）と PR の現在のタイトル・本文を比較し、内容が乖離していないか確認する
3. 更新が必要と判断した場合のみ、以下のルールに従って更新する
   - **ユーザーが手動で編集した部分は保持する**。ユーザーが書いたと思われる説明・コメント・チェックリストは変更しない
   - タイトルは変更の全体像を簡潔に反映する。ユーザーが設定したタイトルと大きく意図が変わらない場合は変更しない
   - 本文は変更内容に合わせて全体を更新してよいが、ユーザーが追記した内容は尊重して保持する
4. 更新不要と判断した場合はスキップし、更新した場合はユーザーに変更内容を報告する
