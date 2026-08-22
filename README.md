# signpost (Android)

道沿いヒント（wayside tips）のための Jetpack Compose ライブラリ。

> **The contract, not the widget.**
> While a tip is showing, nothing but the thing it points at can be touched.
> `signpost` owns that blocking itself, so the calling screen cannot forget it.

## ⭐⭐ 売りは部品ではなく契約

ヒントの部品は世の中にたくさんあります。signpost が引き受けるのはその**契約**のほうです。

**ヒントが出ている間は、それが指すもの以外へ進めない。**

よくある作りでは、この「進めなくする」を呼び出し側が `enabled = !blocked` のように
画面ごとに書き写します。これは**書き忘れても静かに壊れる**——コンパイルでもテストでも
分からず、実機で誰かが先へ進んでしまって初めて気づきます。

signpost は**幕（スクリム）で周囲を覆い、遮ること自体を部品側が持ちます**。
画面側に書くのは「どこに貼るか」だけです。

## インストール

JitPack から取得します。

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.bathtimefish:sisa-signpost-ui-android:0.1.0")
}
```

要件: minSdk 33 / compileSdk 36 / JVM 21 / Compose BOM 2024.12.01 以降。

## 使い方

### 1. 段を定義する

段（tip）を画面ひとつぶんの**群**にまとめます。

```kotlin
val incidentTips = tipGroup(
    id = "incident-detail",
    order = TipOrder.Ordered,
    Tip(
        id = "record-v1",
        title = "対応を記録しましょう",
        message = "気づいたことをそのまま書けば大丈夫です。",
    ),
    Tip(
        id = "report-v1",
        title = "終わったら報告する",
        message = "報告すると関係者に共有されます。",
        // ⭐ 解禁条件。3件書いた人にだけ出す。
        rules = listOf(TipRule.countAtLeast("notes_written", 3)),
    ),
)
```

⚠️ **id は既読の鍵**です。文言を「意味が変わる形で」直したときは `-v2` のように版を足してください。
同じ id のまま中身を変えると、既に読んだ人には二度と出ません。

### 2. エンジンをアプリに1つ持つ

既読とパラメータ（フラグ・カウンタ）は端末に残ります。

```kotlin
val engine = TipEngine(SharedPreferencesTipStore(prefs))

// 進捗を伝える
engine.increment("notes_written")
engine.setFlag("core_loop_completed")

// 出したくない場面では理由を付けて止める
engine.setSuppressed(true, reason = "read-only")
engine.setSuppressed(false, reason = "read-only")
```

### 3. 画面をホストで包み、指すものに貼る

```kotlin
@Composable
fun IncidentDetailScreen(engine: TipEngine) {
    val signpost = rememberSignpostState(engine, incidentTips)

    SignpostHost(state = signpost) {
        Column {
            // 「見る領域」を説明する段は、流れの中にカードを置く
            TipCard(tipId = "record-v1")

            Button(
                onClick = { /* ... */ },
                // 「押すもの」を指す段は貼るだけでよい（カードは浮いて出る）
                modifier = Modifier.tipAnchor("report-v1", edge = TipEdge.Above),
            ) {
                Text("報告する")
            }
        }
    }
}
```

⭐ **貼った対象をタップすると、その段は既読になります。** 押させる段で
「押したのに未読のまま残って、その先が永久に出ない」を防ぐためです
（`tipAnchor` は Initial パスで観測しますが **consume しません**——
下のコントロールは普段どおり効きます）。

## 設計の要点

実装の途中で踏んだもののうち、使う側にも関係するものだけ。

- **幕は「暗く描く層」と「触らせない層」に分けてある。**
  遮るのは穴の外側だけで、**穴の中には何も置かない**のでタップが下へ届きます。
  ⚠️ 全画面 Popup では実現できません（穴の部分もウィンドウが受け取ってしまう）。
- **フェードするのは見た目だけ。触れる/触れないは即座に切り替わります。**
  演出に合わせると、閉じた直後の一瞬がまた空振りになります。
- **群は画面ごとに切るのが安全な既定。** ⚠️ 群をまたいで順序を付けないでください。
  道が分岐して永久に未消化になる段があると、それ以降が全部止まります。
- **抑止は理由の集合**（単一の Boolean ではない）。
  ⚠️ 単一の真偽値にすると、後から来た書き手が前の抑止を黙って解除します。
  ⚠️ 抑止は**全段を止める**ので、新しい理由を足すときは
  **「解けなかったらどうなるか」を先に決めてください**（逃げ道が無いとツアーが静かに死にます）。
- **数えながら入れ替わる段**（あと3回 → あと2回 …）は
  `TipOrder.Unordered` の群に `TipRule.countEquals` で置きます。
  ⚠️ ordered だと閉じないと次へ進まないので、数が増えても入れ替わりません。
- **段は画面の他の要素が描き終わってから出ます**（固定の待ち時間ではなく、
  アンカーの位置が動かなくなったことを合図にしています）。

## 制限

- **minSdk 33。** すりガラス（`BlurEffect`）が API 31 以降で、33 は実機で通した下限です。
- **TalkBack のフォーカスは幕で止めていません**（タップは止まります）。
- **システムの通知バナーは覆えません**（幕はアプリのウィンドウの中だけ）。
- 離れた2箇所を同じ段で指すと、**その間も穴として開きます**（穴は矩形の合成のため）。

## テスト

```
./gradlew :signpost:test
```

⭐ 中核（`TipEngine` / `TipLayout`）は Compose も Android の UI も知らないので、
**エミュレータ無しの素の JUnit で 33 件**を固定しています。
実機の仕事は「どこに貼ったか」だけです。

## iOS 版

同じ契約の SwiftUI 実装があります: [sisa-signpost-ui-ios](https://github.com/bathtimefish/sisa-signpost-ui-ios)

⚠️ **プラットフォーム由来の差は畳んでいません。** iOS はホストがアプリに1つで、
貼り口が「どの画面のものか」を持ちます（`.overlay` がナビゲーションバーの上に出るのは
`NavigationStack` 自体に掛けたときだけ、という制約のため）。
Android は画面ごとにホストがあるので、その必要がありません。

## ライセンス

Apache License 2.0 — [LICENSE](LICENSE)
