package com.synapselib.core.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class FreshnessRegistryTest {

    private data class Key(val name: String, override val duration: Duration) : FreshnessKey

    @Test
    fun `same signature returns the same token within the window`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val key = Key("user:42", 5.minutes)

        val first = registry.get(key)
        time += 4.minutes
        val second = registry.get(key)

        assertEquals(first, second)
    }

    @Test
    fun `new token is issued once the bucket expires`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val key = Key("user:42", 5.minutes)

        val first = registry.get(key)
        time += 6.minutes
        val second = registry.get(key)

        assertNotEquals(first, second)
    }

    @Test
    fun `different signatures receive independent buckets`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val a = Key("a", 5.minutes)
        val b = Key("b", 5.minutes)

        val tokenA = registry.get(a)
        time += 1.minutes
        val tokenB = registry.get(b)

        assertNotEquals(tokenA, tokenB)
        assertEquals(tokenA, registry.get(a))
        assertEquals(tokenB, registry.get(b))
    }

    @Test
    fun `refreshing the same signature yields strictly increasing unique tokens`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val key = Key("s", 1.minutes)

        val tokens = buildList {
            repeat(500) {
                add(registry.get(key))
                time += 2.minutes
            }
        }

        assertEquals(tokens.size, tokens.toSet().size, "tokens must be unique")
        assertTrue(tokens.zipWithNext().all { (a, b) -> a.value < b.value }, "tokens must strictly increase")
    }

    @Test
    fun `expired non-head signature refreshes without disturbing live neighbors`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val long = Key("long", 10.minutes)
        val short = Key("short", 1.minutes)
        val tail = Key("tail", 10.minutes)

        val longToken = registry.get(long)
        val shortToken = registry.get(short)
        val tailToken = registry.get(tail)

        time += 2.minutes

        val refreshed = registry.get(short)
        assertNotEquals(shortToken, refreshed)
        assertEquals(longToken, registry.get(long))
        assertEquals(tailToken, registry.get(tail))
    }

    @Test
    fun `every distinct signature receives a distinct token`() {
        val registry = FreshnessRegistry<Key>(TestTimeSource())
        val tokens = (1..1_000).map { registry.get(Key("sig-$it", 5.minutes)) }
        assertEquals(1_000, tokens.toSet().size)
    }

    @Test
    fun `short-lived bucket inserted after long-lived one is evicted when it expires`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)
        val long = Key("long", 10.minutes)
        val short = Key("short", 30.seconds)

        registry.get(long)
        registry.get(short)   // inserted second, but expires first

        time += 1.minutes     // "short" expired; "long" still live

        // Access some unrelated key — heap-based eviction must reclaim "short"
        // even though it sits behind the still-live "long" in insertion order.
        registry.get(Key("probe", 5.minutes))

        assertEquals(2, internalSize(registry))  // long + probe; short gone
    }

    @Test
    fun `get evicts expired entries opportunistically`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time)

        repeat(100) { registry.get(Key("dead-$it", 1.minutes)) }
        time += 2.minutes

        registry.get(Key("alive", 1.minutes))

        assertEquals(1, internalSize(registry))
    }

    @Test
    fun `maxSize evicts the earliest-expiring bucket on overflow`() {
        val time = TestTimeSource()
        val registry = FreshnessRegistry<Key>(time, maxSize = 2)
        val longLived = Key("long", 10.minutes)
        val mediumLived = Key("medium", 5.minutes)
        val newcomer = Key("new", 20.minutes)

        val longToken = registry.get(longLived)
        val mediumToken = registry.get(mediumLived)
        registry.get(newcomer)  // overflow: mediumLived has the earliest expiry, evict it

        assertEquals(2, internalSize(registry))
        assertEquals(longToken, registry.get(longLived), "long-lived must survive")
        assertNotEquals(mediumToken, registry.get(mediumLived), "medium-lived should have been evicted")
    }

    @Test
    fun `maxSize must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { FreshnessRegistry<Key>(maxSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { FreshnessRegistry<Key>(maxSize = -1) }
    }

    @Test
    fun `concurrent callers on the same signature observe a single token`() {
        val registry = FreshnessRegistry<Key>()
        val key = Key("shared", 5.minutes)
        val threads = 16
        val perThread = 1_000
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val tokens = (1..threads).map {
                pool.submit<Set<FreshnessToken>> {
                    val seen = HashSet<FreshnessToken>()
                    repeat(perThread) { seen.add(registry.get(key)) }
                    seen
                }
            }.flatMap { it.get(5, TimeUnit.SECONDS) }.toSet()

            assertEquals(1, tokens.size, "expected one token under contention, got $tokens")
            assertTrue(tokens.single().value > 0L)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun internalSize(registry: FreshnessRegistry<*>): Int {
        val field = FreshnessRegistry::class.java.getDeclaredField("buckets").apply { isAccessible = true }
        return (field.get(registry) as Map<*, *>).size
    }
}
