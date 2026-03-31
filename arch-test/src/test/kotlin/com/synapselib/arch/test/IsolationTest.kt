package com.synapselib.arch.test

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IsolationTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun interceptorsAreClearedBetweenTests_first() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        synapse.Trigger(Ping("from-first"))
        assertEquals("from-first", ping.assertCaptured().value)
    }

    @Test
    fun interceptorsAreClearedBetweenTests_second() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        ping.assertNotCaptured()
    }
}
