# Verification record

Level: UNVERIFIED

This file says what was verified for this plugin, how and when (spec 16 section 8.4). The level written here must equal
`descriptor.verification` in the code and `level` in `src/test/resources/verification.json`; `VerificationLevelTest`
fails when they differ or when the evidence does not meet the condition of the level.

| Level | Allowed when |
|---|---|
| `UNVERIFIED` | default |
| `DOC_SAMPLES` | at least one `OFFICIAL` vector covers the inbound authenticity check (signature, hash or the documented re-query response), and one covers the outbound request signature when the gateway signs requests |
| `SANDBOX` | a `sandbox` block in `verification.json`: start + inbound (+ refund when refunds are offered) ran against the gateway's test environment; who / when below |
| `LIVE` | the owner ran a real payment; recorded below |

An agent may never raise the level without the evidence recorded here.

## What the example plugin has

- One vector file, `src/test/resources/vectors/webhook.json`, kind `SELF_DERIVED`: the expected signatures were computed
  with an independent implementation (Python `hmac`) from the algorithm below. The gateway of this template is
  imaginary, so there is no official documentation to take a sample from. These vectors prove that the code is stable
  and matches its own description; they prove nothing about a real gateway.
- The flow tests run against `spi.testkit.FakeGateway`, which speaks exactly the protocol below.
- No sandbox run, no live payment. Level: `UNVERIFIED`.

## Protocol of the example gateway (the assumptions the code makes)

| Item | Value |
|---|---|
| Credentials | `Authorization: Bearer <apiKey>` on every call |
| Create payment | `POST /v1/payments`, JSON `{reference, amount, currency, description, successUrl, cancelUrl, notifyUrl, expiresAt, locale, customerEmail?, statementDescriptor?}`, header `Idempotency-Key`; answer `{id, url, expiresAt?}` |
| Read payment | `GET /v1/payments/{id}`; by reference: `GET /v1/payments/lookup?reference=...`; unknown = HTTP 404 |
| Refund | `POST /v1/payments/{id}/refunds` `{amount, currency, reference, reason?}` + `Idempotency-Key`; answer `{id, status: pending / succeeded / failed, amount, currency}`; a 4xx with `{error: {code, message}}` is a refusal |
| Read refund | `GET /v1/refunds/{id}` |
| Account check | `GET /v1/account` |
| Amounts | integer ISO minor units (`1234` = 12.34, JPY `500` = 500 yen); Turkish lira is written `TL` |
| Payment status | `paid`, `underpaid`, `overpaid`, `wrong_currency`, `pending`, `processing`, `failed`, `expired`, `cancelled` (`amountPaid` = what arrived) |
| Webhook | `POST` JSON `{id, type: payment.updated / refund.updated, createdAt, data}` |
| Webhook signature | header `Example-Signature: t=<unix seconds>,v1=<hex>`, `v1 = HMAC-SHA256(secret, "<t>." + rawBody)`, several `v1` while a secret rotates; timestamp tolerance 300 s |
| Hosts | live and sandbox hosts in `ExampleEndpoints.kt` only |

### If an assumption turns out wrong

For a real gateway, every row of this table is an `UNVERIFIED` assumption until a sandbox run confirms it. Record each
one that the brief marks `UNVERIFIED` here, and say how the code behaves if the assumption is wrong:

| Assumption | Behaviour if wrong |
|---|---|
| the signature covers `"<t>." + rawBody` | every webhook is rejected with 400 (`signature: mismatch`): payments stay pending until market's reconciliation (`queryPayment`) confirms them; nothing is ever accepted by mistake |
| `amountPaid` is the collected amount | a `paid` without it becomes `NeedsReview(OTHER)`; a different meaning cannot make a wrong `Succeeded` because the amount is never taken from anywhere else |
| refunds answer a final status synchronously | an unknown status is `RefundResult.Unknown`; market polls `queryRefund` |

## Sandbox / live record

None.
