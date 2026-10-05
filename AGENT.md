# Agent brief — <Display name> (`<slug>`)

Read first, in this order: this file → `pano-market-plugin-spec/gateways/<slug>.md` (the only source of
protocol truth; do not browse beyond it unless it says UNVERIFIED and you need the detail) →
`pano-market-plugin-spec/design/02-payment-spi.md` §3–§10 → the `stripe/` folder as the worked example.

You own only `<slug>/`. Never edit: build.gradle.kts, anything under com/panomc/plugins/license/, another
folder, the repo root, market, the platform. If the SPI cannot express something, stop and report it — do not
work around it.

Fixed values: provider id `<slug>`; plugin id `<plugin id>`; package `<package>`; old settings keys to keep:
<list from the brief>.

Do, in order:
1. Settings + schema + locales (tr, en-US, ru) + descriptor + capabilities (conservative where UNVERIFIED).
2. Signature and mapper as pure functions, with vector tests first (OFFICIAL where the docs give samples).
3. Client (ctx.http only, 15 s timeouts, ctx.log.exchange, error table of spec 16 §8.5).
4. startPayment, handleInbound (skeleton of spec 16 §8.6), queryPayment, refund, recurring — only what the
   brief documents.
5. Flow test against FakeGateway; contract test; verification.json + VERIFICATION.md.
6. store/store.json + description.html; logo.png (≤ 64 KB).

Done means: `./gradlew :plugins:pano-plugin-market-payments:<slug>:build -Pnoui` is green from the platform
root (one Gradle run at a time, wrapped in `systemd-run --user --scope -p MemoryMax=6G`), every test in spec 16
§13.2 exists and passed in a run you actually made, no TODO left, VERIFICATION.md lists each UNVERIFIED item
from the brief and how the code behaves if the assumption is wrong.

Commit: one commit touching only `<slug>/`, message `feat: added <Display name> payment provider`. No push.
Report: capabilities chosen, verification level, deviations from the brief, SPI gaps.

Rules the brief relies on (also in the repo-root `AGENT.md`): English only; no secrets in code, tests or logs;
never `pkill`; never push; domain `panomc.com`; customer-facing text names only the gateway itself.

## How this folder maps to the steps above

The code in this folder is a complete, tested plugin for an imaginary gateway; replace its protocol, keep its shape.

| Step | Files |
|---|---|
| 1 | `<Cls>Provider.kt` (`settingsSchema`, `capabilities`, `descriptor`), `<Cls>Settings.kt` (every key as a constant), `<Cls>Texts.kt` (every text as an i18n key), `src/main/resources/locales/{en-US,tr,ru}.json` |
| 2 | `<Cls>Signature.kt`, `<Cls>Mapper.kt` (pure: no `ctx`, no I/O, never a `Double`), `src/test/resources/vectors/*.json`, `<Cls>SignatureTest.kt`, `<Cls>MapperTest.kt` |
| 3 | `<Cls>Client.kt` (all outbound HTTP; the error mapping of spec 16 §8.5 lives in its `call` function), `<Cls>Endpoints.kt` (the only file with a host name) |
| 4 | `<Cls>Provider.kt` (guard → settings → client → map → return), `handleInbound` in the order of spec 16 §8.6 |
| 5 | `<Cls>FlowTest.kt`, `<Cls>InboundTest.kt`, `<Cls>ProviderTest.kt`, `<Cls>ContractTest.kt`, `VerificationLevelTest.kt`, `src/test/resources/verification.json`, `VERIFICATION.md` |
| 6 | `store/store.json`, `store/description.html`, `src/main/resources/logo.png` |

Rules of thumb that the tests enforce (do not weaken a test to make it pass; fix the code or report the SPI gap):

- `license.assertLicensed()` is the first statement of every suspend method that moves money or goods
  (`startPayment`, `continuePayment`, `handleInbound`, `refund`, `chargeRecurring`, `cancelSubscription`), never of
  `queryPayment` / `queryRefund` (P-23).
- A notification is authenticated from the raw bytes and the exact header value before anything is parsed. An
  unsigned notification or a browser return is never believed: re-query the gateway first (P-16, P-17).
- `paid` is exactly what the gateway says it collected. Under-, over- and wrong-currency payments are `NeedsReview`,
  never a `Succeeded` with an adjusted amount (P-18).
- `eventKey` is the gateway's delivery id when the signature covers the whole payload, else a hash of exactly the
  signed fields (P-14, contract suite).
- No secret value in a log line, an exchange record or an error text (P-22). No host name outside `<Cls>Endpoints.kt`
  (P-24). Every i18n key exists in all three locale files and every key in the files is used (P-03).
- A case of P-01 to P-26 that does not apply to the gateway stays as a test that says why it does not apply
  (`CoverageTest` fails when an id has no test).
- The verification level is `UNVERIFIED` until the evidence for the next level exists (P-26).

Not covered by the unit tests, because the platform classes are not on the test run time classpath: the plugin class and
the extension class (three lines each, spec 16 §7.1). They are exercised when the plugin is built into the platform
(`GW <slug>`, embedded mode) and by `verifyPluginJar`.
