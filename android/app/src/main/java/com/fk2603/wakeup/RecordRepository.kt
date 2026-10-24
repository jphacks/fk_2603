package com.fk2603.wakeup

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

data class Record(
    val id: String,
    val uid: String,
    val nickname: String,
    val durationSec: Long,
    val ringAt: Long,
    val resleep: Boolean,
)

/** 起床記録を Firestore に保存・取得・削除する（他端末のユーザーと共有） */
object RecordRepository {
    private val auth get() = FirebaseAuth.getInstance()
    private val records get() = FirebaseFirestore.getInstance().collection(Config.RECORDS_COLLECTION)

    private suspend fun uid(): String =
        auth.currentUser?.uid ?: auth.signInAnonymously().await().user!!.uid

    suspend fun upload(result: WakeResult, nickname: String) {
        records.add(
            mapOf(
                "uid" to uid(),
                "nickname" to nickname.ifBlank { "名無し" },
                "durationSec" to result.durationSec,
                "ringAt" to result.ringAt,
                "resleep" to result.resleep,
                "createdAt" to FieldValue.serverTimestamp(),
            )
        ).await()
    }

    /** 全ユーザーのランキング（二度寝による再アラームの記録は除く） */
    suspend fun ranking(): List<Record> {
        uid()
        return records.orderBy("durationSec", Query.Direction.ASCENDING)
            .limit(Config.RANKING_LIMIT * 2)
            .get().await()
            .documents.mapNotNull { it.toRecord() }
            .filter { !it.resleep }
            .take(Config.RANKING_LIMIT.toInt())
    }

    suspend fun myRecords(): List<Record> =
        records.whereEqualTo("uid", uid()).get().await()
            .documents.mapNotNull { it.toRecord() }
            .sortedByDescending { it.ringAt }

    /** 自分の記録をすべて削除 */
    suspend fun deleteAllMine(): Int {
        val docs = records.whereEqualTo("uid", uid()).get().await().documents
        docs.chunked(400).forEach { chunk ->
            val batch = FirebaseFirestore.getInstance().batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        return docs.size
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toRecord(): Record? = runCatching {
        Record(
            id = id,
            uid = getString("uid")!!,
            nickname = getString("nickname") ?: "",
            durationSec = getLong("durationSec")!!,
            ringAt = getLong("ringAt") ?: 0,
            resleep = getBoolean("resleep") ?: false,
        )
    }.getOrNull()
}
