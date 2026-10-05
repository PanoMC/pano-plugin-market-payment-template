package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.payment.AttemptRef
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryReason
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundLine
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P-11, P-12, P-17, P-19 to P-21: start, return, query and refund against the FakeGateway. */
class ExampleFlowTest {
    private fun start(env: Env, amount: Money = eur(1000)): StartPaymentResult {
        val request = SampleData.startRequest(amount, providerId = env.provider.id)
        return blocking { env.provider.startPayment(env.ctx, request) }
    }

    // ---- P-11 / P-12: startPayment -----------------------------------------------------------------------------

    @Test
    fun `P-11 startPayment sends amount, reference, urls and idempotency key and redirects`(): Unit = env { env ->
        env.happyGateway()
        val request = SampleData.startRequest(eur(1000), providerId = env.provider.id)
        val result = blocking { env.provider.startPayment(env.ctx, request) }

        assertTrue(result is StartPaymentResult.Redirect)
        assertEquals("https://pay.examplepay.example/checkout/pay_9", (result as StartPaymentResult.Redirect).url)
        assertEquals("pay_9", result.gatewayTransactionId)
        assertEquals((NOW_SECONDS + 3600) * 1000L, result.expiresAt)
        assertEquals("REDIRECT", result.kind)

        val sent = env.gateway.requestsTo("/v1/payments").single()
        assertEquals("POST", sent.method)
        assertEquals("Bearer $API_KEY", sent.header("Authorization"))
        assertEquals("idem-1", sent.header("Idempotency-Key"))
        val body = JsonObject(sent.bodyText())
        assertEquals(REF, body.getString("reference"))
        assertEquals(1000L, body.getLong("amount"))
        assertEquals("EUR", body.getString("currency"))
        assertEquals(request.urls.success, body.getString("successUrl"))
        assertEquals(request.urls.cancel, body.getString("cancelUrl"))
        assertEquals(request.urls.notify, body.getString("notifyUrl"))
        assertEquals(request.expiresAt / 1000L, body.getLong("expiresAt"))
        assertEquals("steve@example.com", body.getString("customerEmail"))
        assertEquals("en-US", body.getString("locale"))
        // The settings of the test context carry a statement descriptor: it is cut to 22 characters.
        assertEquals(22, body.getString("statementDescriptor").length)
        assertFalse(sent.bodyText().contains(API_KEY), "the API key belongs in a header only")
        val json = result.toPaymentStartJson("en-US")
        assertEquals("REDIRECT", json.getString("kind"))
    }

    @Test
    fun `P-11 zero-decimal and aliased currencies go out in the gateway format`(): Unit = env { env ->
        env.happyGateway()
        start(env, Money(50_000, "JPY"))
        start(env, Money(1234, "TRY"))
        val bodies = env.gateway.requestsTo("/v1/payments").map { JsonObject(it.bodyText()) }
        assertEquals(500L, bodies[0].getLong("amount"))
        assertEquals("JPY", bodies[0].getString("currency"))
        assertEquals(1234L, bodies[1].getLong("amount"))
        assertEquals("TL", bodies[1].getString("currency"))
    }

