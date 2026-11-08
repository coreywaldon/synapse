package com.synapselib.arch.test

import com.synapselib.arch.base.SynapseScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The rule inherits the shared [SynapseScope] verbs instead of re-declaring them. */
class SharedVerbsTest {

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<String>, FetchItems> { listOf(it.query, it.query.uppercase()) }
    }

    /** A helper written once against SynapseScope works with the rule too. */
    private suspend fun SynapseScope.announce(name: String) {
        Broadcast(Session.LoggedIn(name))
        Trigger(Ping(name))
    }

    @Test
    fun ruleIsASynapseScopeOverItsSwitchBoard() {
        val scope: SynapseScope = synapse
        assertTrue(scope.switchboard === synapse.switchBoard)
    }

    @Test
    fun scopeExtensionHelpersWorkOnTheRule() = synapse.runTest {
        val session = synapse.onState<Session>()
        val ping = synapse.onImpulse<Ping>()

        synapse.announce("ada")

        assertEquals(Session.LoggedIn("ada"), session.assertCaptured())
        assertEquals("ada", ping.assertCaptured().value)
    }

    @Test
    fun listenForReturnsReplayedStateFlow() = synapse.runTest {
        synapse.Broadcast(Counter(3))
        assertEquals(Counter(3), synapse.ListenFor<Counter>().first())
    }

    @Test
    fun reactToDeliversImpulses() = synapse.runTest {
        val received = mutableListOf<String>()
        val job = launch { synapse.ReactTo<Ping>().collect { received += it.value } }

        synapse.Trigger(Ping("a"))
        synapse.Trigger(Ping("b"))

        assertEquals(listOf("a", "b"), received)
        job.cancel()
    }

    @Test
    fun requestRoutesThroughStubbedProvider() = synapse.runTest {
        val result = synapse.Request(FetchItems("x")).first { it is DataState.Success }
        assertEquals(DataState.Success(listOf("x", "X")), result)
    }

    @Test
    fun requestWithoutProviderEmitsError() = synapse.runTest {
        val result = synapse.Request(FetchNothing(1)).first { it is DataState.Error }
        assertTrue(result is DataState.Error)
    }
}
