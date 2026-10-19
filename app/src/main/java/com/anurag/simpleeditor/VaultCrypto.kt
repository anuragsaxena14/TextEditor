package com.anurag.simpleeditor

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted file format (.sev):
 *
 *   "SEV1" (4 bytes) | version (1) | PBKDF2 iterations (4, big-endian) |
 *   salt (16) | IV (12) | AES-256-GCM ciphertext + 16-byte auth tag
 *
 * - Key = PBKDF2-HMAC-SHA256(password, salt, iterations), 256 bits.
 * - The whole header is authenticated (GCM additional data), so any
 *   tampering with the file, header included, makes decryption fail.
 * - A fresh random IV is used for every save.
 *
 * Only standard Android/Java crypto is used; no third-party code.
 */
object VaultCrypto {

    const val ITERATIONS = 600_000            // OWASP recommendation for PBKDF2-SHA256
    private const val VERSION: Byte = 1
    private val MAGIC = "SEV1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val HEADER_LEN = 4 + 1 + 4 + SALT_LEN + IV_LEN

    private val random = SecureRandom()

    class Header(val iterations: Int, val salt: ByteArray, val iv: ByteArray)

    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= HEADER_LEN && (0 until 4).all { data[it] == MAGIC[it] }

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { random.nextBytes(it) }

    /** Slow on purpose (~1 s). Call from a background thread. Does not wipe [password]. */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun readHeader(data: ByteArray): Header {
        if (!isEncrypted(data) || data.size < HEADER_LEN + TAG_BITS / 8) {
            throw GeneralSecurityException("Not an encrypted file")
        }
        val buf = ByteBuffer.wrap(data)
        buf.position(4)
        val version = buf.get()
        if (version != VERSION) throw GeneralSecurityException("Unsupported file version $version")
        val iterations = buf.int
        if (iterations < 100_000 || iterations > 10_000_000) {
            throw GeneralSecurityException("Corrupted file header")
        }
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        return Header(iterations, salt, iv)
    }

    fun encrypt(plain: ByteArray, key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** Throws AEADBadTagException on a wrong password or a damaged/tampered file. */
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val h = readHeader(data)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, h.iv))
        cipher.updateAAD(data, 0, HEADER_LEN)
        return cipher.doFinal(data, HEADER_LEN, data.size - HEADER_LEN)
    }
}
