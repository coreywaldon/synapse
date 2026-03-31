package com.synapselib.arch.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class CaptureImpulseTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun onImpulseCapturesSingleImpulse() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        synapse.Trigger(Ping("hello"))

        assertEquals("hello", ping.assertCaptured().value)
    }

    @Test
    fun onImpulseNotCapturedBeforeTrigger() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        ping.assertNotCaptured()
    }

    @Test
    fun onImpulseReplacesWithLatest() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        synapse.Trigger(Ping("first"))
        synapse.Trigger(Ping("second"))

        assertEquals("second", ping.assertCaptured().value)
    }

    @Test
    fun onImpulseOnlyCapturesMatchingType() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        val pong = synapse.onImpulse<Pong>()

        synapse.Trigger(Ping("hello"))

        ping.assertCaptured()
        pong.assertNotCaptured()
    }

    @Test
    fun captureValueReturnsNullBeforeTrigger() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        assertNull(ping.value)
    }

    @Test
    fun captureValueReturnsValueAfterTrigger() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        synapse.Trigger(Ping("hello"))

        assertNotNull(ping.value)
        assertEquals("hello", ping.value!!.value)
    }
}
