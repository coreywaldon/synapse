package com.synapselib.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FreshnessRegistryStressTest {

    private data class Key(val name: String, override val duration: Duration) : FreshnessKey

    @Test
    fun `high contention on disjoint signatures keeps each token isolated`() {
        val registry = FreshnessRegistry<Key>()
        val threads = 32
        val perThread = 10_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { id ->
                val key = Key("sig-$id", 5.minutes)
                pool.submit<FreshnessToken> {
                    start.await()
                    val first = registry.get(key)
                    repeat(perThread) {
                        val seen = registry.get(key)
                        check(seen == first) { "thread $id: expected $first, saw $seen" }
                    }
                    first
                }
            }
            start.countDown()
            val tokens = futures.map { it.get(30, TimeUnit.SECONDS) }.toSet()
            assertEquals(threads, tokens.size, "each signature must hold a unique token")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `memory stays bounded as thousands of signatures churn through expiry`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)

        repeat(50_000) { i ->
            registry.get(Key("sig-$i", 1.minutes))
            if (i % 20 == 19) time += 10.seconds
        }

        assertTrue(internalSize(registry) < 200, "size ${internalSize(registry)} not bounded")
    }

    @Test
    fun `mixed durations never leave stale entries accumulated`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)

        // Alternate long-lived and short-lived inserts; the shorts expire first but
        // sit behind longs in insertion order. Heap eviction must reclaim them.
        repeat(1_000) { i ->
            registry.get(Key("long-$i", 1.hours()))
            registry.get(Key("short-$i", 30.seconds))
        }

        time += 2.minutes  // every "short-*" now expired
        registry.get(Key("trigger", 1.hours()))

        // After triggering eviction, only the longs + trigger should remain (~1001).
        assertTrue(internalSize(registry) <= 1_002, "size ${internalSize(registry)} not bounded")
        // And none of the shorts should be there.
        assertTrue(internalKeys(registry).none { (it as Key).name.startsWith("short-") })
    }

    @Test
    fun `maxSize holds the cap under concurrent insertion pressure`() {
        val registry = FreshnessRegistry<Key>(maxSize = 64)
        val threads = 16
        val perThread = 10_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { id ->
                pool.submit {
                    start.await()
                    repeat(perThread) { i ->
                        registry.get(Key("$id-$i", 5.minutes))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            assertTrue(internalSize(registry) <= 64, "size ${internalSize(registry)} exceeded cap")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `per-thread view of a refreshing signature never reverts to an earlier token`() {
        val registry = FreshnessRegistry<Key>()
        val key = Key("hot", 50.milliseconds)
        val threads = 16
        val runtimeNanos = 500.milliseconds.inWholeNanoseconds
        val pool = Executors.newFixedThreadPool(threads)
        val observations = List(threads) { ArrayList<FreshnessToken>(100_000) }
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { id ->
                pool.submit {
                    start.await()
                    val deadline = System.nanoTime() + runtimeNanos
                    val seen = observations[id]
                    while (System.nanoTime() < deadline && seen.size < 100_000) {
                        seen.add(registry.get(key))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            observations.forEachIndexed { id, seen ->
                val retired = HashSet<FreshnessToken>()
                var current = seen.firstOrNull() ?: return@forEachIndexed
                for (token in seen) {
                    if (token != current) {
                        retired.add(current)
                        check(token !in retired) {
                            "thread $id: token $token resurfaced after being retired"
                        }
                        current = token
                    }
                }
            }

            val totalGenerations = observations.flatten().toSet().size
            assertTrue(totalGenerations >= 2, "expected multiple generations across 500ms of 50ms windows, got $totalGenerations")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `mixed workload of churning and long-lived signatures stays correct`() {
        val registry = FreshnessRegistry<Key>()
        val longLived = Key("long-lived", 5.minutes)
        val threads = 8
        val perThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { id ->
                pool.submit<FreshnessToken> {
                    start.await()
                    if (id % 2 == 0) {
                        val first = registry.get(longLived)
                        repeat(perThread) {
                            check(registry.get(longLived) == first)
                        }
                        first
                    } else {
                        repeat(perThread) { i ->
                            registry.get(Key("short-$id-$i", 1.milliseconds))
                        }
                        FreshnessToken(-1L)
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }

            val livingTokens = (0 until threads step 2).map { _ ->
                pool.submit<FreshnessToken> { registry.get(longLived) }.get(5, TimeUnit.SECONDS)
            }
            assertEquals(1, livingTokens.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `zero duration issues a fresh token every call and never grows the map`() {
        val registry = FreshnessRegistry<Key>()
        val key = Key("churn", Duration.ZERO)
        val tokens = HashSet<FreshnessToken>()
        repeat(10_000) { tokens.add(registry.get(key)) }

        assertEquals(10_000, tokens.size, "ZERO duration must never reuse a token")
        assertEquals(1, internalSize(registry), "only the most recent bucket should remain")
    }

    @Test
    fun `infinite duration never rolls regardless of simulated time or contention`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val key = Key("forever", Duration.INFINITE)
        val first = registry.get(key)

        time += 10_000.days
        assertEquals(first, registry.get(key))

        val threads = 16
        val perThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threads).map {
                pool.submit<Set<FreshnessToken>> {
                    start.await()
                    val seen = HashSet<FreshnessToken>()
                    repeat(perThread) { seen.add(registry.get(key)) }
                    seen
                }
            }
            start.countDown()
            val all = futures.flatMap { it.get(10, TimeUnit.SECONDS) }.toSet()
            assertEquals(setOf(first), all)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `negative duration degrades cleanly to per-call bucketing`() {
        val registry = FreshnessRegistry<Key>()
        val key = Key("neg", (-5).minutes)
        val a = registry.get(key)
        val b = registry.get(key)
        assertNotEquals(a, b, "a past-expiry mark must be treated as expired")
        assertEquals(1, internalSize(registry))
    }

    @Test
    fun `clear racing with a hot get storm never corrupts state`() {
        val registry = FreshnessRegistry<Key>()
        val keys = (0 until 4).map { Key("sig-$it", 5.minutes) }
        val postKey = Key("post", 5.minutes)
        val threads = 16
        val runtimeMs = 500L
        val pool = Executors.newFixedThreadPool(threads + 1)
        val start = CountDownLatch(1)
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()

        try {
            val workers = (0 until threads).map { id ->
                pool.submit {
                    try {
                        start.await()
                        val key = keys[id % keys.size]
                        while (!stop.get()) {
                            val token = registry.get(key)
                            val again = registry.get(key)
                            check(again.value >= token.value) {
                                "token regressed: ${again.value} < ${token.value}"
                            }
                        }
                    } catch (t: Throwable) {
                        errors.add(t)
                    }
                }
            }
            val clearer = pool.submit {
                try {
                    start.await()
                    while (!stop.get()) {
                        registry.clear()
                        Thread.sleep(1)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }

            start.countDown()
            Thread.sleep(runtimeMs)
            stop.set(true)
            workers.forEach { it.get(5, TimeUnit.SECONDS) }
            clearer.get(5, TimeUnit.SECONDS)

            assertTrue(errors.isEmpty(), "unexpected failures: $errors")
            assertEquals(registry.get(postKey), registry.get(postKey))
        } finally {
            stop.set(true)
            pool.shutdownNow()
        }
    }

    @Test
    fun `interleaved infinite and near-zero durations keep each signature honest`() {
        val registry = FreshnessRegistry<Key>()
        val threads = 8
        val perThread = 10_000
        val pool = Executors.newFixedThreadPool(threads * 2)
        val start = CountDownLatch(1)

        try {
            val forevers = (0 until threads).map { id ->
                val key = Key("permanent-$id", Duration.INFINITE)
                pool.submit<FreshnessToken> {
                    start.await()
                    val first = registry.get(key)
                    repeat(perThread) {
                        check(registry.get(key) == first)
                    }
                    first
                }
            }
            val churners = (0 until threads).map { id ->
                val key = Key("ephemeral-$id", Duration.ZERO)
                pool.submit<Set<FreshnessToken>> {
                    start.await()
                    val seen = HashSet<FreshnessToken>()
                    repeat(perThread) { seen.add(registry.get(key)) }
                    seen
                }
            }

            start.countDown()
            val permanentTokens = forevers.map { it.get(30, TimeUnit.SECONDS) }.toSet()
            val ephemeralTokens = churners.flatMap { it.get(30, TimeUnit.SECONDS) }.toSet()

            assertEquals(threads, permanentTokens.size, "each permanent signature must hold its own token")
            assertEquals(threads * perThread, ephemeralTokens.size, "ZERO duration must issue a fresh token every call")
            assertTrue(permanentTokens.intersect(ephemeralTokens).isEmpty())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun Int.hours(): Duration = (this * 60).minutes

    private fun internalSize(registry: FreshnessRegistry<*>): Int {
        val field = FreshnessRegistry::class.java.getDeclaredField("buckets").apply { isAccessible = true }
        return (field.get(registry) as Map<*, *>).size
    }

    private fun internalKeys(registry: FreshnessRegistry<*>): Set<*> {
        val field = FreshnessRegistry::class.java.getDeclaredField("buckets").apply { isAccessible = true }
        return (field.get(registry) as Map<*, *>).keys
    }
}
