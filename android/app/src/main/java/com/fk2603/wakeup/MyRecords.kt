package com.fk2603.wakeup

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Firestore の自分の記録とランキングを画面とサービスで共有する（refresh() で読み直す） */
object MyRecords {
    private const val TAG = "MyRecords"

    private val _mine = MutableStateFlow<List<Record>>(emptyList())
    /** 新しい順 */
    val mine: StateFlow<List<Record>> = _mine

    private val _ranking = MutableStateFlow<List<Record>>(emptyList())
    val ranking: StateFlow<List<Record>> = _ranking

    private val _uid = MutableStateFlow<String?>(null)
    val uid: StateFlow<String?> = _uid

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val mutex = Mutex()

    suspend fun refresh() = mutex.withLock {
        try {
            _uid.value = RecordRepository.uid()
            _mine.value = RecordRepository.myRecords()
            _ranking.value = RecordRepository.ranking()
            _error.value = null
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: $e")
            _error.value = "記録を読み込めませんでした: ${e.message}"
        }
    }
}
