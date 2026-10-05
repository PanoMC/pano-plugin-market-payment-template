package com.panomc.plugins.marketpay.example

import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.payment.PaymentProvider

class ExampleExtension(license: LicenseCheck) : MarketExtension {
    override val spiVersion: Int = MarketSpi.VERSION

    private val providers = listOf<PaymentProvider>(ExampleProvider(license))

    override fun paymentProviders(): List<PaymentProvider> = providers
}
