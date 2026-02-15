package com.synapselib.arch.base

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.java
import kotlin.reflect.KClass

/**
 * A composable interceptor that can observe, transform, or short-circuit data
 * flowing through a [Chain].
 *
 * Interceptors form a pipeline (similar to OkHttp interceptors or middleware stacks):
 * each interceptor receives the current data and a [Chain] handle that delegates
 * to the next interceptor in line. An interceptor may:
 *
 * - **Pass through**: call `chain.proceed(data)` unchanged (observation / logging).
 * - **Transform**: modify data before or after calling `chain.proceed`.
 * - **Short-circuit**: return a value *without* calling `chain.proceed`, skipping
 *   all downstream interceptors.
 *
 * This is a **functional (SAM) interface**, so it can be implemented with a lambda:
 * ```kotlin
 * val logger = Interceptor<Request> { data, chain ->
 *     println("Before: $data")
 *     val result = chain.proceed(data)
 *     println("After: $result")
 *     result
 * }
 * ```
 *
 * For common patterns, prefer the factory methods in the [Companion]:
 * [read], [transform], and [full].
 *
 * @param T the type of data flowing through the interceptor chain.
 */
fun interface Interceptor<T> {

    /**
     * Intercepts the given [data] and optionally delegates to the next interceptor
     * via [chain].
     *
     * @param data  the current value flowing through the pipeline.
     * @param chain handle to invoke the next interceptor; call [Chain.proceed] to
     *              continue the chain, or omit the call to short-circuit.
     * @return the (possibly transformed) result to be returned to the caller or
     *         the upstream interceptor.
     */
    suspend fun intercept(data: T, chain: Chain<T>): T

    /**
     * Represents the downstream portion of an interceptor pipeline.
     *
     * Calling [proceed] hands data to the next interceptor in the chain. If
     * there are no more interceptors, the terminal handler simply returns data
     * as-is (identity).
     *
     * @param T the type of data flowing through the chain.
     */
    fun interface Chain<T> {

        /**
         * Forwards [data] to the next interceptor in the pipeline and returns the
         * result produced by the remainder of the chain.
         *
         * @param data the value to pass downstream.
         * @return the result after all downstream interceptors have executed.
         */
        suspend fun proceed(data: T): T
    }

    companion object {
        /**
         * Creates a **read-only** (side effect) interceptor.
         *
         * The [block] receives the current data for inspection (e.g. logging,
         * metrics, analytics) but cannot modify it. The interceptor always
         * proceeds to the next link in the chain with the original data.
         *
         * ```kotlin
         * registry.add(Interceptor.read<Request> { request ->
         *     logger.info("Processing ${request.id}")
         * })
         * ```
         *
         * @param block a suspending function invoked with the current data.
         * @return an [Interceptor] that observes data without modifying it.
         */
        fun <T> read(block: suspend (T) -> Unit): Interceptor<T> = Interceptor { data, chain ->
            block(data)
            chain.proceed(data)
        }

        /**
         * Creates a **transforming** interceptor.
         *
         * The [block] receives the current data and returns a new (or the same)
         * value that will be forwarded to the next interceptor via `chain.proceed`.
         *
         * ```kotlin
         * registry.add(Interceptor.transform<Request> { request ->
         *     request.copy(timestamp = Clock.System.now())
         * })
         * ```
         *
         * @param block a suspending function that maps input data to output data.
         * @return an [Interceptor] that transforms data before it continues
         *         down the chain.
         */
        fun <T> transform(block: suspend (T) -> T): Interceptor<T> = Interceptor { data, chain ->
            chain.proceed(block(data))
        }

        /**
         * Creates an interceptor with **full control** over the chain.
         *
         * The [block] receives both the current data and a `proceed` function.
         * This allows the interceptor to:
         * - Modify data before *and* after proceeding.
         * - Call `proceed` multiple times (retry).
         * - Skip `proceed` entirely (short-circuit).
         *
         * ```kotlin
         * registry.add(Interceptor.full<Request> { request, proceed ->
         *     try {
         *         proceed(request)
         *     } catch (e: Exception) {
         *         proceed(request.copy(retryCount = request.retryCount + 1))
         *     }
         * })
         * ```
         *
         * @param block a suspending function that receives the data and a
         *              `proceed` callback for invoking downstream interceptors.
         * @return an [Interceptor] with arbitrary control over the pipeline.
         */
        fun <T> full(block: suspend (data: T, proceed: suspend (T) -> T) -> T): Interceptor<T> =
            Interceptor { data, chain ->
                block(data) { chain.proceed(it) }
            }
    }
}

/**
 * A handle returned when registering an interceptor, allowing the caller to
 * later remove it from the registry.
 *
 * Calling [unregister] is idempotent — subsequent calls are safe no-ops.
 */
fun interface Registration {

    /**
     * Removes the associated interceptor from the registry.
     *
     * After this call the interceptor will no longer participate in future
     * pipeline executions. In-flight executions that already captured a
     * snapshot of the chain are unaffected.
     */
    fun unregister()
}

