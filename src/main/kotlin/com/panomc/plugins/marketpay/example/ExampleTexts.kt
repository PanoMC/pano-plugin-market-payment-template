package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.common.LocalizedText

/**
 * Every admin-facing text of the plugin as an i18n key (16 section 8.2): the key is `plugins.<pluginId>.<path>`, the
 * same path inside `locales/{en-US,tr,ru}.json`, and the English fallback lives here. [all] lists every text so the
 * locale test can prove that the three files hold exactly these keys.
 */
object ExampleTexts {
    const val PLUGIN_ID = "pano-plugin-market-example"

    private val registry = ArrayList<LocalizedText>()

    private fun text(path: String, fallback: String): LocalizedText =
        LocalizedText.key("plugins.$PLUGIN_ID.$path", fallback).also { registry.add(it) }

    val displayName = text("descriptor.name", "Example Pay")
    val description = text("descriptor.description", "Accept card and wallet payments through Example Pay.")
    val checkoutHint = text("descriptor.hint", "Pay securely with Example Pay")

    val groupCredentials = text("settings.groups.credentials", "Credentials")
    val groupOptions = text("settings.groups.options", "Options")

    val apiKeyLabel = text("settings.fields.apiKey.label", "API key")
    val apiKeyHelp = text(
        "settings.fields.apiKey.help",
        "Secret API key from the developer section of your Example Pay dashboard. Use a test key together with test mode."
    )
    val webhookSecretLabel = text("settings.fields.webhookSecret.label", "Webhook secret")
    val webhookSecretHelp = text(
        "settings.fields.webhookSecret.help",
        "Signing secret of the webhook endpoint in your Example Pay dashboard."
    )
    val webhookUrlLabel = text("settings.fields.webhookUrl.label", "Webhook URL")
    val webhookUrlHelp = text(
        "settings.fields.webhookUrl.help",
        "Add this URL as a webhook endpoint in your Example Pay dashboard and select the events payment.updated and refund.updated."
    )
    val refundsEnabledLabel = text("settings.fields.refundsEnabled.label", "Allow refunds from the panel")
    val refundsEnabledHelp = text(
        "settings.fields.refundsEnabled.help",
        "When off, Pano Market never asks Example Pay for a refund. Refund in your Example Pay dashboard instead."
    )
    val statementDescriptorLabel = text("settings.fields.statementDescriptor.label", "Statement descriptor")
    val statementDescriptorHelp = text(
        "settings.fields.statementDescriptor.help",
        "Text on the buyer's card statement, at most 22 characters. Leave empty for the Example Pay default."
    )

    val testConnectionLabel = text("settings.actions.test-connection.label", "Test connection")

    val errorMissing = text("errors.missing", "This field is required.")
    val errorAuthentication = text("errors.authentication", "Example Pay rejected the API key.")
    val errorUnreachable = text("errors.unreachable", "Example Pay could not be reached. Try again in a moment.")
    val connectionOk = text("messages.connectionOk", "The connection to Example Pay works.")

    val eligibilityCurrency = text("eligibility.currency", "Example Pay cannot take payments in this currency.")

    /** Every text above, in declaration order. */
    val all: List<LocalizedText> get() = registry.toList()
}
