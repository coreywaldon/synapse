package com.synapselib.arch.test

import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class SynapseTestRuleBasicTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun switchBoardIsInitialized() {
        assertNotNull(synapse.switchBoard)
    }

    @Test
    fun lifecycleOwnerIsAvailable() {
        assertNotNull(synapse.lifecycleOwner)
    }

    @Test
    fun testDispatcherIsAvailable() {
        assertNotNull(synapse.testDispatcher)
    }
}
