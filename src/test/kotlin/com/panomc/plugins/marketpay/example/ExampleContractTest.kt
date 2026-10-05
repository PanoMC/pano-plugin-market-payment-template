package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.ProviderContractTest
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.SignedSample
import io.vertx.core.json.JsonObject

/**
 * P-01: the shared contract of 02 section 14.1 against the example provider. Every check must run: none may be skipped,
 * which is why a signed notification, a signed unknown event and a fake gateway are all supplied.
 */
class ExampleContractTest : ProviderContractTest() {
    private val gatewayHolder = lazy {
        FakeGateway.start(vertx).also { g ->
            g.on("POST", "/v1/payments") {
                Reply.json("""{"id":"pay_1","url":"https://pay.examplepay.example/checkout/pay_1"}""")
            }
            g.on("GET", "/v1/account") { Reply.json("""{"id":"acct_1"}""") }
        }
    }

    override fun createProvider(): PaymentProvider = ExampleProvider(ALLOW, { ExampleEndpoints(gatewayHolder.value.baseUrl) })

    override val gatewayIsFake: Boolean get() = true

    override fun signedNotification(ctx: PaymentContext): SignedSample {
        val secret = ctx.settings.require(ExampleSettings.Keys.WEBHOOK_SECRET)
        val body = Hooks.envelope(Hooks.payment()).encode().toByteArray()
        val header = ExampleSignature.header(secret, NOW_SECONDS, body)
        return SignedSample(
            request = Hooks.request(body, header),
            // A query parameter and a header outside the signature: they must not change the delivery key.
            withUnsignedField = Hooks.request(body, header, rawQuery = "utm=1", extraHeaders = mapOf("x-forwarded-note" to listOf("added")))
        )
    }

    override fun signedUnknownEvent(ctx: PaymentContext): InboundRequest {
        val secret = ctx.settings.require(ExampleSettings.Keys.WEBHOOK_SECRET)
        val body = Hooks.envelope(JsonObject().put("anything", true), type = "payment.invented_later", id = "evt_9").encode().toByteArray()
        return Hooks.request(body, ExampleSignature.header(secret, NOW_SECONDS, body), InboundKind.WEBHOOK)
    }

    override fun close() {
        if (gatewayHolder.isInitialized()) gatewayHolder.value.close()
        super.close()
    }
}
