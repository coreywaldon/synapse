package com.synapselib.arch.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.reflect.KClass

/**
 * The outcome API of [InterceptorPipeline]: [InterceptorPipeline.applyInterceptorsOrNull]
 * returns the value to dispatch, or `null` when no `proceed` call reached the
 * end of the chain; the legacy [InterceptorPipeline.applyInterceptors] keeps
 * returning the chain's result regardless.
 */
class InterceptorOutcomeTest {

    data class Request(val id: String, val value: Int = 0, val attempt: Int = 0, val tag: String = "")

    sealed interface Event
    data class Click(val x: Int) : Event

    private val registry = InterceptorRegistry()

    // ── Dispatched ──────────────────────────────────────────────────────

    @Test
    fun `no interceptors - the value is dispatched unchanged and by identity`() = runTest {
        val input = Request("r")
        assertSame(input, registry.applyInterceptorsOrNull(Request::class, input))
        assertSame(input, registry.applyInterceptorsOrNull(input))
    }

    @Test
    fun `interceptors registered for other types do not block`() = runTest {
        registry.add(Interceptor<Click> { data, _ -> data })
        val input = Request("r")
        assertSame(input, registry.applyInterceptorsOrNull(input))
    }

    @Test
    fun `read and transform interceptors dispatch the transformed value`() = runTest {
        val seen = CopyOnWriteArrayList<Request>()
        registry.add(Interceptor.read<Request> { seen += it }, priority = 0)
        registry.add(Interceptor.transform<Request> { it.copy(value = it.value + 1) }, priority = 1)
        registry.add(Interceptor.read<Request> { seen += it }, priority = 2)

        assertEquals(Request("r", value = 1), registry.applyInterceptorsOrNull(Request("r")))
        assertEquals(listOf(Request("r"), Request("r", value = 1)), seen)
    }

    @Test
    fun `the dispatched value is the head's return value, post-processing included`() = runTest {
        registry.add(Interceptor.full<Request> { r, proceed -> proceed(r).copy(tag = "post") }, priority = 0)
        registry.add(Interceptor.transform<Request> { it.copy(value = 9) }, priority = 1)

        assertEquals(Request("r", value = 9, tag = "post"), registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `substituting through proceed dispatches the substitute`() = runTest {
        registry.add(Interceptor.full<Request> { _, proceed -> proceed(Request("substitute")) })

        assertEquals(Request("substitute"), registry.applyInterceptorsOrNull(Request("r")))
    }

    // ── Blocked ─────────────────────────────────────────────────────────

    @Test
    fun `an interceptor that skips proceed blocks the value`() = runTest {
        val later = AtomicInteger(0)
        registry.add(Interceptor<Request> { data, _ -> data.copy(tag = "discarded") }, priority = 0)
        registry.add(Interceptor.read<Request> { later.incrementAndGet() }, priority = 1)

        assertNull(registry.applyInterceptorsOrNull(Request("r")))
        assertEquals(0, later.get(), "interceptors after the blocking one never run")
    }

    @Test
    fun `the docs' full gate blocks when the predicate fails`() = runTest {
        registry.add(Interceptor.full<Request> { r, proceed -> if (r.value >= 10) proceed(r) else r })

        assertNull(registry.applyInterceptorsOrNull(Request("low", value = 3)))
        assertEquals(Request("high", value = 30), registry.applyInterceptorsOrNull(Request("high", value = 30)))
    }

    @Test
    fun `nested - outer proceeds but inner blocks - blocked`() = runTest {
        val outerSaw = CopyOnWriteArrayList<Request>()
        registry.add(
            Interceptor.full<Request> { r, proceed -> proceed(r).also { outerSaw += it }.copy(tag = "outer") },
            priority = 0,
        )
        registry.add(Interceptor.transform<Request> { it.copy(value = 1) }, priority = 1)
        registry.add(Interceptor<Request> { data, _ -> data.copy(tag = "inner") }, priority = 2)

        assertNull(registry.applyInterceptorsOrNull(Request("r")))
        // The outer interceptor still received the inner one's return value.
        assertEquals(listOf(Request("r", value = 1, tag = "inner")), outerSaw)
    }

    @Test
    fun `a supertype interceptor can block subtype values`() = runTest {
        registry.add(Interceptor<Any> { data, _ -> data })
        assertNull(registry.applyInterceptorsOrNull(Click(1)))
        assertNull(registry.applyInterceptorsOrNull(Event::class, Click(2)))
    }

    @Test
    fun `primitive-boxed types can be blocked and dispatched`() = runTest {
        registry.add(Interceptor.full<Int> { n, proceed -> if (n > 0) proceed(n * 2) else n })
        assertEquals(4, registry.applyInterceptorsOrNull(2))
        assertNull(registry.applyInterceptorsOrNull(-2))
    }

    @Test
    fun `calling proceed only after the interceptor returned does not dispatch`() = runTest {
        var late: Interceptor.Chain<Request>? = null
        registry.add(Interceptor<Request> { data, chain -> late = chain; data })

        assertNull(registry.applyInterceptorsOrNull(Request("r")))
        // A later call through the leaked chain neither throws nor changes the outcome reported above.
        assertEquals(Request("late"), late!!.proceed(Request("late")))
    }

    // ── Retry ───────────────────────────────────────────────────────────

    @Test
    fun `proceeding twice reaches the terminal twice and dispatches the returned value`() = runTest {
        val terminalHits = AtomicInteger(0)
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                proceed(r)
                proceed(r.copy(attempt = 2))
            },
            priority = 0,
        )
        registry.add(Interceptor.read<Request> { terminalHits.incrementAndGet() }, priority = 1)

        assertEquals(Request("r", attempt = 2), registry.applyInterceptorsOrNull(Request("r")))
        assertEquals(2, terminalHits.get())
    }

