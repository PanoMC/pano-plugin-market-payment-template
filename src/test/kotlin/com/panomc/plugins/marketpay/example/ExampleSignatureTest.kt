package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.marketpay.example.ExampleSignature.Verdict
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** P-05 to P-08: the signature as pure functions, then the same verdicts through `handleInbound` (reply 400, nothing applied). */
class ExampleSignatureTest {
    private class Vector(val name: String, val secret: String, val timestamp: Long, val input: String, val expected: String)

    private fun loadVectors(): Pair<String, List<Vector>> {
        val text = javaClass.getResourceAsStream("/vectors/webhook.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val json = JsonObject(text)
        val kind = json.getString("kind").lowercase().replace('_', '-')
        return kind to json.getJsonArray("cases").map { it as JsonObject }.map {
            Vector(it.getString("name"), it.getString("secret"), it.getLong("timestamp"), it.getString("input"), it.getString("expected"))
        }
    }

    // Display names start with "official -" or "self-derived -" (16 section 8.3).
    @TestFactory
    fun `P-05 signature vectors`(): List<DynamicTest> {
        val (kind, vectors) = loadVectors()
        assertTrue(vectors.isNotEmpty(), "the vector file holds no case")
        return vectors.map { v ->
            DynamicTest.dynamicTest("$kind - ${v.name}") {
                val body = v.input.toByteArray(Charsets.UTF_8)
                assertEquals(v.expected, ExampleSignature.sign(v.secret, v.timestamp, body))
                val header = "t=${v.timestamp},v1=${v.expected}"
                assertEquals(Verdict.VALID, ExampleSignature.verify(v.secret, header, body, v.timestamp * 1000L))
                assertEquals(header, ExampleSignature.header(v.secret, v.timestamp, body))
            }
        }
    }

    private val body = Hooks.envelope(Hooks.payment()).encode().toByteArray()
    private val good = ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS, body)

    private fun verdict(secret: String = WEBHOOK_SECRET, header: String? = good, bytes: ByteArray = body, nowMs: Long = TestContexts.START_MS) =
        ExampleSignature.verify(secret, header, bytes, nowMs)

    @Test
    fun `P-06 a flipped byte, a flipped signature, a wrong secret or a bad header never verify`() {
        assertEquals(Verdict.VALID, verdict())
        val flippedBody = body.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertEquals(Verdict.MISMATCH, verdict(bytes = flippedBody))
        val sig = good.substringAfter("v1=")
        val flippedSig = good.replace(sig, (if (sig[0] == '0') "1" else "0") + sig.substring(1))
        assertEquals(Verdict.MISMATCH, verdict(header = flippedSig))
        assertEquals(Verdict.MISMATCH, verdict(secret = WEBHOOK_SECRET + "x"))
        assertEquals(Verdict.MISSING_HEADER, verdict(header = null))
        assertEquals(Verdict.MISSING_HEADER, verdict(header = "  "))
        for (bad in listOf(
            "v1=$sig", // no timestamp
            "t=abc,v1=$sig", // timestamp is not a number
            "t=$NOW_SECONDS", // no signature
            "t=$NOW_SECONDS,v1=zz", // signature is not hex
            "t=$NOW_SECONDS,v1=${sig.take(63)}", // one nibble short
            "t=$NOW_SECONDS,t=$NOW_SECONDS,v1=$sig", // two timestamps
            "t=-1,v1=$sig", // negative timestamp
            "garbage",
            "t=$NOW_SECONDS,,v1=$sig"
        )) assertEquals(Verdict.MALFORMED_HEADER, verdict(header = bad), "header '$bad'")
    }

