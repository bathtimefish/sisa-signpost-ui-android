package com.bathtimefish.signpost

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * スクリムに開ける穴と、カード・尾の置き場所。
 *
 * ⭐⭐ **ここは Compose の描画も Android も知らない純粋な計算。**
 * [TipEngine] を Compose 非依存にしたのと同じ理由——**エミュレータ無しの素の JUnit で
 * 固定できる範囲を最大にする**ため。実機の仕事は「どこに貼ったか」だけに寄せる。
 */
data class TipPlacement(
    /** 明るく残す領域（スクリムに開ける穴）。 */
    val hole: Rect,
    /** カードの左上。カードの幅は [computeTipPlacement] の `cardWidth` と同じ。 */
    val cardTopLeft: Offset,
    /** 実際にカードを置けた side。⚠️ 希望した側に入らなければ反転する。 */
    val edge: TipEdge,
    /** カード左端から尾の左端までの距離。尾は穴の中心を指す。 */
    val caretLeftInCard: Float,
)

/**
 * カードとスクリムの配置を決める。
 *
 * ⭐ **尾は穴の中心を指す。** カードは画面幅いっぱい（左右 [cardMargin] のみ）に置き、
 * 尾だけを横へずらす。⚠️ **カードを中央寄せして尾をカード中央に固定すると、画面端の
 * ボタンで「隣のボタンを指している」ように見え、ユーザーが違うボタンを押す**
 * （2026-08-18 の実機ユーザーテストで実際に指摘された）。
 *
 * ⚠️ **希望した側に収まらなければ反対側へ反転する。** 反転しても収まらない場合は
 * 広いほうへ置いて画面内へ寄せる（＝**カードが画面外に消えることはない**）。
 *
 * @param anchor 明るく残したい領域（複数あるときは呼び出し側で union 済みのもの）
 * @param screen 画面（ホスト）の大きさ
 * @param cardHeight 実測したカードの高さ。0 のとき（初回計測前）でも破綻しない
 * @param safeTop **カードを置いてはいけない上の帯**（ステータスバー）。ホストが
 *   システムバーの下に居るなら 0 でよい
 * @param safeBottom 同じく下の帯（ホームインジケータ / ジェスチャバー）
 */
fun computeTipPlacement(
    anchor: Rect,
    screen: Size,
    preferredEdge: TipEdge,
    cardWidth: Float,
    cardHeight: Float,
    cardMargin: Float,
    holePadding: Float,
    caretWidth: Float,
    caretHeight: Float,
    caretCornerInset: Float = caretWidth,
    // ⚠️⚠️ **「入るかどうか」を画面の端で測ると、上端のアンカーでカードがシステムバーの
    // 下へ潜り込む。** そこは見えないだけでなく**押せない**（その帯へのタップは
    // システムが持っていく）ので、**カードをタップしても段が閉じない**。
    // 2026-08-20 に iOS の報告書画面（C1）で実際に起き、時計とカードの文字が重なっていた。
    // ⭐ ホストがシステムバーの内側に居る（Android の現状）なら 0 のままでよい。
    safeTop: Float = 0f,
    safeBottom: Float = 0f,
): TipPlacement {
    val hole = Rect(
        left = (anchor.left - holePadding).coerceAtLeast(0f),
        top = (anchor.top - holePadding).coerceAtLeast(0f),
        right = (anchor.right + holePadding).coerceAtMost(screen.width),
        bottom = (anchor.bottom + holePadding).coerceAtMost(screen.height),
    )

    val needed = cardHeight + caretHeight
    // ⚠️ 穴はホスト全体の座標だが、**カードが使えるのは安全領域の内側だけ**。
    val roomAbove = hole.top - safeTop
    val roomBelow = screen.height - safeBottom - hole.bottom
    val edge = when {
        preferredEdge == TipEdge.Above && roomAbove >= needed -> TipEdge.Above
        preferredEdge == TipEdge.Below && roomBelow >= needed -> TipEdge.Below
        // ⚠️ 希望した側に入らないときだけ反転する（既定を勝手に無視しない）。
        preferredEdge == TipEdge.Above && roomBelow >= needed -> TipEdge.Below
        preferredEdge == TipEdge.Below && roomAbove >= needed -> TipEdge.Above
        // どちらにも入らない＝アンカーが画面の大半を占める。広いほうへ寄せる。
        roomAbove >= roomBelow -> TipEdge.Above
        else -> TipEdge.Below
    }

    val rawTop = when (edge) {
        TipEdge.Above -> hole.top - caretHeight - cardHeight
        TipEdge.Below -> hole.bottom + caretHeight
    }
    // ⚠️ 反転しても収まらないとき（アンカーが画面の大半を占める）は寄せるが、
    // **安全領域の外へは絶対に出さない**。押せないカードを出すくらいなら重ねる。
    val minTop = safeTop
    val maxTop = (screen.height - safeBottom - cardHeight).coerceAtLeast(minTop)
    val cardTop = rawTop.coerceIn(minTop, maxTop)

    val cardLeft = cardMargin
    // ⭐ 尾は穴の中心。カードの角に食い込まないよう内側へ寄せる。
    val minCaret = caretCornerInset
    val maxCaret = (cardWidth - caretCornerInset - caretWidth).coerceAtLeast(minCaret)
    val caretLeft = (hole.center.x - cardLeft - caretWidth / 2f).coerceIn(minCaret, maxCaret)

    return TipPlacement(
        hole = hole,
        cardTopLeft = Offset(cardLeft, cardTop),
        edge = edge,
        caretLeftInCard = caretLeft,
    )
}

