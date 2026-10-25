package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 戦績の計算に使う1回の起床（Gamification の入力） */
data class Record(
    val id: String,
    val uid: String,
    val nickname: String,
    val durationSec: Long,
    val ringAt: Long,
    val resleep: Boolean,
)

/** 1回の起床（アラームが鳴ってからベッドを出るまで） */
data class WakeEntry(
    val id: String,          // M5Stack の記録 id（M5Stack 再起動で戻るので ringAt と組み合わせて使う）。デモは "demo-n"
    val ringAt: Long,
    val awakeAt: Long,
    val durationSec: Long,
    val resleep: Boolean,
    val source: String,      // SOURCE_M5 / SOURCE_DEMO
) {
    fun toRecord() = Record(id = id, uid = "", nickname = "", durationSec = durationSec, ringAt = ringAt, resleep = resleep)
}

/**
 * 起床記録を CSV ファイル wake_records.csv に保存する（戦績・XP・バッジの計算元。CsvFile の形式）
 * - 1行 = 1回の起床。古い順
 *     id,鳴動,起床,離床秒,二度寝,source
 *     12,2026-10-25 07:30:00,2026-10-25 07:31:12,72,0,m5
 * - source: m5 = M5Stack から届いた記録 / demo = デモファイルから取り込んだ記録
 * - 同じ記録（鳴動・起床の時刻が同じ）が2回届いても1件にする
 */
object WakeStore {
    private const val TAG = "WakeStore"
    const val SOURCE_M5 = "m5"
    const val SOURCE_DEMO = "demo"
    private val csv = CsvFile(
        "wake_records.csv", "id,鳴動,起床,離床秒,二度寝,source",
        oldHeaders = setOf("id,鳴動,起床,離床秒,二度寝,source,synced"),
    )

    private val _records = MutableStateFlow<List<WakeEntry>>(emptyList())
    /** 新しい順 */
    val records: StateFlow<List<WakeEntry>> = _records
    private var loadedModified = -1L

    fun file(context: Context) = csv.file(context)

    /** ファイルを読む（前回読んだ後に変わっていなければ何もしない） */
    @Synchronized
    fun load(context: Context) {
        val modified = csv.lastModified(context)
        if (modified == loadedModified) return
        loadedModified = modified
        val rows = try {
            csv.read(context)
        } catch (e: Exception) {
            Log.w(TAG, "read failed: $e")
            return
        }
        _records.value = rows
            .mapNotNull { c ->
                runCatching { parseRow(c) }.getOrElse {
                    Log.w(TAG, "skip line: $c ($it)")
                    null
                }
            }
            .sortedByDescending { it.ringAt }
    }

    /** 1件追加する。同じ記録がすでにあれば false */
    @Synchronized
    fun add(context: Context, entry: WakeEntry): Boolean {
        load(context)
        if (_records.value.any { it.ringAt == entry.ringAt && it.awakeAt == entry.awakeAt }) return false
        save(context, (_records.value + entry).sortedByDescending { it.ringAt })
        Log.i(TAG, "added ${entry.source} ${entry.id}")
        return true
    }

    fun add(context: Context, result: WakeResult): Boolean = add(
        context,
        WakeEntry(result.id.toString(), result.ringAt, result.awakeAt, result.durationSec, result.resleep, SOURCE_M5),
    )

    @Synchronized
    fun clear(context: Context): Int {
        load(context)
        val n = _records.value.size
        save(context, emptyList())
        return n
    }

    private fun parseRow(c: List<String>) = WakeEntry(
        id = c[0],
        ringAt = CsvFile.parseTime(c[1]),
        awakeAt = CsvFile.parseTime(c[2]),
        durationSec = c[3].toLong(),
        resleep = c[4] == "1",
        source = c.getOrElse(5) { SOURCE_M5 }.ifEmpty { SOURCE_M5 },
    )

    private fun toRow(e: WakeEntry) = listOf(
        e.id, CsvFile.formatTime(e.ringAt), CsvFile.formatTime(e.awakeAt), e.durationSec.toString(),
        if (e.resleep) "1" else "0", e.source,
    )

    private fun save(context: Context, list: List<WakeEntry>) {
        csv.write(context, list.sortedBy { it.ringAt }.map(::toRow))
        _records.value = list
        loadedModified = csv.lastModified(context)
    }
}
