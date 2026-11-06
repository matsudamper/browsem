# Compose / UiState

## 共通ルール

### UiState
- UI と ViewModel の接点は UiState（表示もイベントも）
- ViewModel の public 関数を UI から直接呼ばない。Callbacks / Event interface を UiState 経由で使う
- UiState に表示に使わない値（id など）を載せない
- UiState にデフォルト値を付けない（該当リポの方針がある場合はそれに従う）
- UiState はバックエンドのモデルや内部事情に依存させず、画面の表示・操作に必要な状態を定義する

#### Boolean の名前

どの部品がどうなるかで名付ける。`canX` / `isX` は「できるか」というドメインの可否になり、
画面が何をするかが名前から落ちる。

```kotlin
// 名前が「保存できるか」までしか言っていない。画面が何をするかは KDoc にしか無い
/** false の間は登録のボタンを押せなくする */
val canSave: Boolean

// 名前で分かる。KDoc は要らない
val saveButtonEnabled: Boolean
```

「〜の間はボタンを押せなくする」「〜でボタンの文字が変わる」と説明したくなったら、それは名前。
KDoc に残すのは、名前にしても分からない理由だけ。

```kotlin
/** 閉じるとこの画面ごと消えて登録も打ち切られるので、登録の途中は false になる */
val closeEnabled: Boolean
```

`closeEnabled` のように部品が 1 つに決まらないもの（やめるボタンと、枠の外を押したときの両方）は、
部品名を入れず操作で名付ける。

### Compose
- Composable には表示・UI 操作以外の業務ロジックを置かず、ViewModel に記述する
- 画面 Composable は必要に応じて `internal`
- State Holder には `@Stable`
- `material-icons-extended` / Compose Material Icons Extended は使わない。アイコンは XML/SVG 等
- Composable 内の早期 return は避け、if-else / when で分岐する（該当リポの方針がある場合）

#### 表示条件

Composable の中で条件を組み立てない。どういうときに押せるかは表示の判断で、UI の仕事ではない。

```kotlin
// UI にロジックがある
OutlinedTextField(enabled = !uiState.fetching && !uiState.saving)

// 組み立てた結果を UiState が持つ
OutlinedTextField(enabled = uiState.urlInputEnabled)
```
