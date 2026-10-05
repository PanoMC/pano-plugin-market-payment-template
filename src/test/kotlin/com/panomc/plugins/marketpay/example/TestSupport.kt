package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.spi.testkit.TestPaymentContext
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The attempt reference of every fixture (`[A-Z0-9]{20}`); it is what market hands the gateway as `reference`. */
internal const val REF = "ABCDEFGHJKMNPQRSTVWX"

internal val API_KEY: String = TestContexts.secretMarker("apiKey")
internal val WEBHOOK_SECRET: String = TestContexts.secretMarker("webhookSecret")

/** `TestContexts.START_MS / 1000`: the second the test clock starts at. */
internal const val NOW_SECONDS = TestContexts.START_MS / 1000L

internal fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

internal fun eur(minor: Long) = Money(minor, "EUR")

/** A license check that never objects (a FREE build). */
internal val ALLOW = LicenseCheck { }

internal class LicenseDenied : RuntimeException("license denied (test)")

internal val DENY = LicenseCheck { throw LicenseDenied() }

/**
 * One provider, one FakeGateway, one test context. [overrides] replace stored settings, [removed] drops keys (a missing
 * setting). The provider's endpoints always point at the fake; [modesSeen] records the `testMode` it asked for.
 */
internal class Env(
    overrides: Map<String, Any?> = emptyMap(),
    removed: Set<String> = emptySet(),
    testMode: Boolean = false,
    license: LicenseCheck = ALLOW,
    timeoutMs: Long = 3_000
) : AutoCloseable {
    val vertx: Vertx = Vertx.vertx()
    val gateway: FakeGateway = FakeGateway.start(vertx)
    val modesSeen = CopyOnWriteArrayList<Boolean>()
    val provider = ExampleProvider(license, { mode ->
        modesSeen.add(mode)
        ExampleEndpoints(gateway.baseUrl)
    }, timeoutMs)
    val values: Map<String, Any?> = (TestContexts.defaultValues(provider.settingsSchema()) + overrides).filterKeys { it !in removed }
    val ctx: TestPaymentContext = TestContexts.payment(provider.id, TestContexts.settings(values), vertx, testMode)

    override fun close() {
        gateway.close()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }
}

internal inline fun <T> env(
    overrides: Map<String, Any?> = emptyMap(),
    removed: Set<String> = emptySet(),
    testMode: Boolean = false,
    license: LicenseCheck = ALLOW,
    timeoutMs: Long = 3_000,
    block: (Env) -> T
): T = Env(overrides, removed, testMode, license, timeoutMs).use(block)

/** Runs [block], which must throw a [ProviderException]; returns it for further assertions. */
internal fun expectProviderError(code: ProviderErrorCode, block: suspend () -> Unit): ProviderException {
    try {
        blocking(block)
    } catch (e: ProviderException) {
        assertEquals(code, e.code, "wrong error code (message: ${e.message})")
        return e
    }
    fail<Unit>("expected ProviderException($code), nothing was thrown")
    throw IllegalStateException()
}

internal fun attempt(
    amount: Money = eur(1000),
    gatewayId: String? = "pay_1",
    paid: Money? = null,
    refunded: Money = Money(0, amount.currency),
    reference: String = REF
) = PaymentAttemptView(
    id = 5, reference = reference, token = "tok", status = "PENDING", amount = amount, orderId = 1, orderPublicId = REF,
    gatewayTransactionId = gatewayId, gatewayRefs = emptyMap(), providerData = null, testMode = false, createdAt = TestContexts.START_MS,
    expiresAt = null, subscription = null, paidAmount = paid, refundedAmount = refunded, paidAt = null
)

/** What the gateway sends and signs. */
internal object Hooks {
    fun payment(
        status: String = "paid",
        amount: Long = 1000,
        currency: String = "EUR",
        amountPaid: Long? = amount,
        reference: String? = REF,
        id: String = "pay_1"
    ): JsonObject = JsonObject().put("id", id).put("status", status).put("amount", amount).put("currency", currency).also {
        if (amountPaid != null) it.put("amountPaid", amountPaid)
        if (reference != null) it.put("reference", reference)
    }

    fun refund(status: String = "succeeded", amount: Long = 500, currency: String = "EUR"): JsonObject =
        JsonObject().put("id", "rf_1").put("paymentId", "pay_1").put("reference", REF).put("status", status).put("amount", amount)
            .put("currency", currency).put("idempotencyKey", "refund-key-1")

    fun envelope(data: JsonObject?, type: String = "payment.updated", id: String = "evt_1"): JsonObject =
        JsonObject().put("id", id).put("type", type).put("createdAt", NOW_SECONDS).also { if (data != null) it.put("data", data) }

    fun request(
        body: ByteArray,
        header: String?,
        kind: InboundKind = InboundKind.WEBHOOK,
        rawQuery: String? = null,
        extraHeaders: Map<String, List<String>> = emptyMap()
    ): InboundRequest {
        val headers = HashMap<String, List<String>>()
        headers["content-type"] = listOf("application/json")
        if (header != null) headers[ExampleSignature.HEADER.lowercase()] = listOf(header)
        headers.putAll(extraHeaders)
        val query = rawQuery?.split('&')?.associate { it.substringBefore('=') to listOf(it.substringAfter('=', "")) } ?: emptyMap()
        return InboundRequest(
            kind = kind, channel = "default", method = "POST", rawQuery = rawQuery, query = query, headers = headers,
            contentType = "application/json", body = body, remoteIp = "203.0.113.9", receivedAt = TestContexts.START_MS
        )
    }

    /** A correctly signed webhook for [body] with the timestamp [timestampSeconds]. */
    fun signed(
        body: JsonObject,
        secret: String = WEBHOOK_SECRET,
        timestampSeconds: Long = NOW_SECONDS,
        kind: InboundKind = InboundKind.WEBHOOK,
        rawQuery: String? = null
    ): InboundRequest {
        val bytes = body.encode().toByteArray(Charsets.UTF_8)
        return request(bytes, ExampleSignature.header(secret, timestampSeconds, bytes), kind, rawQuery)
    }

    fun inbound(http: InboundRequest, attempt: PaymentAttemptView? = null, outcome: ReturnOutcome? = null) =
        PaymentInboundRequest(http, attempt, outcome, null)
}

internal fun jsonReply(body: JsonObject, status: Int = 200) = Reply.json(body.encode(), status)

internal fun errorReply(status: Int, code: String, message: String) =
    Reply.json(JsonObject().put("error", JsonObject().put("code", code).put("message", message)).encode(), status)

/** Registers the happy path of every gateway endpoint the provider uses. */
internal fun Env.happyGateway() {
    gateway.on("POST", "/v1/payments") {
        jsonReply(JsonObject().put("id", "pay_9").put("url", "https://pay.examplepay.example/checkout/pay_9").put("expiresAt", NOW_SECONDS + 3600))
    }
    gateway.on("GET", "/v1/account") { jsonReply(JsonObject().put("id", "acct_1")) }
}
