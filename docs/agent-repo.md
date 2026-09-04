# browsem 固有ルール

## 概要
GeckoView ベースの Android ブラウザ。Kotlin / Jetpack Compose / Material 3 / Navigation 3 / Koin DI。

## アーキテクチャ
- ViewModelとUIのやり取りはクリックも表示もUiState経由。UIとの唯一の接点
- OneShotイベントはChannelを使ったevent
- データフロー: Proto → DataStore/Room → Repository → ViewModel(ViewModelStateFlow) → UiState → Compose
- GeckoRuntimeはプロセスに1つ。`GeckoRuntime.getDefault(context)` で取得し ViewModel・Controller 経由で配布

## GeckoView 調査
- Bugzilla: https://bugzilla.mozilla.org/
- コンポーネント例: `Core :: GeckoView` / `GeckoView :: General`

## Compose / UI
- 画面 Composable は `internal`
- State Holder には `@Stable`
- Compose Material Icons Extended は使用禁止

## デグレ防止
変更・削除前に `git log -p` で追加経緯を確認する。

## テスト
- 単体: JUnit 4。`./gradlew test`（Paparazziは自動除外）
- Instrumentation: Gradle Managed Device のみ（実機・通常エミュレータ禁止）
- 通常は全件実行せず class 指定で絞る
- UI操作は Compose セマンティクス API。生のタッチ注入禁止
- repository等をテストから直接いじらない。UI操作で行う
- コンポーネント特定は `hasTestTag`（テキスト監視以外で `hasText` を使わない）
- TestTagは直接stringせず既存パターンに合わせる
- GMD起動失敗時は名前・IDを一時変更して再実行可

## Paparazzi / UI変更
UI関連はPaparazziで差分を取る。
- スナップショットはコミットしない。PRとチャットに貼る
- 画像なしでUI作業完了にしない

## ビルド例
```bash
./gradlew :app:assembleDebug
./gradlew test
./gradlew :app:verifyPaparazziDebug
./gradlew :app:recordPaparazziDebug -Dpaparazzi.filter="PreviewName"
./gradlew :app:lintDebug detekt
./gradlew :app:pixel6Api34DebugAndroidTest
```

## PRフォロー（Cursor Cloud Agent）
リモート実行ではPR push後にCI・PR購読。ローカルCLI/IDE拡張はユーザー指示時のみ。