    @Test
    fun `retry after an inner block dispatches the retry`() = runTest {
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                val first = proceed(r)
                if (first.tag == "rejected") proceed(r.copy(attempt = 1)) else first
            },
            priority = 0,
        )
        registry.add(
            Interceptor.full<Request> { r, proceed -> if (r.attempt > 0) proceed(r) else r.copy(tag = "rejected") },
            priority = 1,
        )

        assertEquals(Request("r", attempt = 1), registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `retry on exception dispatches the retry`() = runTest {
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                try {
                    proceed(r)
                } catch (e: IllegalStateException) {
                    proceed(r.copy(attempt = r.attempt + 1))
                }
            },
            priority = 0,
        )
        registry.add(
            Interceptor.transform<Request> { if (it.attempt == 0) throw IllegalStateException("first try") else it },
            priority = 1,
        )

        assertEquals(Request("r", attempt = 1), registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `once any proceed call reached the terminal the value is dispatched`() = runTest {
        // First attempt passes, the second is blocked by the inner gate, and the
        // outer interceptor returns the (blocked) second result: still dispatched,
        // as the head's return value.
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                proceed(r)
                proceed(r.copy(attempt = 1))
            },
            priority = 0,
        )
        registry.add(
            Interceptor.full<Request> { r, proceed -> if (r.attempt == 0) proceed(r) else r.copy(tag = "blocked") },
            priority = 1,
        )

        assertEquals(Request("r", attempt = 1, tag = "blocked"), registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `concurrent proceed calls within one execution are safe`() = runTest {
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                coroutineScope {
                    val a = async { proceed(r.copy(value = 1)) }
                    val b = async { proceed(r.copy(value = 2)) }
                    a.await().copy(value = a.await().value + b.await().value)
                }
            },
            priority = 0,
        )
        registry.add(Interceptor.read<Request> { yield() }, priority = 1)

        assertEquals(Request("r", value = 3), registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `proceed from another dispatcher is observed`() = runBlocking {
        registry.add(
            Interceptor.full<Request> { r, proceed -> withContext(Dispatchers.Default) { proceed(r) } },
        )
        repeat(200) {
            assertEquals(Request("r$it"), registry.applyInterceptorsOrNull(Request("r$it")))
        }
    }

    // ── (g) concurrency ─────────────────────────────────────────────────

    @Test
    fun `concurrent executions with alternating decisions give exact counts`() = runBlocking {
        val terminalHits = AtomicInteger(0)
        registry.add(Interceptor.read<Request> { yield() }, priority = 0)
        registry.add(
            Interceptor.full<Request> { r, proceed ->
                yield()
                if (r.value % 2 == 0) proceed(r) else r.copy(tag = "blocked")
            },
            priority = 1,
        )
        registry.add(Interceptor.read<Request> { terminalHits.incrementAndGet(); yield() }, priority = 2)
        registry.add(Interceptor.full<Request> { r, proceed -> proceed(r).copy(tag = "post") }, priority = 3)

        val n = 10_000
        val results = withContext(Dispatchers.Default) {
            (0 until n).map { i -> async { i to registry.applyInterceptorsOrNull(Request("r$i", value = i)) } }.awaitAll()
        }

        val dispatched = results.filter { it.second != null }
        val blocked = results.filter { it.second == null }
        assertEquals(n / 2, dispatched.size)
        assertEquals(n / 2, blocked.size)
        assertEquals(n / 2, terminalHits.get())
        assertTrue(dispatched.all { (i, r) -> i % 2 == 0 && r == Request("r$i", value = i, tag = "post") })
        assertTrue(blocked.all { (i, _) -> i % 2 == 1 })
    }

    @Test
    fun `concurrent registrations do not disturb in-flight outcomes`() = runBlocking {
        registry.add(Interceptor.full<Request> { r, proceed -> if (r.value % 3 == 0) r else proceed(r) })
        val n = 3_000
        val results = withContext(Dispatchers.Default) {
            val churn = launch {
                repeat(500) { registry.add(Interceptor.read<Request> { }, priority = 10).unregister(); yield() }
            }
            val out = (0 until n).map { i -> async { i to registry.applyInterceptorsOrNull(Request("r", value = i)) } }
                .awaitAll()
            churn.join()
            out
        }
        assertTrue(results.all { (i, r) -> if (i % 3 == 0) r == null else r == Request("r", value = i) })
    }

    // ── (i) legacy applyInterceptors unchanged ──────────────────────────

    @Test
    fun `legacy applyInterceptors still returns the blocking interceptor's value`() = runTest {
        registry.add(Interceptor<Request> { data, _ -> data.copy(tag = "short") }, priority = 0)
        registry.add(Interceptor.transform<Request> { it.copy(value = 99) }, priority = 1)

        assertEquals(Request("r", tag = "short"), registry.applyInterceptors(Request("r")))
        assertEquals(Request("r", tag = "short"), registry.applyInterceptors(Request::class, Request("r")))
        assertNull(registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `legacy and outcome APIs agree on dispatched values`() = runTest {
        registry.add(Interceptor.full<Request> { r, proceed -> proceed(r.copy(value = 1)).copy(tag = "t") })
        registry.add(Interceptor.transform<Request> { it.copy(attempt = 5) })

        val legacy = registry.applyInterceptors(Request("r"))
        assertEquals(Request("r", value = 1, attempt = 5, tag = "t"), legacy)
        assertEquals(legacy, registry.applyInterceptorsOrNull(Request("r")))
    }

    @Test
    fun `third-party pipelines inherit a default that never blocks`() = runTest {
        val calls = AtomicInteger(0)
        val custom = object : InterceptorPipeline {
            override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T {
                calls.incrementAndGet()
                return data
            }
        }
        val input = Request("r")
        assertSame(input, custom.applyInterceptorsOrNull(Request::class, input))
        assertSame(input, custom.applyInterceptorsOrNull(input))
        assertEquals(2, calls.get())
    }

    @Test
    fun `the new interface members are JVM default methods`() {
        // Third-party InterceptorPipeline / SwitchBoardObserver implementations
        // compiled against 1.0.x must keep linking.
        val outcome = InterceptorPipeline::class.java.getMethod(
            "applyInterceptorsOrNull", KClass::class.java, Any::class.java, Continuation::class.java,
        )
        assertTrue(outcome.isDefault)
        val onBlocked = SwitchBoardObserver::class.java.getMethod(
            "onBlocked", InterceptPoint::class.java, KClass::class.java, Any::class.java, TraceContext::class.java,
        )
        assertTrue(onBlocked.isDefault)
    }

    @Test
    fun `blocking interceptors keep working after unregister and re-register`() = runTest {
        val gate = registry.add(Interceptor<Request> { data, _ -> data })
        assertNull(registry.applyInterceptorsOrNull(Request("r")))
        gate.unregister()
        assertEquals(Request("r"), registry.applyInterceptorsOrNull(Request("r")))
        registry.add(Interceptor<Request> { data, _ -> delay(1); data })
        assertNull(registry.applyInterceptorsOrNull(Request("r")))
    }
}
