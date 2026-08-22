package com.bathtimefish.signpost

/**
 * どの段を出すかを決める中核。**Compose も Android の UI も知らない。**
 *
 * ⭐⭐ **ここが Compose 非依存であることが、このライブラリの検証戦略の前提。**
 * 「同時に1枚」「順序」「解禁条件」「既読の永続」「リセット」は全部ここで決まるので、
 * **エミュレータ無しの素の JUnit で全部書ける**。画面との結線（どこに貼ったか）だけが
 * 実機の仕事になる。逆に、ここに Compose を持ち込むと**その日から検証が実機頼み**になる。
 */
class TipEngine(
    private val store: TipStore,
) {
    /**
     * 出してはいけない理由の集合。⭐ 1つでもあれば**全段を止める**。
     *
     * ⚠️⚠️ **理由を1つの Boolean にまとめない。** 抑止の理由は独立して複数ありうる
     * （例: 閲覧専用モード / 検証のために止めている）。単一の真偽値にすると
     * **後から来た書き手が前の書き手の抑止を黙って解除する**——
     * 実際に、「検証用に止める」が「閲覧専用かどうか」の再評価で毎回上書きされて
     * 効かなくなる不具合を踏んでいる。
     */
    private val suppressReasons = mutableSetOf<String>()

    /**
     * ⭐ **抑止を UI 側の判定に散らさず、ここ1か所に持つ。** 画面ごとに `if` を書く形にすると
     * 必ずどこかで漏れるし、「なぜ出ないか」の答えが探せなくなる。
     */
    val suppressed: Boolean get() = suppressReasons.isNotEmpty()

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * 状態が変わったら呼ばれる。戻り値を呼ぶと解除。
     * ⚠️ coroutines に依存しないのは、利用者に構成を強制しないため。
     */
    fun addChangeListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyChanged() {
        // ⚠️ 反復中に解除されうるのでコピーしてから回す。
        listeners.toList().forEach { it() }
    }

    /**
     * この群でいま出す1枚（無ければ null）。
     *
     * ⭐ [TipOrder.Ordered] では**未読の先頭が条件を満たしていなければ null を返す**
     * （その先を飛ばさない）。これが「前が閉じられて初めて次」の実体であり、
     * 同時に「条件の厳しい段を群に混ぜると以降が止まる」の理由でもある。
     */
    /**
     * 抑止の理由を1つ立てる／下ろす。
     * @param reason 理由の名前。**書き手ごとに別の名前を使うこと**（上書きを防ぐため）。
     */
    fun setSuppressed(value: Boolean, reason: String = DEFAULT_SUPPRESS_REASON) {
        val changed = if (value) suppressReasons.add(reason) else suppressReasons.remove(reason)
        if (changed) notifyChanged()
    }

    companion object {
        const val DEFAULT_SUPPRESS_REASON = "default"
    }

    fun current(group: TipGroup): Tip? {
        if (suppressed) return null
        return when (group.order) {
            TipOrder.Ordered ->
                group.tips.firstOrNull { !store.isDismissed(it.id) }?.takeIf { it.isEligible() }
            TipOrder.Unordered ->
                group.tips.firstOrNull { !store.isDismissed(it.id) && it.isEligible() }
        }
    }

    // ⚠️ **`current(group, anyOf)` / `isShowing(group, anyOf)` は 2026-08-20 に削除した。**
    // どちらも「どの段が出ているかを呼び出し側が見て、自分でコントロールを止める」という
    // **撤回した設計**のための API だった。いまは幕（`SignpostHost`）が構造的に止めるので、
    // 画面側が「出ているか」を知る必要は無い（段と貼り付け口は tip id で結ぶ）。
    // ⭐ 残しておくと、次の人が grep で最初に当てて**古いほうの作り方に戻る**。

    private fun Tip.isEligible(): Boolean = rules.all { it.isSatisfied(store) }

    /** 読んで閉じた。**即座に永続する**（次に開くのが再起動後とは限らない）。 */
    fun dismiss(tipId: String) {
        if (store.isDismissed(tipId)) return
        store.markDismissed(tipId)
        notifyChanged()
    }

    /** 一度きりの事実が起きた（例: 一周した）。 */
    fun setFlag(key: String, value: Boolean = true) {
        if (store.flag(key) == value) return
        store.setFlag(key, value)
        notifyChanged()
    }

    /** 数える事実が1つ増えた（例: 投稿した）。 */
    fun increment(key: String) {
        store.setCount(key, store.count(key) + 1)
        notifyChanged()
    }

    fun count(key: String): Int = store.count(key)

    fun flag(key: String): Boolean = store.flag(key)

    /** 全部を未読へ戻す（検証用）。 */
    fun reset() {
        store.reset()
        notifyChanged()
    }
}
