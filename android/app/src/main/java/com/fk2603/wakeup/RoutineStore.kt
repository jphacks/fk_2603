package com.fk2603.wakeup

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

/** 朝のルーティンのタスク。毎週の曜日（月=1〜日=7）か、指定日だけ */
data class RoutineTask(
    val id: Long,
    val title: String,
    val weekdays: Set<Int> = (1..7).toSet(),
    val onlyDate: String? = null,
) {
    fun appliesTo(date: LocalDate): Boolean =
        if (onlyDate != null) onlyDate == date.toString() else date.dayOfWeek.value in weekdays
}

/** ある日のルーティンの結果 */
data class RoutineDay(val date: LocalDate, val items: List<Pair<String, Boolean>>) {
    val total get() = items.size
    val done get() = items.count { it.second }
    /** 達成度 0〜10（完了数 ÷ タスク数） */
    val score get() = if (total == 0) 0 else (done * RoutineStore.SCORE_MAX.toDouble() / total).roundToInt()
}

/**
 * 朝のルーティン
 * - タスクの定義は設定値なので SharedPreferences `dopa_routines` に JSON で持つ（ホームの「編集 ＋」で登録）
 * - 毎日のチェック結果は記録なので CSV ファイル routine_log.csv（CsvFile の形式）に残す
 *     列: 日付,タスクID,タスク,完了（その日に当てはまるタスク1つにつき1行。完了は 1/0）
 *   その日を最初に表示したときに、当てはまるタスクを未完了で書き出す（チェックしなかった日も記録に残る）
 */
object RoutineStore {
    private const val TAG = "RoutineStore"
    const val SCORE_MAX = 10
    private val csv = CsvFile("routine_log.csv", "日付,タスクID,タスク,完了")

    private data class LogRow(val date: LocalDate, val taskId: Long, val title: String, val done: Boolean)

    private val _tasks = MutableStateFlow<List<RoutineTask>>(emptyList())
    val tasks: StateFlow<List<RoutineTask>> = _tasks

    private val _history = MutableStateFlow<List<RoutineDay>>(emptyList())
    /** 日ごとの結果（新しい順） */
    val history: StateFlow<List<RoutineDay>> = _history

    private var log: List<LogRow> = emptyList()
    private var loadedModified = -1L
    private var tasksLoaded = false

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("dopa_routines", Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        if (!tasksLoaded) {
            tasksLoaded = true
            _tasks.value = runCatching {
                val arr = JSONArray(sp(context).getString("tasks", "[]") ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val days = o.optJSONArray("days") ?: JSONArray()
                    RoutineTask(
                        o.getLong("id"), o.getString("title"),
                        (0 until days.length()).map { days.getInt(it) }.toSet(),
                        o.optString("onlyDate").takeIf { it.isNotBlank() },
                    )
                }
            }.getOrElse {
                Log.w(TAG, "read tasks failed: $it")
                emptyList()
            }
        }
        val modified = csv.lastModified(context)
        if (modified != loadedModified) {
            loadedModified = modified
            log = try {
                csv.read(context).mapNotNull { c ->
                    runCatching { LogRow(LocalDate.parse(c[0]), c[1].toLong(), c[2], c[3] == "1") }.getOrNull()
                }
            } catch (e: Exception) {
                Log.w(TAG, "read log failed: $e")
                return
            }
            migrateDoneKeys(context)
            publish()
        }
    }

    @Synchronized
    fun addTask(context: Context, title: String, weekdays: Set<Int>, onlyDate: String?): RoutineTask? {
        load(context)
        val clean = title.replace(",", "、").replace("\n", " ").trim()
        if (clean.isEmpty()) return null
        val task = RoutineTask(System.nanoTime(), clean, weekdays, onlyDate)
        saveTasks(context, _tasks.value + task)
        return task
    }

    @Synchronized
    fun removeTask(context: Context, id: Long) {
        load(context)
        saveTasks(context, _tasks.value.filter { it.id != id })
        // まだ完了していない今日以降の記録からも外す（過去の記録は残す）
        val today = LocalDate.now()
        saveLog(context, log.filterNot { it.taskId == id && !it.done && !it.date.isBefore(today) })
    }

    /** その日に当てはまるタスクを記録に書き出す（まだなら未完了で） */
    @Synchronized
    fun ensureDay(context: Context, date: LocalDate) {
        load(context)
        val missing = _tasks.value.filter { t -> t.appliesTo(date) && log.none { it.date == date && it.taskId == t.id } }
        if (missing.isNotEmpty()) saveLog(context, log + missing.map { LogRow(date, it.id, it.title, false) })
    }

    /** その日に完了したタスクの ID */
    fun doneIds(context: Context, date: LocalDate): Set<Long> {
        load(context)
        return log.filter { it.date == date && it.done }.map { it.taskId }.toSet()
    }

    @Synchronized
    fun setDone(context: Context, date: LocalDate, taskId: Long, done: Boolean) {
        ensureDay(context, date)
        if (log.none { it.date == date && it.taskId == taskId }) return
        saveLog(context, log.map { if (it.date == date && it.taskId == taskId) it.copy(done = done) else it })
    }

    @Synchronized
    fun toggle(context: Context, date: LocalDate, taskId: Long) {
        load(context)
        setDone(context, date, taskId, taskId !in doneIds(context, date))
    }

    @Synchronized
    fun clearLog(context: Context): Int {
        load(context)
        val days = log.map { it.date }.distinct().size
        saveLog(context, emptyList())
        return days
    }

    private fun publish() {
        _history.value = log.groupBy { it.date }
            .map { (date, rows) -> RoutineDay(date, rows.map { it.title to it.done }) }
            .sortedByDescending { it.date }
    }

    // 前の版はチェックを SharedPreferences の "done"（"日付:タスクID"）に持っていたので CSV に移す
    private fun migrateDoneKeys(context: Context) {
        val raw = sp(context).getString("done", null) ?: return
        val keys = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        val rows = keys.mapNotNull { k ->
            val (d, id) = k.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val task = _tasks.value.find { it.id.toString() == id } ?: return@mapNotNull null
            runCatching { LogRow(LocalDate.parse(d), task.id, task.title, true) }.getOrNull()
        }.filter { r -> log.none { it.date == r.date && it.taskId == r.taskId } }
        if (rows.isNotEmpty()) saveLog(context, log + rows)
        sp(context).edit().remove("done").apply()
    }

    private fun saveTasks(context: Context, list: List<RoutineTask>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("onlyDate", t.onlyDate ?: "")
                put("days", JSONArray().apply { t.weekdays.sorted().forEach { put(it) } })
            })
        }
        sp(context).edit().putString("tasks", arr.toString()).apply()
        _tasks.value = list
    }

    private fun saveLog(context: Context, rows: List<LogRow>) {
        csv.write(context, rows.sortedWith(compareBy({ it.date }, { it.taskId })).map {
            listOf(it.date.toString(), it.taskId.toString(), it.title, if (it.done) "1" else "0")
        })
        log = rows
        loadedModified = csv.lastModified(context)
        publish()
    }
}
