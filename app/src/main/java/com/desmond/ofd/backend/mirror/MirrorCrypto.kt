package com.desmond.ofd.backend.mirror

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing + response/body crypto for the secondary "latest" firmware source.
 * Every parameter below was verified against the live endpoint.
 *
 *  - Path signing: AES/CBC of `path[?query]`, wrapped as `0x80 ‖ epochSec(8, BE) ‖ iv ‖ ct`
 *    with an appended `HMAC-SHA256`, base64url (padding kept); the wire path becomes
 *    `/{bucket}/{token}` and the real path/query live only inside the token.
 *  - Response decryption: `AES/CTR` using the base64 key/iv carried in the response headers.
 *  - Body encryption (non-GET): fresh random `AES-256/CTR`; key/iv travel in request headers.
 *
 * The signing key is split from `SHA-256(secret)`: bytes 0..15 = HMAC key, 16..31 = AES key.
 */
internal class MirrorCrypto(secret: String) {

    private val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    private val hmacKey = digest.copyOfRange(0, 16)
    private val aesKey = digest.copyOfRange(16, 32)
    private val random = SecureRandom()
    private val std = Base64.getEncoder()
    private val stdDec = Base64.getDecoder()
    private val urlEnc = Base64.getUrlEncoder() // URL-safe, padding retained, no wrapping

    /** Sign an already-encoded `path[?query]` into the `/{bucket}/{token}` wire path. */
    fun signColorOsPath(encodedPathWithQuery: String, nowSeconds: Long): String {
        val iv = ByteArray(16).also(random::nextBytes)
        val ct = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(encodedPathWithQuery.toByteArray(Charsets.UTF_8))
        }
        val blob = ByteBuffer.allocate(1 + 8 + 16 + ct.size)
            .put(0x80.toByte())
            .putLong(nowSeconds) // 8 bytes, big-endian
            .put(iv)
            .put(ct)
            .array()
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hmacKey, "HmacSHA256"))
            doFinal(blob)
        }
        val token = urlEnc.encodeToString(blob + mac)
        val bucket = 100 + random.nextInt(100) // ColorOS bucket range 100..199
        return "/$bucket/$token"
    }

    /** Decrypt an `x-encrypted-data: true` response body with its header-supplied key/iv. */
    fun decryptResponse(base64Body: String, keyB64: String, ivB64: String): String {
        val key = stdDec.decode(keyB64.trim())
        val iv = stdDec.decode(ivB64.trim())
        val plain = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(stdDec.decode(base64Body.trim()))
        }
        return String(plain, Charsets.UTF_8)
    }

    data class EncryptedBody(val body: String, val keyB64: String, val ivB64: String)

    /** Encrypt a JSON body for a non-GET request; the fresh key/iv are sent as headers. */
    fun encryptBody(json: String): EncryptedBody {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val ct = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(json.toByteArray(Charsets.UTF_8))
        }
        return EncryptedBody(std.encodeToString(ct), std.encodeToString(key), std.encodeToString(iv))
    }
}