    @Test
    fun `P-11 an unsupported currency and a subscription are refused before any request`(): Unit = env { env ->
        env.happyGateway()
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { start(env, Money(1000, "CAD")) }
        val base = SampleData.startRequest(eur(1000), providerId = env.provider.id)
        val plan = SubscriptionPlan(1, "plan-key", "Rank", eur(1000), IntervalUnit.MONTH, 1, null)
        val subscribed = StartPaymentRequest(
            base.attempt, base.amount, base.order, base.buyer, base.billing, base.shipping, plan, base.urls, base.idempotencyKey,
            base.locale, base.expiresAt, base.replaces
        )
        expectProviderError(ProviderErrorCode.UNSUPPORTED) { env.provider.startPayment(env.ctx, subscribed) }
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `P-12 gateway errors map to the codes of the error table`(): Unit = env { env ->
        val path = "/v1/payments"
        env.happyGateway()

        env.gateway.failNext(path, 401)
        expectProviderError(ProviderErrorCode.AUTHENTICATION) { start(env) }
        env.gateway.failNext(path, 403)
        expectProviderError(ProviderErrorCode.AUTHENTICATION) { start(env) }

        env.gateway.failNext(path, 429)
        expectProviderError(ProviderErrorCode.RATE_LIMITED) { start(env) }.also { assertTrue(it.retryable) }

        env.gateway.failNext(path, 500)
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { start(env) }.also { assertTrue(it.retryable) }
        env.gateway.failNext(path, 503)
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { start(env) }.also { assertTrue(it.retryable) }

        env.gateway.on("POST", path) { errorReply(422, "invalid_amount", "amount is below the minimum") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { start(env) }.also {
            assertEquals("amount is below the minimum", it.adminMessage)
            assertFalse(it.retryable)
        }

        env.gateway.on("POST", path) { errorReply(403, "ip_not_allowed", "198.51.100.7 is not allow-listed") }
        expectProviderError(ProviderErrorCode.IP_NOT_ALLOWED) { start(env) }

        env.gateway.on("POST", path) { errorReply(400, "invalid_api_key", "no such key") }
        expectProviderError(ProviderErrorCode.AUTHENTICATION) { start(env) }

        // An HTML challenge page where JSON was expected.
        env.gateway.on("POST", path) { Reply.text("<html><body>Just a moment...</body></html>", 200, "text/html; charset=utf-8") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { start(env) }.also { assertTrue(it.retryable) }

        // A body that is not JSON.
        env.gateway.on("POST", path) { Reply.text("this is not json", 200, "text/plain") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { start(env) }.also { assertEquals("this is not json", it.adminMessage) }

        // JSON without the fields we need, and a checkout url the SPI refuses.
        env.gateway.on("POST", path) { Reply.json("""{"id":"pay_1"}""") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { start(env) }
        env.gateway.on("POST", path) { Reply.json("""{"id":"pay_1","url":"javascript:alert(1)"}""") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { start(env) }
    }

    @Test
    fun `P-12 a redirect is never followed, so the API key cannot travel to another host`(): Unit = env { env ->
        env.gateway.on("POST", "/v1/payments") { Reply(302, listOf("Location" to env.gateway.baseUrl + "/elsewhere")) }
        env.gateway.on("POST", "/elsewhere") { Reply.json("{}") }
        env.gateway.on("GET", "/elsewhere") { Reply.json("{}") }
        expectProviderError(ProviderErrorCode.GATEWAY_REJECTED) { start(env) }
        assertTrue(env.gateway.requestsTo("/elsewhere").isEmpty(), "the redirect target was requested")
    }

    @Test
    fun `P-12 a gateway that never answers times out as unreachable and retryable`(): Unit = env(timeoutMs = 300) { env ->
        env.gateway.hang("/v1/payments")
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { start(env) }.also { assertTrue(it.retryable) }
    }

    @Test
    fun `P-12 a closed gateway is unreachable and retryable`(): Unit = env { env ->
        env.gateway.close()
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { start(env) }.also { assertTrue(it.retryable) }
    }

    // ---- P-17: RETURN ------------------------------------------------------------------------------------------

    private fun returned(env: Env, attempt: PaymentAttemptView?, outcome: ReturnOutcome = ReturnOutcome.SUCCESS, query: String = "status=paid&amount=1") =
        blocking {
            env.provider.handleInbound(
                env.ctx,
                Hooks.inbound(Hooks.request(ByteArray(0), null, InboundKind.RETURN, rawQuery = query), attempt, outcome)
            )
        }

    @Test
    fun `P-17 a browser return is confirmed with the gateway before it produces Succeeded`(): Unit = env { env ->
        // The browser says paid, the gateway says pending: no Succeeded.
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "pending")) }
        val pending = returned(env, attempt())
        assertTrue(pending.reply.orderPage, "RETURN answers toOrderPage()")
        assertEquals(303, pending.reply.status)
        assertTrue(pending.events.none { it is PaymentEvent.Succeeded })
        assertEquals(PendingReason.AWAITING_BUYER, (pending.events.single() as PaymentEvent.Pending).reason)

        // The gateway says paid with its own amount: that, not the query string, is what is reported.
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "paid", amountPaid = 1000)) }
        val paid = returned(env, attempt(), query = "status=paid&amount=1")
        assertTrue(paid.verified)
        assertEquals(eur(1000), (paid.events.single() as PaymentEvent.Succeeded).paid)
        assertTrue(env.gateway.requestsTo("/v1/payments/pay_1").size == 2, "both returns asked the gateway")

        // A cancel return does not trust the browser either.
        val cancelled = returned(env, attempt(), ReturnOutcome.CANCEL)
        assertTrue(cancelled.events.single() is PaymentEvent.Succeeded, "the gateway's answer wins over the outcome in the URL")
    }

