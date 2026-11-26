package com.smartcart.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.UUID

/** ES256 signing key pair for access tokens. Production supplies it; dev/test generate one per process. */
class SigningKeys(val public: ECPublicKey, val private: ECPrivateKey) {
    companion object {
        fun generate(): SigningKeys {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return SigningKeys(kp.public as ECPublicKey, kp.private as ECPrivateKey)
        }

        /** From base64 DER: PKCS#8 private key and X.509 public key (e.g. from `openssl pkcs8 -topk8 -nocrypt`). */
        fun fromBase64(privatePkcs8: String, publicX509: String): SigningKeys {
            val kf = KeyFactory.getInstance("EC")
            return SigningKeys(
                kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicX509))) as ECPublicKey,
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privatePkcs8))) as ECPrivateKey,
            )
        }
    }
}

/** Issues and verifies short-lived access tokens (D-018) and mints opaque refresh tokens. */
class Tokens(keys: SigningKeys, private val clock: Clock, val accessTtl: Duration = Duration.ofMinutes(15)) {
    private val algorithm = Algorithm.ECDSA256(keys.public, keys.private)
    private val random = SecureRandom()
    private val b64url = Base64.getUrlEncoder().withoutPadding()

    val verifier: JWTVerifier = (
        JWT.require(algorithm)
            .withIssuer(ISSUER).withAudience(AUDIENCE)
            .withClaimPresence("sid")
            .acceptLeeway(5) as JWTVerifier.BaseVerification
        ).build(clock) // same clock as issuance, so expiry is testable

    fun accessToken(userId: String, sessionId: String): String {
        val now = clock.instant()
        return JWT.create()
            .withIssuer(ISSUER).withAudience(AUDIENCE)
            .withSubject(userId)
            .withClaim("sid", sessionId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(accessTtl)))
            .sign(algorithm)
    }

    /** 256 random bits, URL-safe. Only [hash] of it is ever stored. */
    fun newRefreshToken(): String = b64url.encodeToString(ByteArray(32).also(random::nextBytes))

    fun newId(prefix: String): String = prefix + "_" + b64url.encodeToString(ByteArray(12).also(random::nextBytes))

    /** Six-digit recovery code. */
    fun newCode(): String = (random.nextInt(900_000) + 100_000).toString()

    companion object {
        const val ISSUER = "smartcart"
        const val AUDIENCE = "smartcart-app"

        fun hash(secret: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
    }
}