    @Test
    fun `P-06 the same failures through handleInbound answer 400, verified false and no event`(): Unit = env { env ->
        val request = Hooks.request(body, good)
        val flipped = body.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        for (http in listOf(
            Hooks.request(flipped, good),
            Hooks.request(body, null),
            Hooks.request(body, "t=abc,v1=00"),
            Hooks.request(body, ExampleSignature.header(WEBHOOK_SECRET + "x", NOW_SECONDS, body))
        )) {
            val result = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }
            assertFalse(result.verified)
            assertEquals(400, result.reply.status)
            assertTrue(result.events.isEmpty())
            assertEquals(null, result.eventKey)
            assertTrue(result.rejectReason!!.startsWith("signature:"), result.rejectReason)
        }
        // The control: the unmodified request is accepted by the same provider.
        assertTrue(blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(request)) }.verified)
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `P-07 the signature covers the raw bytes, not the parsed JSON`() {
        val compact = """{"id":"evt_1","type":"payment.updated"}""".toByteArray()
        val spaced = """{ "type": "payment.updated",  "id": "evt_1" }""".toByteArray()
        assertEquals(JsonObject(String(compact)), JsonObject(String(spaced))) // same parsed JSON ...
        val header = ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS, compact)
        assertEquals(Verdict.VALID, verdict(header = header, bytes = compact))
        assertEquals(Verdict.MISMATCH, verdict(header = header, bytes = spaced)) // ... different bytes, different verdict
        assertNotEquals(ExampleSignature.sign(WEBHOOK_SECRET, NOW_SECONDS, compact), ExampleSignature.sign(WEBHOOK_SECRET, NOW_SECONDS, spaced))
        // A trailing newline added in transit breaks it too.
        assertEquals(Verdict.MISMATCH, verdict(header = header, bytes = compact + "\n".toByteArray()))
    }

    @Test
    fun `P-08 the timestamp window is 300 seconds either way and follows the context clock`() {
        for (offset in listOf(-300L, 0L, 300L)) {
            val t = NOW_SECONDS + offset
            assertEquals(Verdict.VALID, verdict(header = ExampleSignature.header(WEBHOOK_SECRET, t, body)), "offset $offset")
        }
        for (offset in listOf(-301L, 301L, -86_400L)) {
            val t = NOW_SECONDS + offset
            assertEquals(Verdict.TIMESTAMP_OUTSIDE_TOLERANCE, verdict(header = ExampleSignature.header(WEBHOOK_SECRET, t, body)), "offset $offset")
        }
        // A wrong signature with a stale timestamp is a mismatch first: the window is only consulted for an authentic request.
        assertEquals(Verdict.MISMATCH, verdict(secret = "other-secret", header = ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS - 10_000, body)))
    }

    @Test
    fun `P-08 handleInbound uses ctx now, a replayed request is accepted inside the window and rejected after it`(): Unit = env { env ->
        val http = Hooks.request(body, good)
        assertTrue(blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }.verified)
        env.ctx.advance(299_000)
        assertTrue(blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }.verified)
        env.ctx.advance(2_000) // 301 s after the signed timestamp
        val late = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }
        assertFalse(late.verified)
        assertEquals(400, late.reply.status)
        assertTrue(late.rejectReason!!.contains("timestamp_outside_tolerance"))
        // A timestamp from the future beyond the window is refused as well.
        val future = ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS + 301 + 301, body)
        assertFalse(blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(body, future))) }.verified)
    }

    @Test
    fun `secret rotation, several v1 values and an unknown scheme`() {
        val other = ExampleSignature.sign("old-secret-value", NOW_SECONDS, body)
        val current = good.substringAfter("v1=")
        assertEquals(Verdict.VALID, verdict(header = "t=$NOW_SECONDS,v1=$other,v1=$current"))
        assertEquals(Verdict.VALID, verdict(header = "t=$NOW_SECONDS,v2=future-scheme,v1=$current"))
        assertEquals(Verdict.VALID, verdict(header = "t=$NOW_SECONDS,v1=${current.uppercase()}"))
        assertEquals(Verdict.MISMATCH, verdict(header = "t=$NOW_SECONDS,v1=$other"))
    }

    @Test
    fun `an oversized body is refused before it is hashed`(): Unit = env { env ->
        val big = ByteArray(ExampleSignature.MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
        val result = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(big, ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS, big)))) }
        assertFalse(result.verified)
        assertEquals("body too large", result.rejectReason)
    }
}
