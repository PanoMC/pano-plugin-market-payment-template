package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderAsset
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.Verification
import com.panomc.plugins.market.spi.common.WebhookSetup
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.ActionResult
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.QueryPaymentRequest
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import com.panomc.plugins.market.spi.payment.RefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.SettingsValidation
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import io.vertx.core.json.JsonObject

/**
 * The imaginary gateway "Example Pay" (provider id `example`). Every SPI method is: guard, read settings, call the
 * client, map, return (16 section 8.1). Capabilities: partial refunds, status query, one account-level webhook plus a
 * per-payment notify URL; no recurring payments (the recurring methods only guard and refuse).
 *
 * [endpoints] is the test seam (point the provider at a FakeGateway); [timeoutMs] is the per-request timeout.
 */
class ExampleProvider(
    private val license: LicenseCheck,
    endpoints: ((Boolean) -> ExampleEndpoints)? = null,
    timeoutMs: Long = ExampleClient.DEFAULT_TIMEOUT_MS
) : PaymentProvider {
    private val client = ExampleClient(endpoints ?: { testMode -> ExampleEndpoints.of(testMode) }, timeoutMs)

    override val id: String = "example"

    override val descriptor: ProviderDescriptor =
        ProviderDescriptor(ExampleTexts.displayName, ExampleTexts.description, "fa-solid fa-credit-card").also {
            it.logo = loadLogo()
            it.color = "#4f46e5"
            it.region = "global"
            it.docsUrl = ExampleEndpoints.DOCS_URL
            it.checkoutHint = ExampleTexts.checkoutHint
            it.verification = Verification.UNVERIFIED // must equal src/test/resources/verification.json (VerificationLevelTest)
        }

    override fun settingsSchema(): SettingsSchema = settingsSchema {
        group(ExampleSettings.Keys.GROUP_CREDENTIALS, ExampleTexts.groupCredentials)
        group(ExampleSettings.Keys.GROUP_OPTIONS, ExampleTexts.groupOptions)

        secret(ExampleSettings.Keys.API_KEY) {
            label = ExampleTexts.apiKeyLabel
            help = ExampleTexts.apiKeyHelp
            required = true
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        secret(ExampleSettings.Keys.WEBHOOK_SECRET) {
            label = ExampleTexts.webhookSecretLabel
            help = ExampleTexts.webhookSecretHelp
            required = true
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        webhookUrl(ExampleSettings.Keys.WEBHOOK_URL) {
            label = ExampleTexts.webhookUrlLabel
            help = ExampleTexts.webhookUrlHelp
            group = ExampleSettings.Keys.GROUP_CREDENTIALS
        }
        switch(ExampleSettings.Keys.REFUNDS_ENABLED) {
            label = ExampleTexts.refundsEnabledLabel
            help = ExampleTexts.refundsEnabledHelp
            default = true
            group = ExampleSettings.Keys.GROUP_OPTIONS
        }
        text(ExampleSettings.Keys.STATEMENT_DESCRIPTOR) {
            label = ExampleTexts.statementDescriptorLabel
            help = ExampleTexts.statementDescriptorHelp
            group = ExampleSettings.Keys.GROUP_OPTIONS
        }
        action(ExampleSettings.Keys.ACTION_TEST_CONNECTION) {
            label = ExampleTexts.testConnectionLabel
        }
    }

    override fun capabilities(settings: ProviderSettings): PaymentCapabilities = PaymentCapabilities().also {
        it.currencies = ExampleMapper.SUPPORTED_CURRENCIES
        it.refund = if (ExampleSettings(settings).refundsEnabled) RefundSupport.PARTIAL else RefundSupport.NONE
        it.statusQuery = true
        it.testMode = TestModeSupport.FLAG
        it.webhookSetup = WebhookSetup.MANUAL_URL
        it.needsPublicUrl = true
    }

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility =
        if (checkout.order.currency in ExampleMapper.SUPPORTED_CURRENCIES) Eligibility.eligible()
        else Eligibility.ineligible("currency", ExampleTexts.eligibilityCurrency)

    // ---- settings ---------------------------------------------------------------------------------------------

    override suspend fun validateSettings(ctx: PaymentContext, settings: ProviderSettings): SettingsValidation {
        val missing = LinkedHashMap<String, LocalizedText>()
        if (settings.string(ExampleSettings.Keys.API_KEY) == null) missing[ExampleSettings.Keys.API_KEY] = ExampleTexts.errorMissing
        if (settings.string(ExampleSettings.Keys.WEBHOOK_SECRET) == null) missing[ExampleSettings.Keys.WEBHOOK_SECRET] = ExampleTexts.errorMissing
        if (missing.isNotEmpty()) return SettingsValidation.invalid(missing)
        return try {
            client.account(ctx, ExampleSettings(settings))
            SettingsValidation.ok()
        } catch (e: ProviderException) {
            when (e.code) {
                ProviderErrorCode.AUTHENTICATION -> SettingsValidation.invalid(mapOf(ExampleSettings.Keys.API_KEY to ExampleTexts.errorAuthentication))
                else -> SettingsValidation.invalid(emptyMap(), ExampleTexts.errorUnreachable)
            }
        }
    }

    override suspend fun runAction(ctx: PaymentContext, actionId: String, input: JsonObject): ActionResult {
        if (actionId != ExampleSettings.Keys.ACTION_TEST_CONNECTION) throw ProviderException(ProviderErrorCode.UNSUPPORTED, "unknown action $actionId")
        return try {
            client.account(ctx, ExampleSettings(ctx.settings))
            ActionResult.Message(ExampleTexts.connectionOk, true)
        } catch (e: ProviderException) {
            when (e.code) {
                ProviderErrorCode.CONFIGURATION -> throw e
                ProviderErrorCode.AUTHENTICATION -> ActionResult.Message(ExampleTexts.errorAuthentication, false)
                else -> ActionResult.Message(ExampleTexts.errorUnreachable, false)
            }
        }
    }

    // ---- start ------------------------------------------------------------------------------------------------

    override suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult {
        license.assertLicensed()
        val settings = ExampleSettings(ctx.settings)
        settings.requireCredentials()
        val amount = request.amount
        if (amount.currency !in ExampleMapper.SUPPORTED_CURRENCIES) {
            throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "currency ${amount.currency} is not supported")
        }
        if (request.subscription != null) throw ProviderException(ProviderErrorCode.UNSUPPORTED, "recurring payments are not supported")

        val wire = ExampleMapper.toWire(amount)
        val body = JsonObject()
            .put("reference", request.attempt.reference)
            .put("amount", wire.minor)
            .put("currency", wire.currency)
            .put("description", request.order.description.take(200))
            .put("successUrl", request.urls.success)
            .put("cancelUrl", request.urls.cancel)
            .put("notifyUrl", request.urls.notify)
            .put("expiresAt", request.expiresAt / 1000L)
            .put("locale", request.locale)
        request.buyer.email?.let { body.put("customerEmail", it) }
        settings.statementDescriptor?.let { body.put("statementDescriptor", it.take(22)) }

        val created = client.createPayment(ctx, settings, body, request.idempotencyKey)
        val gatewayId = created.str("id")
            ?: throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the gateway answer has no payment id", created.encode().take(500))
        val url = created.str("url")
            ?: throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the gateway answer has no checkout url", created.encode().take(500))
        val result = try {
            StartPaymentResult.Redirect(url)
        } catch (e: IllegalArgumentException) {
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the gateway answer has an invalid checkout url", url.take(500))
        }
        result.gatewayTransactionId = gatewayId
        created.long("expiresAt")?.let { result.expiresAt = it * 1000L }
        return result
    }

    /** Example Pay has no embedded step; the guard still runs first (16 section 7.3). */
    override suspend fun continuePayment(ctx: PaymentContext, request: ContinuePaymentRequest): StartPaymentResult {
        license.assertLicensed()
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "continuePayment")
    }

    // ---- inbound (order of 16 section 8.6) ---------------------------------------------------------------------

    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        license.assertLicensed()
        return when (request.http.kind) {
            InboundKind.RETURN -> handleReturn(ctx, request)
            InboundKind.WEBHOOK, InboundKind.NOTIFY -> handleNotification(ctx, request)
        }
    }

    private fun handleNotification(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val http = request.http
        if (http.body.size > ExampleSignature.MAX_BODY_BYTES) return rejected("body too large")
        val settings = ExampleSettings(ctx.settings)
        // Authenticate from the raw bytes and the exact header value before anything is parsed.
        val verdict = ExampleSignature.verify(settings.webhookSecret, http.header(ExampleSignature.HEADER), http.body, ctx.now())
        if (verdict != ExampleSignature.Verdict.VALID) return rejected("signature: ${verdict.name.lowercase()}")

        // Authentic from here on: whatever we do not understand is `ignored` with the literal reply, never an error.
        val envelope = try {
            JsonObject(http.bodyAsString())
        } catch (e: Exception) {
            return acknowledged()
        }
        val data = envelope.obj("data") ?: return acknowledged()
        val occurredAt = envelope.long("createdAt")?.let { it * 1000L }
        val event: PaymentEvent = when (envelope.str("type")) {
            "payment.updated" -> ExampleMapper.paymentEvent(data, occurredAt)
            "refund.updated" -> ExampleMapper.refundEvent(data, occurredAt)
            else -> null
        } ?: return acknowledged()
        // A per-attempt NOTIFY must be about that attempt: a notification for another object changes nothing.
        val attempt = request.attempt
        if (attempt != null) {
            val target = event.target
            if (target is PaymentTarget.Reference && target.reference != attempt.reference) return acknowledged()
        }
        // The signature covers the whole body, so the gateway's event id is a safe delivery key.
        return InboundResult.accepted(HttpReply.text("OK"), listOf(event), eventKey = envelope.str("id"))
    }

    /**
     * The browser came back. Nothing the browser sent is trusted: the state is re-read from the gateway with the id market
     * stored (or the merchant reference) and only that answer produces events.
     */
    private suspend fun handleReturn(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val attempt = request.attempt ?: return InboundResult.ignored(HttpReply.toOrderPage())
        val settings = ExampleSettings(ctx.settings)
        val payment = try {
            val gatewayId = attempt.gatewayTransactionId
            if (gatewayId != null) client.getPayment(ctx, settings, gatewayId) else client.findPayment(ctx, settings, attempt.reference)
        } catch (e: ProviderException) {
            if (e.code == ProviderErrorCode.CONFIGURATION) throw e
            // The buyer still lands on the order page; market's reconciliation asks again later.
            ctx.log.warn("return re-query failed (${e.code})")
            return InboundResult.ignored(HttpReply.toOrderPage())
        } ?: return InboundResult.ignored(HttpReply.toOrderPage())
        val reference = payment.str("reference")
        if (reference != null && reference != attempt.reference) return InboundResult.ignored(HttpReply.toOrderPage())
        val event = ExampleMapper.paymentEvent(payment, null) ?: return InboundResult.ignored(HttpReply.toOrderPage())
        return InboundResult.accepted(HttpReply.toOrderPage(), listOf(event))
    }

    private fun rejected(reason: String): InboundResult = InboundResult.rejected(HttpReply.text("invalid request", 400), reason)

    private fun acknowledged(): InboundResult = InboundResult.ignored(HttpReply.text("OK"))

    // ---- query -------------------------------------------------------------------------------------------------

    override suspend fun queryPayment(ctx: PaymentContext, request: QueryPaymentRequest): PaymentQueryResult {
        val settings = ExampleSettings(ctx.settings)
        val attempt = request.attempt
        val gatewayId = attempt.gatewayTransactionId
        val payment = (if (gatewayId != null) client.getPayment(ctx, settings, gatewayId) else client.findPayment(ctx, settings, attempt.reference))
            ?: return PaymentQueryResult.unknown()
        val event = ExampleMapper.paymentEvent(payment, null) ?: return PaymentQueryResult.unknown()
        return PaymentQueryResult.of(event).also {
            if (event is PaymentEvent.Pending) it.pollAgainAfterSeconds = 30
        }
    }

    // ---- refunds -----------------------------------------------------------------------------------------------

    override suspend fun refund(ctx: PaymentContext, request: RefundRequest): RefundResult {
        license.assertLicensed()
        if (capabilities(ctx.settings).refund == RefundSupport.NONE) throw ProviderException(ProviderErrorCode.UNSUPPORTED, "refund")
        val settings = ExampleSettings(ctx.settings)
        val attempt = request.attempt
        val paymentId = attempt.gatewayTransactionId
            ?: throw ProviderException(ProviderErrorCode.INVALID_REQUEST, "the attempt has no gateway transaction id")

        // `full` = everything the gateway still holds, which can exceed `amount` when the buyer paid a surcharge.
        val amount: Money = if (request.full) {
            val paid = attempt.paidAmount
            if (paid != null && paid.currency == attempt.refundedAmount.currency) Money(paid.amount - attempt.refundedAmount.amount, paid.currency) else request.amount
        } else {
            request.amount
        }
        if (amount.amount <= 0L) return RefundResult.Failed("nothing_to_refund", "nothing is left to refund")
        val wire = ExampleMapper.toWire(amount)
        val body = JsonObject().put("amount", wire.minor).put("currency", wire.currency).put("reference", attempt.reference)
        request.reason?.let { body.put("reason", it.take(200)) }

        val response = client.createRefund(ctx, settings, paymentId, body, request.idempotencyKey)
        if (response.status == 404) response.requireSuccess()
        if (!response.ok) {
            // The gateway understood and refused (already refunded, too old, over the balance): a final answer.
            return RefundResult.Failed(response.errorCode ?: "rejected", response.adminMessage)
        }
        val result = ExampleMapper.refundResult(response.json)
        // The gateway accepted the request (2xx) but the status is missing or unknown: the refund exists and is not final.
        // Pending with the gateway refund id lets market's reconcile job poll queryRefund; a bare Unknown would leave a
        // row without an id that can never be queried while the money may already be on its way back to the buyer.
        // Without an id (empty 2xx body) there is nothing to poll: Unknown stays, and a retry re-sends the same idempotency key.
        if (result is RefundResult.Unknown && result.gatewayRefundId != null) {
            return RefundResult.Pending().also {
                it.gatewayRefundId = result.gatewayRefundId
                it.refundedAmount = result.refundedAmount
            }
        }
        return result
    }

    override suspend fun queryRefund(ctx: PaymentContext, request: QueryRefundRequest): RefundResult {
        val refundId = request.gatewayRefundId ?: return RefundResult.unknown()
        val refund = client.getRefund(ctx, ExampleSettings(ctx.settings), refundId) ?: return RefundResult.unknown()
        return ExampleMapper.refundResult(refund)
    }

    // ---- recurring: not offered, but guarded like every entry point that moves money (16 section 7.3) ------------

    override suspend fun chargeRecurring(ctx: PaymentContext, request: RecurringChargeRequest): RecurringChargeResult {
        license.assertLicensed()
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "chargeRecurring")
    }

    override suspend fun cancelSubscription(ctx: PaymentContext, request: CancelSubscriptionRequest): CancelSubscriptionResult {
        license.assertLicensed()
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "cancelSubscription")
    }

    private fun loadLogo(): ProviderAsset? = try {
        ExampleProvider::class.java.getResourceAsStream("/logo.png")?.use { ProviderAsset("image/png", it.readBytes()) }
    } catch (e: Exception) {
        null
    }
}
