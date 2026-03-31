package com.synapselib.arch.test

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CaptureAllImpulsesTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun collectsAllInOrder() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()
        val list = listOf(Ping("a"), Ping("b"), Ping("c"))

        synapse.Trigger(list[0])
        synapse.Trigger(list[1])
        synapse.Trigger(list[2])

        pings.assertCount(3)
        assertEquals(pings.assertCaptured(), list)
    }

    @Test
    fun emptyBeforeTrigger() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()

        pings.assertNotCaptured()
        pings.assertCount(0)
    }

    @Test
    fun countReflectsNumberCaptured() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()

        assertEquals(0, pings.count)
        synapse.Trigger(Ping("a"))
        assertEquals(1, pings.count)
        synapse.Trigger(Ping("b"))
        assertEquals(2, pings.count)
    }

    @Test
    fun latestReturnsLast() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()

        synapse.Trigger(Ping("a"))
        synapse.Trigger(Ping("b"))

        assertEquals("b", pings.latest!!.value)
    }

    @Test
    fun assertCapturedReturnsAll() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()

        synapse.Trigger(Ping("a"))

        val all = pings.assertCaptured()
        assertEquals(1, all.size)
    }

    @Test
    fun onlyCapturesMatchingType() = synapse.runTest {
        val pings = synapse.onAllImpulses<Ping>()
        val pongs = synapse.onAllImpulses<Pong>()

        synapse.Trigger(Ping("a"))
        synapse.Trigger(Pong("b"))
        synapse.Trigger(Ping("c"))

        pings.assertCount(2)
        pongs.assertCount(1)
    }
}

class CaptureAllStatesTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun collectsAllInOrder() = synapse.runTest {
        val counters = synapse.onAllStates<Counter>()

        synapse.Broadcast(Counter(1))
        synapse.Broadcast(Counter(2))

        counters.assertCount(2)
        assertEquals(1, counters.values[0].count)
        assertEquals(2, counters.values[1].count)
    }
}
