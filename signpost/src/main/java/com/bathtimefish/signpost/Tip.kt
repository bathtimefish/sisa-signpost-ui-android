package com.bathtimefish.signpost

/**
 * 道沿いに1枚だけ出すヒント。
 *
 * ⚠️ **文言を「意味が変わる形で」改訂したときは id に版を足す**（`foo` → `foo-v2`）。
 * 既読は id で持つので、同じ id のまま中身を変えると**既に読んだ人には二度と出ない**。
 */
data class Tip(
    val id: String,
    val title: String? = null,
    val message: String? = null,
    /** すべて満たされたときだけ出す。空なら無条件。 */
    val rules: List<TipRule> = emptyList(),
    /** 出し方。⭐ 既定は幕つき（[TipForm.Spotlight]）。 */
    val form: TipForm = TipForm.Spotlight,
)

/** 段の出し方。 */
enum class TipForm {
    /**
     * ⭐ **既定**。幕で周囲を覆い、**その段が指すもの以外は触らせない**。
     * 「読む前に先へ進めない」という契約を守るのはこちら。
     */
    Spotlight,

    /**
     * **幕なし・遮りなし。ただ対象を指すだけ。**
     *
     * ⭐ 使いどころは「**やることは既に伝えてあって、あとは繰り返すだけ**」の段。
     * 幕で止めると繰り返しの邪魔になるが、指しておくと迷わない
     * （例: 「あと3回」「あと2回」…と数えながら同じボタンを指し続ける）。
     * ⚠️ 契約（先へ進めない）は**持たない**。持たせたい段には使わないこと。
     */
    Bare,
}

/** ヒントを出してよいかの条件。参照するのは [TipParameters]（＝永続する小さな状態）だけ。 */
fun interface TipRule {
    fun isSatisfied(parameters: TipParameters): Boolean

    companion object {
        /** フラグが立っていること。「中核ループを1周した」のような一度きりの事実に使う。 */
        fun flagIsSet(key: String): TipRule = TipRule { it.flag(key) }

        /** カウンタが閾値以上であること。「3件書いた人にだけ次を出す」に使う。 */
        fun countAtLeast(key: String, min: Int): TipRule = TipRule { it.count(key) >= min }

        /**
         * カウンタが**ちょうどその値**であること。
         *
         * ⭐ 「あと3回 / あと2回 / あと1回」のように**数えながら入れ替わる**段に使う。
         * ⚠️ こういう段は [TipOrder.Unordered] の群に置くこと——
         * ordered だと**閉じないと次へ進まない**ので、カウンタが増えても入れ替わらない。
         */
        fun countEquals(key: String, value: Int): TipRule = TipRule { it.count(key) == value }
    }
}

/**
 * ルールが読む状態。**アプリ全体で1つ**（画面や項目ごとではない）。
 *
 * ⚠️⚠️ **「いま開いている〇〇が××か」はここで表せない。** パラメータはアプリ全体に1つなので、
 * 対象ごとの条件を書こうとすると必ず破綻する。そういう段は**その状態のときだけ貼る**
 * （呼び出し側で `tip` に null を渡す）ことで満たすこと。
 */
interface TipParameters {
    fun flag(key: String): Boolean
    fun count(key: String): Int
}

/** 群の出し方。 */
enum class TipOrder {
    /**
     * 前が閉じられて初めて次に進む。
     *
     * ⚠️⚠️ **群をまたいで順序を付けないこと。** 道が分岐して**永久に未消化になる段**があると、
     * それ以降が全部止まる。群は「画面ごと」に切るのが安全な既定。
     * ⚠️ 同じ理由で、**別の段の消化を解禁条件にしない**（条件が厳しい段を群に混ぜると、
     * その条件を満たさない人には以降が出なくなる）。
     */
    Ordered,

    /** 条件を満たしたものから順不同で出す。 */
    Unordered,
}

/** 画面ひとつぶんのヒントの群。 */
data class TipGroup(
    val id: String,
    val order: TipOrder,
    val tips: List<Tip>,
)

fun tipGroup(id: String, order: TipOrder = TipOrder.Ordered, vararg tips: Tip): TipGroup =
    TipGroup(id = id, order = order, tips = tips.toList())
