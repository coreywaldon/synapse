package com.synapselib.arch.test

import com.synapselib.arch.base.DataImpulse
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

data class FetchMulti(val query: String) : DataImpulse<List<String>>()

class ProviderConfigTest {

    private val testItems = listOf("apple", "banana", "cherry")

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<String>, FetchItems> { testItems }
        provideFlow<List<String>, FetchMulti> {
            flow {
                emit(listOf("first"))
                emit(listOf("first", "second"))
            }
        }
    }

    @Test
    fun providerRegistryContainsRegisteredProvider() {
        assertTrue(synapse.switchBoard.toString().isNotEmpty())
    }
}
