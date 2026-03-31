package com.synapselib.arch.test

import org.junit.Rule
import org.junit.Test

class AssertionFailureTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test(expected = AssertionError::class)
    fun assertCapturedThrowsWhenNotCaptured() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        ping.assertCaptured()
    }

    @Test(expected = AssertionError::class)
    fun assertNotCapturedThrowsWhenCaptured() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        synapse.Trigger(Ping())
        ping.assertNotCaptured()
    }

    @Test(expected = AssertionError::class)
    fun captureAllAssertCountThrowsOnMismatch() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()
        synapse.Trigger(Ping())
        pings.assertCount(2)
    }

    @Test(expected = AssertionError::class)
    fun captureAllAssertCapturedThrowsWhenEmpty() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()
        pings.assertCaptured()
    }

    @Test(expected = AssertionError::class)
    fun captureAllAssertNotCapturedThrowsWhenNonEmpty() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()
        synapse.Trigger(Ping())
        pings.assertNotCaptured()
    }
}
