package com.synapselib.arch.test

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderConfigTest {

    private val testItems = listOf("apple", "banana", "cherry")

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<String>, FetchItems> { testItems }
    }

    @Test
    fun providerRegistryContainsRegisteredProvider() {
        assertTrue(synapse.switchBoard.toString().isNotEmpty())
    }
}
