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
./gradlew :app:lintDebug

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

## コーディング規約

### 言語・スタイル

- コメントは日本語で書く
- import はワイルドカード不使用。個別 import
- FQCN ではなく import して短縮名を使う（名前衝突時を除く）

### Compose パターン

- 画面 Composable は `internal` visibility
- State Holder クラスには `@Stable` アノテーション

### 永続化

- 設定: Proto → DataStore (`SettingsRepository`)
- タブ状態: Room (`TabRepository`)
- 履歴: Room (`HistoryRepository`)
- Proto 定義: `proto/src/main/proto/browser_settings.proto`

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

## 作業フロー

UIに関連した実装はPaparazziで差分を取って作業するのが良い

### 変更後

1. `./gradlew :app:assembleDebug` でビルド確認
2. `./gradlew test` でユニットテスト通過を確認
3. UI 変更を含む場合は `@Preview` を追加/更新し、Paparazzi スナップショットを撮影

## Claude への運用ルール

- 1 コミット = 1 論理的変更を目安にする
- ビルド時にネットワークエラーになった場合は原因を調べなくて良い。作業を完了し、通らなかったエラーを知らせるだけで良い
