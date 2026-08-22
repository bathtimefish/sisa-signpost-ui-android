package com.bathtimefish.signpost

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** カードをアンカーのどちら側に出すか。⚠️ 入らなければ反転する（[computeTipPlacement]）。 */
enum class TipEdge { Above, Below }

/** 「戻る」が押されたときの振る舞い。 */
enum class TipBackBehavior {
    /**
     * ⭐ **既定**。戻る＝ヒントを閉じる（1回目でヒント、2回目で画面を出る）。
     * Android のイディオム（戻るは最前面のものを閉じる）に合わせている。
     */
    CloseTip,

    /** 何もしない。⚠️ **戻るが壊れたように見える**ので、選ぶ理由がある場合だけ。 */
    Ignore,

    /** 画面側に素通しする（ヒントは出したまま）。 */
    PassThrough,
}

/**
 * 幕の見え方。⭐ 部品の既定は**ぼかし無しの黒い幕**で、すりガラスは opt-in。
 * ⚠️ ぼかしは画面の内容をもう一度描くので、**ただにはならない**（既定で有効にしない）。
 */
@Immutable
data class TipScrimStyle(
    /**
     * 幕の色。⚠️ **不透明にしない**——「いま画面のどこに居るか」が分からなくなると、
     * ヒントを読んだあとに戻ってこられない。
     */
    val color: Color = Color.Black.copy(alpha = 0.55f),
    /**
     * すりガラスのぼかし半径。`0.dp` で**ぼかし無し**（＝ただの半透明の幕）。
     * ⭐ 穴の中はぼかさない——「鮮明 vs ぼけている」の差そのものが
     * 「ここだけが生きている」の合図になる。
     */
    val blurRadius: Dp = 0.dp,
    /**
     * 明るく残す領域の輪郭。
     * ⚠️⚠️ **白い幕では実質必須。** 明るい画面に白い幕をかけると、
     * 穴と幕の**明るさがほぼ同じ**になり、「どこが押せるか」が読めなくなる
     * （黒い幕は明るさの差だけで成立するので不要だった）。
     */
    val holeOutline: Color = Color.Transparent,
    val holeOutlineWidth: Dp = 1.dp,
    /**
     * 幕の出入りのフェード（ミリ秒）。`0` で即時。
     * ⭐ **フェードするのは見た目だけで、触れる/触れないは即座に切り替わる**
     * （フェード中も待たされない。下の contract を参照）。
     */
    val fadeMillis: Int = 0,
    /**
     * 同じ画面で次の段へ進むときも、**いったん消してから出す**か。
     *
     * `false`（既定）= 幕は出たまま穴が移る（速い）。
     * `true` = 段ごとに [fadeMillis] ぶんフェードアウト → フェードイン
     * （**1段ごとに画面の全景が見える**ぶん直感的だが、切り替えに 2 倍の時間がかかる）。
     *
     * ⚠️ **消えている間は幕も外す。** 契約は「ヒントが出ている**間**は先へ進めない」なので、
     * 1枚も出ていない瞬間に触れるのは矛盾しない。逆にここを塞ぐと
     * **切り替えのたびに空振りの時間を作る**ことになる。
     */
    val fadeBetweenTips: Boolean = false,
)

/** カードとスクリムの配色。**アプリのテーマに合わせて差し替えられるように**外から供給する。 */
@Immutable
data class TipColors(
    val container: Color,
    val content: Color,
    val scrim: TipScrimStyle = TipScrimStyle(),
)

/** 配色の供給口。⭐ 呼び出し側に配色を書かせない（1か所で決める）。 */
val LocalTipColors = compositionLocalOf<TipColors?> { null }

@Composable
private fun tipColors(): TipColors = LocalTipColors.current ?: TipColors(
    container = MaterialTheme.colorScheme.secondaryContainer,
    content = MaterialTheme.colorScheme.onSecondaryContainer,
)

/** 画面が「落ち着いた」と見なすまでの静止時間。 */
const val DefaultSettleMillis: Long = 250L