    @Test
    fun `P-17 a return degrades to the order page when the gateway cannot confirm`(): Unit = env { env ->
        env.gateway.on("GET", "/v1/payments/pay_1") { errorReply(500, "boom", "down") }
        returned(env, attempt()).also {
            assertTrue(it.reply.orderPage)
            assertTrue(it.events.isEmpty())
        }
        env.gateway.on("GET", "/v1/payments/pay_1") { errorReply(404, "not_found", "no such payment") }
        returned(env, attempt()).also { assertTrue(it.events.isEmpty()) }
        // A payment object of another reference is not this attempt.
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(reference = "ZZZZZZZZZZZZZZZZZZZZ")) }
        returned(env, attempt()).also { assertTrue(it.events.isEmpty()) }
        // No attempt at all: nothing to confirm.
        returned(env, null).also {
            assertTrue(it.reply.orderPage)
            assertTrue(it.events.isEmpty())
        }
    }

    @Test
    fun `P-17 a return without a stored gateway id looks the payment up by reference`(): Unit = env { env ->
        env.gateway.on("GET", "/v1/payments/lookup") { jsonReply(Hooks.payment(status = "paid")) }
        val result = returned(env, attempt(gatewayId = null))
        assertTrue(result.events.single() is PaymentEvent.Succeeded)
        assertEquals("reference=$REF", env.gateway.requestsTo("/v1/payments/lookup").single().query)
    }

    // ---- P-19: queryPayment ------------------------------------------------------------------------------------

    private fun query(env: Env, attempt: PaymentAttemptView = attempt()) =
        blocking { env.provider.queryPayment(env.ctx, QueryPaymentRequest(attempt, QueryReason.RECONCILE)) }

    @Test
    fun `P-19 queryPayment maps paid, pending, failed and unknown`(): Unit = env { env ->
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "paid", amountPaid = 1000)) }
        assertEquals(eur(1000), (query(env).events.single() as PaymentEvent.Succeeded).paid)

        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "pending")) }
        query(env).also {
            assertTrue(it.events.single() is PaymentEvent.Pending)
            assertEquals(30L, it.pollAgainAfterSeconds)
        }

        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "failed")) }
        assertTrue(query(env).events.single() is PaymentEvent.Failed)

        env.gateway.on("GET", "/v1/payments/pay_1") { errorReply(404, "not_found", "no such payment") }
        query(env).also {
            assertTrue(it.unknown, "a payment the gateway does not know is unknown, not failed")
            assertTrue(it.events.isEmpty())
        }

        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "mystery")) }
        assertTrue(query(env).unknown)
    }

    @Test
    fun `P-19 queryPayment without a gateway id asks by reference and transport errors propagate`(): Unit = env { env ->
        env.gateway.on("GET", "/v1/payments/lookup") { jsonReply(Hooks.payment(status = "pending")) }
        assertTrue(query(env, attempt(gatewayId = null)).events.single() is PaymentEvent.Pending)
        env.gateway.on("GET", "/v1/payments/pay_1") { errorReply(500, "boom", "down") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { query(env) }
    }

    // ---- P-20: refunds -----------------------------------------------------------------------------------------

    private fun refundRequest(
        amount: Money,
        full: Boolean = false,
        attempt: PaymentAttemptView = attempt(amount = eur(1000), paid = eur(1000)),
        key: String = "refund-key-1",
        reason: String? = "requested by the buyer"
    ) = RefundRequest(
        refundId = 3, idempotencyKey = key, attempt = attempt, order = SampleData.order(eur(1000)), amount = amount, full = full,
        lines = emptyList<RefundLine>(), reason = reason, paidAt = TestContexts.START_MS - 60_000
    )

    private fun refund(env: Env, request: RefundRequest) = blocking { env.provider.refund(env.ctx, request) }

    @Test
    fun `P-20 a partial refund sends the amount, the reference and the idempotency key`(): Unit = env { env ->
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { jsonReply(Hooks.refund("succeeded", amount = 250)) }
        val result = refund(env, refundRequest(eur(250)))
        assertTrue(result is RefundResult.Succeeded)
        assertEquals("rf_1", result.gatewayRefundId)
        assertEquals(eur(250), result.refundedAmount)
        val sent = env.gateway.requestsTo("/v1/payments/pay_1/refunds").single()
        assertEquals("refund-key-1", sent.header("Idempotency-Key"))
        val body = JsonObject(sent.bodyText())
        assertEquals(250L, body.getLong("amount"))
        assertEquals("EUR", body.getString("currency"))
        assertEquals(REF, body.getString("reference"))
        assertEquals("requested by the buyer", body.getString("reason"))
    }

    @Test
    fun `P-20 a full refund sends what the gateway still holds, a surcharge included`(): Unit = env { env ->
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { jsonReply(Hooks.refund("succeeded", amount = 1030)) }
        // The buyer paid 10.50 for a 10.00 order (instalment surcharge) and 0.20 was refunded before: 10.30 remain.
        val partlyRefunded = attempt(amount = eur(1000), paid = eur(1050), refunded = eur(20))
        refund(env, refundRequest(eur(1000), full = true, attempt = partlyRefunded))
        assertEquals(1030L, JsonObject(env.gateway.requests.single().bodyText()).getLong("amount"))
        // Nothing left: no request, a final failure.
        env.gateway.clearRequests()
        val result = refund(env, refundRequest(eur(1000), full = true, attempt = attempt(paid = eur(1000), refunded = eur(1000))))
        assertEquals("nothing_to_refund", (result as RefundResult.Failed).code)
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `P-20 zero-decimal refunds use whole units on the wire`(): Unit = env { env ->
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { jsonReply(Hooks.refund("succeeded", amount = 200, currency = "JPY")) }
        val yen = attempt(amount = Money(50_000, "JPY"), paid = Money(50_000, "JPY"))
        val result = refund(env, refundRequest(Money(20_000, "JPY"), attempt = yen))
        assertEquals(200L, JsonObject(env.gateway.requests.single().bodyText()).getLong("amount"))
        assertEquals(Money(20_000, "JPY"), result.refundedAmount)
    }

    @Test
    fun `P-20 an asynchronous refund is Pending, a refusal is Failed with the gateway code`(): Unit = env { env ->
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { jsonReply(Hooks.refund("pending")) }
        assertTrue(refund(env, refundRequest(eur(500))) is RefundResult.Pending)

        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { errorReply(422, "refund_not_allowed", "payment is older than 180 days") }
        (refund(env, refundRequest(eur(500))) as RefundResult.Failed).also {
            assertEquals("refund_not_allowed", it.code)
            assertEquals("payment is older than 180 days", it.message)
        }

        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { jsonReply(Hooks.refund("failed").put("failureCode", "insufficient_balance")) }
        assertEquals("insufficient_balance", (refund(env, refundRequest(eur(500))) as RefundResult.Failed).code)

        // Transport trouble is an exception (unknown outcome), not a Failed result that market would treat as final.
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { errorReply(500, "boom", "down") }
        expectProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE) { refund(env, refundRequest(eur(500))) }
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { errorReply(404, "not_found", "no such payment") }
        expectProviderError(ProviderErrorCode.NOT_FOUND) { refund(env, refundRequest(eur(500))) }
        expectProviderError(ProviderErrorCode.INVALID_REQUEST) { refund(env, refundRequest(eur(500), attempt = attempt(gatewayId = null))) }
    }

    @Test
    fun `P-20 with refunds switched off the capability is NONE and refund is UNSUPPORTED`(): Unit = env(overrides = mapOf("refundsEnabled" to false)) { env ->
        assertEquals(RefundSupport.NONE, env.provider.capabilities(env.ctx.settings).refund)
        expectProviderError(ProviderErrorCode.UNSUPPORTED) { refund(env, refundRequest(eur(500))) }
        assertTrue(env.gateway.requests.isEmpty())
        // On (the default) it is PARTIAL.
        assertEquals(RefundSupport.PARTIAL, env.provider.capabilities(TestContexts.settings(mapOf("refundsEnabled" to true))).refund)
    }

    @Test
    fun `P-20 queryRefund maps the gateway state`(): Unit = env { env ->
        fun ask(gatewayId: String?) = blocking {
            env.provider.queryRefund(env.ctx, QueryRefundRequest(3, "refund-key-1", gatewayId, attempt(paid = eur(1000)), eur(500)))
        }
        env.gateway.on("GET", "/v1/refunds/rf_1") { jsonReply(Hooks.refund("succeeded")) }
        assertTrue(ask("rf_1") is RefundResult.Succeeded)
        env.gateway.on("GET", "/v1/refunds/rf_1") { jsonReply(Hooks.refund("pending")) }
        assertTrue(ask("rf_1") is RefundResult.Pending)
        env.gateway.on("GET", "/v1/refunds/rf_1") { errorReply(404, "not_found", "no such refund") }
        assertTrue(ask("rf_1") is RefundResult.Unknown)
        assertTrue(ask(null) is RefundResult.Unknown)
    }

    // ---- P-21: recurring is not offered ------------------------------------------------------------------------

    @Test
    fun `P-21 recurring is not offered, so the recurring entry points refuse`(): Unit = env { env ->
        assertEquals(RecurringSupport.NONE, env.provider.capabilities(env.ctx.settings).recurring)
        val view = SubscriptionView(1, "ACTIVE", "sub_1", null, eur(1000), IntervalUnit.MONTH, 1, null, null, false)
        expectProviderError(ProviderErrorCode.UNSUPPORTED) {
            env.provider.chargeRecurring(
                env.ctx,
                RecurringChargeRequest(AttemptRef(6, REF, "tok"), eur(1000), view, StoredPaymentMethod("pm_1"), SampleData.order(eur(1000)), SampleData.buyer(), "idem-2", "https://shop.example/notify")
            )
        }
        expectProviderError(ProviderErrorCode.UNSUPPORTED) { env.provider.cancelSubscription(env.ctx, CancelSubscriptionRequest(view, true, null, null)) }
        assertNotNull(env.provider.descriptor)
        assertNull(env.provider.capabilities(env.ctx.settings).recurringMaxCycles)
        assertTrue(env.gateway.requests.isEmpty())
    }
}
