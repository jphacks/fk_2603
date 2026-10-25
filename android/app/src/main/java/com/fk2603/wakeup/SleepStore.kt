package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 1回の睡眠（ベッドに入ってから、20分以上ベッドを離れるまで） */
data class SleepRecord(
    val startAt: Long,
    val endAt: Long,            // 起床時刻（最後にベッドを出た時刻）。0 = 記録中、-1 = 起床が届かなかった
    val returnsAt: List<Long>,  // アラーム後にベッドに戻った時刻（二度寝）
) {
    val resleepCount get() = returnsAt.size
    val isSleeping get() = endAt == 0L
}

/**
 * 睡眠記録を CSV ファイル sleep_records.csv に保存する（CsvFile の形式）
 * - 1行 = 1回の睡眠。古い順
 *     就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻
 *     2026-10-24 23:00:00,2026-10-25 07:05:00,485,1,2026-10-25 06:40:00
 *   起床が空欄 = 記録中、「不明」= 起床が届かなかった。戻った時刻は ; 区切り
 * - ファイルが外で書き換えられたら、次に読むときに読み直す
 * M5Stack から届く S/R/E のイベントで組み立てる。同じイベントが2回届いても結果は変わらない
 */
object SleepStore {
    private const val TAG = "SleepStore"
    private const val UNKNOWN = "不明"
    private val csv = CsvFile("sleep_records.csv", "就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻")

    private val _records = MutableStateFlow<List<SleepRecord>>(emptyList())
    /** 新しい順 */
    val records: StateFlow<List<SleepRecord>> = _records
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
            .sortedByDescending { it.startAt }
    }

    private fun parseRow(c: List<String>): SleepRecord {
        val end = c.getOrElse(1) { "" }
        return SleepRecord(
            startAt = CsvFile.parseTime(c[0]),
            endAt = when (end) {
                "" -> 0
                UNKNOWN -> -1
                else -> CsvFile.parseTime(end)
            },
            returnsAt = c.getOrElse(4) { "" }.split(";").filter { it.isNotBlank() }.map { CsvFile.parseTime(it) },
        )
    }

    private fun toRow(r: SleepRecord): List<String> = listOf(
        CsvFile.formatTime(r.startAt),
        when {
            r.isSleeping -> ""
            r.endAt < 0 -> UNKNOWN
            else -> CsvFile.formatTime(r.endAt)
        },
        if (r.endAt > 0) ((r.endAt - r.startAt) / 60).toString() else "",
        r.resleepCount.toString(),
        r.returnsAt.joinToString(";") { CsvFile.formatTime(it) },
    )

    @Synchronized
    fun apply(context: Context, event: SleepEvent) {
        load(context)
        val list = _records.value.toMutableList()
        val open = list.indexOfFirst { it.isSleeping }
        when (event.type) {
            'S' -> {
                if (list.any { it.startAt == event.at }) return
                // 起床が届かないまま次の就寝が来た（M5Stack の電源が切れたなど）
                if (open >= 0) list[open] = list[open].copy(endAt = -1)
                list.add(0, SleepRecord(event.at, 0, emptyList()))
            }
            'R' -> {
                if (list.any { event.at in it.returnsAt }) return
                if (open >= 0) {
                    list[open] = list[open].copy(returnsAt = list[open].returnsAt + event.at)
                } else {
                    list.add(0, SleepRecord(event.at, 0, listOf(event.at)))
                }
            }
            'E' -> {
                if (open < 0 || list.any { it.endAt == event.at }) return
                list[open] = list[open].copy(endAt = event.at)
            }
            else -> return
        }
        save(context, list.sortedByDescending { it.startAt })
        Log.i(TAG, "applied ${event.type} ${event.at}")
    }

    @Synchronized
    fun clear(context: Context): Int {
        load(context)
        val n = _records.value.size
        save(context, emptyList())
        return n
    }

    private fun save(context: Context, list: List<SleepRecord>) {
        csv.write(context, list.sortedBy { it.startAt }.map(::toRow))
        // 書けたときだけ反映する（書けなかったイベントは M5Stack から届き直したときに再度書く）
        _records.value = list
        loadedModified = csv.lastModified(context)
    }
}
