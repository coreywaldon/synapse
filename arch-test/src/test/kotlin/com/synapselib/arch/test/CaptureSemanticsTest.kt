package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.addInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Observer-based capture semantics: timing, matching, reuse and thread safety. */
class CaptureSemanticsTest {

    @get:Rule
    val synapse = SynapseTestRule()

    /** Created at field-initialisation time, before the rule starts. */
    private val earlyPing = synapse.onImpulse<Ping>()

    @Test
    fun captureCreatedBeforeRuleStartsWorks() = synapse.runTest {
        synapse.Trigger(Ping("early"))
        assertEquals("early", earlyPing.assertCaptured().value)
    }

    @Test
    fun captureSeesValueAfterEveryInterceptorEvenOnesAddedLater() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        // Registered after the capture, at the very last priority: the old
        // interceptor-based capture would have run before this one.
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REACTION_UPSTREAM,
            interceptor = Interceptor.transform<Ping> { Ping(it.value + "!") },
            priority = Int.MAX_VALUE,
        )

        synapse.Trigger(Ping("hi"))

        assertEquals("hi!", ping.assertCaptured().value)
    }

    @Test
    fun captureNeverAltersTheInterceptorChain() = synapse.runTest {
        val seenByInterceptor = mutableListOf<String>()
        synapse.onAllImpulses<Ping>()
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REACTION_UPSTREAM,
            interceptor = Interceptor.read<Ping> { seenByInterceptor += it.value },
        )
        val delivered = mutableListOf<String>()
        val job = launch { synapse.ReactTo<Ping>().collect { delivered += it.value } }

        synapse.Trigger(Ping("x"))

        assertEquals(listOf("x"), seenByInterceptor)
        assertEquals(listOf("x"), delivered)
        job.cancel()
    }

    @Test
    fun supertypeCaptureMatchesSubtypeEmissions() = synapse.runTest {
        val sessions = synapse.onAllStates<Session>()

        synapse.Broadcast(Session.LoggedOut)
        synapse.Broadcast(Session.LoggedIn("ada"))

        assertEquals(listOf(Session.LoggedOut, Session.LoggedIn("ada")), sessions.values)
    }

    @Test
    fun subtypeCaptureIgnoresEmissionsUnderSupertypeToken() = synapse.runTest {
        val loggedIn = synapse.onState<Session.LoggedIn>()

        // Emitted under the Session token, like a ListenFor<Session.LoggedIn>
        // listener, the subtype capture does not see it.
        synapse.Broadcast<Session>(Session.LoggedIn("ada"))

        loggedIn.assertNotCaptured()
    }

    @Test
    fun genericCaptureAtDownstreamPointSeesDeliveredValues() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.STATE_DOWNSTREAM,
            interceptor = Interceptor.transform<Counter> { it.copy(count = it.count * 10) },
        )
        val upstream = synapse.capture<Counter>(InterceptPoint.STATE_UPSTREAM)
        val downstream = synapse.captureAll<Counter>(InterceptPoint.STATE_DOWNSTREAM)
        val job = launch { synapse.ListenFor<Counter>().collect { } }

        synapse.Broadcast(Counter(1))
        synapse.Broadcast(Counter(2))

        assertEquals(Counter(2), upstream.assertCaptured())
        assertEquals(listOf(Counter(10), Counter(20)), downstream.values)
        assertEquals(InterceptPoint.STATE_DOWNSTREAM, downstream.point)
        job.cancel()
    }

    @Test
    fun clearResetsCaptures() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        val pings = synapse.onAllImpulses<Ping>()
        synapse.Trigger(Ping("a"))

        ping.clear()
        pings.clear()

        assertNull(ping.value)
        pings.assertNotCaptured()
        synapse.Trigger(Ping("b"))
        assertEquals("b", ping.assertCaptured().value)
        assertEquals(listOf("b"), pings.values.map { it.value })
    }

    @Test
    fun assertionMessagesNameTypeAndPoint() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        val error = runCatching { ping.assertCaptured() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message, error.message!!.contains("Ping"))
        assertTrue(error.message, error.message!!.contains("REACTION/UPSTREAM"))
    }

    @Test
    fun capturesAreThreadSafeUnderConcurrentEmission() = synapse.runTest {
        val all = synapse.onAllImpulses<Ping>()
        val latest = synapse.onImpulse<Ping>()
        val threads = 8
        val perThread = 500

        withContext(Dispatchers.Default) {
            repeat(threads) { t ->
                launch { repeat(perThread) { i -> synapse.Trigger(Ping("$t-$i")) } }
            }
        }

        all.assertCount(threads * perThread)
        assertEquals(threads * perThread, all.values.map { it.value }.toSet().size)
        assertNotNull(latest.value)
    }
}
