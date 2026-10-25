package com.fk2603.wakeup

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * M5Stack との通信の暗号化（QR ペアリング + ECDH P-256 + AES-256-GCM）
 * フレームの形式は arduino/wakeup_alarm/secure_link.h と合わせる。Android に依存しないので JVM でテストできる
 */
object SecureChannel {
    const val FRAME_PAIR: Byte = 0x01
    const val FRAME_AUTH: Byte = 0x02
    const val FRAME_HELLO: Byte = 0x10
    const val FRAME_PAIR_RESULT: Byte = 0x11
    const val FRAME_AUTH_FAIL: Byte = 0x12
    const val FRAME_DATA: Byte = 0x20

    const val PAIR_OK = 0

    private const val PUB_LEN = 65
    private const val PHONE_ID_LEN = 8
    private const val NONCE_LEN = 16
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128
    private const val DIR_TO_PHONE: Byte = 0x01
    private const val DIR_FROM_PHONE: Byte = 0x02

    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    /** LCD の QR: WAKEUP1:<devId>:<base64url(公開鍵)>:<base64url(トークン)> */
    data class PairingQr(val devId: String, val devPub: ByteArray, val token: ByteArray)

    fun parseQr(text: String): PairingQr? {
        val parts = text.trim().split(":")
        if (parts.size != 4 || parts[0] != "WAKEUP1") return null
        val devId = parts[1].lowercase()
        if (!devId.matches(Regex("[0-9a-f]{8}"))) return null
        return try {
            val pub = Base64.getUrlDecoder().decode(parts[2])
            val token = Base64.getUrlDecoder().decode(parts[3])
            // devId は公開鍵のハッシュ。QR の一部だけ書き換えられていないか確かめる
            if (pub.size != PUB_LEN || pub[0] != 0x04.toByte() || token.size != 16 || devIdOf(pub) != devId) null
            else PairingQr(devId, pub, token)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun devIdOf(pub: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pub).take(4).joinToString("") { "%02x".format(it) }

    /** 認証前に M5Stack が送ってくる hello */
    data class Hello(val devId: String, val nonce: ByteArray, val pairing: Boolean)

    fun parseHello(frame: ByteArray): Hello? {
        if (frame.size != 1 + 4 + NONCE_LEN + 1 || frame[0] != FRAME_HELLO) return null
        return Hello(
            devId = frame.copyOfRange(1, 5).joinToString("") { "%02x".format(it) },
            nonce = frame.copyOfRange(5, 5 + NONCE_LEN),
            pairing = frame[5 + NONCE_LEN].toInt() != 0,
        )
    }

    /** 登録した M5Stack と共有する鍵 */
    data class Pairing(val devId: String, val phoneId: ByteArray, val key: ByteArray)

    /**
     * ペアリングのフレームを作る。返した Pairing は M5Stack が受け入れたときだけ保存する
     * 公開鍵は QR から直接受け取るので、BLE の途中で公開鍵をすり替えられても鍵は一致しない（中間者攻撃を防ぐ）
     */
    fun buildPair(qr: PairingQr): Pair<ByteArray, Pairing> {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val kp = gen.generateKeyPair()
        val phonePub = encodePublic(kp.public as ECPublicKey)
        val devPub = decodePublic(qr.devPub, kp.public as ECPublicKey)
        val z = KeyAgreement.getInstance("ECDH").run {
            init(kp.private)
            doPhase(devPub, true)
            generateSecret()
        }
        val phoneId = randomBytes(PHONE_ID_LEN)
        val info = "wakeup-v1".toByteArray() + hex(qr.devId) + phoneId
        val key = hkdf(salt = qr.token, ikm = z, info = info)
        z.fill(0)
        val frame = byteArrayOf(FRAME_PAIR) + phonePub + phoneId + hmac(qr.token, phonePub, phoneId)
        return frame to Pairing(qr.devId, phoneId, key)
    }

    /** 接続ごとの認証。返したセッションで以降のフレームを暗号化・復号する */
    fun buildAuth(pairing: Pairing, hello: Hello): Pair<ByteArray, Session> {
        val nonceP = randomBytes(NONCE_LEN)
        val frame = byteArrayOf(FRAME_AUTH) + pairing.phoneId + nonceP +
            hmac(pairing.key, "auth".toByteArray(), hello.nonce, nonceP)
        val sessionKey = hkdf(salt = hello.nonce + nonceP, ikm = pairing.key, info = "session".toByteArray())
        return frame to Session(sessionKey)
    }

    /**
     * 1回の接続の暗号化。方向ごとにカウンタを単調増加させ、再送されたフレーム（リプレイ）を拒否する
     * IV に方向を入れるので、M5Stack が送ったフレームを M5Stack に送り返しても通らない
     */
    class Session(private val key: ByteArray) {
        private var txCounter = 0L
        private var rxCounter = 0L

        fun seal(text: String): ByteArray {
            val ctr = ++txCounter
            val header = ByteBuffer.allocate(5).put(FRAME_DATA).putInt(ctr.toInt()).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv(DIR_FROM_PHONE, ctr)))
            cipher.updateAAD(header)
            return header + cipher.doFinal(text.toByteArray())
        }

        /** 改ざん・リプレイ・鍵違いは SecurityException */
        fun open(frame: ByteArray): String {
            if (frame.size < 5 + TAG_BITS / 8 || frame[0] != FRAME_DATA) throw SecurityException("bad frame")
            val ctr = ByteBuffer.wrap(frame, 1, 4).int.toLong() and 0xffffffffL
            if (ctr <= rxCounter) throw SecurityException("replayed counter $ctr")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv(DIR_TO_PHONE, ctr)))
            cipher.updateAAD(frame, 0, 5)
            val plain = try {
                cipher.doFinal(frame, 5, frame.size - 5)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw SecurityException("tag mismatch")
            }
            rxCounter = ctr
            return String(plain)
        }
    }

    // ---- 部品 ----

    private fun iv(dir: Byte, ctr: Long) = ByteBuffer.allocate(12).put(dir).put(ByteArray(7)).putInt(ctr.toInt()).array()

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** RFC 5869（SHA-256） */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int = KEY_LEN): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    private fun encodePublic(pub: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + unsigned32(pub.w.affineX) + unsigned32(pub.w.affineY)

    /** 非圧縮の点を公開鍵に。曲線上にない点は KeyAgreement が拒否する */
    private fun decodePublic(bytes: ByteArray, sameCurve: ECPublicKey): ECPublicKey {
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), sameCurve.params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun unsigned32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