/**
 * A type-safe pipeline that applies all registered [Interceptor]s for a given
 * data type, in priority order.
 *
 * Implementations are expected to be safe for concurrent use.
 *
 * @see InterceptorRegistry for the default implementation.
 */
interface InterceptorPipeline {

    /**
     * Applies all interceptors registered for [clazz] to [data], returning the
     * final result after the full chain has executed.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged.
     *
     * @param T     the data type, which must be a non-nullable [Any] subtype.
     * @param clazz the [KClass] token used to look up registered interceptors.
     * @param data  the initial value to pass into the interceptor chain.
     * @return the result produced by the interceptor chain.
     */
    suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T
}

/**
 * Reified convenience overload for [InterceptorPipeline.applyInterceptors] that
 * infers the [KClass] token from the type parameter.
 *
 * ```kotlin
 * val processed: MyRequest = pipeline.applyInterceptors(myRequest)
 * ```
 *
 * @param T    the data type (inferred).
 * @param data the initial value to pass into the interceptor chain.
 * @return the result produced by the interceptor chain.
 */
suspend inline fun <reified T : Any> InterceptorPipeline.applyInterceptors(data: T): T =
    applyInterceptors(T::class, data)

/**
 * Thread-safe, priority-ordered registry of [Interceptor]s keyed by data type.
 *
 * Interceptors are grouped by their target [KClass] and executed in
 * **ascending priority** order (lowest value runs first). Interceptors with
 * equal priority are executed in **FIFO insertion order**.
 *
 * ## Thread Safety
 *
 * All public methods are safe to call from any thread or coroutine.
 * Registration and un-registration may happen concurrently with pipeline
 * execution. Note, however, that an in-flight [applyInterceptors] call sees a
 * snapshot of the interceptor set at the moment it builds the chain; changes
 * that occur *during* execution will only be visible to subsequent calls.
 *
 * ## Usage
 *
 * ```kotlin
 * val registry = InterceptorRegistry()
 *
 * // Register interceptors (optionally with priority)
 * val reg = registry.add(Interceptor.read<MyEvent> { println(it) }, priority = 10)
 * registry.add(Interceptor.transform<MyEvent> { it.copy(processed = true) }, priority = 0)
 *
 * // Execute the pipeline
 * val result = registry.applyInterceptors(MyEvent("hello"))
 *
 * // Later, remove an interceptor
 * reg.unregister()
 * ```
 */
class InterceptorRegistry : InterceptorPipeline {

    /**
     * Type-safe wrapper that pairs a [Class] token with the original [Interceptor].
     *
     * All internal casts go through [Class.cast], providing a checked,
     * runtime-verified cast rather than an unchecked suppression. This
     * preserves type safety even though the backing map erases generics.
     *
     * @param T              the interceptor's data type.
     * @property priority       numeric priority; lower values execute first.
     * @property insertionOrder globally unique, monotonically increasing counter
     *                          used to break priority ties in FIFO order.
     * @property javaClass      the [Class] token for runtime type checking/casting.
     * @property interceptor    the underlying [Interceptor] instance.
     */
    private class Entry<T : Any>(
        val priority: Int,
        val insertionOrder: Long,
        val javaClass: Class<T>,
        val interceptor: Interceptor<T>,
    ) {
        /**
         * Invokes this entry's [interceptor] with an untyped [data] value and
         * [chain], performing safe checked casts on both the input and the
         * downstream chain's return value.
         *
         * @param data  the raw data value (erased to [Any]).
         * @param chain the downstream chain handle (erased to `Chain<Any>`).
         * @return the result of this interceptor, cast back to [Any].
         * @throws ClassCastException if [data] or a downstream result is not
         *         assignable to [T].
         */
        suspend fun interceptUntyped(data: Any, chain: Interceptor.Chain<Any>): Any {
            val typed: T = javaClass.cast(data)
            val typedChain = Interceptor.Chain<T> { modified -> javaClass.cast(chain.proceed(modified)) }
            return interceptor.intercept(typed, typedChain)
        }
    }

    /**
     * Comparator: lowest priority first, ties broken by insertion order (FIFO).
     *
     * Because [Entry.insertionOrder] is globally unique, no two distinct entries
     * are ever considered "equal" — a requirement for correct
     * [ConcurrentSkipListSet] behavior (which uses comparator equality in lieu
     * of `equals`/`hashCode`).
     */
    private val entryComparator = Comparator<Entry<*>> { a, b ->
        val cmp = a.priority.compareTo(b.priority)
        if (cmp != 0) cmp else a.insertionOrder.compareTo(b.insertionOrder)
    }

    /** Monotonically increasing counter that ensures unique insertion ordering. */
    private val insertionCounter = AtomicLong(0)

    /**
     * Global version stamp, incremented on every add/remove. Used to cheaply
     * detect whether the [resolvedCache] entries are still valid without
     * clearing the entire cache or taking a lock.
     */
    private val version = AtomicLong(0)

