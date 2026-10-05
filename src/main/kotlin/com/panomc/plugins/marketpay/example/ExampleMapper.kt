package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.ReviewReason
import io.vertx.core.json.JsonObject

/**
 * Pure functions between the gateway's JSON and the SPI types: no I/O, no `ctx`, never a `Double`.
 *
 * Wire amounts are ISO minor units (`1234` = 12.34 EUR, `500` = 500 JPY); `Money` holds value x 100, so the conversion
 * always goes through `Money.toMinorUnits()` / `Money.ofMinorUnits()`. The gateway writes Turkish lira as `TL`.
 */
object ExampleMapper {
    /** Currencies this gateway accepts (the capabilities and the eligibility check use the same set). */
    val SUPPORTED_CURRENCIES: Set<String> = setOf("EUR", "USD", "GBP", "TRY", "JPY")

    class Wire(val minor: Long, val currency: String)

    fun toWireCurrency(code: String): String = if (code == "TRY") "TL" else code

    fun fromWireCurrency(code: String): String = code.trim().uppercase().let { if (it == "TL") "TRY" else it }

    fun toWire(money: Money): Wire = Wire(money.toMinorUnits(), toWireCurrency(money.currency))

    /** Throws `IllegalArgumentException` / `ArithmeticException` for a currency the SPI refuses or an overflowing amount. */
    fun fromWire(minor: Long, wireCurrency: String): Money = Money.ofMinorUnits(minor, fromWireCurrency(wireCurrency))

    /** Null instead of throwing: the notification is authentic but carries something the SPI cannot represent. */
    fun moneyOrNull(minor: Long?, wireCurrency: String?): Money? {
        if (minor == null || wireCurrency == null) return null
        return try {
            fromWire(minor, wireCurrency)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * One gateway payment object to the event it states, or null when the object has no usable identity or its status is
     * unknown (the caller answers `ignored`). `paid` is exactly what the gateway says it collected (`amountPaid`).
     */
    fun paymentEvent(payment: JsonObject, occurredAtMs: Long?): PaymentEvent? {
        val id = payment.str("id")
        val reference = payment.str("reference")
        val target: PaymentTarget = when {
            reference != null -> PaymentTarget.Reference(reference)
            id != null -> PaymentTarget.GatewayTransaction(id)
            else -> return null
        }
        val currency = payment.str("currency")
        val paid = moneyOrNull(payment.long("amountPaid"), currency)
        val event: PaymentEvent = when (payment.str("status")?.lowercase()) {
            "paid" -> when {
                currency != null && moneyOrNull(0, currency) == null -> review(target, ReviewReason.CURRENCY_MISMATCH, null)
                paid == null -> review(target, ReviewReason.OTHER, null)
                else -> PaymentEvent.Succeeded(target, paid).also { succeeded ->
                    succeeded.gatewayFee = moneyOrNull(payment.long("fee"), currency)
                    succeeded.methodDetail = payment.str("method")
                }
            }
            "underpaid" -> review(target, ReviewReason.UNDERPAID, paid)
            "overpaid" -> review(target, ReviewReason.OVERPAID, paid)
            "wrong_currency" -> review(target, ReviewReason.CURRENCY_MISMATCH, moneyOrNull(payment.long("amountPaid"), payment.str("currencyPaid")))
            "pending" -> PaymentEvent.Pending(target, PendingReason.AWAITING_BUYER)
            "processing" -> PaymentEvent.Pending(target, PendingReason.AWAITING_CONFIRMATIONS)
            "failed" -> PaymentEvent.Failed(target, payment.str("failureCode") ?: "failed", payment.str("failureMessage")).also { it.final = true }
            "expired" -> PaymentEvent.Expired(target)
            "cancelled" -> PaymentEvent.Cancelled(target)
            else -> return null
        }
        event.gatewayTransactionId = id
        event.occurredAt = occurredAtMs
        payment.bool("livemode")?.let { event.testMode = !it }
        return event
    }

    private fun review(target: PaymentTarget, reason: ReviewReason, received: Money?): PaymentEvent.NeedsReview =
        PaymentEvent.NeedsReview(target, reason).also { it.received = received }

    fun refundState(status: String?): RefundState? = when (status?.lowercase()) {
        "succeeded" -> RefundState.SUCCEEDED
        "pending" -> RefundState.PENDING
        "failed" -> RefundState.FAILED
        "cancelled" -> RefundState.CANCELLED
        else -> null
    }

    /** One gateway refund object (webhook `refund.updated`) to an event, null when it cannot be attributed or has an unknown status. */
    fun refundEvent(refund: JsonObject, occurredAtMs: Long?): PaymentEvent.RefundUpdated? {
        val reference = refund.str("reference")
        val paymentId = refund.str("paymentId")
        val target: PaymentTarget = when {
            reference != null -> PaymentTarget.Reference(reference)
            paymentId != null -> PaymentTarget.GatewayTransaction(paymentId)
            else -> return null
        }
        val state = refundState(refund.str("status")) ?: return null
        return PaymentEvent.RefundUpdated(target, state, moneyOrNull(refund.long("amount"), refund.str("currency"))).also {
            it.gatewayRefundId = refund.str("id")
            it.refundKey = refund.str("idempotencyKey")
            it.occurredAt = occurredAtMs
        }
    }

    /** The answer of `POST /v1/payments/{id}/refunds` or `GET /v1/refunds/{id}`; unknown status is [RefundResult.unknown]. */
    fun refundResult(refund: JsonObject): RefundResult {
        val result: RefundResult = when (refundState(refund.str("status"))) {
            RefundState.SUCCEEDED -> RefundResult.Succeeded()
            RefundState.PENDING -> RefundResult.Pending()
            RefundState.FAILED, RefundState.CANCELLED -> RefundResult.Failed(refund.str("failureCode") ?: "failed", refund.str("failureMessage"))
            null -> return RefundResult.unknown()
        }
        result.gatewayRefundId = refund.str("id")
        result.refundedAmount = moneyOrNull(refund.long("amount"), refund.str("currency"))
        return result
    }
}

/** Tolerant readers: a wrong JSON type is "absent", never a `ClassCastException`. */
internal fun JsonObject.str(key: String): String? = (getValue(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

/** Whole numbers only: a fractional JSON number (`12.34`) is "absent", it is never rounded or truncated into an amount. */
internal fun JsonObject.long(key: String): Long? = when (val value = getValue(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal fun JsonObject.bool(key: String): Boolean? = getValue(key) as? Boolean

internal fun JsonObject.obj(key: String): JsonObject? = getValue(key) as? JsonObject
