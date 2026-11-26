package com.smartcart.server.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing (D-018), stored as a PHC string:
 * `$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>`. Parameters live in the string, so they can be raised
 * later and old hashes still verify.
 */
class Passwords(
    private val memoryKiB: Int = 19_456,
    private val iterations: Int = 2,
    private val parallelism: Int = 1,
    private val random: SecureRandom = SecureRandom(),
) {
    private val b64 = Base64.getEncoder().withoutPadding()
    private val b64d = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val out = derive(password, salt, memoryKiB, iterations, parallelism)
        return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${b64.encodeToString(salt)}\$${b64.encodeToString(out)}"
    }

    fun verify(password: String, phc: String): Boolean {
        val parts = phc.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = b64d.decode(parts[4])
        val expected = b64d.decode(parts[5])
        val actual = derive(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        return MessageDigest.isEqual(expected, actual)
    }

    /**
     * A real hash of a random password. Login verifies against it when the user ID doesn't exist, so an
     * unknown user costs the same time as a wrong password (no enumeration by timing).
     */
    val dummyHash: String by lazy { hash(java.util.UUID.randomUUID().toString()) }

    private fun derive(password: String, salt: ByteArray, m: Int, t: Int, p: Int, len: Int = 32): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt).withMemoryAsKB(m).withIterations(t).withParallelism(p).build(),
        )
        return ByteArray(len).also { gen.generateBytes(password.toCharArray(), it) }
    }
}

/** Rejects passwords that meet the length rule but are still easy to guess. */
object PasswordPolicy {
    // Most common passwords that are 10+ characters (subset of public breach corpora). A full breached-
    // password check (k-anonymity range query) needs an outbound dependency and is a Phase 5 item.
    private val common = setOf(
        "1234567890", "0123456789", "qwertyuiop", "1q2w3e4r5t", "password12", "password123", "password1234",
        "iloveyou12", "qwerty1234", "1qaz2wsx3edc", "asdfghjkl1", "abcdefghij", "abc1234567", "1111111111",
        "0000000000", "aaaaaaaaaa", "123123123123", "zaq12wsxcde", "passw0rd12", "welcome123", "letmein123",
        "sunshine12", "football12", "princess12", "baseball12", "superman12", "trustno1234", "admin12345",
    )

    /** True if [password] should be refused with `weak_password`. */
    fun isWeak(password: String, userId: String, email: String): Boolean {
        val p = password.lowercase()
        if (p in common) return true
        if (p.toSet().size < 4) return true
        if (userId.length >= 4 && userId.lowercase() in p) return true
        val local = email.substringBefore('@').lowercase()
        if (local.length >= 4 && local in p) return true
        return false
    }
}
