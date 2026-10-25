package com.fk2603.wakeup

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 端末に保存する記録の CSV ファイル（記録はすべてこの形で持つ）
 * - 場所: Android/data/com.fk2603.wakeup/files/<name>（PC から USB や adb pull で取り出せる）
 * - UTF-8（BOM 付き）・1行目は見出し・改行は CRLF（Excel でそのまま開ける）
 * - 書きかけで壊れないよう、一時ファイルに書いてから置き換える
 * 値にカンマは入れない（時刻・数値・英数字の id だけ）
 */
class CsvFile(
    private val name: String,
    private val header: String,
    /** 前の版の見出し（列を足す前のファイルも読めるように、見出しとして読み飛ばす） */
    private val oldHeaders: Set<String> = emptySet(),
) {

    fun file(context: Context): File {
        val app = context.applicationContext
        return File(app.getExternalFilesDir(null) ?: app.filesDir, name)
    }

    /** 最終更新時刻。ファイルがなければ 0 */
    fun lastModified(context: Context): Long = file(context).let { if (it.exists()) it.lastModified() else 0L }

    fun exists(context: Context): Boolean = file(context).exists()

    /** 見出し・空行を除いた行を列に分けて返す。ファイルがなければ空 */
    fun read(context: Context): List<List<String>> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8)
            .map { it.removePrefix(BOM).trim() }
            .filter { it.isNotEmpty() && it != header && it !in oldHeaders }
            .map { line -> line.split(",").map { it.trim().removeSurrounding("\"") } }
    }

    fun write(context: Context, rows: List<List<String>>) {
        val f = file(context)
        val tmp = File(f.parentFile, "$name.tmp")
        val body = buildString {
            append(BOM).append(header).append("\r\n")
            rows.forEach { append(it.joinToString(",")).append("\r\n") }
        }
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(body, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val BOM = "﻿"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        // Excel で保存し直すと「2026/10/24 23:00」のような形になるので、それも読めるようにする
        private val READ_FORMATS = listOf(
            TIME_FORMAT,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        )

        fun formatTime(epochSec: Long): String =
            TIME_FORMAT.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

        fun parseTime(s: String): Long {
            val text = s.trim().removeSurrounding("\"")
            val t = READ_FORMATS.firstNotNullOfOrNull { runCatching { LocalDateTime.parse(text, it) }.getOrNull() }
                ?: throw IllegalArgumentException("bad time: $text")
            return t.atZone(ZoneId.systemDefault()).toEpochSecond()
        }
    }
}