    /**
     * The primary data structure: a map from data type ([KClass]) to a sorted
     * set of [Entry] instances ordered by [entryComparator].
     *
     * Both the outer map and the inner sets are concurrent, allowing
     * lock-free reads and writes from multiple threads.
     */
    private val map = ConcurrentHashMap<KClass<*>, ConcurrentSkipListSet<Entry<*>>>()

    /**
     * Cached, pre-resolved interceptor arrays keyed by target [KClass].
     *
     * Each value is a [VersionedSnapshot]: the [version] at which it was
     * computed, plus the flattened, sorted [Array] of [Entry] objects that
     * matched the key type via [Class.isAssignableFrom]. On a cache hit with
     * a matching version the hot path is a single map lookup + long comparison.
     *
     * Entries become stale (but never incorrect) when [version] advances;
     * stale entries are lazily recomputed on the next access.
     */
    private val resolvedCache = ConcurrentHashMap<KClass<*>, VersionedSnapshot>()

    /**
     * A snapshot of resolved entries tagged with the [version] at which it
     * was computed.
     */
    private class VersionedSnapshot(
        val snapshotVersion: Long,
        val entries: Array<Entry<*>>,
    )

    /**
     * Registers an [interceptor] for the given data type [clazz].
     *
     * Lower [priority] values execute first. Interceptors with the same
     * priority run in the order they were registered (FIFO).
     *
     * @param T           the data type the interceptor operates on.
     * @param clazz       the [KClass] token identifying the data type.
     * @param interceptor the interceptor to register.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle — call [Registration.unregister] to remove
     *         the interceptor from future pipeline executions.
     */
    fun <T : Any> add(
        clazz: KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration {
        val set = map.getOrPut(clazz) { ConcurrentSkipListSet(entryComparator) }
        val entry = Entry(priority, insertionCounter.getAndIncrement(), clazz.java, interceptor)
        set.add(entry)
        return Registration {
            set.remove(entry)
            version.incrementAndGet()
        }
    }

    /**
     * Reified convenience overload that infers the [KClass] token from the
     * type parameter [T].
     *
     * @param T           the data type the interceptor operates on (inferred).
     * @param interceptor the interceptor to register.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle.
     * @see add
     */
    inline fun <reified T : Any> add(
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration = add(T::class, interceptor, priority)

    /**
     * Returns the resolved, sorted array of [Entry] objects applicable to
     * [clazz]. Results are cached and only recomputed when the global
     * [version] has advanced since the last snapshot.
     *
     * @param clazz the target data type.
     * @return a (possibly shared) array of entries in execution order.
     */
    private fun resolveEntries(clazz: KClass<*>): Array<Entry<*>> {
        val currentVersion = version.get()

        // Fast path: cache hit with current version — no allocation at all.
        val cached = resolvedCache[clazz]
        if (cached != null && cached.snapshotVersion == currentVersion) {
            return cached.entries
        }

        // Slow path: recompute. Multiple threads may race here; that's fine —
        // they'll produce identical results and the last write wins harmlessly.
        val targetJavaClass = clazz.java

        // Single-pass collection: iterate map entries, accumulate matching
        // entries into a mutable list, then sort once.
        val result = ArrayList<Entry<*>>()
        for ((key, set) in map) {
            if (key.java.isAssignableFrom(targetJavaClass)) {
                // ConcurrentSkipListSet iteration is weakly-consistent and
                // already in comparator order, but entries from *different*
                // sets must be merged, so we still need a final sort.
                result.addAll(set)
            }
        }

        val entries = if (result.isEmpty()) {
            emptyArray<Entry<*>>()
        } else {
            result.sortWith(entryComparator)
            result.toTypedArray()
        }

        resolvedCache[clazz] = VersionedSnapshot(currentVersion, entries)
        return entries
    }

    /**
     * Runs all registered interceptors for [clazz] in priority order
     * (lowest first), threading [data] through the chain.
     *
     * The chain is constructed from tail to head so that the highest-priority
     * (lowest-numbered) interceptor executes first and the identity function
     * serves as the terminal handler.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged with no allocations beyond the map lookup.
     *
     * @param T     the data type.
     * @param clazz the [KClass] token used to look up interceptors.
     * @param data  the initial value entering the chain.
     * @return the final value after all interceptors have executed.
     * @throws ClassCastException if an interceptor returns a value that is not
     *         assignable to [T].
     */
    override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T {
        val entries = resolveEntries(clazz)
        if (entries.isEmpty()) return data

        // Build the chain iteratively from tail to head. Each link is a single
        // lambda capturing only the entry and the next chain reference
        var chain: Interceptor.Chain<Any> = Interceptor.Chain { it }
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val next = chain
            chain = Interceptor.Chain { d -> entry.interceptUntyped(d, next) }
        }

        val result = chain.proceed(data)
        return clazz.java.cast(result)
    }
}
