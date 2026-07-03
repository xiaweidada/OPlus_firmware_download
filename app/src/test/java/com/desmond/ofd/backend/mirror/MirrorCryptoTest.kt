package com.desmond.ofd.backend.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Validates [MirrorCrypto] by reconstructing exactly what the server does to verify a request,
 * and by round-tripping the response/body cipher. Uses a throwaway secret, not the real one.
 */
class MirrorCryptoTest {

    private val secret = "Test\$Key#2025@Unit"
    private val crypto = MirrorCrypto(secret)

    private val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
    private val hmacKey = digest.copyOfRange(0, 16)
    private val aesKey = digest.copyOfRange(16, 32)

    @Test fun signed_path_is_structurally_valid_and_hmac_checks_out() {
        val epoch = 1_783_000_000L
        val path = "/api/coloros/fullVersions/OnePlus%2015"
        val wire = crypto.signColorOsPath(path, epoch)

        // "/{bucket}/{token}", bucket in the ColorOS range.
        val parts = wire.trimStart('/').split("/", limit = 2)
        assertEquals(2, parts.size)
        val bucket = parts[0].toInt()
        assertTrue("bucket $bucket in 100..199", bucket in 100..199)

        val raw = Base64.getUrlDecoder().decode(parts[1])
        val blob = raw.copyOfRange(0, raw.size - 32)
        val mac = raw.copyOfRange(raw.size - 32, raw.size)

        // Envelope layout: 0x80 | epoch(8, BE) | iv(16) | ct.
        assertEquals(0x80.toByte(), blob[0])
        val parsedEpoch = ByteBuffer.wrap(blob, 1, 8).long
        assertEquals(epoch, parsedEpoch)
        val iv = blob.copyOfRange(9, 25)
        val ct = blob.copyOfRange(25, blob.size)

        // HMAC over the whole blob must match the appended tag.
        val expectedMac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(hmacKey, "HmacSHA256")); doFinal(blob)
        }
        assertArrayEquals(expectedMac, mac)

        // The AES-CBC ciphertext must decrypt (with the derived key) back to the path.
        val decrypted = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(ct)
        }
        assertEquals(path, String(decrypted))
    }

    @Test fun body_encrypt_then_response_decrypt_round_trips() {
        val plaintext = """{"email":"x@y.z","device":"OnePlus 15","package_type":"full"}"""
        val enc = crypto.encryptBody(plaintext)
        // The response path uses the same AES-CTR with the header-carried key/iv.
        val decoded = crypto.decryptResponse(enc.body, enc.keyB64, enc.ivB64)
        assertEquals(plaintext, decoded)
    }

    @Test fun two_signatures_differ_by_random_iv_and_bucket() {
        val a = crypto.signColorOsPath("/api/coloros/devices/", 1_783_000_000L)
        val b = crypto.signColorOsPath("/api/coloros/devices/", 1_783_000_000L)
        assertTrue("random iv/bucket should make tokens differ", a != b)
    }
}
