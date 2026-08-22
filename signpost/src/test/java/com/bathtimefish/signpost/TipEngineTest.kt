package com.bathtimefish.signpost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ **これが CI で走る唯一の網**（Android の CI は `lint test assembleDebug` で
 * instrumented は1本も走らない）。だから中核は Compose 非依存にしてある。
 *
 * 見るのは「同時に1枚」「順序」「解禁条件」「既読の永続」「リセット」——
 * ヒント機構の壊れ方はほぼ全部ここに落ちる。画面との結線だけが実機の仕事。
 */
class TipEngineTest {

    private val a1 = Tip("a1", "1枚目")
    private val a2 = Tip("a2", "2枚目")
    private val a3 = Tip("a3", "3枚目")

    private fun engine(store: TipStore = InMemoryTipStore(), suppressed: Boolean = false) =
        TipEngine(store).apply { if (suppressed) setSuppressed(true) }

    @Test
    fun `ordered は同時に1枚しか出さない`() {
        val group = tipGroup("g", TipOrder.Ordered, a1, a2, a3)
        val engine = engine()

        assertEquals(a1, engine.current(group))
        // ⭐ 2枚目は「出ていない」ことまで見る（1枚目が出ていることの確認だけでは足りない）。
        assertFalse(engine.current(group)?.id == a2.id)
    }

    @Test
    fun `ordered は閉じた順に次へ進み、最後まで行くと null`() {
        val group = tipGroup("g", TipOrder.Ordered, a1, a2, a3)
        val engine = engine()

        engine.dismiss(a1.id)
        assertEquals(a2, engine.current(group))
        engine.dismiss(a2.id)
        assertEquals(a3, engine.current(group))
        engine.dismiss(a3.id)
        assertNull(engine.current(group))
    }

    @Test
    fun `⭐ ordered は先頭の条件が未達なら、その先を飛ばさずに止まる`() {
        // ⚠️ これが「条件の厳しい段を群に混ぜると以降が出なくなる」の実体。
        // 仕様として意図した挙動なので、テストで固定しておく。
        val gated = Tip("gated", rules = listOf(TipRule.countAtLeast("posts", 3)))
        val group = tipGroup("g", TipOrder.Ordered, gated, a2)
        val engine = engine()

        assertNull(engine.current(group))
        repeat(3) { engine.increment("posts") }
        assertEquals(gated, engine.current(group))
    }

    @Test
    fun `unordered は条件を満たしていない段を飛ばす`() {
        val gated = Tip("gated", rules = listOf(TipRule.flagIsSet("done")))
        val group = tipGroup("g", TipOrder.Unordered, gated, a2)
        val engine = engine()

        assertEquals(a2, engine.current(group))
        engine.dismiss(a2.id)
        assertNull(engine.current(group))
        engine.setFlag("done")
        assertEquals(gated, engine.current(group))
    }

    @Test
    fun `countAtLeast は閾値ちょうどで解禁される`() {
        val gated = Tip("gated", rules = listOf(TipRule.countAtLeast("posts", 3)))
        val group = tipGroup("g", TipOrder.Ordered, gated)
        val engine = engine()

        engine.increment("posts")
        engine.increment("posts")
        assertNull(engine.current(group))
        engine.increment("posts")
        assertEquals(gated, engine.current(group))
    }

    @Test
    fun `⭐ 出してはいけない状況では全段を止める`() {
        val group = tipGroup("g", TipOrder.Ordered, a1, a2)
        val engine = engine(suppressed = true)

        assertNull(engine.current(group))
        // ⭐ 陽性対照: 抑止を外せば出る（＝「そもそも出ない設定」で緑になっていない）。
        engine.setSuppressed(false)
        assertEquals(a1, engine.current(group))
    }

    @Test
    fun `⭐⭐ 抑止の理由は独立していて、片方を下ろしても もう片方は効いたまま`() {
        // ⚠️ 単一の Boolean にすると**後から来た書き手が前の抑止を黙って解除する**。
        // 「検証用に止める」が「閲覧専用かどうか」の再評価に毎回上書きされて
        // 効かなくなる不具合を実際に踏んだので、理由ごとに持つ形をここで固定する。
        val group = tipGroup("g", TipOrder.Ordered, a1)
        val engine = engine()

        engine.setSuppressed(true, reason = "read-only")
        engine.setSuppressed(true, reason = "testing")
        assertNull(engine.current(group))

        engine.setSuppressed(false, reason = "read-only")
        assertNull(engine.current(group))

        engine.setSuppressed(false, reason = "testing")
        assertEquals(a1, engine.current(group))
    }

    @Test
    fun `既読は store に残り、reset で未読に戻る`() {
        val group = tipGroup("g", TipOrder.Ordered, a1, a2)
        val store = InMemoryTipStore()

        engine(store).dismiss(a1.id)
        // 別インスタンスから見ても既読（＝状態は store が持っている）。
        assertEquals(a2, engine(store).current(group))

        engine(store).reset()
        assertEquals(a1, engine(store).current(group))
    }

