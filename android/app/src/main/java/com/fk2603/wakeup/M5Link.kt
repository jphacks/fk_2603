package com.fk2603.wakeup

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class WakeResult(
    val id: Long,
    val ringAt: Long,
    val awakeAt: Long,
    val durationSec: Long,
    val resleep: Boolean,
)

/** M5Stack から届く睡眠のイベント。受け取ったら ackSleepEvent() で知らせる */
data class SleepEvent(
    val id: Long,
    val type: Char, // S=就寝 R=二度寝（ベッドに戻った） E=起床
    val at: Long,
)

data class M5Status(
    val state: String,
    val locked: Boolean,
    val alarmEpoch: Long,
    val volume: Int,
    val now: Long,
    val pressureRaw: Int,
    val inBed: Boolean,
    val lastResult: WakeResult?,
    val sleepEvent: SleepEvent?,
    val ackSeq: Int,
    val ackError: String,
    val config: M5Config?,  // 古いファームでは null
    val pairingMode: Boolean = false,  // M5Stack が再接続モード（右ボタン長押しで QR 表示中）
)

/** M5Stack の判定設定（アプリから変更できる） */
data class M5Config(
    val threshold: Int,       // 在床しきい値（raw 0-4095）
    val wakeConfirmSec: Int,  // ベッドを出てこの秒数で起床
    val resleepDelayMin: Int? = null,  // 二度寝: ベッドに戻ってから再アラームまで（分）。古いファームでは null
) {
    companion object {
        const val RAW_MAX = 4095
        // wakeup_alarm.ino の範囲と合わせる
        val THRESHOLD_RANGE = 100..3500
        val WAKE_CONFIRM_RANGE = 5..300
        val RESLEEP_DELAY_CHOICES = listOf(10, 20, 30)

        /** ベッドを出てもこの秒数は止められない（wakeup_alarm.ino の STOP_AFTER_LEAVE_SEC） */
        const val STOP_AFTER_LEAVE_SEC = 5
    }
}

open class M5Exception(message: String) : Exception(message)

/** QR での登録に失敗した（接続をやり直しても直らないので、もう一度 QR を読んでもらう） */
class PairingException(message: String) : M5Exception(message)

/**
 * M5Stack と BLE でつなぐ（アプリ全体で1本の接続を共有する）
 * - QR で登録した M5Stack（WakeUp-<devId>）だけにつなぎ、通信はすべて暗号化する（SecureChannel）
 * - 状態は notify で受け取り StatusHolder に入れる
 * - 接続時と一定間隔でスマホの時刻を送る（M5Stack は Wi-Fi を使わないため）
 */
@SuppressLint("MissingPermission")
object M5Link {
    private const val TAG = "M5Link"

    // M5Stack 側 wakeup_alarm.ino と合わせる
    private val SERVICE_UUID = UUID.fromString("6e0f0001-3b6a-4c1e-9b3a-2a4f8c1d7e01")
    private val STATUS_UUID = UUID.fromString("6e0f0002-3b6a-4c1e-9b3a-2a4f8c1d7e01")
    private val COMMAND_UUID = UUID.fromString("6e0f0003-3b6a-4c1e-9b3a-2a4f8c1d7e01")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val NAME_PREFIX = "WakeUp-"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private val opMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    // アプリを再起動しても M5Stack に残っている ack と重ならないようにする
    private val seq = AtomicInteger((System.currentTimeMillis() / 1000 % 1_000_000).toInt())

    /** 認証が済んだ接続の暗号化。null の間は状態を受け取らず、コマンドも送らない */
    @Volatile private var session: SecureChannel.Session? = null
    /** 認証前のフレーム（hello・ペアリング結果・認証失敗） */
    private val handshakeFrames = Channel<ByteArray>(16, BufferOverflow.DROP_OLDEST)
    /** 読み取った QR（登録が終わるまで） */
    @Volatile private var pendingQr: SecureChannel.PairingQr? = null
    @Volatile private var pairDone: CompletableDeferred<Unit>? = null

    @Volatile private var appContext: Context? = null

