package com.synapselib.arch.test

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureStateTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun onStateCapturesBroadcast() = synapse.runTest {
        val counter = synapse.onState<Counter>()

        synapse.Broadcast(Counter(42))

        assertEquals(42, counter.assertCaptured().count)
    }

    @Test
    fun onStateNotCapturedBeforeBroadcast() = synapse.runTest {
        val counter = synapse.onState<Counter>()

        counter.assertNotCaptured()
    }

    @Test
    fun onStateReplacesWithLatest() = synapse.runTest {
        val counter = synapse.onState<Counter>()

        synapse.Broadcast(Counter(1))
        synapse.Broadcast(Counter(2))

        assertEquals(2, counter.assertCaptured().count)
    }
}
