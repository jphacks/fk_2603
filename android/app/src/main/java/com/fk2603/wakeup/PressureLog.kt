package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 1分ごとの圧力（raw の平均） */
data class PressureSample(val at: Long, val raw: Int)

/**
 * 圧力センサーの値を直近 8 時間ぶん残す（デバイス画面のグラフ用）
 * MonitorService が M5Stack から届くたびに add() し、1分ごとに平均して1点にする。
 * アプリが終了しても残るよう filesDir/pressure_log.csv に保存する
 */
object PressureLog {
    private const val TAG = "PressureLog"
    private const val FILE_NAME = "pressure_log.csv"
    private const val BUCKET_SEC = 60L
    const val WINDOW_SEC = 8 * 3600L

    private val _samples = MutableStateFlow<List<PressureSample>>(emptyList())
    /** 古い順 */
    val samples: StateFlow<List<PressureSample>> = _samples
    private var loaded = false
    private var bucketStart = 0L
    private var bucketSum = 0L
    private var bucketCount = 0

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        _samples.value = runCatching {
            f.readLines().mapNotNull { line ->
                val c = line.split(",")
                if (c.size == 2) PressureSample(c[0].toLong(), c[1].toInt()) else null
            }
        }.getOrElse {
            Log.w(TAG, "read failed: $it")
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, raw: Int, nowSec: Long = System.currentTimeMillis() / 1000) {
        load(context)
        val start = nowSec - nowSec % BUCKET_SEC
        if (start != bucketStart) {
            flush(context)
            bucketStart = start
        }
        bucketSum += raw
        bucketCount++
    }

    private fun flush(context: Context) {
        if (bucketCount == 0) return
        val sample = PressureSample(bucketStart, (bucketSum / bucketCount).toInt())
        bucketSum = 0
        bucketCount = 0
        val list = (_samples.value + sample).filter { it.at > sample.at - WINDOW_SEC }
        _samples.value = list
        try {
            file(context).writeText(list.joinToString("") { "${it.at},${it.raw}\n" })
        } catch (e: Exception) {
            Log.w(TAG, "write failed: $e")
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
