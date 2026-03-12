# CLAUDE.md

## プロジェクト概要

GeckoView ベースの Android ブラウザアプリ。
Kotlin / Jetpack Compose / Material 3 / Navigation 3 / Koin DI。

## モジュール構成

| モジュール | 役割 |
|-----------|------|
| `:app` | メインアプリ。Activity・Compose UI・GeckoView ホスト・翻訳・拡張機能 |
| `:browser-core` | 純 JVM ライブラリ。タブ選択ポリシー等のドメインロジック |
| `:browser-engine-gecko` | Android ライブラリ。GeckoView 統合・メディア再生 |
| `:feature-browser` | Android ライブラリ。ブラウザ画面の ViewModel |
| `:feature-tabs` | Android ライブラリ。タブ一覧画面の ViewModel |
| `:data` | Android ライブラリ。DataStore + Room による永続化 |
| `:proto` | 純 Java ライブラリ。Protocol Buffers スキーマ (protobuf-lite) |

依存グラフ:

```
:app → :browser-engine-gecko → :browser-core
                              → :data → :proto
     → :feature-browser → :data
     → :feature-tabs → :browser-core
                     → :browser-engine-gecko
     → :data
```

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

# Android Instrumentation テスト (Managed Device)
./gradlew :app:pixel6Api34DebugAndroidTest
```

## ツールチェーン

- **Kotlin**: 2.3.10 / JVM Target 21
- **Gradle**: 9.4.0
- **AGP**: 9.1.0
- **compileSdk / targetSdk**: 36
- **minSdk**: 30
- **バージョンカタログ**: `gradle/libs.versions.toml`

## アーキテクチャ方針

### データフロー

```
Proto → DataStore/Room → Repository → ViewModel (StateFlow) → Compose
```

### DI (Koin)

- 設定は `app/.../di/AppModule.kt` に集約
- `GeckoRuntime` は `single` スコープ (プロセス内シングルトン)
- Repository は `single`、ViewModel は `viewModel { }` で登録

### GeckoRuntime

- **プロセスに1つ**。`GeckoRuntime.getDefault(context)` で取得
- ViewModel・Controller 経由で各タブに配布

### タブ管理

- `BrowserSessionController` がタブのライフサイクルを管理
- タブ切替は `AppDestination.Browser(tabId, beforeTab)` で表現
- タブ状態は Room + ファイルキャッシュ (サムネイル) で永続化

### ナビゲーション

- Navigation 3 (`androidx.navigation3`) + Kotlin Serialization
- `AppDestination` sealed interface で型安全なルート定義
- カスタム `NavController` でバックスタックを操作

## コード構成 (app モジュール)

パッケージルート: `app/src/main/java/net/matsudamper/browser/`

- `MainActivity.kt` — Activity エントリポイント
- `BrowserViewModel.kt` — メイン ViewModel
- `BrowserSessionController.kt` — タブ管理
- `navigation/` — `AppDestination`, `NavController`
- `screen/browser/` — ブラウザ画面 (BrowserScreen, BrowserToolBar, GeckoBrowserTab, BrowserTabScreenState)
- `screen/tab/` — タブ一覧
- `screen/settings/` — 設定
- `screen/extensions/` — 拡張機能管理
- `screen/history/` — 履歴
- `screen/notificationpermissions/` — 通知許可管理
- `translate/` — 翻訳 (GeckoView 内蔵 / ML Kit の 2 プロバイダ)
- `di/` — Koin モジュール定義
- `media/` — メディア再生サービス

## コーディング規約

### 言語・スタイル

- **コメントは日本語で書く**
- Kotlin コードスタイル: `official` (`gradle.properties` で指定)
- 最大行長: 200 文字 (`.editorconfig`)
- import はワイルドカード不使用。個別 import

### ファイル命名

| 種別 | 命名パターン | 例 |
|------|------------|-----|
| 画面 Composable | `*Screen.kt` | `SettingsScreen.kt` |
| ViewModel | `*ViewModel.kt` | `BrowserViewModel.kt` |
| State Holder | `*ScreenState.kt` | `BrowserTabScreenState.kt` |
| ツールバー等の UI 部品 | 機能名で命名 | `BrowserToolBar.kt` |

### Compose パターン

- 画面 Composable は `internal` visibility
- State Holder クラスには `@Stable` アノテーション
- `collectAsState()` で Flow → State 変換
- `@Preview` は Paparazzi でスクリーンショットテストされる

### 永続化

- 設定: Proto → DataStore (`SettingsRepository`)
- タブ状態: Room (`TabRepository`)
- 履歴: Room (`HistoryRepository`)
- Proto 定義: `proto/src/main/proto/browser_settings.proto`

## テスト方針

- **単体テスト**: JUnit 4。`./gradlew test` で実行 (Paparazzi テストは自動除外)
- **スクリーンショットテスト**: Paparazzi。`@Preview` 付き Composable を自動スキャンしてスナップショット
- **Instrumentation テスト**: Managed Device (Pixel 6, API 34)。`app/src/androidTest/`
- **Lint**: `./gradlew :app:lintDebug`

## 作業フロー

### 変更前

1. `./gradlew :app:assembleDebug` でビルドが通ることを確認
2. 変更対象のファイルを読み、既存パターンを把握

### 変更後

1. `./gradlew :app:assembleDebug` でビルド確認
2. `./gradlew test` でユニットテスト通過を確認
3. UI 変更を含む場合は `@Preview` を追加/更新し、Paparazzi スナップショットを撮影

### PR 前チェック

```bash
./gradlew :app:assembleDebug && ./gradlew test && ./gradlew :app:lintDebug
```

## Claude への運用ルール

- **既存パターン優先**: 新しいパターンを導入する前に、同種の既存実装を確認して合わせる
- **最小限の変更**: 依頼された範囲だけを変更する。無関係なリファクタ・コメント追加・import 整理をしない
- **影響範囲が大きい変更** (モジュール構成の変更、DI 設定の変更、Navigation ルート追加など) は実装前にユーザーに確認する
- **コメントは日本語**で書く
- **1 コミット = 1 論理的変更** を目安にする

## 参考ファイル

- `@settings.gradle.kts` — モジュール一覧
- `@gradle/libs.versions.toml` — バージョンカタログ
- `@app/build.gradle.kts` — アプリモジュール設定・依存関係
- `@app/src/main/java/net/matsudamper/browser/di/AppModule.kt` — DI 設定
- `@app/src/main/java/net/matsudamper/browser/navigation/AppDestination.kt` — ナビゲーション定義
- `@proto/src/main/proto/browser_settings.proto` — 設定スキーマ
- `@.github/workflows/` — CI ワークフロー

## 要確認事項

- ktlint / detekt 等の静的解析ツールは未導入 (導入予定があるか要確認)
- `app/lint.xml` は空。プロジェクト固有の lint ルールがあるか要確認
- E2E テスト方針が定まっているか要確認
