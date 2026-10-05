package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.ReviewReason
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** P-09 and P-10: money and status mapping. */
class ExampleMapperTest {
    @Test
    fun `P-09 Money to wire amounts`() {
        // TRY 12.34 is 1234 internal units; the gateway writes the currency TL.
        ExampleMapper.toWire(Money(1234, "TRY")).also {
            assertEquals(1234L, it.minor)
            assertEquals("TL", it.currency)
        }
        // JPY 500 is 50000 internal units (value x 100) but 500 on the wire.
        ExampleMapper.toWire(Money(50_000, "JPY")).also {
            assertEquals(500L, it.minor)
            assertEquals("JPY", it.currency)
        }
        assertEquals(5L, ExampleMapper.toWire(eur(5)).minor)
        assertEquals(0L, ExampleMapper.toWire(eur(0)).minor)
        assertEquals(99_999_999_999L, ExampleMapper.toWire(eur(99_999_999_999L)).minor) // no precision loss on big amounts
    }

    @Test
    fun `P-09 wire amounts to Money`() {
        assertEquals(Money(1234, "TRY"), ExampleMapper.fromWire(1234, "TL"))
        assertEquals(Money(1234, "TRY"), ExampleMapper.fromWire(1234, "tl"))
        assertEquals(Money(1234, "TRY"), ExampleMapper.fromWire(1234, "TRY"))
        assertEquals(Money(50_000, "JPY"), ExampleMapper.fromWire(500, "JPY"))
        assertEquals(Money(1, "EUR"), ExampleMapper.fromWire(1, "EUR"))
        assertThrows(IllegalArgumentException::class.java) { ExampleMapper.fromWire(1, "KWD") } // three decimals: refused by the SPI
        assertNull(ExampleMapper.moneyOrNull(1, "XXX"))
        assertNull(ExampleMapper.moneyOrNull(null, "EUR"))
        assertNull(ExampleMapper.moneyOrNull(Long.MAX_VALUE, "JPY")) // x100 overflows: refused, never wrapped
    }

    @Test
    fun `P-09 a fractional wire amount is never rounded into money`() {
        // 12.34 as a JSON number is a Double: the reader treats it as absent, the event becomes a review, not a payment.
        val payment = JsonObject("""{"id":"pay_1","reference":"$REF","status":"paid","amount":1234,"currency":"EUR","amountPaid":12.34}""")
        val event = ExampleMapper.paymentEvent(payment, null)
        assertTrue(event is PaymentEvent.NeedsReview)
        assertEquals(ReviewReason.OTHER, (event as PaymentEvent.NeedsReview).reason)
        assertNull(JsonObject("""{"n":1.0}""").long("n"))
        assertNull(JsonObject("""{"n":"5"}""").long("n"))
        assertEquals(5L, JsonObject("""{"n":5}""").long("n"))
        assertEquals(9_000_000_000_000L, JsonObject("""{"n":9000000000000}""").long("n"))
    }

