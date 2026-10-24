package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * 睡眠記録を CSV ファイルに保存する（Firestore には送らない）
 * - 場所: Android/data/com.fk2603.wakeup/files/sleep_records.csv（PC から USB や adb pull で取り出せる）
 * - 1行 = 1回の睡眠。古い順。Excel で開けるよう UTF-8（BOM 付き）
 *     就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻
 *     2026-10-24 23:00:00,2026-10-25 07:05:00,485,1,2026-10-25 06:40:00
 *   起床が空欄 = 記録中、「不明」= 起床が届かなかった。戻った時刻は ; 区切り
 * - ファイルが外で書き換えられたら、次に読むときに読み直す
 * M5Stack から届く S/R/E のイベントで組み立てる。同じイベントが2回届いても結果は変わらない
 */
object SleepStore {
    private const val TAG = "SleepStore"
    private const val FILE_NAME = "sleep_records.csv"
    private const val HEADER = "就寝,起床,睡眠時間(分),二度寝回数,ベッドに戻った時刻"
    private const val UNKNOWN = "不明"
    private const val BOM = "﻿"
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val _records = MutableStateFlow<List<SleepRecord>>(emptyList())
    /** 新しい順 */
    val records: StateFlow<List<SleepRecord>> = _records
    private var loadedModified = -1L

    fun file(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, FILE_NAME)

    /** ファイルを読む（前回読んだ後に変わっていなければ何もしない） */
    @Synchronized
    fun load(context: Context) {
        val f = file(context)
        val modified = if (f.exists()) f.lastModified() else 0L
        if (modified == loadedModified) return
        loadedModified = modified
        if (!f.exists()) {
            _records.value = emptyList()
            return
        }
        val lines = try {
            f.readLines(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "read failed: $e")
            return
        }
        _records.value = lines
            .map { it.removePrefix(BOM).trim() }
            .filter { it.isNotEmpty() && it != HEADER }
            .mapNotNull { line ->
                runCatching { parseLine(line) }.getOrElse {
                    Log.w(TAG, "skip line: $line ($it)")
                    null
                }
            }
            .sortedByDescending { it.startAt }
    }

    private fun parseLine(line: String): SleepRecord {
        val c = line.split(",")
        val end = c.getOrElse(1) { "" }
        return SleepRecord(
            startAt = parseTime(c[0]),
            endAt = when (end) {
                "" -> 0
                UNKNOWN -> -1
                else -> parseTime(end)
            },
            returnsAt = c.getOrElse(4) { "" }.split(";").filter { it.isNotBlank() }.map { parseTime(it) },
        )
    }

    // Excel で保存し直すと「2026/10/24 23:00」のような形になるので、それも読めるようにする
    private val READ_FORMATS = listOf(
        TIME_FORMAT,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
    )

    private fun parseTime(s: String): Long {
        val text = s.trim().removeSurrounding("\"")
        val t = READ_FORMATS.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }
            ?: throw IllegalArgumentException("bad time: $text")
        return t.atZone(ZoneId.systemDefault()).toEpochSecond()
    }

    private fun formatTime(epochSec: Long): String =
        TIME_FORMAT.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

    private fun toLine(r: SleepRecord): String = listOf(
        formatTime(r.startAt),
        when {
            r.isSleeping -> ""
            r.endAt < 0 -> UNKNOWN
            else -> formatTime(r.endAt)
        },
        if (r.endAt > 0) ((r.endAt - r.startAt) / 60).toString() else "",
        r.resleepCount.toString(),
        r.returnsAt.joinToString(";") { formatTime(it) },
    ).joinToString(",")

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

    // 書きかけで壊れないよう、一時ファイルに書いてから置き換える
    private fun save(context: Context, list: List<SleepRecord>) {
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        val body = buildString {
            append(BOM).append(HEADER).append("\r\n")
            list.sortedBy { it.startAt }.forEach { append(toLine(it)).append("\r\n") }
        }
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(body, Charsets.UTF_8)
            tmp.delete()
        }
        // 書けたときだけ反映する（書けなかったイベントは M5Stack から届き直したときに再度書く）
        _records.value = list
        loadedModified = f.lastModified()
    }
}
