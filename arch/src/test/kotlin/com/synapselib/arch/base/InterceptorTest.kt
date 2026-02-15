package com.synapselib.arch.base

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class InterceptorTest {

    private lateinit var registry: InterceptorRegistry

    data class Request(val id: String, val value: Int = 0, val retryCount: Int = 0)
    data class Response(val body: String)

    @BeforeEach
    fun setUp() {
        registry = InterceptorRegistry()
    }

    @Test
    fun `read interceptor observes data without modifying it`() = runTest {
        val observed = mutableListOf<Request>()
        registry.add(Interceptor.read<Request> { observed.add(it) })

        val input = Request("r1", value = 42)
        val result = registry.applyInterceptors(input)

        assertEquals(input, result)
        assertEquals(listOf(input), observed)
    }

    @Test
    fun `multiple read interceptors all observe the same data`() = runTest {
        val log1 = mutableListOf<String>()
        val log2 = mutableListOf<String>()

        registry.add(Interceptor.read<Request> { log1.add(it.id) })
        registry.add(Interceptor.read<Request> { log2.add(it.id) })

        registry.applyInterceptors(Request("x"))

        assertEquals(listOf("x"), log1)
        assertEquals(listOf("x"), log2)
    }

    @Test
    fun `transform interceptor modifies data before proceeding`() = runTest {
        registry.add(Interceptor.transform<Request> { it.copy(value = it.value + 10) })

        val result = registry.applyInterceptors(Request("t1", value = 5))

        assertEquals(15, result.value)
    }

    @Test
    fun `chained transforms compose correctly`() = runTest {
        registry.add(Interceptor.transform<Request> { it.copy(value = it.value * 2) })
        registry.add(Interceptor.transform<Request> { it.copy(value = it.value + 3) })

        // Same priority (0), FIFO: first *2, then +3
        val result = registry.applyInterceptors(Request("t2", value = 5))

        assertEquals((5 * 2) + 3, result.value)
    }

    @Test
    fun `full interceptor can modify data before and after proceeding`() = runTest {
        registry.add(Interceptor.full<Request> { data, proceed ->
            val modified = proceed(data.copy(value = data.value + 1))
            modified.copy(value = modified.value * 10)
        })

        val result = registry.applyInterceptors(Request("f1", value = 2))

        assertEquals(30, result.value)
    }

    @Test
    fun `full interceptor can short-circuit without calling proceed`() = runTest {
        val downstreamCalled = AtomicInteger(0)

        registry.add(
            Interceptor.full<Request> { data, _ -> data.copy(id = "short-circuited") },
            priority = 0
        )
        registry.add(
            Interceptor.read<Request> { downstreamCalled.incrementAndGet() },
            priority = 10
        )

        val result = registry.applyInterceptors(Request("original"))

        assertEquals("short-circuited", result.id)
        assertEquals(0, downstreamCalled.get(), "Downstream interceptor should not have been called")
    }

    @Test
    fun `full interceptor can retry by calling proceed multiple times`() = runTest {
        val attempts = AtomicInteger(0)

        registry.add(
            Interceptor.full<Request> { data, proceed ->
                val first = proceed(data)
                if (first.retryCount == 0) proceed(data.copy(retryCount = 1)) else first
            },
            priority = 0
        )
        registry.add(
            Interceptor.read<Request> { attempts.incrementAndGet() },
            priority = 10
        )

        val result = registry.applyInterceptors(Request("retry", retryCount = 0))

        assertEquals(1, result.retryCount)
        assertEquals(2, attempts.get(), "Downstream should be invoked twice due to retry")
    }

    @Test
    fun `interceptor can be created via SAM lambda`() = runTest {
        val interceptor = Interceptor<Request> { data, chain ->
            chain.proceed(data.copy(value = 99))
        }
        registry.add(interceptor)

        val result = registry.applyInterceptors(Request("sam", value = 0))
        assertEquals(99, result.value)
    }

    @Test
    fun `lower priority values execute first`() = runTest {
        val order = CopyOnWriteArrayList<String>()

        registry.add(Interceptor.read<Request> { order.add("p10") }, priority = 10)
        registry.add(Interceptor.read<Request> { order.add("p0") }, priority = 0)
        registry.add(Interceptor.read<Request> { order.add("p5") }, priority = 5)

        registry.applyInterceptors(Request("ord"))

        assertEquals(listOf("p0", "p5", "p10"), order)
    }

    @Test
    fun `same priority preserves FIFO insertion order`() = runTest {
        val order = CopyOnWriteArrayList<String>()

        registry.add(Interceptor.read<Request> { order.add("first") }, priority = 0)
        registry.add(Interceptor.read<Request> { order.add("second") }, priority = 0)
        registry.add(Interceptor.read<Request> { order.add("third") }, priority = 0)

        registry.applyInterceptors(Request("fifo"))

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `negative priorities execute before zero`() = runTest {
        val order = CopyOnWriteArrayList<String>()

        registry.add(Interceptor.read<Request> { order.add("zero") }, priority = 0)
        registry.add(Interceptor.read<Request> { order.add("neg") }, priority = -5)

        registry.applyInterceptors(Request("neg"))

        assertEquals(listOf("neg", "zero"), order)
    }

    @Test
    fun `unregister removes interceptor from future executions`() = runTest {
        val counter = AtomicInteger(0)
        val reg = registry.add(Interceptor.read<Request> { counter.incrementAndGet() })

        registry.applyInterceptors(Request("a"))
        assertEquals(1, counter.get())

        reg.unregister()

        registry.applyInterceptors(Request("b"))
        assertEquals(1, counter.get(), "Interceptor should no longer be invoked after unregister")
    }

    @Test
    fun `unregister is idempotent`() {
        val reg = registry.add(Interceptor.read<Request> { })

        reg.unregister()
        reg.unregister()
        reg.unregister()
    }

    @Test
    fun `unregistering one interceptor does not affect others`() = runTest {
        val order = CopyOnWriteArrayList<String>()

        val regA = registry.add(Interceptor.read<Request> { order.add("A") })
        registry.add(Interceptor.read<Request> { order.add("B") })

        regA.unregister()
        registry.applyInterceptors(Request("sel"))

        assertEquals(listOf("B"), order)
    }

    @Test
    fun `interceptors for different types do not interfere`() = runTest {
        val requestSeen = AtomicInteger(0)
        val responseSeen = AtomicInteger(0)

        registry.add(Interceptor.read<Request> { requestSeen.incrementAndGet() })
        registry.add(Interceptor.read<Response> { responseSeen.incrementAndGet() })

        registry.applyInterceptors(Request("iso"))

        assertEquals(1, requestSeen.get())
        assertEquals(0, responseSeen.get())
    }

    @Test
    fun `applying interceptors on unregistered type returns data unchanged`() = runTest {
        val result = registry.applyInterceptors(Response("hello"))
        assertEquals(Response("hello"), result)
    }

    @Test
    fun `identity chain returns data as-is when no interceptors registered`() = runTest {
        val input = Request("id", value = 123)
        val result = registry.applyInterceptors(input)
        assertEquals(input, result)
    }

    @Test
    fun `interceptors execute in correct nested before-after order`() = runTest {
        val log = CopyOnWriteArrayList<String>()

        registry.add(Interceptor<Request> { data, chain ->
            log.add("A-before")
            val result = chain.proceed(data)
            log.add("A-after")
            result
        }, priority = 0)

        registry.add(Interceptor<Request> { data, chain ->
            log.add("B-before")
            val result = chain.proceed(data)
            log.add("B-after")
            result
        }, priority = 1)

        registry.applyInterceptors(Request("nested"))

        assertEquals(listOf("A-before", "B-before", "B-after", "A-after"), log)
    }

    @Test
    fun `transform results propagate back up the chain`() = runTest {
        val captured = mutableListOf<Int>()

        registry.add(Interceptor<Request> { data, chain ->
            val result = chain.proceed(data)
            captured.add(result.value)
            result
        }, priority = 0)

        registry.add(
            Interceptor.transform<Request> { it.copy(value = 999) },
            priority = 1
        )

        val result = registry.applyInterceptors(Request("prop", value = 0))

        assertEquals(999, result.value)
        assertEquals(listOf(999), captured, "Upstream should see the transformed value")
    }

    @Test
    fun `reified add and applyInterceptors work correctly`() = runTest {
        registry.add(Interceptor.transform<Request> { it.copy(value = 7) })
        val result = registry.applyInterceptors(Request("reified"))
        assertEquals(7, result.value)
    }

    @Test
    fun `applyInterceptors via explicit KClass overload works`() = runTest {
        registry.add(Request::class, Interceptor.transform { it.copy(value = 42) })
        val result = registry.applyInterceptors(Request::class, Request("kc", value = 0))
        assertEquals(42, result.value)
    }

    @Test
    fun `single interceptor works correctly`() = runTest {
        registry.add(Interceptor.transform<Request> { it.copy(value = 1) })
        val result = registry.applyInterceptors(Request("single", value = 0))
        assertEquals(1, result.value)
    }

    @Test
    fun `many interceptors execute in order`() = runTest {
        val count = 100
        repeat(count) { i ->
            registry.add(
                Interceptor.transform<Request> { it.copy(value = it.value + 1) },
                priority = i
            )
        }

        val result = registry.applyInterceptors(Request("many", value = 0))
        assertEquals(count, result.value)
    }

    @Test
    fun `interceptor that throws propagates exception`() = runTest {
        registry.add(Interceptor.read<Request> { throw IllegalStateException("boom") })

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest {
                registry.applyInterceptors(Request("err"))
            }
        }
    }

    @Test
    fun `exception in downstream does not corrupt registry state`() = runTest {
        val counter = AtomicInteger(0)

        registry.add(Interceptor.read<Request> { counter.incrementAndGet() }, priority = 0)
        registry.add(
            Interceptor.read<Request> {
                if (it.id == "fail") throw RuntimeException("fail")
            },
            priority = 1
        )

        runCatching { registry.applyInterceptors(Request("fail")) }

        counter.set(0)
        val result = registry.applyInterceptors(Request("ok"))
        assertEquals("ok", result.id)
        assertEquals(1, counter.get())
    }

    @Test
    fun `concurrent applyInterceptors calls are safe`() = runTest {
        registry.add(Interceptor.transform<Request> { it.copy(value = it.value + 1) })

        val results = coroutineScope {
            (1..200).map { i ->
                async {
                    registry.applyInterceptors(Request("c$i", value = i))
                }
            }.awaitAll()
        }

        results.forEachIndexed { index, req ->
            assertEquals(index + 1 + 1, req.value, "Each call should independently add 1")
        }
    }

    @Test
    fun `concurrent registration and execution do not crash`() = runTest {
        registry.add(Interceptor.read<Request> { })

        coroutineScope {
            val registrations = (1..50).map {
                async { registry.add(Interceptor.read<Request> { }) }
            }.awaitAll()

            (1..50).map {
                async { registry.applyInterceptors(Request("conc")) }
            }.awaitAll()

            registrations.map {
                async { it.unregister() }
            }.awaitAll()
        }
    }

    @Test
    fun `registry is assignable to InterceptorPipeline`() {
        val pipeline: InterceptorPipeline = registry
        assertNotNull(pipeline)
    }
}