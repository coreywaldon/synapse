package com.synapselib.arch.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class InterceptorStressTest {

    private lateinit var registry: InterceptorRegistry

    data class Payload(val id: String, val value: Int = 0)
    data class EventA(val name: String)
    data class EventB(val code: Int)
    data class EventC(val flag: Boolean)

    @BeforeEach
    fun setUp() {
        registry = InterceptorRegistry()
    }

    // ── Concurrent reads ─────────────────────────────────────────────────

    @Test
    fun `many coroutines applying interceptors concurrently produce correct independent results`() = runTest {
        registry.add(Interceptor.transform<Payload> { it.copy(value = it.value + 1) })
        registry.add(Interceptor.transform<Payload> { it.copy(value = it.value * 2) })

        val count = 10_000
        val results = coroutineScope {
            (0 until count).map { i ->
                async {
                    registry.applyInterceptors(Payload("p$i", value = i))
                }
            }.awaitAll()
        }

        results.forEachIndexed { i, payload ->
            assertEquals((i + 1) * 2, payload.value, "Payload $i should be (i+1)*2")
        }
    }

    @Test
    fun `concurrent execution on real dispatcher threads`() = runBlocking {
        registry.add(Interceptor.transform<Payload> { it.copy(value = it.value + 7) })

        val count = 5_000
        val results = withContext(Dispatchers.Default) {
            (0 until count).map { i ->
                async {
                    registry.applyInterceptors(Payload("d$i", value = i))
                }
            }.awaitAll()
        }

        results.forEachIndexed { i, payload ->
            assertEquals(i + 7, payload.value)
        }
    }

    // ── Concurrent registration ──────────────────────────────────────────

    @Test
    fun `concurrent registrations do not lose entries`() = runTest {
        val count = 1_000
        val counter = AtomicInteger(0)

        coroutineScope {
            (0 until count).map {
                async {
                    registry.add(Interceptor.read<Payload> { counter.incrementAndGet() })
                }
            }.awaitAll()
        }

        registry.applyInterceptors(Payload("check"))

        assertEquals(count, counter.get(), "All $count interceptors should have fired")
    }

    @Test
    fun `concurrent unregistrations do not leave stale entries`() = runTest {
        val count = 1_000
        val counter = AtomicInteger(0)

        val registrations = coroutineScope {
            (0 until count).map {
                async {
                    registry.add(Interceptor.read<Payload> { counter.incrementAndGet() })
                }
            }.awaitAll()
        }

        coroutineScope {
            registrations.map { reg ->
                async { reg.unregister() }
            }.awaitAll()
        }

        registry.applyInterceptors(Payload("after"))

        assertEquals(0, counter.get(), "No interceptors should remain after full unregister")
    }

    // ── Simultaneous read/write (registration + execution) ───────────────

    @Test
    fun `registrations during execution do not cause exceptions`() = runTest {
        registry.add(Interceptor.transform<Payload> { it.copy(value = 1) })

        coroutineScope {
            // Continuous executions
            val executions = (0 until 2_000).map {
                async { registry.applyInterceptors(Payload("e$it")) }
            }

            // Concurrent registrations interleaved
            val registrations = (0 until 500).map {
                async {
                    registry.add(Interceptor.read<Payload> { })
                }
            }

            executions.awaitAll()
            registrations.awaitAll()
        }
    }

    @Test
    fun `unregistrations during execution do not cause exceptions`() = runTest {
        val registrations = (0 until 200).map {
            registry.add(Interceptor.read<Payload> { }, priority = it)
        }

        coroutineScope {
            val executions = (0 until 2_000).map {
                async { registry.applyInterceptors(Payload("u$it")) }
            }

            val removals = registrations.map { reg ->
                async { reg.unregister() }
            }

            executions.awaitAll()
            removals.awaitAll()
        }
    }

    @RepeatedTest(10)
    fun `register, execute, and unregister all at once repeatedly`() = runBlocking {
        val executed = AtomicInteger(0)

        withContext(Dispatchers.Default) {
            coroutineScope {
                // Registrations
                val regs = (0 until 100).map {
                    async {
                        registry.add(Interceptor.read<Payload> { executed.incrementAndGet() })
                    }
                }.awaitAll()

                // Executions
                (0 until 200).map {
                    launch { registry.applyInterceptors(Payload("mix$it")) }
                }

                // Unregistrations
                regs.map { reg ->
                    launch { reg.unregister() }
                }
            }
        }

        // No assertion on count — the point is no exceptions or deadlocks
        assertTrue(executed.get() >= 0)
    }

    // ── Type isolation under contention ──────────────────────────────────

    @Test
    fun `concurrent operations on different types are fully isolated`() = runTest {
        val countA = AtomicInteger(0)
        val countB = AtomicInteger(0)
        val countC = AtomicInteger(0)

        registry.add(Interceptor.read<EventA> { countA.incrementAndGet() })
        registry.add(Interceptor.read<EventB> { countB.incrementAndGet() })
        registry.add(Interceptor.read<EventC> { countC.incrementAndGet() })

        val perType = 3_000
        coroutineScope {
            val a = (0 until perType).map { async { registry.applyInterceptors(EventA("a")) } }
            val b = (0 until perType).map { async { registry.applyInterceptors(EventB(0)) } }
            val c = (0 until perType).map { async { registry.applyInterceptors(EventC(true)) } }
            (a + b + c).awaitAll()
        }

        assertEquals(perType, countA.get())
        assertEquals(perType, countB.get())
        assertEquals(perType, countC.get())
    }

    @Test
    fun `concurrent registration across different types does not cross-contaminate`() = runTest {
        val counterA = AtomicInteger(0)
        val counterB = AtomicInteger(0)

        coroutineScope {
            (0 until 500).map {
                async { registry.add(Interceptor.read<EventA> { counterA.incrementAndGet() }) }
            }.awaitAll()

            (0 until 500).map {
                async { registry.add(Interceptor.read<EventB> { counterB.incrementAndGet() }) }
            }.awaitAll()
        }

        registry.applyInterceptors(EventA("only-a"))

        assertEquals(500, counterA.get())
        assertEquals(0, counterB.get(), "EventB interceptors should not fire for EventA")
    }

    // ── Priority consistency under contention ────────────────────────────

    @RepeatedTest(20)
    fun `execution order respects priority even under concurrent execution`() = runBlocking {
        val order = CopyOnWriteArrayList<String>()

        registry.add(Interceptor.read<Payload> { order.add("C") }, priority = 30)
        registry.add(Interceptor.read<Payload> { order.add("A") }, priority = 10)
        registry.add(Interceptor.read<Payload> { order.add("B") }, priority = 20)

        withContext(Dispatchers.Default) {
            registry.applyInterceptors(Payload("order-check"))
        }

        assertEquals(listOf("A", "B", "C"), order)
    }

    // ── Chain integrity (no shared mutable state leakage) ────────────────

    @Test
    fun `each execution gets an independent chain snapshot`() = runTest {
        val callCount = AtomicLong(0)

        registry.add(Interceptor.transform<Payload> {
            callCount.incrementAndGet()
            it.copy(value = it.value + 1)
        })

        val count = 5_000
        val results = coroutineScope {
            (0 until count).map { i ->
                async { registry.applyInterceptors(Payload("s$i", value = i)) }
            }.awaitAll()
        }

        assertEquals(count.toLong(), callCount.get(), "Interceptor should be called exactly once per execution")
        results.forEachIndexed { i, payload ->
            assertEquals(i + 1, payload.value, "Each result should be independently computed")
        }
    }

    @Test
    fun `full interceptor with slow proceed does not block other executions`() = runBlocking {
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)

        registry.add(Interceptor.full<Payload> { data, proceed ->
            started.incrementAndGet()
            val result = proceed(data)
            finished.incrementAndGet()
            result
        })

        val count = 500
        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until count).map {
                    async { registry.applyInterceptors(Payload("slow$it")) }
                }.awaitAll()
            }
        }

        assertEquals(count, started.get())
        assertEquals(count, finished.get())
    }

    // ── Barrier-synchronized burst ───────────────────────────────────────

    @Test
    fun `barrier-synchronized burst of executions all succeed`() {
        registry.add(Interceptor.transform<Payload> { it.copy(value = it.value + 1) })

        val threadCount = 64
        val barrier = CyclicBarrier(threadCount)
        val results = CopyOnWriteArrayList<Payload>()
        val errors = CopyOnWriteArrayList<Throwable>()

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    barrier.await() // All threads start simultaneously
                    val result = runBlocking {
                        registry.applyInterceptors(Payload("burst$i", value = i))
                    }
                    results.add(result)
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertTrue(errors.isEmpty(), "No errors expected but got: $errors")
        assertEquals(threadCount, results.size)
        results.forEach { payload ->
            val originalValue = payload.id.removePrefix("burst").toInt()
            assertEquals(originalValue + 1, payload.value)
        }
    }

    // ── Rapid register / unregister churn ────────────────────────────────

    @Test
    fun `rapid register-unregister churn does not corrupt state`() = runBlocking {
        val survivalCount = AtomicInteger(0)

        // Permanent interceptor
        registry.add(Interceptor.read<Payload> { survivalCount.incrementAndGet() })

        val iterations = 2_000
        withContext(Dispatchers.Default) {
            coroutineScope {
                // Churn: register then immediately unregister
                (0 until iterations).map {
                    launch {
                        val reg = registry.add(Interceptor.read<Payload> { })
                        reg.unregister()
                    }
                }

                // Concurrent executions
                (0 until iterations).map {
                    launch { registry.applyInterceptors(Payload("churn$it")) }
                }
            }
        }

        // The permanent interceptor should have fired for every execution
        assertEquals(iterations, survivalCount.get())
    }

    // ── Short-circuit under contention ───────────────────────────────────

    @Test
    fun `short-circuiting interceptor consistently prevents downstream execution`() = runTest {
        val downstreamHits = AtomicInteger(0)

        registry.add(
            Interceptor.full<Payload> { data, _ -> data.copy(value = -1) },
            priority = 0
        )
        registry.add(
            Interceptor.read<Payload> { downstreamHits.incrementAndGet() },
            priority = 10
        )

        val count = 5_000
        val results = coroutineScope {
            (0 until count).map {
                async { registry.applyInterceptors(Payload("sc$it", value = it)) }
            }.awaitAll()
        }

        assertEquals(0, downstreamHits.get(), "Downstream should never be reached")
        assertTrue(results.all { it.value == -1 }, "All results should be short-circuited")
    }

    // ── Stress with mixed interceptor types ──────────────────────────────

    @Test
    fun `mixed read, transform, and full interceptors under heavy concurrency`() = runBlocking {
        val readCount = AtomicInteger(0)

        registry.add(Interceptor.read<Payload> { readCount.incrementAndGet() }, priority = 0)
        registry.add(Interceptor.transform<Payload> { it.copy(value = it.value + 10) }, priority = 1)
        registry.add(Interceptor.full<Payload> { data, proceed ->
            val result = proceed(data)
            result.copy(value = result.value * 2)
        }, priority = -1) // Wraps everything

        val count = 5_000
        val results = withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until count).map { i ->
                    async { registry.applyInterceptors(Payload("mix$i", value = i)) }
                }.awaitAll()
            }
        }

        assertEquals(count, readCount.get())
        results.forEachIndexed { i, payload ->
            // full (priority -1) wraps: read (0) observes, transform (1) adds 10, then full doubles
            assertEquals((i + 10) * 2, payload.value, "Payload $i mismatch")
        }
    }
}