/** ⚠️ 動き続ける画面で永久に出ないことがないよう、待ちの上限。 */
const val DefaultMaxWaitMillis: Long = 2_000L

private val CardMargin: Dp = 16.dp
private val CaretWidth: Dp = 18.dp
private val CaretHeight: Dp = 9.dp
private val HolePadding: Dp = 6.dp
private val HoleCorner: Dp = 12.dp

/**
 * 領域の名前。⭐ **穴は同じ段に属する領域をまとめて開ける**ので、
 * 1つの段が**複数の対象**を指せる（例: 画面上部＋カメラ映像）。
 */
internal data class TipRegionKey(val tipId: String, val slot: String)

/** inline カード自身が使う予約名。 */
internal const val CardSlot = "@card"

/** [Modifier.tipAnchor] の既定の名前。 */
const val DefaultAnchorSlot = "anchor"

/**
 * ホストが持つ状態。**どの段を出すか**（[TipEngine] が決める）と、
 * **その段のアンカーが画面のどこにあるか**（各アンカーが自己申告する）を結ぶ。
 *
 * ⭐ アンカーの位置は毎フレーム自己申告なので、**スクロールで画面に入ってきた瞬間に出せる**。
 * ⚠️ これは iOS の TipKit に無い性質——TipKit は「有効になった瞬間にアンカーが
 * 画面にあるか」だけを見て、出し直さない。移植時にこの差を消せる。
 */
@Stable
class SignpostState internal constructor(
    private val engine: TipEngine,
    private val groups: List<TipGroup>,
) {
    /** 各群の「次に出す段」。⚠️ この時点ではまだ**この画面に貼られているとは限らない**。 */
    private var candidates: List<Tip> by mutableStateOf(groups.mapNotNull { engine.current(it) })

    /**
     * いま出す段（無ければ null）。
     *
     * ⭐⭐ **候補のうち、アンカーがこの画面に実在するものを採る。**
     * ⚠️⚠️ 単純に「先に書いた群が勝つ」にしてはいけない——**貼られていない段が勝つと、
     * 何も出ないまま後続の群を永久に塞ぐ**。2026-08-20 の実機で実際に踏んだ:
     * インシデント詳細は群を2つ使っていて、「クローズ」の段（解決済みのときだけ貼る）が
     * 未読なので勝ち、**対応中はアンカーが無いので何も出ず、B1 以降が全部出なくなった**。
     * ⚠️ 群の順番を入れ替えるだけでは直らない（今度は逆向きに同じことが起きる）。
     *
     * ⭐ アンカーは毎フレーム自己申告しているので、「この画面に居るか」はホストが知っている。
     * スクロールで画面外に出ている段も同じ規則で飛ばされる。
     */
    val current: Tip? get() = selectShowableTip(candidates) { holeFor(it) != null }

    /**
     * この画面に属する段（**まだ見えていなくてよい**）。
     * ⭐ 画面側が「見える位置まで運ぶ」きっかけに使う。→ [selectPendingTip]
     */
    val pending: Tip? get() = selectPendingTip(candidates) { hasAnyAnchor(it) }

    internal val regions = mutableStateMapOf<TipRegionKey, Rect>()
    internal val edges = mutableStateMapOf<String, TipEdge>()
    internal var hostBounds by mutableStateOf(Rect.Zero)

    internal fun refresh() {
        candidates = groups.mapNotNull { engine.current(it) }
    }

    /** その段を読んだ（閉じた）。 */
    fun dismiss(tipId: String) = engine.dismiss(tipId)

    /** いま出ている段を閉じる。 */
    fun dismissCurrent() {
        current?.let { engine.dismiss(it.id) }
    }

    fun isShowing(tipId: String): Boolean = current?.id == tipId

    /**
     * ⭐ **レイアウトが動いた回数**。画面が落ち着いたかの判定に使う（[SignpostHost] 参照）。
     * ⚠️ 毎フレーム上げてはいけない——**動いていないのに動いた事になり、永久に落ち着かない**。
     */
    internal var layoutRevision by mutableIntStateOf(0)
        private set

    internal fun putRegion(tipId: String, slot: String, boundsInWindow: Rect) {
        val key = TipRegionKey(tipId, slot)
        if (regions[key] == boundsInWindow) return
        regions[key] = boundsInWindow
        layoutRevision++
    }

    internal fun removeRegion(tipId: String, slot: String) {
        regions.remove(TipRegionKey(tipId, slot))
    }

    internal fun hasRegion(tipId: String, slot: String): Boolean =
        regions.containsKey(TipRegionKey(tipId, slot))

    /** その段の**対象**（カード以外）が1つでも貼られているか。 */
    internal fun hasAnyAnchor(tipId: String): Boolean =
        regions.keys.any { it.tipId == tipId && it.slot != CardSlot }

    /** その段の領域を**ホスト座標**で合成して返す。無ければ null。 */
    internal fun holeFor(tipId: String): Rect? {
        // ⚠️⚠️ **クリップされた領域を union に混ぜない。** スクロールで画面外へ出た要素の
        // `boundsInWindow()` は Rect.Zero を返すので、そのまま合成すると
        // **穴が画面左上まで広がる**（＝関係ないところが明るく開く）。
        val mine = regions.filterKeys { it.tipId == tipId }.values
            .filter { it.width > 0f && it.height > 0f }
        val union = unionOf(mine.toList()) ?: return null
        val local = union.translate(-hostBounds.left, -hostBounds.top)
        // ⚠️ 完全にクリップされている（スクロールで画面外）ときは出さない。
        if (local.width <= 0f || local.height <= 0f) return null
        return local
    }
}