/**
 * スクリムの当たり判定に使う、穴の外側4枚。
 *
 * ⭐⭐ **スクリムは「描くもの」と「触れないようにするもの」を分ける。**
 * Compose の当たり判定は**ポインタ入力を持つノードしか拾わない**ので、暗く描くだけの
 * レイヤーはタップを遮らない。遮るのはこの4枚だけで、**穴の中には何も置かない**
 * ——だからタップがそのまま下のコントロールへ届く。
 * ⚠️ 全画面 1 枚で「穴の中なら consume しない」と書くより、この形のほうが
 * 「どこが押せるか」がコードから読める。
 */
fun scrimBlockers(hole: Rect, screen: Size): List<Rect> = listOfNotNull(
    Rect(0f, 0f, screen.width, hole.top).takeIf { it.height > 0f },
    Rect(0f, hole.bottom, screen.width, screen.height).takeIf { it.height > 0f },
    Rect(0f, hole.top, hole.left, hole.bottom).takeIf { it.width > 0f && it.height > 0f },
    Rect(hole.right, hole.top, screen.width, hole.bottom).takeIf { it.width > 0f && it.height > 0f },
)

/** 複数の領域を1つにまとめる（inline カードとアンカーを一緒に明るく残すため）。 */
fun unionOf(rects: Collection<Rect>): Rect? {
    if (rects.isEmpty()) return null
    var acc = rects.first()
    rects.drop(1).forEach { acc = acc.expandToInclude(it) }
    return acc
}

private fun Rect.expandToInclude(other: Rect): Rect = Rect(
    left = minOf(left, other.left),
    top = minOf(top, other.top),
    right = maxOf(right, other.right),
    bottom = maxOf(bottom, other.bottom),
)

/**
 * 候補の中から**この画面で実際に出せる段**を選ぶ。
 *
 * ⚠️⚠️ **「先に書いた群が勝つ」にしてはいけない。** 貼られていない段が勝つと、
 * **何も出ないまま後続の群を永久に塞ぐ**。2026-08-20 の実機で実際に踏んだ:
 * インシデント詳細は群を2つ使っていて、「クローズ」の段（解決済みのときだけ貼る）が
 * 未読なので勝ち、対応中は**アンカーが無いので何も出ず、それ以降が全部出なくなった**。
 * ⚠️ 群の順番を入れ替えるだけでは直らない（逆向きに同じことが起きる）。
 *
 * @param isVisible その段のアンカーが**いま画面に見えているか**
 */
fun selectShowableTip(candidates: List<Tip>, isVisible: (String) -> Boolean): Tip? =
    candidates.firstOrNull { isVisible(it.id) }

/**
 * 候補のうち**この画面に属する**最初の段（まだ見えていなくてよい）。
 *
 * ⚠️⚠️ **「貼られていない」と「貼られているが今は見えていない」は別物。**
 * 混同すると 2026-08-20 の実機で踏んだ**デッドロック**になる:
 * 報告書画面の「登録する」は長いフォームの画面外にあり、
 * **見えていないから段が出ない → 段が出ないから最下段へスクロールしない**、で永久に出ない。
 * ⭐ 画面側は**これ**を見て「見える位置まで運ぶ」（`animateScrollTo` 等）を行い、
 * 運び終えたら [selectShowableTip] が拾って実際に出る。
 *
 * @param isAttached その段のアンカーが**この画面に貼られているか**（描かれてさえいれば true）
 */
fun selectPendingTip(candidates: List<Tip>, isAttached: (String) -> Boolean): Tip? =
    candidates.firstOrNull { isAttached(it.id) }
