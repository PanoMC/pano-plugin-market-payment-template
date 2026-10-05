package com.panomc.plugins.marketpay.example

/**
 * The only place that holds a host name of the gateway (P-24 scans the other sources for one). The provider takes an
 * optional `(Boolean) -> ExampleEndpoints` constructor argument that tests use to point it at a FakeGateway.
 */
class ExampleEndpoints(val api: String) {
    companion object {
        const val LIVE_API = "https://api.examplepay.example"
        const val SANDBOX_API = "https://sandbox.api.examplepay.example"
        const val DOCS_URL = "https://docs.examplepay.example"

        /** `testMode` is `ctx.testMode`: the method flag or the store-wide test mode. */
        fun of(testMode: Boolean): ExampleEndpoints = ExampleEndpoints(if (testMode) SANDBOX_API else LIVE_API)
    }
}