@Composable
fun rememberSignpostState(engine: TipEngine, vararg groups: TipGroup): SignpostState {
    val key = groups.joinToString(",") { it.id }
    val state = remember(engine, key) { SignpostState(engine, groups.toList()) }
    DisposableEffect(state) {
        state.refresh()
        val unsubscribe = engine.addChangeListener { state.refresh() }
        onDispose { unsubscribe() }
    }
    return state
}

internal val LocalSignpost = compositionLocalOf<SignpostState?> { null }

/** ホストの内側で「いま出ている段」を読む。⚠️ ホストの外では null。 */
@Composable
fun currentSignpostTip(): Tip? = LocalSignpost.current?.current

/**
 * ホストの内側で「この画面の次の段」を読む。**まだ画面に見えていなくても返る。**
 *
 * ⭐⭐ **アンカーが画面外にある段を、見える位置まで運ぶために使う。**
 * ⚠️⚠️ ここで [currentSignpostTip] を使ってはいけない——
 * **見えないから出ない → 出ないから運ばない**のデッドロックになる
 * （2026-08-20 に報告書画面の「登録する」で実際に起きた）。
 */
@Composable
fun pendingSignpostTip(): Tip? = LocalSignpost.current?.pending

@Composable
private fun requireSignpost(who: String): SignpostState =
    LocalSignpost.current ?: error("$who は SignpostHost の内側でだけ使えます")

/**
 * 画面ひとつを包む。**幕（スクリム）と浮かぶカードはここが描く。**
 *
 * ⭐⭐ **契約: ヒントが出ている間、その段が指しているもの以外は操作できない。**
 * ⚠️⚠️ **この契約を呼び出し側の善意に任せない**のが、この形にした理由。
 * 以前は「対象のコントロールを `enabled = false` にするのは呼び出し側の責任」としていたが、
 * それは 6 画面へ `enabled = !blocked` を書き写す作業になり、**書き忘れても静かに壊れる**
 * （契約が破れていることがコンパイルでもテストでも分からない）。
 * いまは**幕が構造的に保証する**ので、呼び出し側に書くことは何も無い。
 *
 * ⭐ 幕は「暗く描くレイヤー」と「触らせないレイヤー」に分かれている（[scrimBlockers] 参照）。
 */
