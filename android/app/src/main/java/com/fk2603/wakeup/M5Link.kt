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

data class M5Status(
    val state: String,
    val locked: Boolean,
    val alarmEpoch: Long,
    val volume: Int,
    val now: Long,
    val pressureRaw: Int,
    val inBed: Boolean,
    val lastResult: WakeResult?,
    val ackSeq: Int,
    val ackError: String,
)

class M5Exception(message: String) : Exception(message)

/**
 * M5Stack と BLE でつなぐ（アプリ全体で1本の接続を共有する）
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private val opMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    // アプリを再起動しても M5Stack に残っている ack と重ならないようにする
    private val seq = AtomicInteger((System.currentTimeMillis() / 1000 % 1_000_000).toInt())

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
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runLoop(app) }
    }

    suspend fun setAlarm(epochSec: Long): M5Status =
        command(JSONObject().put("cmd", "set").put("epoch", epochSec))

    suspend fun cancelAlarm(): M5Status = command(JSONObject().put("cmd", "cancel"))

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
            val error = when {
                requiredPermissions().any {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                } -> "Bluetooth の権限を許可してください"
                adapter == null -> "この端末は Bluetooth に対応していません"
                !adapter.isEnabled -> "Bluetooth をオンにしてください"
                else -> null
            }
            if (error != null) {
                StatusHolder.error.value = error
                delay(3_000)
                continue
            }
            try {
                val device = scan(adapter.bluetoothLeScanner ?: throw IOException("scanner unavailable"))
                if (device == null) {
                    StatusHolder.error.value = "M5Stack が見つかりません（電源と距離を確認）"
                } else {
                    session(context, device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "session ended: $e")
            }
            if (StatusHolder.error.value == null) StatusHolder.error.value = "M5Stack との接続が切れました"
            delay(Config.BLE_RECONNECT_DELAY_MS)
        }
    }

    private suspend fun scan(scanner: android.bluetooth.le.BluetoothLeScanner): BluetoothDevice? {
        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
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

    private suspend fun session(context: Context, device: BluetoothDevice) {
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
                onStatus(value)
            }

            // Android 12 以前
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) @Suppress("DEPRECATION") onStatus(c.value)
            }
        }

        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        try {
            withTimeout(Config.BLE_CONNECT_TIMEOUT_MS) { ready.await() }
            val service = g.getService(SERVICE_UUID)
            val statusChar = service.getCharacteristic(STATUS_UUID)
            commandChar = service.getCharacteristic(COMMAND_UUID)

            g.setCharacteristicNotification(statusChar, true)
            writeDescriptor(g, statusChar.getDescriptor(CCCD_UUID), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            sendTime()
            StatusHolder.error.value = null
            Log.i(TAG, "connected to ${device.address}")

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
            gatt = null
            commandChar = null
            g.disconnect()
            g.close()
        }
    }

    private fun onStatus(value: ByteArray) {
        try {
            StatusHolder.status.value = parseStatus(String(value))
            lastStatusMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "bad status (${value.size} bytes): ${e.message}")
        }
    }

    // ---- GATT 操作（同時に1つしか実行できないので直列化する） ----

    private suspend fun write(text: String) = gattOp {
        val g = gatt ?: throw IOException("M5Stack に接続していません")
        val c = commandChar ?: throw IOException("M5Stack に接続していません")
        val bytes = text.toByteArray()
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
            ackSeq = k?.optInt(0) ?: 0,
            ackError = k?.optString(1).orEmpty(),
        )
    }
}
