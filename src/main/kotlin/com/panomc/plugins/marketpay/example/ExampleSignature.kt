package com.panomc.plugins.marketpay.example

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook authenticity of the imaginary gateway, as pure functions of bytes and strings (no `ctx`, no I/O).
 *
 * Header `Example-Signature: t=<unix seconds>,v1=<hex>[,v1=<hex>...]`, where `v1 = HMAC-SHA256(secret, "<t>." + rawBody)`
 * as lower-case hex. The gateway sends several `v1` values while a secret is being rotated; one match is enough.
 * The signature covers the raw request bytes: re-encoded JSON (other key order, other whitespace) does not verify.
 */
object ExampleSignature {
    const val HEADER = "Example-Signature"

    /** Accepted distance between the signed timestamp and `ctx.now()`, in either direction. */
    const val TOLERANCE_SECONDS = 300L

    /** Notifications are small; anything larger is refused before any hashing. */
    const val MAX_BODY_BYTES = 512 * 1024

    enum class Verdict { VALID, MISSING_HEADER, MALFORMED_HEADER, MISMATCH, TIMESTAMP_OUTSIDE_TOLERANCE }

    private val TIMESTAMP = Regex("^[0-9]{1,13}$")
    private val HEX_SIGNATURE = Regex("^[0-9a-fA-F]{64}$")

    /** Lower-case hex of `HMAC-SHA256(secret, "<timestampSeconds>." + body)`. */
    fun sign(secret: String, timestampSeconds: Long, body: ByteArray): String {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$timestampSeconds.".toByteArray(Charsets.US_ASCII))
        mac.update(body)
        return hex(mac.doFinal())
    }

    /** The header value the gateway would send. */
    fun header(secret: String, timestampSeconds: Long, body: ByteArray): String =
        "t=$timestampSeconds,v1=${sign(secret, timestampSeconds, body)}"

    fun verify(secret: String, header: String?, body: ByteArray, nowMs: Long, toleranceSeconds: Long = TOLERANCE_SECONDS): Verdict {
        if (header.isNullOrBlank()) return Verdict.MISSING_HEADER
        var timestamp: Long? = null
        val signatures = ArrayList<String>()
        for (part in header.split(',')) {
            val piece = part.trim()
            val eq = piece.indexOf('=')
            if (eq <= 0) return Verdict.MALFORMED_HEADER
            val name = piece.substring(0, eq)
            val value = piece.substring(eq + 1)
            when (name) {
                "t" -> {
                    if (timestamp != null || !TIMESTAMP.matches(value)) return Verdict.MALFORMED_HEADER
                    timestamp = value.toLong()
                }
                "v1" -> {
                    if (!HEX_SIGNATURE.matches(value)) return Verdict.MALFORMED_HEADER
                    signatures.add(value.lowercase())
                }
                else -> Unit // a later scheme (v2, ...) is ignored
            }
        }
        if (timestamp == null || signatures.isEmpty()) return Verdict.MALFORMED_HEADER
        val expected = sign(secret, timestamp, body).toByteArray(Charsets.US_ASCII)
        // Compare every candidate (no early exit on the first hit) in constant time per comparison.
        var matched = false
        for (candidate in signatures) {
            if (MessageDigest.isEqual(expected, candidate.toByteArray(Charsets.US_ASCII))) matched = true
        }
        if (!matched) return Verdict.MISMATCH
        val distanceSeconds = Math.abs(nowMs / 1000L - timestamp)
        return if (distanceSeconds > toleranceSeconds) Verdict.TIMESTAMP_OUTSIDE_TOLERANCE else Verdict.VALID
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
