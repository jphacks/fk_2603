package com.fk2603.wakeup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** M5Stack 側（secure_link.cpp）の手順を JVM で再現して、スマホ側と鍵が一致するか確かめる */
class SecureChannelTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class FakeDevice {
        val kp: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        val pub: ByteArray = (kp.public as ECPublicKey).let {
            byteArrayOf(4) + unsigned(it.w.affineX) + unsigned(it.w.affineY)
        }
        val devId = SecureChannel.devIdOf(pub)
        val token = SecureChannel.randomBytes(16)
        val nonce = SecureChannel.randomBytes(16)

        fun qr() = "WAKEUP1:$devId:${b64(pub)}:${b64(token)}"

        fun hello(pairing: Boolean) = byteArrayOf(SecureChannel.FRAME_HELLO) + hexBytes(devId) + nonce + byteArrayOf(if (pairing) 1 else 0)

        /** handlePair(): HMAC を確かめて K を作る */
        fun acceptPair(frame: ByteArray): Pair<ByteArray, ByteArray>? {
            val phonePub = frame.copyOfRange(1, 66)
            val phoneId = frame.copyOfRange(66, 74)
            val mac = frame.copyOfRange(74, 106)
            if (!mac.contentEquals(SecureChannel.hmac(token, phonePub, phoneId))) return null
            val x = BigInteger(1, phonePub.copyOfRange(1, 33))
            val y = BigInteger(1, phonePub.copyOfRange(33, 65))
            val peer = KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), (kp.public as ECPublicKey).params))
            val z = KeyAgreement.getInstance("ECDH").run { init(kp.private); doPhase(peer, true); generateSecret() }
            val key = SecureChannel.hkdf(token, z, "wakeup-v1".toByteArray() + hexBytes(devId) + phoneId)
            return phoneId to key
        }

        /** handleAuth() と seal()（方向 = M5Stack → スマホ） */
        fun sessionKey(key: ByteArray, auth: ByteArray): ByteArray? {
            val nonceP = auth.copyOfRange(9, 25)
            val mac = auth.copyOfRange(25, 57)
            if (!mac.contentEquals(SecureChannel.hmac(key, "auth".toByteArray(), nonce, nonceP))) return null
            return SecureChannel.hkdf(nonce + nonceP, key, "session".toByteArray())
        }

        fun seal(sessionKey: ByteArray, ctr: Int, text: String, dir: Byte = 1): ByteArray {
            val header = ByteBuffer.allocate(5).put(SecureChannel.FRAME_DATA).putInt(ctr).array()
            val iv = ByteBuffer.allocate(12).put(dir).put(ByteArray(7)).putInt(ctr).array()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(header)
            return header + c.doFinal(text.toByteArray())
        }

        companion object {
            fun unsigned(n: BigInteger): ByteArray {
                val b = n.toByteArray()
                return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
            }
            fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
            fun hexBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = SecureChannel.hkdf(
            salt = hex("000102030405060708090a0b0c"),
            ikm = ByteArray(22) { 0x0b },
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun pairAuthAndExchangeFrames() {
        val dev = FakeDevice()
        val qr = SecureChannel.parseQr(dev.qr())!!
        val (pairFrame, pairing) = SecureChannel.buildPair(qr)
        assertEquals(106, pairFrame.size)
        val (phoneId, deviceKey) = dev.acceptPair(pairFrame)!!
        assertArrayEquals(pairing.phoneId, phoneId)
        assertArrayEquals(pairing.key, deviceKey)

        val hello = SecureChannel.parseHello(dev.hello(pairing = false))!!
        assertEquals(dev.devId, hello.devId)
        val (auth, session) = SecureChannel.buildAuth(pairing, hello)
        val sk = dev.sessionKey(deviceKey, auth)!!

        // M5Stack → スマホ
        assertEquals("""{"s":"IDLE"}""", session.open(dev.seal(sk, 1, """{"s":"IDLE"}""")))
        // スマホ → M5Stack（M5Stack の鍵で復号できる）
        val sealed = session.seal("""{"cmd":"cancel"}""")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteBuffer.allocate(12).put(2).put(ByteArray(7)).put(sealed, 1, 4).array()
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sk, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(sealed, 0, 5)
        assertEquals("""{"cmd":"cancel"}""", String(c.doFinal(sealed, 5, sealed.size - 5)))
    }

    @Test
    fun rejectsTamperReplayAndReflection() {
        val dev = FakeDevice()
        val (_, pairing) = SecureChannel.buildPair(SecureChannel.parseQr(dev.qr())!!)
        val (_, deviceKey) = dev.acceptPair(SecureChannel.buildPair(SecureChannel.parseQr(dev.qr())!!).first)!!
        val hello = SecureChannel.parseHello(dev.hello(false))!!

        // 鍵が違う（QR を見ていない端末）
        val (auth, session) = SecureChannel.buildAuth(pairing, hello)
        assertNull(dev.sessionKey(deviceKey, auth))

        val sk = dev.sessionKey(pairing.key, auth)!!
        val frame = dev.seal(sk, 1, """{"l":0}""")
        val tampered = frame.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() }
        assertThrows(SecurityException::class.java) { session.open(tampered) }
        session.open(frame)
        assertThrows(SecurityException::class.java) { session.open(frame) }  // リプレイ
        // スマホが送った方向のフレームを送り返されても通らない
        assertThrows(SecurityException::class.java) { session.open(dev.seal(sk, 2, """{"l":0}""", dir = 2)) }
    }

    @Test
    fun parseQrRejectsBrokenOrSwappedKey() {
        val dev = FakeDevice()
        assertNotNull(SecureChannel.parseQr(dev.qr()))
        assertNull(SecureChannel.parseQr("https://example.com"))
        assertNull(SecureChannel.parseQr(dev.qr().replace("WAKEUP1", "WAKEUP2")))
        // 公開鍵だけ別のものに差し替えた QR（devId と合わない）
        val other = FakeDevice()
        assertNull(SecureChannel.parseQr("WAKEUP1:${dev.devId}:${FakeDevice.b64(other.pub)}:${FakeDevice.b64(dev.token)}"))
    }
}
