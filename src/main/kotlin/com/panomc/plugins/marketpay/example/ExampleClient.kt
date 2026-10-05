package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.CancellationException

/**
 * Every outbound HTTP call of the plugin (through `ctx.http`), with the error mapping of spec 16 section 8.5. It makes no
 * business decision: it returns what the gateway answered, or throws a [ProviderException] for everything that is not an
 * answer (transport failure, credentials, rate limit, server error, a challenge page, a body that is not JSON).
 */
class ExampleClient(
    private val endpointsFor: (Boolean) -> ExampleEndpoints,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /** A parsed answer. [adminMessage] is the gateway's own text (or the start of the body), with secrets removed. */
    class Response(val status: Int, val json: JsonObject, val adminMessage: String?) {
        val ok: Boolean get() = status in 200..299

        val errorCode: String? get() = json.obj("error")?.str("code")

        /** 2xx returns itself; 404 is `NOT_FOUND`; everything else the gateway refused is `GATEWAY_REJECTED`. */
        fun requireSuccess(): Response {
            if (ok) return this
            if (status == 404) throw ProviderException(ProviderErrorCode.NOT_FOUND, "the gateway does not know the object", adminMessage)
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the gateway rejected the request (HTTP $status)", adminMessage)
        }
    }

    suspend fun createPayment(ctx: ProviderContext, settings: ExampleSettings, body: JsonObject, idempotencyKey: String): JsonObject =
        call(ctx, settings, "create-payment", HttpMethod.POST, "/v1/payments", body, idempotencyKey).requireSuccess().json

    /** Null when the gateway does not know the payment. */
    suspend fun getPayment(ctx: ProviderContext, settings: ExampleSettings, paymentId: String): JsonObject? {
        val response = call(ctx, settings, "get-payment", HttpMethod.GET, "/v1/payments/${encode(paymentId)}")
        return if (response.status == 404) null else response.requireSuccess().json
    }

    /** Finds a payment by the merchant reference (used when the gateway id never reached market). Null when unknown. */
    suspend fun findPayment(ctx: ProviderContext, settings: ExampleSettings, reference: String): JsonObject? {
        val response = call(ctx, settings, "find-payment", HttpMethod.GET, "/v1/payments/lookup", query = mapOf("reference" to reference))
        return if (response.status == 404) null else response.requireSuccess().json
    }

    /** The raw answer: a 4xx refusal (`refund_not_allowed`, ...) is the caller's business ([ExampleProvider.refund]). */
    suspend fun createRefund(ctx: ProviderContext, settings: ExampleSettings, paymentId: String, body: JsonObject, idempotencyKey: String): Response =
        call(ctx, settings, "create-refund", HttpMethod.POST, "/v1/payments/${encode(paymentId)}/refunds", body, idempotencyKey)

    suspend fun getRefund(ctx: ProviderContext, settings: ExampleSettings, refundId: String): JsonObject? {
        val response = call(ctx, settings, "get-refund", HttpMethod.GET, "/v1/refunds/${encode(refundId)}")
        return if (response.status == 404) null else response.requireSuccess().json
    }

    /** Cheapest authenticated call: used by `validateSettings` and the test-connection action. */
    suspend fun account(ctx: ProviderContext, settings: ExampleSettings): JsonObject =
        call(ctx, settings, "account", HttpMethod.GET, "/v1/account").requireSuccess().json

    private suspend fun call(
        ctx: ProviderContext,
        settings: ExampleSettings,
        channel: String,
        method: HttpMethod,
        path: String,
        body: JsonObject? = null,
        idempotencyKey: String? = null,
        query: Map<String, String> = emptyMap()
    ): Response {
        val secrets = settings.secretValues()
        val url = endpointsFor(ctx.testMode).api + path
        // No redirect is followed: the Authorization header must never travel to a host the gateway sent us to.
        val request = ctx.http.requestAbs(method, url).timeout(timeoutMs).followRedirects(false)
            .putHeader("Authorization", "Bearer " + settings.apiKey)
            .putHeader("Accept", "application/json")
        query.forEach { (name, value) -> request.addQueryParam(name, value) }
        if (idempotencyKey != null) request.putHeader("Idempotency-Key", idempotencyKey)

        val started = ctx.now()
        val response = try {
            if (body != null) request.sendJsonObject(body).coAwait() else request.send().coAwait()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.log.exchange(channel, describe(method, path, body), "transport error: ${e.javaClass.simpleName}", null, ctx.now() - started)
            throw ProviderException(
                ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway could not be reached (${e.javaClass.simpleName})",
                redact(e.message, secrets), retryable = true, cause = e
            )
        }

        val status = response.statusCode()
        val text = response.bodyAsString() ?: ""
        ctx.log.exchange(channel, describe(method, path, body), redact(text.take(LOG_LIMIT), secrets), status, ctx.now() - started)

        // A challenge or maintenance page where JSON was expected: the gateway is effectively unreachable.
        val contentType = response.getHeader("content-type")?.lowercase().orEmpty()
        if (contentType.contains("text/html") || text.trimStart().startsWith("<")) {
            throw ProviderException(
                ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway answered with an HTML page (HTTP $status)",
                redact(text.take(ADMIN_LIMIT), secrets), retryable = true
            )
        }
        val parsed: JsonObject? = try {
            if (text.isBlank()) JsonObject() else JsonObject(text)
        } catch (e: Exception) {
            null
        }
        val message = redact(parsed?.obj("error")?.str("message") ?: text.take(ADMIN_LIMIT), secrets)?.takeIf { it.isNotEmpty() }
        val code = parsed?.obj("error")?.str("code")
        if (code == "ip_not_allowed") throw ProviderException(ProviderErrorCode.IP_NOT_ALLOWED, "the gateway does not accept this server's IP address", message)
        if (status == 401 || status == 403 || code == "invalid_api_key") throw ProviderException(ProviderErrorCode.AUTHENTICATION, "the gateway rejected the credentials", message)
        if (status == 429) throw ProviderException(ProviderErrorCode.RATE_LIMITED, "the gateway rate limit was hit", message, retryable = true)
        if (status >= 500) throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway failed (HTTP $status)", message, retryable = true)
        if (parsed == null && status != 404) {
            throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "the gateway answer is not JSON (HTTP $status)", redact(text.take(ADMIN_LIMIT), secrets))
        }
        return Response(status, parsed ?: JsonObject(), message)
    }

    /** One line for `ProviderLog.exchange`: method, path and the JSON body (it carries no credential, those are headers). */
    private fun describe(method: HttpMethod, path: String, body: JsonObject?): String =
        "${method.name()} $path" + (body?.let { " " + it.encode() } ?: "")

    private fun redact(text: String?, secrets: List<String>): String? {
        var out = text ?: return null
        for (secret in secrets) out = out.replace(secret, "***")
        return out
    }

    private fun encode(segment: String): String = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val LOG_LIMIT = 2_000
        private const val ADMIN_LIMIT = 500
    }
}
