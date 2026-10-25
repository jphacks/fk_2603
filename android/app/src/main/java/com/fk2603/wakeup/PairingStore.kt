package com.fk2603.wakeup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * QR で登録した M5Stack（1台）を保存する
 * 共有鍵は Android Keystore の鍵で暗号化してから保存する（端末から取り出されても平文の鍵は出てこない）
 */
object PairingStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "wakeup_pairing"

    private val _paired = MutableStateFlow(false)
    /** 登録済みか（ホームの「デバイスを追加」を出すかどうか） */
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    /** 登録した M5Stack の ID（表示用） */
    @Volatile var devId: String? = null
        private set

    private fun sp(context: Context) = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(context: Context): SecureChannel.Pairing? {
        val sp = sp(context)
        val devId = sp.getString("devId", null)
        val phoneId = sp.getString("phoneId", null)
        val iv = sp.getString("iv", null)
        val wrapped = sp.getString("key", null)
        val pairing = if (devId == null || phoneId == null || iv == null || wrapped == null) {
            null
        } else {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, decode(iv)))
                SecureChannel.Pairing(devId, decode(phoneId), cipher.doFinal(decode(wrapped)))
            } catch (e: Exception) {
                null  // Keystore の鍵が消えた（端末の初期化など）。登録し直してもらう
            }
        }
        this.devId = pairing?.devId
        _paired.value = pairing != null
        return pairing
    }

    fun save(context: Context, pairing: SecureChannel.Pairing) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val wrapped = cipher.doFinal(pairing.key)
        sp(context).edit()
            .putString("devId", pairing.devId)
            .putString("phoneId", encode(pairing.phoneId))
            .putString("iv", encode(cipher.iv))
            .putString("key", encode(wrapped))
            .apply()
        devId = pairing.devId
        _paired.value = true
    }

    fun clear(context: Context) {
        sp(context).edit().clear().apply()
        devId = null
        _paired.value = false
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
