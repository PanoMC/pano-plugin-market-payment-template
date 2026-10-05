package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.AttemptRef
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryReason
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.spi.testkit.SampleData
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** P-02, P-04, P-22 to P-25: identity, configuration errors, redaction, the license guard, endpoints and the settings actions. */
class ExampleProviderTest {
    // ---- P-02 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-02 id, plugin id, descriptor and logo`() = env { env ->
        val provider = env.provider
        assertTrue(Regex("^[a-z0-9-]{2,29}$").matches(provider.id), "provider id '${provider.id}'")
        assertEquals("pano-plugin-market-${provider.id}", ExampleTexts.PLUGIN_ID)
        assertTrue(ExampleTexts.PLUGIN_ID.length <= 48, "plugin id '${ExampleTexts.PLUGIN_ID}' is longer than the 48 characters of the store")
        val descriptor = provider.descriptor
        assertTrue(descriptor.icon.startsWith("fa-"), "icon is a FontAwesome class")
        assertEquals("global", descriptor.region)
        assertNotNull(descriptor.docsUrl)
        assertNotNull(descriptor.checkoutHint)
        val logo = descriptor.logo
        assertNotNull(logo, "logo.png is read from the plugin's own resources")
        assertEquals("image/png", logo!!.contentType)
        assertTrue(logo.bytes.size in 1..65_536, "logo is ${logo.bytes.size} bytes")
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), logo.bytes.take(4).map { it.toInt() and 0xFF }, "PNG signature")
        assertEquals(descriptor.verification, Verification.valueOf(JsonObject(resource("/verification.json")).getString("level")))
    }

    // ---- P-04 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-04 a missing required setting is a CONFIGURATION error on every path that needs it`() {
        val start = SampleData.startRequest(providerId = "example")
        for (key in listOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.WEBHOOK_SECRET)) {
            env(removed = setOf(key)) { env ->
                expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.startPayment(env.ctx, start) }
                assertTrue(env.gateway.requests.isEmpty(), "nothing is sent without credentials ($key)")
            }
        }
        env(removed = setOf(ExampleSettings.Keys.WEBHOOK_SECRET)) { env ->
            expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.signed(Hooks.envelope(Hooks.payment())))) }
        }
        env(removed = setOf(ExampleSettings.Keys.API_KEY)) { env ->
            expectProviderError(ProviderErrorCode.CONFIGURATION) {
                env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(ByteArray(0), null, InboundKind.RETURN), attempt(), ReturnOutcome.SUCCESS))
            }
            expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.queryPayment(env.ctx, QueryPaymentRequest(attempt(), QueryReason.PANEL)) }
            expectProviderError(ProviderErrorCode.CONFIGURATION) {
                env.provider.refund(env.ctx, refundOf(attempt(paid = eur(1000))))
            }
        }
    }

    @Test
    fun `P-04 capabilities with empty settings do not throw and need no I-O`() = env { env ->
        val capabilities = env.provider.capabilities(TestContexts.settings())
        assertEquals(setOf("EUR", "USD", "GBP", "TRY", "JPY"), capabilities.currencies)
        assertTrue(capabilities.statusQuery)
        assertTrue(env.gateway.requests.isEmpty())
        // The schema builds, serialises and names both credentials as required secrets.
        val schema = env.provider.settingsSchema()
        schema.toJson()
        assertEquals(setOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.WEBHOOK_SECRET), schema.secretKeys)
        assertTrue(schema.field(ExampleSettings.Keys.API_KEY)!!.required)
        assertTrue(schema.field(ExampleSettings.Keys.WEBHOOK_SECRET)!!.required)
        assertTrue(schema.actions.any { it.id == ExampleSettings.Keys.ACTION_TEST_CONNECTION })
        assertNotNull(schema.field(ExampleSettings.Keys.WEBHOOK_URL)!!.readonly)
    }

    // ---- P-22 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-22 no secret setting value reaches a log line, an exchange record or an error text`() = env { env ->
        val echo = "key $API_KEY and secret $WEBHOOK_SECRET are wrong"
        // The gateway repeats the credentials back in every error it sends.
        env.gateway.on("POST", "/v1/payments") { errorReply(422, "invalid_request", echo) }
        env.gateway.on("GET", "/v1/payments/pay_1") { errorReply(500, "boom", echo) }
        env.gateway.on("GET", "/v1/account") { errorReply(401, "invalid_api_key", echo) }
        env.gateway.on("POST", "/v1/payments/pay_1/refunds") { errorReply(422, "refund_not_allowed", echo) }

        val messages = ArrayList<String>()
        fun record(block: suspend () -> Any?) {
            try {
                blocking { block() }
            } catch (e: ProviderException) {
                messages += listOfNotNull(e.message, e.adminMessage)
            }
        }
        record { env.provider.startPayment(env.ctx, SampleData.startRequest(providerId = env.provider.id)) }
        record { env.provider.queryPayment(env.ctx, QueryPaymentRequest(attempt(), QueryReason.PANEL)) }
                record { env.provider.refund(env.ctx, refundOf(attempt(paid = eur(1000)))) }
        record { env.provider.runAction(env.ctx, ExampleSettings.Keys.ACTION_TEST_CONNECTION, JsonObject()) }
        record { env.provider.validateSettings(env.ctx, env.ctx.settings) }
        record { env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.signed(Hooks.envelope(Hooks.payment())))) }
        record {
            env.provider.handleInbound(env.ctx, Hooks.inbound(Hooks.request(ByteArray(0), null, InboundKind.RETURN), attempt(), ReturnOutcome.SUCCESS))
        }
        val refund = blocking { env.provider.refund(env.ctx, refundOf(attempt(paid = eur(1000)))) }
        messages += listOfNotNull((refund as? RefundResult.Failed)?.message)

        assertTrue(messages.isNotEmpty())
        val everything = env.ctx.recordedLog.everything() + "\n" + messages.joinToString("\n")
        assertFalse(everything.contains(API_KEY), "the API key leaked")
        assertFalse(everything.contains(WEBHOOK_SECRET), "the webhook secret leaked")
        assertTrue(everything.contains("***"), "the echoed secrets were replaced")
        assertTrue(env.ctx.recordedLog.exchanges.isNotEmpty(), "outbound calls are logged")
        assertTrue(env.ctx.recordedLog.exchanges.none { (it.request ?: "").contains("Authorization", ignoreCase = true) })
        // Neither does a rejected webhook name the secret it was checked against.
        assertFalse(env.ctx.recordedLog.everything().contains(WEBHOOK_SECRET))
    }

    // ---- P-23 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-23 every money-moving entry point asks the license first and nothing reaches the gateway`() = env(license = DENY) { env ->
        val ctx = env.ctx
        val p = env.provider
        val view = SubscriptionView(1, "ACTIVE", "sub_1", null, eur(1000), IntervalUnit.MONTH, 1, null, null, false)
        val calls: Map<String, suspend () -> Any?> = mapOf(
            "startPayment" to { p.startPayment(ctx, SampleData.startRequest(providerId = p.id)) },
            "continuePayment" to { p.continuePayment(ctx, ContinuePaymentRequest(attempt(), JsonObject(), SampleData.buyer(), SampleData.attemptUrls())) },
            "handleInbound WEBHOOK" to { p.handleInbound(ctx, Hooks.inbound(Hooks.signed(Hooks.envelope(Hooks.payment())))) },
            "handleInbound RETURN" to {
                p.handleInbound(ctx, Hooks.inbound(Hooks.request(ByteArray(0), null, InboundKind.RETURN), attempt(), ReturnOutcome.SUCCESS))
            },
            "refund" to { p.refund(ctx, refundOf(attempt(paid = eur(1000)))) },
            "chargeRecurring" to {
                p.chargeRecurring(ctx, RecurringChargeRequest(AttemptRef(6, REF, "tok"), eur(1000), view, StoredPaymentMethod("pm"), SampleData.order(eur(1000)), SampleData.buyer(), "idem", "https://shop.example/n"))
            },
            "cancelSubscription" to { p.cancelSubscription(ctx, CancelSubscriptionRequest(view, true, null, null)) }
        )
        for ((name, call) in calls) {
            assertThrows(LicenseDenied::class.java, { blocking { call() } }, "$name must throw the license failure")
        }
        assertTrue(env.gateway.requests.isEmpty(), "the gateway saw: ${env.gateway.requests}")

        // Pure and descriptive entry points still work without a license.
        assertNotNull(p.settingsSchema())
        assertNotNull(p.capabilities(ctx.settings))
        assertNotNull(p.descriptor)
        assertTrue(p.checkEligibility(ctx, CheckoutSnapshot(SampleData.order(eur(1000)), SampleData.buyer(), null, false)).eligible)

        // Reconciliation is not guarded: a license hiccup must not turn into wrong payment state.
        env.gateway.on("GET", "/v1/payments/pay_1") { jsonReply(Hooks.payment(status = "pending")) }
        assertTrue(blocking { p.queryPayment(ctx, QueryPaymentRequest(attempt(), QueryReason.RECONCILE)) }.events.isNotEmpty())
    }

    // ---- P-24 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-24 test mode selects the sandbox host, live selects the live host`() {
        assertEquals(ExampleEndpoints.SANDBOX_API, ExampleEndpoints.of(true).api)
        assertEquals(ExampleEndpoints.LIVE_API, ExampleEndpoints.of(false).api)
        assertTrue(ExampleEndpoints.SANDBOX_API != ExampleEndpoints.LIVE_API)
        for (testMode in listOf(true, false)) {
            env(testMode = testMode) { env ->
                env.happyGateway()
                blocking { env.provider.startPayment(env.ctx, SampleData.startRequest(providerId = env.provider.id)) }
                assertEquals(listOf(testMode), env.modesSeen.distinct(), "the provider asked for the endpoints of testMode=$testMode")
            }
        }
    }

    @Test
    fun `P-24 no host literal outside ExampleEndpoints`() {
        val dir = File("src/main/kotlin/com/panomc/plugins")
        assertTrue(dir.isDirectory, "run the tests from the project directory")
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("/license/") && it.name != "ExampleEndpoints.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trim()
                    val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                    if (!isComment && Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(code)) "${file.name}:${i + 1}: $code" else null
                }
            }.toList()
        assertTrue(offenders.isEmpty(), "host literals outside ExampleEndpoints.kt:\n" + offenders.joinToString("\n"))
    }

    // ---- P-25 --------------------------------------------------------------------------------------------------

    @Test
    fun `P-25 validateSettings accepts working credentials and reports a bad key on the key field`() = env { env ->
        env.gateway.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
        assertTrue(blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.ok)
        assertEquals("Bearer $API_KEY", env.gateway.requestsTo("/v1/account").single().header("Authorization"))

        env.gateway.on("GET", "/v1/account") { errorReply(401, "invalid_api_key", "bad key") }
        blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.also {
            assertFalse(it.ok)
            assertEquals(setOf(ExampleSettings.Keys.API_KEY), it.fieldErrors.keys)
            assertEquals(ExampleTexts.errorAuthentication, it.fieldErrors.getValue(ExampleSettings.Keys.API_KEY))
        }

        env.gateway.on("GET", "/v1/account") { errorReply(500, "boom", "down") }
        blocking { env.provider.validateSettings(env.ctx, env.ctx.settings) }.also {
            assertFalse(it.ok)
            assertTrue(it.fieldErrors.isEmpty())
            assertEquals(ExampleTexts.errorUnreachable, it.message)
        }

        // Values that were typed into the form but not saved yet are the ones that are checked.
        val candidate = TestContexts.settings(mapOf("apiKey" to "candidate-key-123456", "webhookSecret" to "candidate-secret-123456"))
        env.gateway.clearRequests()
        env.gateway.on("GET", "/v1/account") { jsonReply(JsonObject()) }
        assertTrue(blocking { env.provider.validateSettings(env.ctx, candidate) }.ok)
        assertEquals("Bearer candidate-key-123456", env.gateway.requests.single().header("Authorization"))
    }

    @Test
    fun `P-25 validateSettings names every missing credential without calling the gateway`() = env { env ->
        val result = blocking { env.provider.validateSettings(env.ctx, TestContexts.settings(mapOf("webhookSecret" to "  "))) }
        assertFalse(result.ok)
        assertEquals(setOf(ExampleSettings.Keys.API_KEY, ExampleSettings.Keys.WEBHOOK_SECRET), result.fieldErrors.keys)
        assertTrue(env.gateway.requests.isEmpty())
    }

    @Test
    fun `P-25 the test-connection action answers a message and refuses unknown actions`() = env { env ->
        env.gateway.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
        (blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) } as ActionResult.Message).also {
            assertTrue(it.success)
            assertEquals(ExampleTexts.connectionOk, it.text)
        }
        env.gateway.on("GET", "/v1/account") { errorReply(401, "invalid_api_key", "bad key") }
        (blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) } as ActionResult.Message).also {
            assertFalse(it.success)
            assertEquals(ExampleTexts.errorAuthentication, it.text)
        }
        env.gateway.on("GET", "/v1/account") { errorReply(503, "maintenance", "later") }
        assertFalse((blocking { env.provider.runAction(env.ctx, "test-connection", JsonObject()) } as ActionResult.Message).success)
        expectProviderError(ProviderErrorCode.UNSUPPORTED) { env.provider.runAction(env.ctx, "import-catalog", JsonObject()) }
    }

    @Test
    fun `P-25 the test-connection action without an API key is a CONFIGURATION error`() = env(removed = setOf("apiKey")) { env ->
        expectProviderError(ProviderErrorCode.CONFIGURATION) { env.provider.runAction(env.ctx, "test-connection", JsonObject()) }
    }

    @Test
    fun `eligibility follows the supported currencies`() = env { env ->
        fun verdict(currency: String) = env.provider.checkEligibility(
            env.ctx, CheckoutSnapshot(SampleData.order(Money(1000, currency)), SampleData.buyer(), null, false)
        )
        assertTrue(verdict("EUR").eligible)
        assertTrue(verdict("JPY").eligible)
        verdict("CAD").also {
            assertFalse(it.eligible)
            assertEquals("currency", it.code)
            assertEquals(ExampleTexts.eligibilityCurrency, it.reason)
        }
    }

    private fun refundOf(attempt: PaymentAttemptView) = RefundRequest(
        refundId = 3, idempotencyKey = "refund-key-1", attempt = attempt, order = SampleData.order(eur(1000)), amount = eur(500), full = false,
        lines = emptyList(), reason = null, paidAt = TestContexts.START_MS - 60_000
    )

    private fun resource(path: String): String = javaClass.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }
}