    /** 最後に状態を受け取った時刻（ms） */
    @Volatile var lastStatusMs = 0L
        private set

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun start(context: Context) {
        appContext = context.applicationContext
        PairingStore.load(context.applicationContext)
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runLoop(app) }
    }

    /** QR で読んだ M5Stack を登録する（つないで鍵を交換し、M5Stack が受け入れたら保存する） */
    suspend fun pair(context: Context, qrText: String) {
        val qr = SecureChannel.parseQr(qrText) ?: throw PairingException("WakeUp の QR コードではありません")
        val done = CompletableDeferred<Unit>()
        pairDone?.cancel()
        pairDone = done
        pendingQr = qr
        start(context)
        gatt?.disconnect()  // ほかの M5Stack とつながっていたら切って、QR の M5Stack で始め直す
        try {
            withTimeout(Config.PAIR_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            throw PairingException("M5Stack が見つかりません（QR を表示したまま近づけてください）")
        } finally {
            if (pendingQr === qr) pendingQr = null
        }
    }

    /**
     * M5Stack が再接続モードになったので、このスマホの登録を消して切断する
     * （ホームに「デバイスを追加」が出るので、表示中の QR を読み直してもらう）
     */
    private fun dropPairing() {
        appContext?.let { PairingStore.clear(it) }
        session = null
        StatusHolder.status.value = null
        StatusHolder.error.value = "M5Stack が再接続モードになったので登録を解除しました。表示中の QR を読み直してください"
        gatt?.disconnect()
        Log.i(TAG, "M5Stack entered pairing mode: unpaired")
    }

    /** このスマホの登録を消す（M5Stack 側の登録も消してから切断する） */
    suspend fun unpair(context: Context) {
        try {
            if (session != null) write(JSONObject().put("cmd", "unpair").toString())
        } catch (e: Exception) {
            Log.w(TAG, "unpair on device failed: $e")
        }
        PairingStore.clear(context)
        StatusHolder.status.value = null
        gatt?.disconnect()
    }

    suspend fun setAlarm(epochSec: Long): M5Status =
        command(JSONObject().put("cmd", "set").put("epoch", epochSec))

    suspend fun cancelAlarm(): M5Status = command(JSONObject().put("cmd", "cancel"))

    /** 鳴動を止めて起床を記録する（ベッドにいる間は M5Exception("in bed")。古いファームは "unknown command"） */
    suspend fun stopAlarm(): M5Status = command(JSONObject().put("cmd", "stop"))

    /**
     * 在床しきい値・起床確認時間・二度寝の再鳴動までを変更（ベッドにいる間・アラーム中は M5Exception("locked")）
     * resleepDelayMin = null なら変えない（古いファーム）
     */
    suspend fun setConfig(threshold: Int, wakeConfirmSec: Int, resleepDelayMin: Int?): M5Status =
        command(
            JSONObject().put("cmd", "config").put("th", threshold).put("wc", wakeConfirmSec)
                .apply { resleepDelayMin?.let { put("rd", it) } },
        )

    /** 睡眠イベントを受け取ったと知らせる（M5Stack は次のイベントを送る） */
    suspend fun ackSleepEvent(id: Long) {
        write(JSONObject().put("cmd", "evack").put("id", id).toString())
    }

    private suspend fun command(json: JSONObject): M5Status {
        val s = seq.incrementAndGet()
        write(json.put("seq", s).toString())
        val status = withTimeout(Config.BLE_COMMAND_TIMEOUT_MS) {
            StatusHolder.status.filterNotNull().first { it.ackSeq == s }
        }
        if (status.ackError.isNotEmpty()) throw M5Exception(status.ackError)
        return status
    }

    private suspend fun sendTime() {
        write(JSONObject().put("cmd", "time").put("epoch", System.currentTimeMillis() / 1000).toString())
    }

    // ---- 接続の維持 ----

    private suspend fun runLoop(context: Context) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        while (scope.isActive) {
            val adapter = manager.adapter
            val qr = pendingQr
            val pairing = PairingStore.load(context)
            val devId = qr?.devId ?: pairing?.devId
            val error = when {
                requiredPermissions().any {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                } -> "Bluetooth の権限を許可してください"
                adapter == null -> "この端末は Bluetooth に対応していません"
                !adapter.isEnabled -> "Bluetooth をオンにしてください"
                devId == null -> "ホームの「デバイスを追加」から M5Stack の QR を読み取ってください"
                else -> null
            }
            if (error != null || devId == null) {
                StatusHolder.error.value = error
                delay(if (devId == null) 1_000 else 3_000)
                continue
            }
            try {
                val device = scan(adapter.bluetoothLeScanner ?: throw IOException("scanner unavailable"), devId)
                if (device == null) {
                    StatusHolder.error.value = "M5Stack が見つかりません（電源と距離を確認）"
                } else {
                    session(context, device, devId, qr, pairing)
                }
            } catch (e: PairingException) {
                Log.w(TAG, "pairing failed: ${e.message}")
                if (pendingQr === qr) pendingQr = null
                pairDone?.completeExceptionally(e)
                StatusHolder.error.value = e.message
            } catch (e: Exception) {
                Log.w(TAG, "session ended: $e")
            }
            if (StatusHolder.error.value == null) StatusHolder.error.value = "M5Stack との接続が切れました"
            delay(Config.BLE_RECONNECT_DELAY_MS)
        }
    }

    /** QR で登録した M5Stack だけを探す（会場にほかの M5Stack があってもつながらない） */
    private suspend fun scan(scanner: android.bluetooth.le.BluetoothLeScanner, devId: String): BluetoothDevice? {
        val found = CompletableDeferred<BluetoothDevice>()
        val name = NAME_PREFIX + devId
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.scanRecord?.deviceName == name) found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IOException("scan failed: $errorCode"))
            }
        }
        // 画面オフでもスキャンできるよう、サービス UUID で絞り込む
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        return try {
            withTimeoutOrNull(Config.BLE_SCAN_TIMEOUT_MS) { found.await() }
        } finally {
            scanner.stopScan(callback)
        }
    }

    private suspend fun session(
        context: Context,
        device: BluetoothDevice,
        devId: String,
        qr: SecureChannel.PairingQr?,
        saved: SecureChannel.Pairing?,
    ) {
        val ready = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    ready.completeExceptionally(IOException("disconnected ($status)"))
                    pendingOp?.completeExceptionally(IOException("disconnected"))
                    disconnected.complete(Unit)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU $mtu (status $status)")
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (g.getService(SERVICE_UUID) != null) {
                    ready.complete(Unit)
                } else {
                    ready.completeExceptionally(IOException("service not found"))
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                pendingOp?.complete(status)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                pendingOp?.complete(status)
            }

            // Android 13 以降
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onFrame(value)
            }

            // Android 12 以前
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) @Suppress("DEPRECATION") onFrame(c.value)
            }
        }

        while (handshakeFrames.tryReceive().isSuccess) Unit  // 前の接続のフレームを捨てる
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        try {
            withTimeout(Config.BLE_CONNECT_TIMEOUT_MS) { ready.await() }
            val service = g.getService(SERVICE_UUID)
            val statusChar = service.getCharacteristic(STATUS_UUID)
            commandChar = service.getCharacteristic(COMMAND_UUID)

            g.setCharacteristicNotification(statusChar, true)
            writeDescriptor(g, statusChar.getDescriptor(CCCD_UUID), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            handshake(context, devId, qr, saved)
            sendTime()
            StatusHolder.error.value = null
            Log.i(TAG, "connected to ${device.address} (encrypted)")

            var lastTimeSyncMs = System.currentTimeMillis()
            while (!disconnected.isCompleted) {
                delay(2_000)
                val now = System.currentTimeMillis()
                if (now - lastStatusMs > Config.BLE_STALE_MS) throw IOException("no status from M5Stack")
                if (now - lastTimeSyncMs >= Config.TIME_SYNC_INTERVAL_MS) {
                    sendTime()
                    lastTimeSyncMs = now
                }
            }
        } finally {
            session = null
            gatt = null
            commandChar = null
            g.disconnect()
            g.close()
        }
    }

    /** QR があれば登録してから、登録済みの鍵で認証する（secure_link.h の手順） */
    private suspend fun handshake(
        context: Context,
        devId: String,
        qr: SecureChannel.PairingQr?,
        saved: SecureChannel.Pairing?,
    ) {
        val hello = withTimeout(Config.BLE_CONNECT_TIMEOUT_MS) {
            var h: SecureChannel.Hello? = null
            while (h == null) h = SecureChannel.parseHello(handshakeFrames.receive())
            h
        }
        if (hello.devId != devId) throw IOException("別の M5Stack です (${hello.devId})")
        // QR を読んでいないのに M5Stack が再接続モード → 登録をやり直してもらう
        if (qr == null && hello.pairing) {
            dropPairing()
            throw IOException("M5Stack is in pairing mode")
        }

        var pairing = saved
        if (qr != null) {
            if (!hello.pairing) throw PairingException("M5Stack の QR の表示が終わっています。C ボタン長押しで出し直してください")
            val (frame, newPairing) = SecureChannel.buildPair(qr)
            writeRaw { frame }
            val result = withTimeout(Config.BLE_COMMAND_TIMEOUT_MS) {
                var r: Int? = null
                while (r == null) {
                    val f = handshakeFrames.receive()
                    if (f.size == 2 && f[0] == SecureChannel.FRAME_PAIR_RESULT) r = f[1].toInt()
                }
                r
            }
            if (result != SecureChannel.PAIR_OK) {
                throw PairingException(
                    if (result == 2) "QR が古いか読み取りが違います。M5Stack に表示中の QR を読み直してください"
                    else "M5Stack が登録を受け付けませんでした ($result)",
                )
            }
            pairing = newPairing
        }
        if (pairing == null) throw IOException("not paired")

        val (auth, newSession) = SecureChannel.buildAuth(pairing, hello)
        session = newSession
        val sentAt = System.currentTimeMillis()
        writeRaw { auth }
        // 認証できれば暗号化された状態が届く。だめなら AUTH_FAIL が届く
        withTimeout(Config.BLE_COMMAND_TIMEOUT_MS) {
            while (lastStatusMs < sentAt) {
                val f = handshakeFrames.tryReceive().getOrNull()
                if (f != null && f.isNotEmpty() && f[0] == SecureChannel.FRAME_AUTH_FAIL) {
                    session = null
                    StatusHolder.error.value = "M5Stack 側でこのスマホの登録が消えています。デバイス画面で登録を解除し、もう一度追加してください"
                    throw IOException("auth rejected")
                }
                delay(50)
            }
        }
        if (qr != null) {
            PairingStore.save(context, pairing)
            if (pendingQr === qr) pendingQr = null
            pairDone?.complete(Unit)
            Log.i(TAG, "paired with $devId")
        }
    }

    private fun onFrame(value: ByteArray) {
        if (value.isEmpty()) return
        if (value[0] != SecureChannel.FRAME_DATA) {
            handshakeFrames.trySend(value)
            return
        }
        val s = session ?: return
        try {
            val status = parseStatus(s.open(value))
            // M5Stack が再接続モードになった（右ボタン長押し）→ このスマホの登録も解除する
            if (status.pairingMode && pendingQr == null) {
                dropPairing()
                return
            }
            StatusHolder.status.value = status
            lastStatusMs = System.currentTimeMillis()
        } catch (e: SecurityException) {
            // 改ざん・リプレイ・偽の M5Stack。信用せず、届かなかったものとして扱う
            Log.w(TAG, "rejected frame: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "bad status (${value.size} bytes): ${e.message}")
        }
    }

    // ---- GATT 操作（同時に1つしか実行できないので直列化する） ----

    /** 暗号化して送る。カウンタの順に届くよう、暗号化も直列化の中で行う */
    private suspend fun write(text: String) = writeRaw {
        (session ?: throw IOException("M5Stack に接続していません")).seal(text)
    }

    private suspend fun writeRaw(frame: () -> ByteArray) = gattOp {
        val g = gatt ?: throw IOException("M5Stack に接続していません")
        val c = commandChar ?: throw IOException("M5Stack に接続していません")
        val bytes = frame()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    private suspend fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) = gattOp {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private suspend fun gattOp(start: () -> Boolean) = opMutex.withLock {
        val done = CompletableDeferred<Int>()
        pendingOp = done
        try {
            if (!start()) throw IOException("BLE の送信に失敗しました")
            val status = withTimeout(Config.BLE_COMMAND_TIMEOUT_MS) { done.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("BLE の送信に失敗しました ($status)")
        } finally {
            pendingOp = null
        }
    }

    // notify 1回に収まるよう短いキーにしている（wakeup_alarm.ino の buildStatusJson() を参照）
    private fun parseStatus(text: String): M5Status {
        val j = JSONObject(text)
        val w = j.optJSONArray("w")
        val k = j.optJSONArray("k")
        return M5Status(
            state = j.getString("s"),
            locked = j.getInt("l") != 0,
            alarmEpoch = j.getLong("a"),
            volume = j.getInt("v"),
            now = j.getLong("t"),
            pressureRaw = j.getInt("r"),
            inBed = j.getInt("b") != 0,
            lastResult = w?.let {
                WakeResult(
                    id = it.getLong(0),
                    ringAt = it.getLong(1),
                    awakeAt = it.getLong(2),
                    durationSec = it.getLong(3),
                    resleep = it.getInt(4) != 0,
                )
            },
            sleepEvent = j.optJSONArray("e")?.let {
                SleepEvent(id = it.getLong(0), type = it.getString(1)[0], at = it.getLong(2))
            },
            ackSeq = k?.optInt(0) ?: 0,
            ackError = k?.optString(1).orEmpty(),
            config = j.optJSONArray("c")?.let {
                M5Config(
                    threshold = it.getInt(0),
                    wakeConfirmSec = it.getInt(1),
                    resleepDelayMin = if (it.length() > 2) it.getInt(2) else null,
                )
            },
            pairingMode = j.optInt("p") == 1,
        )
    }
}
