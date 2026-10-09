# Pano Market payment plugin template

A complete, compiling, tested payment provider plugin for [Pano Market](https://panomc.com), written for an imaginary
gateway called **Example Pay**. Copy it, run `scripts/rename.sh`, replace the example protocol with your gateway's and
you have a plugin: the build, the license wiring, the quality gates and the 26 provider tests come with it.

## What this is

- `src/main/kotlin/com/panomc/plugins/marketpay/example/`: the plugin class, the extension, the provider and the files
  around it (settings, endpoints, client, signature, mapper, texts).
- `src/test/`: the tests every provider plugin must have (P-01 to P-26 of the Pano Market provider test plan), a vector
  file, and the verification record. They run against `spi.testkit.FakeGateway`, a small HTTP server that speaks the
  protocol of the example gateway.
- `build.gradle.kts` (managed, identical in every provider plugin) with two gates: `checkImports` for the sources and
  `verifyPluginJar` for the finished jar.
- `scripts/rename.sh`, `store/`, `.github/workflows/ci.yml`, `.releaserc.json`, `AGENT.md`, `VERIFICATION.md`.

## Requirements

- JDK 21 to run Gradle (the plugin itself is compiled for Java 11 bytecode, which is what Pano runs on; the tests use a
  JDK 21 launcher).
- A Pano release at or above `panoVersion` and Pano Market at or above `marketApiVersion` (both in `gradle.properties`).
- Network access to the GitHub releases of `PanoMC/Pano` and `PanoMC/pano-plugin-market` on the first build, unless you
  pass local jars (below).

## Quick start

```sh
scripts/rename.sh my-gateway "My Gateway"   # once, in a fresh copy
./gradlew build
```

The slug is lower case letters, digits and single hyphens, 2 to 29 characters, starting with a letter. It becomes:

| Thing | Value for `my-gateway` |
|---|---|
| provider id | `my-gateway` |
| plugin id, store resource id, jar name | `pano-plugin-market-my-gateway` |
| root package | `com.panomc.plugins.marketpay.mygateway` |
| classes | `MyGatewayPlugin`, `MyGatewayExtension`, `MyGatewayProvider`, ... |
| i18n namespace | `plugins.pano-plugin-market-my-gateway.*` |

The jar is `build/libs/pano-plugin-market-my-gateway-<version>.jar`. Put it into the `plugins` folder of a Pano that has
Pano Market installed.

## How dependencies resolve

| Context | Platform classes | Market classes | Command |
|---|---|---|---|
| Template / third party (this repository) | Ivy: `Pano-<panoVersion>.jar` from the `PanoMC/Pano` release | Ivy: `pano-plugin-market-api-<marketApiVersion>.jar` from the `PanoMC/pano-plugin-market` release | `./gradlew build` |
| Offline or an unreleased combination | `-PpanoJar=<path>` | `-PmarketApiJar=<path>` | `./gradlew build -PpanoJar=... -PmarketApiJar=...` |
| Platform fallback | `-PpanoSource=jitpack` | as above | |
| Embedded in a Pano checkout (`bootstrap=true`) | `project(":Pano")` | the market project's classes | from the platform root: `./gradlew :plugins:<folder>:jar` |

Market classes and the platform are `compileOnly`: they come from the host at run time and must never be in your jar.
Only `com.panomc.plugins.market.spi.*` may be imported.

## What you may and may not do

A provider never:

1. reports `Succeeded` from an unauthenticated source (a browser return, an unsigned body) without its own server-side
   confirmation;
2. trusts amounts or currencies from the browser; it reports `paid` exactly as the gateway states it;
3. shades or copies market, Kotlin, coroutines, Vert.x or Gson classes, or uses a root package inside
   `com.panomc.plugins.market`;
4. declares routes, DAOs for market tables, timers, or its own SMTP / database access for payment state;
5. blocks the event loop (no blocking HTTP client, no `Thread.sleep`, no heavy synchronous crypto);
6. logs or returns secrets, card numbers, CVV or identity numbers;
7. keeps correctness-relevant state only in memory (use `providerData` or `ctx.state`);
8. throws for an inbound request it merely does not understand (return `ignored` with the reply the gateway expects);
9. answers a webhook with success before it has verified it;
10. depends on the order in which its own plugin and market start;
11. builds a redirect target or an outbound URL from inbound request input;
12. sets `eventKey` for a notification that is only a trigger.

Libraries:

| Need | Use | Never |
|---|---|---|
| Outbound HTTP | `ctx.http` with `.timeout(15_000)` on every request and `ctx.log.exchange(...)` | vendor SDKs, OkHttp, Apache HttpClient, `java.net.http`, `HttpURLConnection`, your own `WebClient` |
| JSON | `io.vertx.core.json.JsonObject` / `JsonArray` | Jackson annotations, kotlinx.serialization, a shaded Gson |
| Form bodies | `InboundRequest.form(charset)` | manual URL decoding, except where the signature needs the raw string |
| HMAC / hashes / RSA / ECDSA | JDK `javax.crypto.Mac`, `MessageDigest`, `java.security.Signature`; constant-time compare with `MessageDigest.isEqual` | BouncyCastle (it breaks the jar size limit) |
| Anything else | `shadedDependencies` + `shadedRelocations` in `gradle.properties`, Java 11 bytecode | un-relocated shading |

`checkImports` fails the build on the import rules (MP-I01 to MP-I05) and `verifyPluginJar` on the jar rules (MP-J01 to
MP-J09: nothing from the host inside the jar, the three locale files and the logo present, class files at most Java 11,
the jar at most 9 500 000 bytes, a manifest with `market-spi: payment` and no version constraint on the dependency).

## Settings schema and locales

The settings form is described in code (`settingsSchema { }` in the provider): secret fields with `secret(...)`, the
read-only webhook URL with `webhookUrl(...)`, an action `test-connection`. Every label, help text, group name, action
label, notice, descriptor text and error text is `LocalizedText.key("plugins.<pluginId>.<path>", "<English text>")`,
all declared in `ExampleTexts.kt`, and the same path exists in `src/main/resources/locales/en-US.json`, `tr.json` and
`ru.json`. `LocaleCompletenessTest` fails when a key is missing in one file, when the files differ, when a value is
empty, when an English text differs from its fallback in the code, or when a key is not used by anything.

Keys of the settings reuse the names the old Market catalogue used for the same gateway, so settings saved before the
plugin existed attach to it.

## The slot view (optional UI)

`src/theme/views/PaymentNote.svelte` is a complete example of a view a gateway plugin puts into the Market's checkout: one file,
and its `<script module>` says where it goes.

```svelte
<script module>
  export const view = { slot: 'market:checkout:payment', id: "example" };
</script>
```

`id` is the gateway's method id, so the Market shows the note only while this method is chosen. A gateway with an in-page step
puts its view into `market:order:payment` instead (id = the gateway key; props `order`, `payment`, `props`, `locale`,
`continuePayment`, `refresh`), and a panel hint is a view with `hook: '<hook name>'`. The build is the kit preset in
`rollup.config.js` (`bun run build`, `bunx pano-plugin check --strict --styles badge`); a plugin that needs no UI deletes
`rollup.config.js`, `package.json` dependencies and `src/theme/`, and builds as a Kotlin-only plugin. `scripts/rename.sh`
renames the namespace of the view along with the rest.

## Testing

```sh
./gradlew test        # also part of ./gradlew build
```

- **Vectors** in `src/test/resources/vectors/*.json` hold signature test values: `OFFICIAL` (copied from the
  gateway's documentation or its official SDK tests, `source` names the page) or `SELF_DERIVED` (computed from the
  documented algorithm). Test names start with `official -` or `self-derived -`. Secrets in vectors are the public
  sample values of the documentation, never real keys.
- **FakeGateway** (`com.panomc.plugins.market.spi.testkit`) is a Vert.x HTTP server on a loopback port that records
  every request (raw bytes included) and answers what a test scripts. The provider is pointed at it through the
  optional `endpoints` constructor argument; nothing in the tests contacts a real host.
- **The contract suite** (`ProviderContractTest`) checks garbage inbound traffic, secrets in logs, purity of
  `capabilities`, unknown event types and delivery keys for every provider.
- **Verification levels** (`UNVERIFIED`, `DOC_SAMPLES`, `SANDBOX`, `LIVE`) are written in
  `src/test/resources/verification.json` and `VERIFICATION.md`, must equal `descriptor.verification`, and are checked
  by `VerificationLevelTest`: a plugin cannot claim a level without the evidence for it.
- `CoverageTest` fails when one of P-01 to P-26 has no test. A case that does not apply to your gateway stays as a test
  that says why (here P-16, the unsigned-gateway case, and P-21, recurring).

## Making it a paid plugin

Set `licenseRequired=true` in `gradle.properties` (and `pluginLicense` to your licence). A release build then fails
(`MP-B02`) unless a license key was embedded: pass `-PlicenseServer=dev|prod` or set `PANO_LICENSE_SERVER` in CI. The store
resource id must equal the plugin id. A local premium test uses `-PlicenseServer=dev` against `api-dev.panomc.com`. The
license classes in `com.panomc.plugins.license` are managed files: never edit them. A free build (the default) makes the
license code a no-op.

## Release configuration

`.releaserc.json` holds the semantic-release setup: prerelease branch `dev`, stable `main`, upload to the store with
`@PanoMC/semantic-release-pano` (resource id = plugin id), and the jar as a GitHub release asset. It uses
`semantic-release-monorepo` because the template is also the source of the folders of a multi-plugin repository; in a
repository of its own, remove the `extends` line and the `repositoryUrl`. `store/store.json` and `store/description.html`
describe the store resource. The commit messages must be conventional commits; a `build:` commit releases nothing.

## Licence

This template is MIT licensed (`LICENSE`). The licence of the plugin you build from it is yours: change `pluginLicense`
in `gradle.properties`, `license` in `store/store.json` and the `LICENSE` file.
