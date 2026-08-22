package com.bathtimefish.signpost

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幕とカードの配置の網。
 *
 * ⭐⭐ **ここを実機に頼らないために [computeTipPlacement] を純粋にしてある。**
 * 実機でしか分からないのは「どこに貼ったか」だけで、貼った矩形から先の計算は全部ここ。
 */
class TipLayoutTest {

    private val screen = Size(1000f, 2000f)

    private fun place(
        anchor: Rect,
        preferredEdge: TipEdge = TipEdge.Above,
        cardHeight: Float = 200f,
        safeTop: Float = 0f,
        safeBottom: Float = 0f,
    ) = computeTipPlacement(
        anchor = anchor,
        screen = screen,
        preferredEdge = preferredEdge,
        cardWidth = screen.width - 32f * 2,
        cardHeight = cardHeight,
        cardMargin = 32f,
        holePadding = 10f,
        caretWidth = 30f,
        caretHeight = 15f,
        safeTop = safeTop,
        safeBottom = safeBottom,
    )

    @Test
    fun `穴はアンカーを少し広げたもの`() {
        val p = place(Rect(400f, 900f, 600f, 1000f))
        assertEquals(Rect(390f, 890f, 610f, 1010f), p.hole)
    }

    @Test
    fun `穴は画面の外へはみ出さない`() {
        // ⚠️ 画面端のアンカー（フッタ右端のアイコン等）で負の座標を作らない。
        val p = place(Rect(0f, 1950f, 60f, 2000f))
        assertEquals(0f, p.hole.left, 0f)
        assertEquals(2000f, p.hole.bottom, 0f)
    }

    @Test
    fun `上に置けるなら上に置き、尾のぶんだけ穴から離す`() {
        val p = place(Rect(400f, 900f, 600f, 1000f), TipEdge.Above, cardHeight = 200f)
        assertEquals(TipEdge.Above, p.edge)
        // カードの下端 + 尾 = 穴の上端
        assertEquals(p.hole.top - 15f, p.cardTopLeft.y + 200f, 0.01f)
    }

    @Test
    fun `上に入らなければ下へ反転する`() {
        // 画面最上部のアンカー＝上に余白が無い。
        val p = place(Rect(400f, 0f, 600f, 60f), TipEdge.Above, cardHeight = 200f)
        assertEquals(TipEdge.Below, p.edge)
        assertEquals(p.hole.bottom + 15f, p.cardTopLeft.y, 0.01f)
    }

    @Test
    fun `下に入らなければ上へ反転する`() {
        val p = place(Rect(400f, 1940f, 600f, 2000f), TipEdge.Below, cardHeight = 200f)
        assertEquals(TipEdge.Above, p.edge)
    }

    @Test
    fun `どちらにも入らないときでもカードは画面内に収まる`() {
        // アンカーが画面をほぼ占める＝上下どちらにも 215px は取れない。
        val p = place(Rect(0f, 100f, 1000f, 1900f), TipEdge.Above, cardHeight = 200f)
        assertTrue("上端が負にならない", p.cardTopLeft.y >= 0f)
        assertTrue("下端が画面外へ出ない", p.cardTopLeft.y + 200f <= screen.height)
    }

    // ── ⚠️⚠️ カードをシステムバーの帯に置かない（2026-08-20 に iPhone 実機で踏んだ）──
    //
    // 幕は全画面へ広げる（ナビゲーションバーごと覆うため）が、**カードまで全画面の座標で
    // 置くと、画面上部のアンカーでカードがステータスバーの下へ潜り込む**。
    // ⭐⭐ そこは**見えないだけでなく押せない**——その帯へのタップはシステム（一番上まで
    // スクロール）が持っていくので、**カードをタップしても段が閉じない**。
    // ⚠️ 「ヒントが閉じられない」という形で出るので、**配置の問題に見えない**のが厄介。

    @Test
    fun `⭐⭐ 上に入るように見えても、ステータスバーの帯しか無いなら下へ反転する`() {
        // 報告書画面の C1 と同じ形: アンカーは上端近く（上に 230 空いているので
        // カード + 尾 = 215 は「入る」が、そのうち 120 はステータスバーの帯）。
        val p = place(Rect(600f, 240f, 900f, 320f), TipEdge.Above, cardHeight = 200f, safeTop = 120f)
        assertEquals(TipEdge.Below, p.edge)
        assertEquals(p.hole.bottom + 15f, p.cardTopLeft.y, 0.01f)
    }

    @Test
    fun `安全領域を渡さなければ従来どおり（Android は今これ）`() {
        // ⭐ 対照。同じアンカーでも safeTop=0 なら上に置ける＝規則を変えたのは
        // 「安全領域を渡したときだけ」であることを固定する。
        val p = place(Rect(600f, 240f, 900f, 320f), TipEdge.Above, cardHeight = 200f)
        assertEquals(TipEdge.Above, p.edge)
    }

    @Test
    fun `どちらにも入らないときでもカードは安全領域の外へ出ない`() {
        // アンカーが画面をほぼ占める＝寄せるしかない。それでも帯には食い込ませない。
        val p = place(
            Rect(0f, 100f, 1000f, 1900f), TipEdge.Above,
            cardHeight = 200f, safeTop = 120f, safeBottom = 80f,
        )
        assertTrue("ステータスバーの帯に入らない", p.cardTopLeft.y >= 120f)
        assertTrue("ホームインジケータの帯に入らない", p.cardTopLeft.y + 200f <= screen.height - 80f)
    }

