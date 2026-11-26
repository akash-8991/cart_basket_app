package com.smartcart.server.security

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Server-side keyed hashing derived from one master secret (SMARTCART_SECRET_KEY): IP hashes, recovery
 * code hashes, idempotency request fingerprints and signed challenge IDs each use their own sub-key.
 */
class Secrets(private val master: ByteArray) {
    init { require(master.size >= 32) { "secret key must be at least 32 bytes" } }

    fun subKey(label: String): ByteArray = hmac(master, label.toByteArray())

    fun hmac(label: String, data: String): ByteArray = hmac(subKey(label), data.toByteArray())

    fun hmacHex(label: String, data: String): String = hmac(label, data).joinToString("") { "%02x".format(it) }

    /**
     * Challenge IDs carry their issue time and a MAC, so the server can tell when *any* challenge ID
     * expires — including ones issued for non-existent accounts — and answer both cases identically.
     */
    fun signedChallengeId(issuedAt: Instant, random: SecureRandom = SecureRandom()): String {
        val body = ByteBuffer.allocate(8 + 12).putLong(issuedAt.toEpochMilli()).put(ByteArray(12).also(random::nextBytes)).array()
        val mac = hmac(subKey("challenge-id"), body).copyOf(16)
        return "chl_" + B64.encodeToString(body + mac)
    }

    /** Issue time of a challenge ID we signed, or null if it is malformed or forged. */
    fun challengeIssuedAt(challengeId: String): Instant? {
        val raw = runCatching { B64D.decode(challengeId.removePrefix("chl_")) }.getOrNull() ?: return null
        if (!challengeId.startsWith("chl_") || raw.size != 36) return null
        val body = raw.copyOfRange(0, 20)
        if (!MessageDigest.isEqual(hmac(subKey("challenge-id"), body).copyOf(16), raw.copyOfRange(20, 36))) return null
        return Instant.ofEpochMilli(ByteBuffer.wrap(body).long)
    }

    companion object {
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()

        fun random(): Secrets = Secrets(ByteArray(32).also(SecureRandom()::nextBytes))

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}
