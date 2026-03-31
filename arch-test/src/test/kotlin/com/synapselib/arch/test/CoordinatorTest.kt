package com.synapselib.arch.test

import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CoordinatorTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun coordinatorHelperInitializesScope() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        synapse.Coordinator {
            ReactTo<Pong> {
                launch { Trigger(Ping(it.value + "-reply")) }
            }
        }

        synapse.Trigger(Pong("hello"))

        assertEquals("hello-reply", ping.assertCaptured().value)
    }

    @Test
    fun multipleCoordinatorsCanCoexist() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        val counter = synapse.onState<Counter>()

        synapse.Coordinator {
            ReactTo<Pong> {
                launch { Trigger(Ping(it.value)) }
            }
        }

        synapse.Coordinator {
            ReactTo<Pong> {
                launch { Broadcast(Counter(it.value.length)) }
            }
        }

        synapse.Trigger(Pong("test"))

        ping.assertCaptured()
        assertEquals(4, counter.assertCaptured().count)
    }
}
