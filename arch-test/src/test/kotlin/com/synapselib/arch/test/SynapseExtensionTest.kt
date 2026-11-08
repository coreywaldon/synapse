package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.SynapseScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

/** `@RegisterExtension` field style, with a configure block. */
class SynapseExtensionFieldTest {

    @JvmField
    @RegisterExtension
    val synapse = SynapseExtension {
        provide<List<String>, FetchItems> { listOf("jupiter-${it.query}") }
    }

    @BeforeEach
    fun startedBeforeEach() {
        assertTrue(synapse.isStarted)
    }

    @Test
    fun triggerAndCapture() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        synapse.Trigger(Ping("j5"))
        assertEquals("j5", ping.assertCaptured().value)
    }

    @Test
    fun providersFromConfigureAreAvailable() = synapse.runTest {
        val request = synapse.onRequest<FetchItems>()
        val result = synapse.Request(FetchItems("q")).first { it is DataState.Success }
        assertEquals(DataState.Success(listOf("jupiter-q")), result)
        assertEquals(FetchItems("q"), request.assertCaptured())
    }

    @Test
    fun coordinatorHelperWorks() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        val events = mutableListOf<String>()
        synapse.Coordinator {
            onStart { events += "start" }
            ReactTo<Pong> { launch { Trigger(Ping(it.value + "-reply")) } }
        }

        synapse.Trigger(Pong("hello"))

        assertEquals(listOf("start"), events)
        assertEquals("hello-reply", ping.assertCaptured().value)
        assertEquals(Lifecycle.State.RESUMED, synapse.lifecycleState)
    }
}

/** `@ExtendWith` parameter style: one shared extension instance, restarted per test. */
@ExtendWith(SynapseExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SynapseExtensionParameterTest {

    companion object {
        private var previousBoard: DefaultSwitchBoard? = null
        private var previousHarness: SynapseTestHarness? = null
    }

    @Test
    @Order(1)
    fun resolvesHarnessScopeAndBoard(
        harness: SynapseTestHarness,
        extension: SynapseExtension,
        scope: SynapseScope,
        switchBoard: SwitchBoard,
        defaultSwitchBoard: DefaultSwitchBoard,
    ) {
        assertSame(harness, extension)
        assertSame(harness, scope)
        assertSame(harness.switchBoard, switchBoard)
        assertSame(harness.switchBoard, defaultSwitchBoard)
        previousBoard = harness.switchBoard
        previousHarness = harness
    }

    @Test
    @Order(2)
    fun eachTestGetsAFreshBoardAndThePreviousOneIsClosed(harness: SynapseTestHarness) = harness.runTest {
        assertSame(previousHarness, harness, "@ExtendWith shares one extension instance per class")
        assertNotSame(previousBoard, harness.switchBoard)
        assertTrue(previousBoard!!.isClosed)

        val counter = harness.onState<Counter>()
        harness.Broadcast(Counter(5))
        assertEquals(5, counter.assertCaptured().count)
    }

    @Nested
    inner class NestedTests {
        @Test
        fun nestedTestsAreStartedToo(harness: SynapseTestHarness) {
            assertTrue(harness.isStarted)
        }
    }

    @AfterEach
    fun stillStartedInAfterEach(harness: SynapseTestHarness) {
        // Extension afterEach callbacks run after @AfterEach methods.
        assertTrue(harness.isStarted)
    }
}