@Composable
fun SignpostHost(
    state: SignpostState,
    modifier: Modifier = Modifier,
    backBehavior: TipBackBehavior = TipBackBehavior.CloseTip,
    settleMillis: Long = DefaultSettleMillis,
    maxWaitMillis: Long = DefaultMaxWaitMillis,
    content: @Composable () -> Unit,
) {
    val colors = tipColors()
    val scrimStyle = colors.scrim
    val density = LocalDensity.current
    val blurPx = with(density) { scrimStyle.blurRadius.toPx() }

    // ⭐ すりガラス用に、画面の内容を**もう一度**記録しておく置き場
    // （ぼかしはレイヤーの性質なので、鮮明な絵とぼけた絵を同じレイヤーからは出せない）。
    val blurLayer = rememberGraphicsLayer()

    // ⭐⭐ **画面の他の要素が描き終わるまでヒントを出さない。**
    // ⚠️ 出したままだと**UI が組み上がる前にヒントが先に現れて不自然**に見える
    // （2026-08-20 bathtimefish。iOS には `tipsPaused` として同型の手当てが入っていた）。
    // ⭐ 合図は**アンカーの位置が動かなくなったこと**——固定の待ち時間だと画面ごとに
    // 当たり外れが出るが、アンカーは毎フレーム自己申告しているので「止まった」が分かる。
    // ⚠️ 画面に入るたび1回だけ（`Unit` キー）。**段ごとには待たない**——
    // 同じ画面で次の段へ進むたびに待つと、幕が出たり消えたりして逆に落ち着かない。
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val startedAt = withFrameMillis { it }
        var seen = state.layoutRevision
        while (true) {
            delay(settleMillis)
            val now = state.layoutRevision
            if (now == seen) break
            seen = now
            // ⚠️ 動き続ける画面で**永久に出ない**ことがないよう、待ちには上限を置く。
            if (withFrameMillis { it } - startedAt >= maxWaitMillis) break
        }
        settled = true
    }

    val liveTip = state.current
    val liveHole = liveTip?.let { state.holeFor(it.id) }

    // ⭐ [TipScrimStyle.fadeBetweenTips] のとき、段が替わる間だけ「出さない」に倒す。
    // ⚠️ 差し替えを待つ間、**古い段のカードと穴を描き続ける**（`lastPainted`）——
    // ここで新しい段の絵に切り替えてしまうと、消える途中で中身だけ入れ替わって見える。
    var swapping by remember { mutableStateOf(false) }
    var shownTipId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(liveTip?.id, scrimStyle.fadeBetweenTips) {
        val next = liveTip?.id
        if (shownTipId == next) return@LaunchedEffect
        if (scrimStyle.fadeBetweenTips && shownTipId != null && next != null) {
            swapping = true
            delay(scrimStyle.fadeMillis.toLong())
            swapping = false
        }
        shownTipId = next
    }

    val visible = settled && !swapping &&
        liveTip != null && liveHole != null &&
        shownTipId == liveTip.id &&
        state.hostBounds.width > 0f

    // ⭐⭐ **フェードするのは見た目だけ。** 触れる/触れないは [visible] で即座に切り替える。
    // ⚠️⚠️ 触れないほうもフェードに合わせると、**閉じた直後の 0.3 秒がまた空振りになる**
    // ——今回の作り替えでいちばん潰したかった挙動を、演出で作り直すことになる。
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = scrimStyle.fadeMillis),
        label = "signpost-scrim",
    )

    // フェードアウトの間も描き続けるため、最後に**見えていた**段と穴を覚えておく。
    // ⚠️ 条件を `visible` にすること——「liveTip があるか」で覚えると、
    // 段の差し替え中に**新しい段の絵へ入れ替わってしまう**（消える途中で中身が変わる）。
    var lastPainted by remember { mutableStateOf<Pair<Tip, Rect>?>(null) }
    // ⚠️ `visible` の中で null を除いてあるので、ここは smart cast が効く。
    val paintable = if (visible) liveTip to liveHole else null
    SideEffect {
        if (paintable != null) lastPainted = paintable
    }
    val shown = paintable ?: lastPainted
    // ⭐ [TipForm.Bare] は**ただ指すだけ**——幕も遮りも出さない（記録もしない）。
    val dimming = shown?.first?.form != TipForm.Bare

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { state.hostBounds = it.boundsInWindow() },
    ) {
        Box(
            modifier = Modifier.drawWithContent {
                // ⚠️ ぼかすときだけ2度描く（ぼかし無しの既定では余計な負荷を掛けない）。
                if (dimming && blurPx > 0f && alpha > 0f) {
                    blurLayer.record { this@drawWithContent.drawContent() }
                }
                drawContent()
            },
        ) {
            CompositionLocalProvider(LocalSignpost provides state) {
                content()
            }
        }

        val paintedTip = shown?.first
        val paintedHole = shown?.second
        if (paintedTip != null && paintedHole != null && state.hostBounds.width > 0f && alpha > 0f) {
            val screen = Size(state.hostBounds.width, state.hostBounds.height)
            val inline = state.hasRegion(paintedTip.id, CardSlot)

            // ⭐ content の**後**に登録するので、画面側の BackHandler より優先される。
            // ⚠️ 先に置くと画面側（確認ダイアログ等）に取られ、戻るでヒントが閉じない。
            if (visible && backBehavior != TipBackBehavior.PassThrough) {
                BackHandler(enabled = true) {
                    if (backBehavior == TipBackBehavior.CloseTip) state.dismissCurrent()
                }
            }

            val cardMarginPx = with(density) { CardMargin.toPx() }
            val caretWidthPx = with(density) { CaretWidth.toPx() }
            val caretHeightPx = with(density) { CaretHeight.toPx() }
            val holePaddingPx = with(density) { HolePadding.toPx() }
            val holeCornerPx = with(density) { HoleCorner.toPx() }
            val outlineWidthPx = with(density) { scrimStyle.holeOutlineWidth.toPx() }
            val cardWidthPx = (screen.width - cardMarginPx * 2).coerceAtLeast(0f)

            var cardHeightPx by remember(paintedTip.id) { mutableFloatStateOf(0f) }

            val placement = computeTipPlacement(
                anchor = paintedHole,
                screen = screen,
                preferredEdge = state.edges[paintedTip.id] ?: TipEdge.Above,
                cardWidth = cardWidthPx,
                cardHeight = cardHeightPx,
                cardMargin = cardMarginPx,
                holePadding = holePaddingPx,
                caretWidth = caretWidthPx,
                caretHeight = caretHeightPx,
            )

            // ① 描くだけのレイヤー。⭐ ポインタ入力を持たないので**タップを遮らない**。
            if (dimming) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        RoundRect(placement.hole, CornerRadius(holeCornerPx, holeCornerPx)),
                    )
                }
                // すりガラス: **穴の外側だけ**、ぼかした複製を重ねる（穴の中は素のまま）。
                if (blurPx > 0f) {
                    blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                    blurLayer.alpha = alpha
                    clipPath(outside) { drawLayer(blurLayer) }
                }
                drawPath(outside, scrimStyle.color, alpha = alpha)
                // ⚠️ 白い幕では輪郭が無いと「どこが押せるか」が読めない。
                if (scrimStyle.holeOutline != Color.Transparent) {
                    drawRoundRect(
                        color = scrimStyle.holeOutline,
                        topLeft = placement.hole.topLeft,
                        size = placement.hole.size,
                        cornerRadius = CornerRadius(holeCornerPx, holeCornerPx),
                        alpha = alpha,
                        style = Stroke(width = outlineWidthPx),
                    )
                }
            }
            }

            // ② 触らせないレイヤー。**穴の中には何も置かない**＝タップは下へ届く。
            // ⭐⭐ **膜が見えている間（`alpha > 0`）は触らせない**（2026-08-20 bathtimefish）。
            // ⚠️ 以前は「出ている段があるときだけ」塞いでいたが、それだと
            // **膜が見えているのにタップが下へ抜ける**という中途半端な状態ができた
            // （フェード中・段の差し替え中）。規則を「見えている＝触れない」に単純化した。
            // ⚠️ 代償: 段の差し替え（フェードアウト→イン）の間も塞がるので、
            // **その 1.4 秒はタップが効かない**。ここは体感で選んだ側。
            //
            // ⚠️⚠️ **幕のタップでは閉じない**（2026-08-20 後半・bathtimefish の指定）。
            // 同日前半は「幕でも閉じる」にしていたが撤回した。閉じる口は
            // **カード**と**その段が指している対象**の2つだけ。
            // ⭐ 撤回しても元の動機（✕ を見落として対象のほうをタップする人が多い）は
            // 満たされている——**対象をタップすれば閉じる**のは変わっていないため。
            // ⭐ 副作用として、**幕の誤タップで段が飛ぶ**（＝二度と出ない）事故が減る。
            if (dimming && alpha > 0f) {
                scrimBlockers(placement.hole, screen).forEach { r ->
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                            .size(
                                width = with(density) { r.width.toDp() },
                                height = with(density) { r.height.toDp() },
                            )
                            // ⚠️ 閉じないが**遮りはする**（下のUIへタップを通さない）。
                            .blockGestures(),
                    )
                }
            }

            // ③ 浮かぶカード。inline カードが置かれている段では出さない（二重になる）。
            if (!inline) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                placement.cardTopLeft.x.roundToInt(),
                                placement.cardTopLeft.y.roundToInt(),
                            )
                        }
                        .width(with(density) { cardWidthPx.toDp() })
                        // ⚠️ 高さを測る前に置くと1フレームだけ違う位置に出る。測るまで隠す。
                        .alpha(if (cardHeightPx > 0f) alpha else 0f)
                        .onGloballyPositioned { cardHeightPx = it.size.height.toFloat() },
                ) {
                    Column {
                        if (placement.edge == TipEdge.Below) {
                            TipCaret(
                                pointingDown = false,
                                offsetX = placement.caretLeftInCard.roundToInt(),
                                color = colors.container,
                            )
                        }
                        TipSurface(
                            tip = paintedTip,
                            onTap = { state.dismissCurrent() },
                            icon = null,
                            colors = colors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (placement.edge == TipEdge.Above) {
                            TipCaret(
                                pointingDown = true,
                                offsetX = placement.caretLeftInCard.roundToInt(),
                                color = colors.container,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「この段が指しているのはここ」とホストへ伝える。**明るく残り、そのまま操作できる。**
 *
 * ⭐⭐ **タップされたらその段を閉じる。ただしイベントは consume しない**ので、
 * 下のコントロールは**普段どおり効く**（言われたとおりタップした人が空振りしない）。
 * ⚠️⚠️ **閉じるほうを省くと ordered な群が止まる。** 未読の先頭しか出さない実装なので、
 * 「押してください」と言われて押した段が未読のまま残ると、**その先が永久に出ない**。
 *
 * ⚠️ ポインタの観測は**出ていないときも外さない**。ジェスチャの途中で modifier の
 * 構成が変わると、進行中のタップが取りこぼされる。
 *
 * @param slot ⭐ **1つの段が複数の対象を指すとき**に、貼り口ごとに別の名前を付ける
 *   （例: 画面上部を `"anchor"`、カメラ映像を `"camera"`）。
 *   穴は**同じ段のすべての領域をまとめた矩形**になる。
 *   ⚠️ 離れた場所を2つ指すと、その**間も明るく開く**（矩形でまとめるため）。
 *   隣り合っているものに使うこと。
 */
@Composable
fun Modifier.tipAnchor(
    tipId: String,
    edge: TipEdge = TipEdge.Above,
    enabled: Boolean = true,
    slot: String = DefaultAnchorSlot,
): Modifier {
    val state = requireSignpost("Modifier.tipAnchor")
    DisposableEffect(state, tipId, edge, enabled, slot) {
        if (enabled) state.edges[tipId] = edge else state.removeRegion(tipId, slot)
        onDispose {
            state.removeRegion(tipId, slot)
            if (!state.hasAnyAnchor(tipId)) state.edges.remove(tipId)
        }
    }
    return this
        .onGloballyPositioned {
            // ⭐ **貼らない条件のときは登録しない**＝その段は出ない。
            // 「いま開いている〇〇が××のときだけ」のような per-item の条件は
            // ルール（アプリ全体に1つのパラメータ）では表せないので、この形で満たす。
            if (enabled) state.putRegion(tipId, slot, it.boundsInWindow())
        }
        .pointerInput(tipId, slot) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // ⚠️ **consume しない**。下のコントロールへそのまま届かせる。
                    if (state.isShowing(tipId) &&
                        state.hasRegion(tipId, slot) &&
                        event.changes.any { it.changedToUpIgnoreConsumed() }
                    ) {
                        state.dismiss(tipId)
                    }
                }
            }
        }
}

/**
 * 流れの中に置く inline カード。**見る領域**を説明する段に使う
 * （押すものを指す段は [tipAnchor] だけでよい＝カードは浮く）。
 *
 * ⭐ このカード自身も穴の一部になる（暗くならず、タップで閉じられる）。
 */
@Composable
fun TipCard(tipId: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val state = requireSignpost("TipCard")
    val tip = state.current?.takeIf { it.id == tipId } ?: return
    val colors = tipColors()
    DisposableEffect(tipId) {
        onDispose { state.removeRegion(tipId, CardSlot) }
    }
    TipSurface(
        tip = tip,
        onTap = { state.dismiss(tipId) },
        icon = icon,
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { state.putRegion(tipId, CardSlot, it.boundsInWindow()) },
    )
}

/**
 * **すべてのジェスチャを Initial パスで握り潰す**
 * （スクロールやスワイプも止める＝「読む前に先へ進む」が起きない）。
 *
 * @param onTap タップだったときに呼ぶ。**null なら遮るだけで何もしない**——
 *   幕がこれ（触れないが閉じもしない）。⚠️ **遮ることと閉じることは別の関心。**
 */
private fun Modifier.blockGestures(onTap: (() -> Unit)? = null): Modifier = this.pointerInput(onTap) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            var down = Offset.Unspecified
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (down == Offset.Unspecified) down = change.position
                if ((change.position - down).getDistance() > slop) moved = true
                val up = event.changes.any { it.changedToUpIgnoreConsumed() }
                // ⚠️ **Initial パスで全部 consume する**。子より先に取らないと素通りする。
                event.changes.forEach { it.consume() }
                if (up) {
                    // 指を滑らせただけ（スクロールしようとした）ときは閉じない。
                    if (!moved) onTap?.invoke()
                    break
                }
            }
        }
    }
}