    @Test
    fun `P-09 the money code never uses a floating point type`() {
        val dir = File("src/main/kotlin")
        assertTrue(dir.isDirectory, "run the tests from the project directory")
        val offenders = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (!isComment && Regex("\\b(Double|Float|toDouble|toFloat)\\b|[0-9]\\.[0-9]+f?\\b").containsMatchIn(code)) "${file.name}:${i + 1}: $code" else null
            }
        }.toList()
        assertTrue(offenders.isEmpty(), "floating point in main sources:\n" + offenders.joinToString("\n"))
    }

    private fun event(status: String, vararg extra: Pair<String, Any>): PaymentEvent? {
        val json = Hooks.payment(status = status)
        extra.forEach { json.put(it.first, it.second) }
        return ExampleMapper.paymentEvent(json, 1_000L)
    }

    @Test
    fun `P-10 every documented payment status maps to its event`() {
        assertTrue(event("paid") is PaymentEvent.Succeeded)
        assertTrue(event("PAID") is PaymentEvent.Succeeded) // status compared case-insensitively
        assertEquals(PendingReason.AWAITING_BUYER, (event("pending") as PaymentEvent.Pending).reason)
        assertEquals(PendingReason.AWAITING_CONFIRMATIONS, (event("processing") as PaymentEvent.Pending).reason)
        assertEquals(ReviewReason.UNDERPAID, (event("underpaid", "amountPaid" to 400L) as PaymentEvent.NeedsReview).reason)
        assertEquals(ReviewReason.OVERPAID, (event("overpaid", "amountPaid" to 1500L) as PaymentEvent.NeedsReview).reason)
        assertEquals(ReviewReason.CURRENCY_MISMATCH, (event("wrong_currency", "currencyPaid" to "USD") as PaymentEvent.NeedsReview).reason)
        (event("failed", "failureCode" to "card_declined", "failureMessage" to "Declined") as PaymentEvent.Failed).also {
            assertEquals("card_declined", it.code)
            assertEquals("Declined", it.message)
            assertTrue(it.final)
        }
        assertEquals("failed", (event("failed") as PaymentEvent.Failed).code)
        assertTrue(event("expired") is PaymentEvent.Expired)
        assertTrue(event("cancelled") is PaymentEvent.Cancelled)
    }

    @Test
    fun `P-10 an unknown or missing status, or an object without identity, maps to nothing`() {
        assertNull(event("mystery"))
        assertNull(event(""))
        assertNull(ExampleMapper.paymentEvent(JsonObject().put("id", "pay_1"), null))
        assertNull(ExampleMapper.paymentEvent(JsonObject().put("status", "paid").put("amountPaid", 1).put("currency", "EUR"), null))
        assertNull(ExampleMapper.paymentEvent(JsonObject().put("id", 5).put("reference", 7).put("status", "paid"), null)) // wrong JSON types
    }

    @Test
    fun `succeeded carries what the gateway collected and its facts`() {
        val e = ExampleMapper.paymentEvent(
            Hooks.payment(amount = 1000, amountPaid = 1050).put("fee", 45).put("method", "card").put("livemode", false), 5_000L
        ) as PaymentEvent.Succeeded
        assertEquals(eur(1050), e.paid) // exactly what the gateway states, not the requested amount
        assertEquals(eur(45), e.gatewayFee)
        assertEquals("card", e.methodDetail)
        assertEquals("pay_1", e.gatewayTransactionId)
        assertEquals(5_000L, e.occurredAt)
        assertEquals(true, e.testMode)
        assertEquals(REF, (e.target as PaymentTarget.Reference).reference)
        // Without a reference the gateway id is the target.
        val byId = ExampleMapper.paymentEvent(Hooks.payment(reference = null), null)!!
        assertEquals("pay_1", (byId.target as PaymentTarget.GatewayTransaction).gatewayTransactionId)
        assertNull(byId.testMode) // livemode not stated
    }

    @Test
    fun `P-10 refund statuses and results`() {
        assertEquals(RefundState.SUCCEEDED, ExampleMapper.refundEvent(Hooks.refund("succeeded"), null)!!.state)
        assertEquals(RefundState.PENDING, ExampleMapper.refundEvent(Hooks.refund("pending"), null)!!.state)
        assertEquals(RefundState.FAILED, ExampleMapper.refundEvent(Hooks.refund("failed"), null)!!.state)
        assertEquals(RefundState.CANCELLED, ExampleMapper.refundEvent(Hooks.refund("cancelled"), null)!!.state)
        assertNull(ExampleMapper.refundEvent(Hooks.refund("mystery"), null))
        assertNull(ExampleMapper.refundEvent(JsonObject().put("status", "succeeded"), null)) // cannot be attributed
        ExampleMapper.refundEvent(Hooks.refund(), 7L)!!.also {
            assertEquals("rf_1", it.gatewayRefundId)
            assertEquals("refund-key-1", it.refundKey)
            assertEquals(eur(500), it.amount)
            assertEquals(7L, it.occurredAt)
        }
        assertTrue(ExampleMapper.refundResult(Hooks.refund("succeeded")) is RefundResult.Succeeded)
        assertTrue(ExampleMapper.refundResult(Hooks.refund("pending")) is RefundResult.Pending)
        (ExampleMapper.refundResult(Hooks.refund("failed").put("failureCode", "too_old")) as RefundResult.Failed).also { assertEquals("too_old", it.code) }
        assertTrue(ExampleMapper.refundResult(Hooks.refund("mystery")) is RefundResult.Unknown)
        assertFalse(ExampleMapper.refundResult(Hooks.refund("succeeded")).refundedAmount == null)
    }
}
