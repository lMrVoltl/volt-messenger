package com.voltage.messenger.core.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedChannel(
    private val aesKey: SecretKeySpec,
    private val txSalt: ByteArray,
    private val rxSalt: ByteArray,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    val sessionEmojis: String
) {
    private var txSeq = 0L
    private var rxSeq = 0L

    private fun buildIv(salt: ByteArray, seq: Long): ByteArray {
        val buf = ByteBuffer.allocate(12)
        buf.put(salt, 0, 4)
        buf.putLong(seq)
        return buf.array()
    }

    @Synchronized
    fun writeFrame(plain: ByteArray, length: Int = plain.size) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = buildIv(txSalt, txSeq++)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain, 0, length)
        output.writeInt(encrypted.size)
        output.write(encrypted)
    }

    @Synchronized
    fun flush() {
        output.flush()
    }

    fun readFrame(maxSize: Int = 1024 * 1024): ByteArray {
        val len = input.readInt()
        if (len <= 0 || len > maxSize) throw IOException("Invalid encrypted frame size: $len")
        val encrypted = ByteArray(len)
        input.readFully(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = buildIv(rxSalt, rxSeq++)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}

object VoltCrypto {
    private val random = SecureRandom()
    private val emojiPool = listOf(
        "⚡", "🔒", "🔥", "🦊", "🌊", "🚀", "💎", "🛰️",
        "🌙", "🎯", "🛡️", "🎧", "❄️", "🍀", "🔑", "🪐"
    )

    fun emojisFromHash(bytes: ByteArray): String {
        return (0 until 4).joinToString(" ") { idx ->
            val b = bytes[idx % bytes.size].toInt() and 0xFF
            emojiPool[b % emojiPool.size]
        }
    }

    fun handshake(
        input: DataInputStream,
        output: DataOutputStream,
        isClient: Boolean
    ): EncryptedChannel {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val myPubBytes = keyPair.public.encoded
        val mySalt = ByteArray(4).also { random.nextBytes(it) }

        val peerPubBytes: ByteArray
        val peerSalt: ByteArray

        if (isClient) {
            output.writeInt(myPubBytes.size)
            output.write(myPubBytes)
            output.write(mySalt)
            output.flush()

            val peerLen = input.readInt()
            if (peerLen !in 32..512) throw IOException("Bad peer key length")
            peerPubBytes = ByteArray(peerLen)
            input.readFully(peerPubBytes)
            peerSalt = ByteArray(4)
            input.readFully(peerSalt)
        } else {
            val peerLen = input.readInt()
            if (peerLen !in 32..512) throw IOException("Bad peer key length")
            peerPubBytes = ByteArray(peerLen)
            input.readFully(peerPubBytes)
            peerSalt = ByteArray(4)
            input.readFully(peerSalt)

            output.writeInt(myPubBytes.size)
            output.write(myPubBytes)
            output.write(mySalt)
            output.flush()
        }

        val kf = KeyFactory.getInstance("EC")
        val peerPub = kf.generatePublic(X509EncodedKeySpec(peerPubBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPub, true)
        val shared = ka.generateSecret()

        val keyHash = MessageDigest.getInstance("SHA-256").digest(shared)
        val aesKey = SecretKeySpec(keyHash, "AES")
        val emojis = emojisFromHash(keyHash)

        return EncryptedChannel(
            aesKey = aesKey,
            txSalt = mySalt,
            rxSalt = peerSalt,
            input = input,
            output = output,
            sessionEmojis = emojis
        )
    }
}
