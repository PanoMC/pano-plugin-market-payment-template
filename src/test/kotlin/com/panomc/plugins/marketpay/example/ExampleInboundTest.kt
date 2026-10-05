package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.ReviewReason
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P-13 to P-16 and P-18: what `handleInbound` does with authentic notifications. */
class ExampleInboundTest {
    private fun deliver(env: Env, body: JsonObject, kind: InboundKind = InboundKind.WEBHOOK, attempt: PaymentAttemptView? = null): InboundResult =
        blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.signed(body, kind = kind), attempt)) }

    private fun paymentEvent(env: Env, payment: JsonObject, kind: InboundKind = InboundKind.WEBHOOK) = deliver(env, Hooks.envelope(payment), kind).events.single()

    @Test
    fun `P-13 a signed paid notification is verified, carries the gateway amount and key, and gets the literal reply`(): Unit = env { env ->
        val result = deliver(env, Hooks.envelope(Hooks.payment(status = "paid", amount = 1000, amountPaid = 1000).put("livemode", true), id = "evt_77"))
        assertTrue(result.verified)
        assertNull(result.rejectReason)
        assertEquals("evt_77", result.eventKey)
        assertEquals(200, result.reply.status)
        assertEquals("OK", String(result.reply.body))
        assertFalse(result.reply.orderPage)
        val event = result.events.single() as PaymentEvent.Succeeded
        assertEquals(eur(1000), event.paid)
        assertEquals("pay_1", event.gatewayTransactionId)
        assertEquals(REF, (event.target as PaymentTarget.Reference).reference)
        assertEquals(NOW_SECONDS * 1000L, event.occurredAt)
        assertEquals(false, event.testMode)
    }

    @Test
    fun `P-13 the paid amount is what the gateway states, in its own currency and unit`(): Unit = env { env ->
        assertEquals(Money(50_000, "JPY"), (paymentEvent(env, Hooks.payment(amount = 500, currency = "JPY", amountPaid = 500)) as PaymentEvent.Succeeded).paid)
        assertEquals(Money(1234, "TRY"), (paymentEvent(env, Hooks.payment(amount = 1234, currency = "TL", amountPaid = 1234)) as PaymentEvent.Succeeded).paid)
        // The requested amount is 1000 but 1050 arrived (instalment surcharge): the event says 1050, nothing is adjusted.
        assertEquals(eur(1050), (paymentEvent(env, Hooks.payment(amount = 1000, amountPaid = 1050)) as PaymentEvent.Succeeded).paid)
    }

    @Test
    fun `P-13 NOTIFY of the right attempt is accepted the same way`(): Unit = env { env ->
        val result = deliver(env, Hooks.envelope(Hooks.payment()), InboundKind.NOTIFY, attempt())
        assertTrue(result.verified)
        assertTrue(result.events.single() is PaymentEvent.Succeeded)
        assertEquals("evt_1", result.eventKey)
    }

    @Test
    fun `P-13 refund updates and non-success payment states are reported as stated`(): Unit = env { env ->
        (deliver(env, Hooks.envelope(Hooks.refund("succeeded", 500), type = "refund.updated", id = "evt_r1")).events.single() as PaymentEvent.RefundUpdated).also {
            assertEquals(RefundState.SUCCEEDED, it.state)
            assertEquals(eur(500), it.amount)
            assertEquals("rf_1", it.gatewayRefundId)
            assertEquals("refund-key-1", it.refundKey)
        }
        assertEquals(RefundState.PENDING, (deliver(env, Hooks.envelope(Hooks.refund("pending"), type = "refund.updated")).events.single() as PaymentEvent.RefundUpdated).state)
        assertTrue(paymentEvent(env, Hooks.payment(status = "failed")) is PaymentEvent.Failed)
        assertTrue(paymentEvent(env, Hooks.payment(status = "expired")) is PaymentEvent.Expired)
        assertTrue(paymentEvent(env, Hooks.payment(status = "cancelled")) is PaymentEvent.Cancelled)
        assertTrue(paymentEvent(env, Hooks.payment(status = "pending")) is PaymentEvent.Pending)
    }

    @Test
    fun `P-14 the same delivery twice gives the same key and the same events`(): Unit = env { env ->
        val http = Hooks.signed(Hooks.envelope(Hooks.payment(), id = "evt_dup"))
        val first = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }
        val second = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }
        assertEquals("evt_dup", first.eventKey)
        assertEquals(first.eventKey, second.eventKey)
        assertEquals(first.events.size, second.events.size)
        val a = first.events.single() as PaymentEvent.Succeeded
        val b = second.events.single() as PaymentEvent.Succeeded
        assertEquals(a.paid, b.paid)
        assertEquals(a.gatewayTransactionId, b.gatewayTransactionId)
        assertEquals((a.target as PaymentTarget.Reference).reference, (b.target as PaymentTarget.Reference).reference)
        // The key is the gateway's event id: a different delivery has a different key.
        assertEquals("evt_other", deliver(env, Hooks.envelope(Hooks.payment(), id = "evt_other")).eventKey)
        // An unsigned query parameter or header does not mint a new key (the contract suite checks this too).
        val withQuery = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.signed(Hooks.envelope(Hooks.payment(), id = "evt_dup"), rawQuery = "utm=1"))) }
        assertEquals("evt_dup", withQuery.eventKey)
    }

    @Test
    fun `P-15 unknown types, unknown statuses, other objects and unreadable bodies are ignored with the literal reply`(): Unit = env { env ->
        fun assertIgnored(result: InboundResult, what: String) {
            assertTrue(result.verified, "$what: authentic")
            assertTrue(result.events.isEmpty(), "$what: no event")
            assertNull(result.eventKey, "$what: no key")
            assertEquals(200, result.reply.status, what)
            assertEquals("OK", String(result.reply.body), what)
        }
        assertIgnored(deliver(env, Hooks.envelope(JsonObject(), type = "customer.created")), "unknown type")
        assertIgnored(deliver(env, Hooks.envelope(Hooks.payment(status = "mystery"))), "unknown status")
        assertIgnored(deliver(env, Hooks.envelope(null)), "no data object")
        assertIgnored(deliver(env, Hooks.envelope(JsonObject().put("status", "paid"))), "no identity")
        assertIgnored(deliver(env, Hooks.envelope(Hooks.refund("mystery"), type = "refund.updated")), "unknown refund status")
        // A NOTIFY for attempt A that talks about another reference changes nothing.
        assertIgnored(deliver(env, Hooks.envelope(Hooks.payment(reference = "ZZZZZZZZZZZZZZZZZZZZ")), InboundKind.NOTIFY, attempt()), "another merchant object")
        // Authentic bytes that are not JSON / not an object.
        for (raw in listOf("not json at all", "[]", "null", "")) {
            val bytes = raw.toByteArray()
            val http = Hooks.request(bytes, ExampleSignature.header(WEBHOOK_SECRET, NOW_SECONDS, bytes))
            assertIgnored(blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(http)) }, "body '$raw'")
        }
    }

    @Test
    fun `P-16 the gateway signs its notifications, so none needs a re-query (the unsigned path does not apply)`(): Unit = env { env ->
        // A signed success is believed on its signature alone: nothing is fetched. A bad signature fetches nothing either.
        assertTrue(deliver(env, Hooks.envelope(Hooks.payment())).events.single() is PaymentEvent.Succeeded)
        val unsigned = blocking { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(Hooks.envelope(Hooks.payment()).encode().toByteArray(), null))) }
        assertFalse(unsigned.verified)
        assertTrue(unsigned.events.isEmpty())
        assertTrue(env.gateway.requests.isEmpty(), "no outbound call on the webhook path")
    }

    @Test
    fun `P-18 underpaid, overpaid and wrong currency need review and are never Succeeded`(): Unit = env { env ->
        (paymentEvent(env, Hooks.payment(status = "underpaid", amount = 1000, amountPaid = 400)) as PaymentEvent.NeedsReview).also {
            assertEquals(ReviewReason.UNDERPAID, it.reason)
            assertEquals(eur(400), it.received)
        }
        (paymentEvent(env, Hooks.payment(status = "overpaid", amount = 1000, amountPaid = 1500)) as PaymentEvent.NeedsReview).also {
            assertEquals(ReviewReason.OVERPAID, it.reason)
            assertEquals(eur(1500), it.received)
        }
        (paymentEvent(env, Hooks.payment(status = "wrong_currency", amount = 1000).put("currencyPaid", "USD")) as PaymentEvent.NeedsReview).also {
            assertEquals(ReviewReason.CURRENCY_MISMATCH, it.reason)
            assertEquals(Money(1000, "USD"), it.received)
        }
        // A paid notification the SPI cannot represent is a review, not a dropped payment and not a guessed amount.
        (paymentEvent(env, Hooks.payment(status = "paid", currency = "XXX")) as PaymentEvent.NeedsReview).also { assertEquals(ReviewReason.CURRENCY_MISMATCH, it.reason) }
        (paymentEvent(env, Hooks.payment(status = "paid", amountPaid = null)) as PaymentEvent.NeedsReview).also { assertEquals(ReviewReason.OTHER, it.reason) }
        (paymentEvent(env, Hooks.payment(status = "paid").put("amountPaid", 10.5)) as PaymentEvent.NeedsReview).also { assertEquals(ReviewReason.OTHER, it.reason) }
        // The same through the browser return: the gateway's answer decides.
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "underpaid", amountPaid = 400)) }
        val returned = blocking {
            env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(ByteArray(0), null, InboundKind.RETURN), attempt(), ReturnOutcome.SUCCESS))
        }
        assertEquals(ReviewReason.UNDERPAID, (returned.events.single() as PaymentEvent.NeedsReview).reason)
        assertNotNull(returned.reply)
    }
}