    @Test
    fun `reset はパラメータも戻す`() {
        val engine = engine()
        repeat(3) { engine.increment("posts") }
        engine.setFlag("done")

        engine.reset()

        assertEquals(0, engine.count("posts"))
        assertFalse(engine.flag("done"))
    }


    @Test
    fun `状態が変わるとリスナーが呼ばれ、解除できる`() {
        val engine = engine()
        var calls = 0
        val unsubscribe = engine.addChangeListener { calls++ }

        engine.dismiss(a1.id)
        engine.increment("posts")
        engine.setFlag("done")
        assertEquals(3, calls)

        // ⚠️ 変化が無いときは鳴らさない（無駄な再構成を呼ばない）。
        engine.dismiss(a1.id)
        engine.setFlag("done", true)
        assertEquals(3, calls)

        unsubscribe()
        engine.reset()
        assertEquals(3, calls)
    }

    /**
     * ⚠️⚠️ **押させる段を「押させたまま閉じない」と、その先が永久に出ない。**
     *
     * ordered は**未読の先頭**しか返さないので、「◯◯をタップしてください」と書いた段が
     * 未読のまま残ると、**言われたとおりにした人だけツアーが止まる**。
     * だから対象をタップしたときは、その操作を通しつつ**その段を閉じる**必要がある
     * （`Modifier.tipAnchor` が consume せずに観測して dismiss しているのはこのため）。
     */
    @Test
    fun `⭐⭐ 押させた段を閉じないと、その先の段は永久に出ない`() {
        val store = InMemoryTipStore()
        val engine = TipEngine(store)
        val group = tipGroup(
            "b",
            TipOrder.Ordered,
            Tip("b2", title = "押して話す"),
            Tip("b4", title = "できたら報告する"),
        )

        assertEquals("b2", engine.current(group)?.id)
        // 対象を押した（＝アプリ側の操作は進んだ）が、段を閉じなかった場合。
        assertEquals("b2", engine.current(group)?.id)

        // 閉じて初めて次へ進む。
        engine.dismiss("b2")
        assertEquals("b4", engine.current(group)?.id)
    }

    /**
     * ⭐⭐ **数え上げの段は「閉じる」ではなく「数」で入れ替わる。**
     * ⚠️ ordered に置くと**閉じないと次へ進まない**ので、投稿しても文言が変わらない。
     * だから Unordered + `countEquals` にしてある（2026-08-20）。
     */
    @Test
    fun `⭐⭐ 数え上げの段はタップせずカウンタで入れ替わる`() {
        val store = InMemoryTipStore()
        val engine = TipEngine(store)
        val group = tipGroup(
            "chat",
            TipOrder.Unordered,
            Tip("chat-3", rules = listOf(TipRule.countEquals("chat", 0))),
            Tip("chat-2", rules = listOf(TipRule.countEquals("chat", 1))),
            Tip("chat-1", rules = listOf(TipRule.countEquals("chat", 2))),
        )

        assertEquals("chat-3", engine.current(group)?.id)
        engine.increment("chat")
        // ⭐ 閉じていないのに次へ入れ替わる。
        assertEquals("chat-2", engine.current(group)?.id)
        engine.increment("chat")
        assertEquals("chat-1", engine.current(group)?.id)
        engine.increment("chat")
        // 3件そろったら数え上げは役目を終える（この群からは何も出ない）。
        assertNull(engine.current(group))
    }

    @Test
    fun `⚠️ 同じ段を ordered に置くとカウンタが増えても入れ替わらない`() {
        // ⭐ 「なぜ Unordered なのか」を対照で固定する（次の人が ordered に直さないように）。
        val engine = TipEngine(InMemoryTipStore())
        val ordered = tipGroup(
            "chat-ordered",
            TipOrder.Ordered,
            Tip("chat-3", rules = listOf(TipRule.countEquals("chat", 0))),
            Tip("chat-2", rules = listOf(TipRule.countEquals("chat", 1))),
        )

        assertEquals("chat-3", engine.current(ordered)?.id)
        engine.increment("chat")
        // ⚠️ 未読の先頭が条件を外れただけで、次へは進まない＝**何も出なくなる**。
        assertNull(engine.current(ordered))
    }

    @Test
    fun `⭐ 群をまたいでも独立して進む`() {
        // 群は画面ごとに切る前提。片方を消化しても、もう片方の進み方は変わらない。
        val screen1 = tipGroup("s1", TipOrder.Ordered, a1, a2)
        val screen2 = tipGroup("s2", TipOrder.Ordered, a3)
        val engine = engine()

        engine.dismiss(a1.id)
        assertEquals(a2, engine.current(screen1))
        assertEquals(a3, engine.current(screen2))
        assertTrue(engine.current(screen2)?.id == a3.id)
    }
}
