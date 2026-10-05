package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.ProviderSettings

/** Typed view over [ProviderSettings]. Defaults here must equal the `default` of the schema. */
class ExampleSettings(private val raw: ProviderSettings) {
    object Keys {
        const val API_KEY = "apiKey"
        const val WEBHOOK_SECRET = "webhookSecret"
        const val WEBHOOK_URL = "webhookUrl"
        const val REFUNDS_ENABLED = "refundsEnabled"
        const val STATEMENT_DESCRIPTOR = "statementDescriptor"
        const val ACTION_TEST_CONNECTION = "test-connection"
        const val GROUP_CREDENTIALS = "credentials"
        const val GROUP_OPTIONS = "options"
    }

    /** Throws `ProviderException(CONFIGURATION)` when absent. */
    val apiKey: String get() = raw.require(Keys.API_KEY)

    /** Throws `ProviderException(CONFIGURATION)` when absent. */
    val webhookSecret: String get() = raw.require(Keys.WEBHOOK_SECRET)

    val refundsEnabled: Boolean get() = raw.boolean(Keys.REFUNDS_ENABLED, true)

    val statementDescriptor: String? get() = raw.string(Keys.STATEMENT_DESCRIPTOR)

    /** Everything a payment needs: both credentials. */
    fun requireCredentials() {
        apiKey
        webhookSecret
    }

    /** The secret values that may never appear in a log line or an error text. */
    fun secretValues(): List<String> =
        listOfNotNull(raw.string(Keys.API_KEY), raw.string(Keys.WEBHOOK_SECRET)).filter { it.length >= 4 }
}
