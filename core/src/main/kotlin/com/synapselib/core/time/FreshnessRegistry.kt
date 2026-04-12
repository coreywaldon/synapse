package com.synapselib.core.time

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.jvm.JvmInline
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A cache key whose freshness window is part of its identity. Implementors bind a
 * logical cache entry (user id, endpoint, etc.) to the duration it stays fresh,
 * so every call site that uses the same key also uses the same duration.
 */
interface FreshnessKey {
    val duration: Duration
}

/** An opaque freshness token suitable for composing into downstream cache keys. */
@JvmInline
value class FreshnessToken(val value: Long)

/**
 * A thread-safe registry of freshness tokens that stay stable for a bounded duration.
 *
 * Each unique [FreshnessKey] is associated with a token. Subsequent calls with the
 * same key return the same token until the bucket's duration elapses, after which
 * the next call opens a new bucket with a new token.
 *
 * A min-heap ordered by expiry keeps the *actually* next-to-expire bucket at hand,
 * so mixed durations don't cause stale entries to linger behind longer-lived
 * neighbours. If [maxSize] is set, inserting a new key when the map is full evicts
 * the entry with the nearest expiry.
 */
class FreshnessRegistry<K : FreshnessKey>(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val maxSize: Int = Int.MAX_VALUE,
) {

    init {
        require(maxSize > 0) { "maxSize must be positive, was $maxSize" }
    }

    private class Bucket(val token: Long, val expiresAt: ComparableTimeMark)
    private class HeapEntry<K>(val signature: K, val token: Long, val expiresAt: ComparableTimeMark)

    private val lock = SynchronizedObject()
    private val buckets = HashMap<K, Bucket>()
    private val heap = MinHeap<HeapEntry<K>> { a, b -> a.expiresAt.compareTo(b.expiresAt) }
    private var nextToken = 0L

    /**
     * Returns the freshness token for [signature], opening a new bucket of length
     * [FreshnessKey.duration] if none exists or the existing one has expired.
     */
    fun get(signature: K): FreshnessToken = synchronized(lock) {
        evictExpired()
        val existing = buckets[signature]
        if (existing != null && !existing.expiresAt.hasPassedNow()) {
            return@synchronized FreshnessToken(existing.token)
        }
        if (existing == null && buckets.size >= maxSize) evictEarliest()
        val token = ++nextToken
        val expiresAt = timeSource.markNow() + signature.duration
        buckets[signature] = Bucket(token, expiresAt)
        heap.push(HeapEntry(signature, token, expiresAt))
        FreshnessToken(token)
    }

    /** Drops every bucket, regardless of expiry. */
    fun clear() = synchronized(lock) {
        buckets.clear()
        heap.clear()
    }

    private fun evictExpired() {
        while (true) {
            val top = heap.peek() ?: return
            val current = buckets[top.signature]
            when {
                current == null || current.token != top.token -> heap.pop()  // stale pointer
                top.expiresAt.hasPassedNow() -> {
                    heap.pop()
                    buckets.remove(top.signature)
                }
                else -> return  // head is live and not expired; nothing earlier remains
            }
        }
    }

    private fun evictEarliest() {
        while (true) {
            val top = heap.pop() ?: return
            val current = buckets[top.signature]
            if (current != null && current.token == top.token) {
                buckets.remove(top.signature)
                return
            }
        }
    }
}

private class MinHeap<T>(private val compare: (T, T) -> Int) {
    private val items = ArrayList<T>()

    fun push(item: T) {
        items.add(item)
        siftUp(items.size - 1)
    }

    fun peek(): T? = items.firstOrNull()

    fun pop(): T? {
        if (items.isEmpty()) return null
        val top = items[0]
        val last = items.removeAt(items.size - 1)
        if (items.isNotEmpty()) {
            items[0] = last
            siftDown(0)
        }
        return top
    }

    fun clear() = items.clear()

    private fun siftUp(from: Int) {
        var i = from
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (compare(items[i], items[parent]) < 0) {
                swap(i, parent)
                i = parent
            } else return
        }
    }

    private fun siftDown(from: Int) {
        var i = from
        val n = items.size
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var smallest = i
            if (left < n && compare(items[left], items[smallest]) < 0) smallest = left
            if (right < n && compare(items[right], items[smallest]) < 0) smallest = right
            if (smallest == i) return
            swap(i, smallest)
            i = smallest
        }
    }

    private fun swap(a: Int, b: Int) {
        val tmp = items[a]
        items[a] = items[b]
        items[b] = tmp
    }
}