/** カードからアンカーへ伸びる三角。**穴の中心へ水平にずらして置く**（[offsetX] は px）。 */
@Composable
private fun TipCaret(pointingDown: Boolean, offsetX: Int, color: Color) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .offset { IntOffset(offsetX, 0) }
                .size(width = CaretWidth, height = CaretHeight),
        ) {
            val path = Path().apply {
                if (pointingDown) {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                } else {
                    moveTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                }
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * カードの見た目。inline と浮かぶカードで**同じ見た目**にする（同じ物だと分かるように）。
 *
 * ⚠️⚠️ **閉じるボタン（✕）は置かない**（2026-08-20 の決定）。実機のユーザーテストで
 * **✕ を見落として対象のほうをタップする人が続出した**ため、**カードでも対象でも幕でも、
 * どこを触っても進める**形にした。⚠️ 自動では消さない（読み終わる前に消えない）ことは維持。
 */
@Composable
private fun TipSurface(
    tip: Tip,
    onTap: () -> Unit,
    icon: ImageVector?,
    colors: TipColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = colors.container,
        contentColor = colors.content,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.blockGestures(onTap = onTap),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (icon != null) {
                androidx.compose.material3.Icon(icon, contentDescription = null)
                Spacer(Modifier.width(10.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (tip.title != null) {
                    // ⚠️ **現場で読む文字なので小さくしない**（2026-08-18 の実機テストで指摘）。
                    Text(tip.title, style = MaterialTheme.typography.titleMedium)
                }
                if (tip.message != null) {
                    Text(tip.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