    @Test
    fun `帯だけで画面が埋まるような極端な値でも破綻しない`() {
        // ⚠️ min > max の coerceIn は例外になる。上端を優先して潰れないことだけ見る。
        val p = place(
            Rect(400f, 900f, 600f, 1000f), TipEdge.Above,
            cardHeight = 1900f, safeTop = 120f, safeBottom = 80f,
        )
        assertEquals(120f, p.cardTopLeft.y, 0.01f)
    }

    @Test
    fun `尾は穴の中心を指す`() {
        val p = place(Rect(400f, 900f, 600f, 1000f))
        // 穴の中心 = 500。カード左端 = 32。尾の中心が 500 に来る。
        assertEquals(500f, p.cardTopLeft.x + p.caretLeftInCard + 15f, 0.01f)
    }

    @Test
    fun `画面右端のアンカーでも尾はカードの角に食い込まない`() {
        // ⚠️⚠️ これが 2026-08-18 の実機テストで指摘された不具合の回帰テスト。
        // 尾がカード中央に固定されていて「隣のボタンを指して見え、押し間違いを誘発」した。
        val p = place(Rect(940f, 900f, 1000f, 1000f))
        val cardWidth = screen.width - 32f * 2
        assertTrue("左の角に食い込まない", p.caretLeftInCard >= 30f)
        assertTrue("右の角に食い込まない", p.caretLeftInCard + 30f <= cardWidth - 30f + 0.01f)
        // それでも「できるだけ右」を指している（カードの右半分）。
        assertTrue("右寄りを指している", p.caretLeftInCard > cardWidth / 2f)
    }

    @Test
    fun `幕は穴の外側をすべて覆い、穴の中は覆わない`() {
        val hole = Rect(390f, 890f, 610f, 1010f)
        val blockers = scrimBlockers(hole, screen)

        fun covered(x: Float, y: Float) = blockers.any { it.contains(Offset(x, y)) }

        // 穴の中は触れる（＝下のコントロールへタップが届く）。
        assertTrue("穴の中央は覆われない", !covered(500f, 950f))
        // 外側は上下左右とも塞がっている。
        assertTrue("上は覆う", covered(500f, 100f))
        assertTrue("下は覆う", covered(500f, 1900f))
        assertTrue("左は覆う", covered(100f, 950f))
        assertTrue("右は覆う", covered(900f, 950f))
        assertTrue("角も覆う", covered(50f, 50f))
    }

    @Test
    fun `穴が画面いっぱいなら塞ぐものは無い`() {
        val blockers = scrimBlockers(Rect(0f, 0f, 1000f, 2000f), screen)
        assertTrue(blockers.isEmpty())
    }

    @Test
    fun `複数の領域は1つの穴にまとまる`() {
        // inline カード（上）とその説明対象（下）を一緒に明るく残す。
        val union = unionOf(listOf(Rect(32f, 100f, 968f, 200f), Rect(0f, 220f, 1000f, 900f)))
        assertEquals(Rect(0f, 100f, 1000f, 900f), union)
    }

    @Test
    fun `領域が無ければ穴も無い`() {
        assertNull(unionOf(emptyList()))
    }

    /**
     * ⚠️⚠️ **貼られていない段が後続を塞がないこと。**
     * 2026-08-20 の実機で踏んだバグの回帰テスト——「先に書いた群が勝つ」にしていたため、
     * 対応中のインシデントで「クローズ」の段（解決済みのときだけ貼る）が勝ち、
     * **何も出ないまま B1 以降が全部出なくなった**。
     */
    @Test
    fun `⭐⭐ アンカーが画面に無い段は飛ばして、次の候補を出す`() {
        val close = Tip("incident-close", title = "最後に閉じる")
        val record = Tip("incident-record", title = "対応状況のリアルタイム共有")

        // 対応中＝「クローズ」は画面に無い。
        val shown = selectShowableTip(listOf(close, record)) { it == "incident-record" }
        assertEquals(record, shown)

        // 解決済み＝両方ある。**先に書いたほう（条件の狭いほう）が勝つ**。
        val whenResolved = selectShowableTip(listOf(close, record)) { true }
        assertEquals(close, whenResolved)

        // どれも貼られていなければ何も出ない。
        assertNull(selectShowableTip(listOf(close, record)) { false })
    }

    /**
     * ⚠️⚠️ **「貼られていない」と「貼られているが今は見えていない」を混同しないこと。**
     * 2026-08-20 の実機で踏んだデッドロックの回帰テスト——報告書画面の「登録する」は
     * 長いフォームの画面外にあり、**見えないから段が出ない → 出ないから最下段へ
     * スクロールしない**で永久に出なかった。
     */
    @Test
    fun `⭐⭐ 画面外のアンカーは「出さない」が「この画面の段」ではある`() {
        val submit = Tip("resolution-submit", title = "内容を確認して登録する")
        val candidates = listOf(submit)

        // まだ画面外＝出さない。
        assertNull(selectShowableTip(candidates) { false })
        // ⭐ だが「この画面の段」ではある——画面側はこれを見て見える位置まで運ぶ。
        assertEquals(submit, selectPendingTip(candidates) { true })

        // 運び終えたら出る。
        assertEquals(submit, selectShowableTip(candidates) { true })
    }

    @Test
    fun `貼られていない段は pending にもならない`() {
        val close = Tip("incident-close")
        assertNull(selectPendingTip(listOf(close)) { false })
    }
}
