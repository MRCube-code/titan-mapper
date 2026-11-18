package com.titanmapper.core.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional backup encryption with a user passphrase (AES-256-GCM, key from PBKDF2-HMAC-SHA256).
 *
 * Deliberately passphrase-based rather than Android Keystore-based: a Keystore key never leaves
 * the device, so a backup encrypted with it could not be restored on a new phone, which is the
 * main reason to have a backup. The trade-off is that a lost passphrase means lost backup.
 *
 * Blob layout: "TMB1" | salt(16) | iv(12) | ciphertext+tag
 */
object PassphraseCrypto {
    private val MAGIC = "TMB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun isEncrypted(blob: ByteArray): Boolean =
        blob.size > MAGIC.size + SALT_LEN + IV_LEN && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** @throws GeneralSecurityException on a wrong passphrase or tampered data (GCM authenticates both). */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (!isEncrypted(blob)) throw GeneralSecurityException("Not an encrypted backup")
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + IV_LEN)
        val body = blob.copyOfRange(MAGIC.size + SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return cipher.doFinal(body)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
