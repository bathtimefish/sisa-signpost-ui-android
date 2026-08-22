package com.bathtimefish.signpost

import android.content.SharedPreferences

/**
 * 既読とパラメータの置き場。**端末ローカル**が既定の想定。
 *
 * ⚠️ **既読はアカウントではなく端末の性質**。「もう知っている」はサインアウトで失われない。
 */
interface TipStore : TipParameters {
    fun isDismissed(tipId: String): Boolean
    fun markDismissed(tipId: String)
    fun setFlag(key: String, value: Boolean)
    fun setCount(key: String, value: Int)

    /** 全部を未読へ戻す。⚠️ **検証用の口を必ず持つこと**——無いと実機確認が1台1回しかできない。 */
    fun reset()
}

/** テストとプレビュー用。 */
class InMemoryTipStore : TipStore {
    private val dismissed = mutableSetOf<String>()
    private val flags = mutableMapOf<String, Boolean>()
    private val counts = mutableMapOf<String, Int>()

    override fun isDismissed(tipId: String): Boolean = tipId in dismissed
    override fun markDismissed(tipId: String) { dismissed += tipId }
    override fun flag(key: String): Boolean = flags[key] ?: false
    override fun count(key: String): Int = counts[key] ?: 0
    override fun setFlag(key: String, value: Boolean) { flags[key] = value }
    override fun setCount(key: String, value: Int) { counts[key] = value }
    override fun reset() { dismissed.clear(); flags.clear(); counts.clear() }
}

/**
 * `SharedPreferences` 実装。
 *
 * ⚠️⚠️ **既存の prefs を使うときは [keyPrefix] を必ず新しくすること。** 前の世代のヒントが
 * 残した `true` が、名前が同じというだけで**新しいヒントの既読として復活する**。
 * ⭐ 逆に、prefix さえ分けておけば古いキーは**消しに行かなくてよい**（削除コードのほうが事故る）。
 *
 * ⚠️ 書き込みは `apply()`（非同期）ではなく即時性を優先する場面があるので、
 * 既読だけは `commit()` にしていない点に注意——次に開くのが再起動後とは限らないが、
 * プロセスが即死しない限り `apply()` で足りる。
 */
class SharedPreferencesTipStore(
    private val prefs: SharedPreferences,
    private val keyPrefix: String = "signpost_",
) : TipStore {

    private fun seenKey(tipId: String) = "${keyPrefix}seen_$tipId"
    private fun paramKey(key: String) = "${keyPrefix}param_$key"

    override fun isDismissed(tipId: String): Boolean = prefs.getBoolean(seenKey(tipId), false)

    override fun markDismissed(tipId: String) {
        prefs.edit().putBoolean(seenKey(tipId), true).apply()
    }

    override fun flag(key: String): Boolean = prefs.getBoolean(paramKey(key), false)

    override fun count(key: String): Int = prefs.getInt(paramKey(key), 0)

    override fun setFlag(key: String, value: Boolean) {
        prefs.edit().putBoolean(paramKey(key), value).apply()
    }

    override fun setCount(key: String, value: Int) {
        prefs.edit().putInt(paramKey(key), value).apply()
    }

    /** ⚠️ **prefix の付いたキーだけ**を消す（同じ prefs にアプリの他の設定が同居しているため）。 */
    override fun reset() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(keyPrefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
