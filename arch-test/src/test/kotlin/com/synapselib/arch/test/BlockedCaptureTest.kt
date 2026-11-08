@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.addInterceptor
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Values an interceptor blocks (does not pass on with `proceed`) are not
 * dispatched, so ordinary captures never see them; [SynapseTestHarness.onBlocked]
 * records them instead.
 */
class BlockedCaptureTest {

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<String>, FetchItems> { listOf("item-${it.query}") }
    }

    @Test
    fun blockedImpulsesAreNotCapturedAsDispatched() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REACTION_UPSTREAM,
            interceptor = Interceptor.full<Ping> { ping, proceed -> if (ping.value != "spam") proceed(ping) else ping },
        )
        val dispatched = synapse.onAllImpulses<Ping>()
        val blocked = synapse.onBlocked<Ping>(InterceptPoint.REACTION_UPSTREAM)

        synapse.Trigger(Ping("hello"))
        synapse.Trigger(Ping("spam"))

        assertEquals(listOf("hello"), dispatched.values.map { it.value })
        assertEquals(listOf("spam"), blocked.values.map { it.value })
    }

    @Test
    fun blockedStatesAreNotCapturedAsDispatched() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.STATE_UPSTREAM,
            interceptor = Interceptor.full<Counter> { c, proceed -> if (c.count >= 0) proceed(c) else c.copy(count = 0) },
        )
        val state = synapse.onState<Counter>()
        val blocked = synapse.onBlocked<Counter>(InterceptPoint.STATE_UPSTREAM)

        synapse.Broadcast(Counter(1))
        synapse.Broadcast(Counter(-5))

        assertEquals(Counter(1), state.assertCaptured())
        // Recorded as it entered the stage, not as the interceptor's discarded return value.
        assertEquals(listOf(Counter(-5)), blocked.values)
    }

    @Test
    fun blockedRequestIsCapturedAndNeverReachesTheProvider() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REQUEST_UPSTREAM,
            interceptor = Interceptor.full<FetchItems> { req, proceed -> if (req.query != "secret") proceed(req) else req },
        )
        val requests = synapse.onAllRequests<FetchItems>()
        val results = synapse.onAllRequestResults<List<String>>()
        val gated = synapse.onBlocked<FetchItems>(InterceptPoint.REQUEST_UPSTREAM)

        val states = synapse.Request(FetchItems("secret")).take(1).toList()
        synapse.Request(FetchItems("open")).first { it is DataState.Success }

        assertEquals(listOf<DataState<List<String>>>(DataState.Loading), states)
        assertEquals(listOf(FetchItems("secret")), gated.values)
        assertEquals(listOf(FetchItems("open")), requests.values)
        assertEquals(listOf(listOf("item-open")), results.values)
    }

    @Test
    fun blockedRequestResultsAreCapturedDownstream() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REQUEST_DOWNSTREAM,
            interceptor = Interceptor<List<String>> { list, _ -> list },
        )
        val results = synapse.onAllRequestResults<List<String>>()
        val filtered = synapse.onBlocked<List<String>>(InterceptPoint.REQUEST_DOWNSTREAM)

        val states = mutableListOf<DataState<List<String>>>()
        val job = launch { synapse.Request(FetchItems("x")).collect { states += it } }
        advanceUntilIdle()

        // The provider produced a result, but it was filtered out downstream.
        filtered.assertCount(1)
        assertEquals(listOf(listOf("item-x")), filtered.values)
        results.assertNotCaptured()
        assertEquals(listOf<DataState<List<String>>>(DataState.Loading), states)
        job.cancel()
    }

    @Test
    fun blockedCaptureMatchesSubtypesAndPointsLikeOtherCaptures() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.STATE_UPSTREAM,
            interceptor = Interceptor<Session> { s, _ -> s },
        )
        val sessions = synapse.onBlocked<Session>(InterceptPoint.STATE_UPSTREAM)
        val loggedIn = synapse.onBlocked<Session.LoggedIn>(InterceptPoint.STATE_UPSTREAM)
        val elsewhere = synapse.onBlocked<Session>(InterceptPoint.STATE_DOWNSTREAM)

        synapse.Broadcast(Session.LoggedOut)
        synapse.Broadcast(Session.LoggedIn("ada"))

        sessions.assertCount(2)
        // Broadcast<Session.LoggedIn> travels under its own token, so the subtype capture sees it.
        assertEquals(listOf(Session.LoggedIn("ada")), loggedIn.values)
        elsewhere.assertNotCaptured()
    }

    @Test
    fun blockedCaptureMessagesSayBlocked() = synapse.runTest {
        val blocked = synapse.onBlocked<Ping>(InterceptPoint.REACTION_UPSTREAM)
        val error = runCatching { blocked.assertCount(1) }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertTrue(error!!.message!!, error.message!!.contains("REACTION/UPSTREAM (blocked)"))
    }
}
