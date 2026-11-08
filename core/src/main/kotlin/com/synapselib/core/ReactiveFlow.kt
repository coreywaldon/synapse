package com.synapselib.core

import com.synapselib.core.data.buildAutomaton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A specialized wrapper around [Flow] that provides advanced reactive operators for complex event processing.
 *
 * [ReactiveFlow] extends the capabilities of standard Kotlin Coroutine Flows by introducing operators
 * commonly found in functional reactive programming (FRP) libraries but absent from the standard library,
 * such as time-windowed deduplication ([squash]), conditional gating ([gate]), and sequence detection ([combo]).
 *
 * **Time:** the time-dependent operators ([squash], [pace], [combo]) read "now" from [timeSource] and
 * wait with coroutine `delay`/`select`. The two must agree, so pass [TimeSource.Monotonic] (the default)
 * in production and `TestScope.testTimeSource` under `kotlinx-coroutines-test`, where `delay` runs on
 * virtual time. Every operator returns a [ReactiveFlow] that carries the same [timeSource], so chains
 * such as `ReactiveFlow(src, ts).gate(g).squash(d)` keep using `ts` all the way down.
 *
 * **Threading:** every operator keeps its mutable state confined to a single coroutine (or publishes it
 * through a single volatile reference), so they are safe on multi-threaded dispatchers.
 *
 * @param T The type of data emitted by the flow.
 * @property upstream The source [Flow] being wrapped.
 * @property timeSource The clock used by time-dependent operators.
 */
class ReactiveFlow<T>(
    private val upstream: Flow<T>,
    private val timeSource: TimeSource,
) : Flow<T> {

    /** Wraps [upstream] using [TimeSource.Monotonic] for time-dependent operators. */
    constructor(upstream: Flow<T>) : this(upstream, TimeSource.Monotonic)

    /**
     * Wraps [upstream] using a raw nanosecond clock.
     *
     * A bare `() -> Long` cannot be kept consistent with coroutine `delay` (for example under
     * `kotlinx-coroutines-test` virtual time), which is why this overload is deprecated in favour of
     * the [TimeSource]-based constructor.
     */
    @Deprecated(
        message = "Pass a kotlin.time.TimeSource instead (TimeSource.Monotonic, or TestScope.testTimeSource in tests).",
        replaceWith = ReplaceWith("ReactiveFlow(upstream, TimeSource.Monotonic)", "kotlin.time.TimeSource"),
    )
    constructor(
        upstream: Flow<T>,
        timeSource: () -> Long = { System.nanoTime() },
    ) : this(upstream, NanoClockTimeSource(timeSource))

    override suspend fun collect(collector: FlowCollector<T>) {
        upstream.collect(collector)
    }

    // ── Internals ───────────────────────────────────────────────────────

    /** Re-wraps [flow] so the next operator in the chain sees the same [timeSource]. */
    private fun <R> derive(flow: Flow<R>): ReactiveFlow<R> = ReactiveFlow(flow, timeSource)

    /**
     * Returns a monotonic nanosecond reading relative to a fresh origin. Call once per collection so
     * each collector measures against its own origin.
     */
    private fun startClock(): () -> Long {
        val origin: TimeMark = timeSource.markNow()
        return { origin.elapsedNow().inWholeNanoseconds }
    }

    // ── Operators ───────────────────────────────────────────────────────

    /**
     * Gates the data flow based on an external boolean condition, acting as a controllable valve.
     *
     * This operator allows you to pause, buffer, or drop events based on the state of a secondary [condition] flow.
     *
     * **Behavior:**
     * - When [condition] emits `true` (Open): Data passes through immediately.
     * - When [condition] emits `false` (Closed): Data is handled according to the [strategy].
     * - When [condition] transitions from `false` to `true`: Any buffered or stored data is emitted immediately,
     *   preserving order, before new live data is processed.
     * - The gate starts **closed** until [condition] emits its first value.
     *
     * **Completion:** the resulting flow completes normally once the upstream has completed *and* nothing
     * held by the [strategy] is still waiting for the gate to open (an empty buffer for [GateStrategy.BUFFER],
     * no stored value for [GateStrategy.LATEST], immediately for [GateStrategy.DROP]). Upstream errors
     * propagate immediately, even while the gate is closed; held values are discarded in that case.
     *
     * **Use Cases:**
     * - Pausing UI updates when the application is in the background.
     * - Buffering analytics events while the network is offline.
     * - Dropping sensor noise when a device is in a specific physical state.
     *
     * @param condition A [Flow] of Booleans controlling the gate state.
     * @param strategy Determines how data is handled when the gate is closed. Defaults to [GateStrategy.BUFFER].
     * @param bufferCapacity The maximum number of items to hold when using [GateStrategy.BUFFER]; must be positive.
     *                       If the buffer fills, the oldest items are dropped to make room for new ones.
     * @return A [ReactiveFlow] that respects the gating logic.
     * @throws IllegalArgumentException if [bufferCapacity] is not positive.
     */
    fun gate(
        condition: Flow<Boolean>,
        strategy: GateStrategy = GateStrategy.BUFFER,
        bufferCapacity: Int = Int.MAX_VALUE
    ): ReactiveFlow<T> {
        require(bufferCapacity > 0) { "bufferCapacity must be positive, was $bufferCapacity" }

        val gatedFlow = flow {
            val buffer = ArrayDeque<T>()
            var latestValue: Any? = null
            var hasLatest = false
            var isGateOpen = false
            var isUpstreamDone = false

            // True once nothing remains that could still be emitted.
            fun isFinished(): Boolean {
                if (!isUpstreamDone) return false
                return when (strategy) {
                    GateStrategy.BUFFER -> buffer.isEmpty()
                    GateStrategy.LATEST -> !hasLatest
                    GateStrategy.DROP -> true
                }
            }

            // Merge upstream and condition so they are processed sequentially by one coroutine.
            // transformWhile stops collecting (and cancels both sources) as soon as we return false,
            // using the library's own abort mechanism instead of a custom CancellationException.
            val events = merge(
                upstream.map<T, GateEvent<T>> { GateEvent.Data(it) }
                    .onCompletion { cause -> if (cause == null) emit(GateEvent.UpstreamComplete) },
                condition.map { GateEvent.GateState(it) },
            )

            emitAll(events.transformWhile { event ->
                when (event) {
                    is GateEvent.GateState -> {
                        isGateOpen = event.isOpen
                        if (isGateOpen) {
                            when (strategy) {
                                GateStrategy.BUFFER -> while (buffer.isNotEmpty()) emit(buffer.removeFirst())
                                GateStrategy.LATEST -> if (hasLatest) {
                                    @Suppress("UNCHECKED_CAST")
                                    val value = latestValue as T
                                    latestValue = null
                                    hasLatest = false
                                    emit(value)
                                }
                                GateStrategy.DROP -> Unit
                            }
                        }
                    }

                    is GateEvent.Data -> {
                        if (isGateOpen) {
                            // Defensive: preserve order if anything is still buffered.
                            while (buffer.isNotEmpty()) emit(buffer.removeFirst())
                            emit(event.value)
                        } else {
                            when (strategy) {
                                GateStrategy.BUFFER -> {
                                    if (buffer.size >= bufferCapacity) buffer.removeFirst() // drop oldest
                                    buffer.addLast(event.value)
                                }
                                GateStrategy.LATEST -> {
                                    latestValue = event.value
                                    hasLatest = true
                                }
                                GateStrategy.DROP -> Unit
                            }
                        }
                    }

                    GateEvent.UpstreamComplete -> isUpstreamDone = true
                }
                !isFinished()
            })
        }
        return derive(gatedFlow)
    }

    /**
     * Squashes (deduplicates) identical signals within a specific time window.
     *
     * Unlike `distinctUntilChanged`, which remembers the last value indefinitely, [squash] forgets the value
     * after the [retentionPeriod] expires. This allows the same value to be re-emitted if enough time has passed.
     *
     * **Mechanism:**
     * 1. When a value is received, it is checked against a cache of "active" values.
     * 2. If the value is active (seen recently), it is dropped.
     * 3. If the value is new or expired, it is emitted and added to the cache with an expiration timestamp.
     * 4. The cache never grows beyond [maxDistinctValues].
     *
     * Time is read from the flow's [TimeSource]. Completion and errors are those of the upstream.
     *
     * **Use Cases:**
     * - Preventing double-clicks or rapid-fire API calls for the same resource.
     * - Debouncing specific error messages that shouldn't spam the user log, but should reappear if they persist after a minute.
     *
     * @param retentionPeriod The duration for which a specific value is suppressed after emission.
     * @param maxDistinctValues The maximum number of unique values to track simultaneously.
     *                          If this limit is reached, the oldest tracked value is evicted
     *                          (even if its window hasn't expired) to make room.
     * @param shouldSquash A predicate to determine if a value is eligible for squashing.
     *                     If this returns `false`, the value is always emitted and never cached.
     * @return A [ReactiveFlow] with temporal deduplication applied.
     */
    fun squash(
        retentionPeriod: Duration,
        maxDistinctValues: Int = 100,
        shouldSquash: (T) -> Boolean = { true }
    ): ReactiveFlow<T> {
        val retentionNanos = retentionPeriod.inWholeNanoseconds
        return derive(flow {
            val now = startClock()
            // Queue to track expiration times: (Expiration Time, Value). Sorted, since `now` is monotonic.
            val expirationQueue = ArrayDeque<Pair<Long, T>>()
            // Set for O(1) lookup of currently suppressed values
            val activeValues = HashSet<T>()

            upstream.collect { value ->
                val currentTime = now()

                // 1. Cleanup expired entries
                while (expirationQueue.isNotEmpty() && expirationQueue.first().first <= currentTime) {
                    val (_, expiredValue) = expirationQueue.removeFirst()
                    activeValues.remove(expiredValue)
                }

                // 2. Values exempt from squashing pass through untracked
                if (!shouldSquash(value)) {
                    emit(value)
                    return@collect
                }

                // 3. Process the value
                if (!activeValues.contains(value)) {
                    // Enforce capacity limit: evict the oldest entry if full
                    if (activeValues.size >= maxDistinctValues && expirationQueue.isNotEmpty()) {
                        val (_, evictedValue) = expirationQueue.removeFirst()
                        activeValues.remove(evictedValue)
                    }

                    emit(value)

                    if (activeValues.size < maxDistinctValues) {
                        activeValues.add(value)
                        expirationQueue.addLast(saturatedAdd(currentTime, retentionNanos) to value)
                    }
                }
            }
        })
    }

    /**
     * Limits the rate of emissions by ensuring a minimum time [interval] elapses between items.
     *
     * If the upstream emits faster than the [interval], this operator delays the emission of the
     * subsequent items to ensure the gap is respected. This effectively smooths out bursts of data.
     * The first item is always emitted immediately. The gap is measured from the moment the previous
     * emission returned (i.e. after the downstream processed it).
     *
     * **Note:** This is different from `debounce` (which drops items) or `sample` (which picks the latest).
     * [pace] emits *all* items, but spreads them out over time. Completion and errors are those of the upstream.
     *
     * **Use Cases:**
     * - Preventing UI jank by limiting the frequency of complex rendering updates.
     * - Throttling outgoing network requests to respect API rate limits.
     *
     * @param interval The minimum duration required between two consecutive emissions.
     * @return A [ReactiveFlow] that emits items at a paced rate.
     */
    fun pace(
        interval: Duration
    ): ReactiveFlow<T> {
        return derive(flow {
            val now = startClock()
            var hasEmitted = false
            var lastEmissionTime = 0L

            upstream.collect { value ->
                if (hasEmitted) {
                    val timeSinceLast = (now() - lastEmissionTime).nanoseconds
                    if (timeSinceLast < interval) delay(interval - timeSinceLast)
                }

                emit(value)

                hasEmitted = true
                lastEmissionTime = now()
            }
        })
    }

    /**
     * Collects incoming values into lists and emits them periodically.
     *
     * This operator groups elements emitted by the upstream flow into a [List] and emits that list
     * every [period]. If the buffer reaches [maxBufferSize] before the time period elapses,
     * the chunk is emitted immediately. Empty chunks are never emitted.
     *
     * **Completion:** when the upstream completes normally, the remaining partial chunk is emitted and
     * the flow completes. When the upstream fails, the partial chunk is **dropped** and the error is
     * rethrown downstream.
     *
     * **Use Cases:**
     * - Batching database writes to improve transaction performance.
     * - Aggregating log lines before sending them to a remote server.
     *
     * @param period The time duration to wait before emitting the current batch; must be positive.
     * @param maxBufferSize The maximum number of items to hold in a single batch. Defaults to [Int.MAX_VALUE].
     * @return A [ReactiveFlow] emitting lists of type [T].
     * @throws IllegalArgumentException if [period] is not positive.
     */
    fun chunk(
        period: Duration,
        maxBufferSize: Int = Int.MAX_VALUE
    ): ReactiveFlow<List<T>> {
        require(period.isPositive()) { "period must be positive, was $period" }

        return derive(channelFlow {
            val buffer = ArrayList<T>()
            // Single consumer (this coroutine) owns `buffer`; producers only talk through the channel.
            val eventChannel = Channel<Any?>(Channel.UNLIMITED)

            val tickerJob = launch {
                try {
                    while (isActive) {
                        delay(period)
                        eventChannel.send(TickToken)
                    }
                } catch (_: ClosedSendChannelException) {
                    // Channel closed after upstream finished; exit quietly.
                }
            }

            launch {
                var failure: Throwable? = null
                try {
                    upstream.collect { value -> eventChannel.send(value) }
                } catch (e: Throwable) {
                    failure = e
                    // Cancellation must keep propagating; real failures are handed to the consumer
                    // through the channel so it rethrows them deterministically.
                    if (e is CancellationException) throw e
                } finally {
                    tickerJob.cancel()
                    eventChannel.close(failure)
                }
            }

            // Iteration rethrows the upstream failure (if any) once the queued events are drained.
            for (event in eventChannel) {
                if (event === TickToken) {
                    if (buffer.isNotEmpty()) {
                        send(buffer.toList())
                        buffer.clear()
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    buffer.add(event as T)
                    if (buffer.size >= maxBufferSize) {
                        send(buffer.toList())
                        buffer.clear()
                    }
                }
            }

            if (buffer.isNotEmpty()) send(buffer.toList())
        })
    }

    /**
     * Samples the latest value from the upstream flow only when a secondary [trigger] flow emits.
     *
     * This acts like a camera shutter: the upstream flow is the "scene", and the [trigger] flow is the
     * button press. You only capture the scene when the button is pressed.
     *
     * **Behavior:**
     * - If the upstream has not emitted any value yet, the trigger is ignored.
     * - If the upstream has emitted multiple values since the last trigger, only the most recent one is emitted.
     * - Every trigger emission samples, so the same value can be emitted repeatedly.
     *
     * **Completion:** the resulting flow completes when the [trigger] completes; the upstream collection is
     * then cancelled. Upstream *completion* does not end the flow — its last value stays available and
     * is sampled by later triggers. An error from either the upstream or the [trigger] fails the flow.
     *
     * **Use Cases:**
     * - Reading a sensor value only when a user clicks a "Measure" button.
     * - Sampling a high-frequency signal based on a lower-frequency clock.
     *
     * @param trigger The flow that signals when to sample the upstream.
     * @return A [ReactiveFlow] emitting the sampled values.
     */
    fun <R> shutter(trigger: Flow<R>): ReactiveFlow<T> {
        return derive(channelFlow {
            // Written by the upstream collector, read by the trigger collector. A single volatile
            // reference to an immutable box publishes "has a value" and "the value" atomically.
            val latest = LatestValue<T>()

            val upstreamJob = launch {
                upstream.collect { latest.box = Sample(it) }
            }

            trigger.collect {
                val sample = latest.box
                if (sample != null) send(sample.value)
            }

            upstreamJob.cancel()
        })
    }

    /**
     * Detects specific sequences of values (combos) emitted within a defined time window.
     *
     * This operator utilizes a Trie-based automaton to efficiently match sequences of inputs against
     * a set of target [sequences]. Empty sequences are ignored.
     *
     * **Timeout Logic:**
     * The detection state resets if the time between two inputs exceeds [timeout].
     *
     * **Greedy vs Non-Greedy:**
     * - **Non-Greedy (`greedy = false`):** The flow emits a match as soon as the shortest valid sequence is completed.
     * - **Greedy (`greedy = true`):** If a match is found, but it is a prefix of a longer potential match,
     *   the match is *held* while the input keeps following a path towards the longer sequence. The held
     *   match is emitted when the chain breaks (an input that does not extend it), when no further input
     *   arrives within [timeout] of the last step, or when the upstream completes. If the longer sequence
     *   completes, only the longer match is emitted.
     *
     * After any match is emitted the automaton restarts from scratch, except when a held match is emitted
     * because the chain broke: the breaking input may itself start (or complete) a new combo.
     *
     * Time is read from the flow's [TimeSource] and the greedy hold uses coroutine timeouts, so the two must
     * agree (see [ReactiveFlow]). All state lives in a single coroutine. Upstream errors propagate.
     *
     * **Use Cases:**
     * - Detecting "Konami codes" or cheat codes in games.
     * - Implementing complex gesture recognition (e.g., Tap -> Tap -> Hold).
     * - Recognizing barcode scanner preambles/postambles.
     *
     * @param sequences A collection of sequences to detect (e.g., `listOf(listOf(A, B), listOf(A, B, C))`).
     * @param timeout The maximum allowed duration between inputs to maintain the sequence state.
     * @param greedy If `true`, waits to see if a longer sequence can be matched before emitting.
     * @return A [ReactiveFlow] emitting the detected sequences as lists.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun combo(
        sequences: Iterable<Iterable<T>>,
        timeout: Duration,
        greedy: Boolean = false
    ): ReactiveFlow<List<T>> {
        val patterns = sequences.map { it.toList() }.filter { it.isNotEmpty() }
        val timeoutNanos = timeout.inWholeNanoseconds

        return derive(channelFlow {
            val automaton = buildAutomaton(patterns)
            val now = startClock()

            var state = automaton.root
            var lastStepTime = now()

            // Greedy hold: the match and the time at which it fires if nothing extends it.
            var pending: List<T>? = null
            var pendingDeadline = 0L

            // Rendezvous: upstream is suspended until this coroutine takes the value, so timestamps
            // reflect emission time and all state below stays confined to this coroutine.
            val inputs = produce { upstream.collect { send(it) } }

            while (true) {
                val received: ChannelResult<T> = when (val held = pending) {
                    null -> inputs.receiveCatching()
                    else -> {
                        val remaining = pendingDeadline - now()
                        val result = if (remaining > 0) {
                            select<ChannelResult<T>?> {
                                inputs.onReceiveCatching { it }
                                onTimeout(remaining.nanoseconds) { null }
                            }
                        } else {
                            null
                        }
                        if (result == null) {
                            // Hold expired: nothing extended the match in time.
                            pending = null
                            state = automaton.root
                            send(held)
                            continue
                        }
                        result
                    }
                }

                if (received.isClosed) {
                    received.exceptionOrNull()?.let { throw it }
                    // Upstream completed: no input can extend a held match any more.
                    pending?.let { send(it) }
                    break
                }

                val value = received.getOrThrow()
                val currentTime = now()

                // A held match whose window elapsed just as this input arrived fires first.
                pending?.let { held ->
                    if (currentTime >= pendingDeadline) {
                        pending = null
                        state = automaton.root
                        send(held)
                    }
                }

                // Inter-input timeout
                if (currentTime - lastStepTime > timeoutNanos) state = automaton.root
                lastStepTime = currentTime

                // A held match survives only while the input keeps walking down the trie.
                val extendsChain = state.children.containsKey(value)
                pending?.let { held ->
                    if (!extendsChain) {
                        pending = null
                        send(held)
                        // Do NOT reset: this input may start a new combo and the automaton handles it below.
                    }
                }

                state = automaton.next(state, value)

                // Only the longest match ending here matters (e.g. "SHE" over "HE").
                val longestMatch = state.matches.maxByOrNull { it.size }
                when {
                    longestMatch == null -> {
                        // Still walking towards a longer combo: keep holding, refresh the window.
                        if (pending != null) pendingDeadline = saturatedAdd(currentTime, timeoutNanos)
                    }
                    !greedy || state.children.isEmpty() -> {
                        // Non-greedy, or a dead end in the trie: fire immediately and restart.
                        pending = null
                        send(longestMatch)
                        state = automaton.root
                    }
                    else -> {
                        // A longer combo is still possible: hold this one.
                        pending = longestMatch
                        pendingDeadline = saturatedAdd(currentTime, timeoutNanos)
                    }
                }
            }
        })
    }

    /**
     * Automatically retries the upstream flow with exponential backoff if it terminates with an exception.
     *
     * This encapsulates the `retryWhen` logic with a standard exponential backoff algorithm.
     * [CancellationException]s are never retried.
     *
     * **Formula:**
     * `delay = initialDelay * (factor ^ attempt)` where `attempt` starts at 0. The result is computed
     * exactly (fractional factors are honoured) and saturates at [Duration.INFINITE] instead of overflowing.
     *
     * @param maxRetries The maximum number of times to retry before letting the exception propagate.
     * @param initialDelay The delay before the first retry.
     * @param factor The multiplier applied to the delay after each subsequent failure.
     * @return A [ReactiveFlow] that is resilient to transient errors.
     */
    fun resilient(
        maxRetries: Int = 3,
        initialDelay: Duration = 100.milliseconds,
        factor: Double = 2.0
    ): ReactiveFlow<T> {
        return derive(upstream.retryWhen { throwable, attempt ->
            if (attempt < maxRetries && throwable !is CancellationException) {
                delay(backoffDelay(initialDelay, factor, attempt))
                true
            } else {
                false
            }
        })
    }

    /**
     * Converts the flow into a hot [kotlinx.coroutines.flow.SharedFlow] that multicasts values to all collectors.
     *
     * This is a convenience wrapper around `shareIn` with [SharingStarted.WhileSubscribed].
     * Like any shared flow, the result never completes; an upstream error stops the sharing
     * coroutine (and is reported to [scope]'s exception handling), it is not rethrown to collectors.
     *
     * @param scope The [CoroutineScope] in which the shared flow computation is started.
     * @param replay The number of values to replay to new collectors.
     * @param keepAlive The duration to keep the upstream active after the last collector disappears.
     *                  If [Duration.ZERO], it stops immediately. Must not be negative.
     * @return A [ReactiveFlow] backed by a SharedFlow.
     */
    fun broadcast(
        scope: CoroutineScope,
        replay: Int = 0,
        keepAlive: Duration = Duration.ZERO
    ): ReactiveFlow<T> {
        require(!keepAlive.isNegative()) { "keepAlive must not be negative, was $keepAlive" }
        val strategy = SharingStarted.WhileSubscribed(stopTimeoutMillis = keepAlive.inWholeMilliseconds)
        return derive(upstream.shareIn(scope, strategy, replay))
    }

    /**
     * Peeks into the flow lifecycle for debugging purposes without altering the stream.
     *
     * Logs events for: Start, Emission, and termination — `Completed`, `Failed: <cause>` for errors,
     * or `Cancelled` when collection was cancelled (including downstream operators such as `take`
     * stopping early).
     *
     * @param tag A tag to prepend to log messages for identification.
     * @param logger The function used to output the log string. Defaults to [println].
     * @return A [ReactiveFlow] identical to the upstream but with side effect logging.
     */
    fun spy(
        tag: String = "Spy",
        logger: (String) -> Unit = ::println
    ): ReactiveFlow<T> {
        return derive(
            upstream
                .onStart { logger("[$tag] Started") }
                .onEach { logger("[$tag] Emitted: $it") }
                .onCompletion { cause ->
                    when (cause) {
                        null -> logger("[$tag] Completed")
                        is CancellationException -> logger("[$tag] Cancelled")
                        else -> logger("[$tag] Failed: $cause")
                    }
                }
        )
    }

}

/**
 * Formerly thrown internally by [ReactiveFlow.gate] to stop collection.
 *
 * [ReactiveFlow.gate] no longer uses it: the gated flow now completes normally. Previously this
 * exception could leak to callers as a spurious cancellation (e.g. from `toList()`).
 */
@Deprecated("No longer thrown: ReactiveFlow.gate now completes normally. Remove any handling of this type.")
class GateFinishedException : CancellationException("Gate Finished")

/**
 * Defines the behavior of the [ReactiveFlow.gate] operator when the gate is in the "Closed" state.
 */
enum class GateStrategy {
    /**
     * Queues all values received while the gate is closed.
     * When the gate opens, all queued values are emitted in order.
     * Use this for critical data streams where no data loss is allowed.
     */
    BUFFER,

    /**
     * Keeps only the most recent value received while the gate is closed.
     * When the gate opens, only that single latest value is emitted.
     * Use this for UI states or sensor readings where intermediate values are noise.
     */
    LATEST,

    /**
     * Drops (ignores) all values received while the gate is closed.
     * When the gate opens, nothing is emitted until a new value arrives.
     * Use this for "live-only" feeds where past data is irrelevant.
     */
    DROP
}

// ── Private helpers ─────────────────────────────────────────────────────

/** Internal event wrapper used for merging data and control signals in the [ReactiveFlow.gate] operator. */
private sealed interface GateEvent<out T> {
    data class Data<T>(val value: T) : GateEvent<T>
    data class GateState(val isOpen: Boolean) : GateEvent<Nothing>
    data object UpstreamComplete : GateEvent<Nothing>
}

/** Marker sent by the [ReactiveFlow.chunk] ticker. */
private object TickToken

/** Immutable sample published by [ReactiveFlow.shutter]'s upstream collector. */
private class Sample<T>(val value: T)

/** Single-slot, volatile holder shared between [ReactiveFlow.shutter]'s two collectors. */
private class LatestValue<T> {
    @Volatile
    var box: Sample<T>? = null
}

/** Adapts a legacy `() -> Long` nanosecond clock to [TimeSource]. */
private class NanoClockTimeSource(private val nanos: () -> Long) : TimeSource {
    override fun markNow(): TimeMark = Mark(nanos())

    private inner class Mark(private val at: Long) : TimeMark {
        override fun elapsedNow(): Duration = (nanos() - at).nanoseconds
    }
}

/** `a + b` that clamps at [Long.MAX_VALUE] / [Long.MIN_VALUE] instead of overflowing. */
private fun saturatedAdd(a: Long, b: Long): Long {
    val result = a + b
    // Overflow iff both operands have the same sign and the result's sign differs.
    return if (((a xor result) and (b xor result)) < 0) {
        if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    } else {
        result
    }
}

/** `initialDelay * factor^attempt`, computed in [Duration] space and saturating at [Duration.INFINITE]. */
internal fun backoffDelay(initialDelay: Duration, factor: Double, attempt: Long): Duration {
    if (!initialDelay.isPositive()) return Duration.ZERO
    val scale = factor.pow(attempt.toDouble())
    return when {
        scale.isNaN() || scale <= 0.0 -> Duration.ZERO
        scale.isInfinite() -> Duration.INFINITE
        else -> initialDelay * scale
    }
}

// ── Flow extensions ─────────────────────────────────────────────────────

/**
 * Wraps an existing [Flow] into a [ReactiveFlow] to access advanced operators.
 */
fun <T> Flow<T>.asReactive(): ReactiveFlow<T> = ReactiveFlow(this)

/**
 * Wraps an existing [Flow] into a [ReactiveFlow] whose time-dependent operators read [timeSource]
 * (e.g. `TestScope.testTimeSource` in tests).
 */
fun <T> Flow<T>.asReactive(timeSource: TimeSource): ReactiveFlow<T> = ReactiveFlow(this, timeSource)

/**
 * Convenience extension to apply [ReactiveFlow.gate] directly on a standard [Flow].
 *
 * @see ReactiveFlow.gate
 */
fun <T> Flow<T>.gate(
    condition: StateFlow<Boolean>,
    strategy: GateStrategy = GateStrategy.BUFFER,
    bufferCapacity: Int = Int.MAX_VALUE
): ReactiveFlow<T> = ReactiveFlow(this).gate(
    condition,
    strategy,
    bufferCapacity
)

/**
 * Convenience extension to apply [ReactiveFlow.gate] directly on a standard [Flow], using any
 * [Flow] of Booleans (not only a [StateFlow]) as the condition.
 *
 * @see ReactiveFlow.gate
 */
fun <T> Flow<T>.gate(
    condition: Flow<Boolean>,
    strategy: GateStrategy = GateStrategy.BUFFER,
    bufferCapacity: Int = Int.MAX_VALUE
): ReactiveFlow<T> = ReactiveFlow(this).gate(
    condition,
    strategy,
    bufferCapacity
)

/**
 * Convenience extension to apply [ReactiveFlow.squash] directly on a standard [Flow].
 *
 * @param timeSource The clock used to measure [retentionPeriod].
 * @see ReactiveFlow.squash
 */
fun <T> Flow<T>.squash(
    retentionPeriod: Duration,
    maxDistinctValues: Int = 100,
    shouldSquash: (T) -> Boolean = { true },
    timeSource: TimeSource = TimeSource.Monotonic,
): ReactiveFlow<T> = ReactiveFlow(this, timeSource).squash(
    retentionPeriod,
    maxDistinctValues,
    shouldSquash
)

/**
 * Legacy overload taking a raw nanosecond clock.
 *
 * @see ReactiveFlow.squash
 */
@Deprecated(
    message = "Pass a kotlin.time.TimeSource instead (TimeSource.Monotonic, or TestScope.testTimeSource in tests).",
    replaceWith = ReplaceWith(
        "squash(retentionPeriod, maxDistinctValues, shouldSquash, TimeSource.Monotonic)",
        "kotlin.time.TimeSource",
    ),
)
@JvmName("squashWithNanoClock")
fun <T> Flow<T>.squash(
    retentionPeriod: Duration,
    maxDistinctValues: Int = 100,
    shouldSquash: (T) -> Boolean = { true },
    timeSource: () -> Long,
): ReactiveFlow<T> = squash(retentionPeriod, maxDistinctValues, shouldSquash, NanoClockTimeSource(timeSource))

/** Binary-compatibility bridge for callers compiled against the original signature. */
@Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
fun <T> Flow<T>.squash(
    retentionPeriod: Duration,
    maxDistinctValues: Int = 100,
    shouldSquash: (T) -> Boolean = { true },
    timeSource: () -> Long = { System.nanoTime() },
): ReactiveFlow<T> = squash(retentionPeriod, maxDistinctValues, shouldSquash, NanoClockTimeSource(timeSource))

/**
 * Convenience extension to apply [ReactiveFlow.pace] directly on a standard [Flow].
 *
 * @param timeSource The clock used to measure the gap between emissions.
 * @see ReactiveFlow.pace
 */
fun <T> Flow<T>.pace(
    interval: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): ReactiveFlow<T> = ReactiveFlow(this, timeSource).pace(interval)

/**
 * Legacy overload taking a raw nanosecond clock.
 *
 * @see ReactiveFlow.pace
 */
@Deprecated(
    message = "Pass a kotlin.time.TimeSource instead (TimeSource.Monotonic, or TestScope.testTimeSource in tests).",
    replaceWith = ReplaceWith("pace(interval, TimeSource.Monotonic)", "kotlin.time.TimeSource"),
)
@JvmName("paceWithNanoClock")
fun <T> Flow<T>.pace(
    interval: Duration,
    timeSource: () -> Long,
): ReactiveFlow<T> = pace(interval, NanoClockTimeSource(timeSource))

/** Binary-compatibility bridge for callers compiled against the original signature. */
@Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
fun <T> Flow<T>.pace(
    interval: Duration,
    timeSource: () -> Long = { System.nanoTime() },
): ReactiveFlow<T> = pace(interval, NanoClockTimeSource(timeSource))

/**
 * Convenience extension to apply [ReactiveFlow.chunk] directly on a standard [Flow].
 *
 * @see ReactiveFlow.chunk
 */
fun <T> Flow<T>.chunk(
    period: Duration
): ReactiveFlow<List<T>> = ReactiveFlow(this).chunk(period)

/**
 * Convenience extension to apply [ReactiveFlow.chunk] directly on a standard [Flow], with a
 * size cap per chunk.
 *
 * @see ReactiveFlow.chunk
 */
fun <T> Flow<T>.chunk(
    period: Duration,
    maxBufferSize: Int,
): ReactiveFlow<List<T>> = ReactiveFlow(this).chunk(period, maxBufferSize)

/**
 * Convenience extension to apply [ReactiveFlow.shutter] directly on a standard [Flow].
 *
 * @see ReactiveFlow.shutter
 */
fun <T, R> Flow<T>.shutter(
    trigger: Flow<R>
): ReactiveFlow<T> = ReactiveFlow(this).shutter(trigger)

/**
 * Convenience extension to apply [ReactiveFlow.combo] directly on a standard [Flow].
 *
 * @param interval The maximum allowed duration between inputs (the operator's `timeout`).
 * @param timeSource The clock used to measure gaps between inputs.
 * @see ReactiveFlow.combo
 */
fun <T> Flow<T>.combo(
    sequences: Iterable<Iterable<T>>,
    interval: Duration,
    greedy: Boolean = false,
    timeSource: TimeSource = TimeSource.Monotonic,
): ReactiveFlow<List<T>> = ReactiveFlow(this, timeSource).combo(
    sequences,
    interval,
    greedy
)

/**
 * Legacy overload taking a raw nanosecond clock.
 *
 * @see ReactiveFlow.combo
 */
@Deprecated(
    message = "Pass a kotlin.time.TimeSource instead (TimeSource.Monotonic, or TestScope.testTimeSource in tests).",
    replaceWith = ReplaceWith("combo(sequences, interval, greedy, TimeSource.Monotonic)", "kotlin.time.TimeSource"),
)
@JvmName("comboWithNanoClock")
fun <T> Flow<T>.combo(
    sequences: Iterable<Iterable<T>>,
    interval: Duration,
    greedy: Boolean = false,
    timeSource: () -> Long,
): ReactiveFlow<List<T>> = combo(sequences, interval, greedy, NanoClockTimeSource(timeSource))

/** Binary-compatibility bridge for callers compiled against the original signature. */
@Deprecated("Binary compatibility only.", level = DeprecationLevel.HIDDEN)
fun <T> Flow<T>.combo(
    sequences: Iterable<Iterable<T>>,
    interval: Duration,
    greedy: Boolean = false,
    timeSource: () -> Long = { System.nanoTime() },
): ReactiveFlow<List<T>> = combo(sequences, interval, greedy, NanoClockTimeSource(timeSource))

/**
 * Convenience extension to apply [ReactiveFlow.broadcast] directly on a standard [Flow].
 *
 * @see ReactiveFlow.broadcast
 */
fun <T> Flow<T>.broadcast(
    scope: CoroutineScope,
    replays: Int = 0,
    keepAlive: Duration = Duration.ZERO
): ReactiveFlow<T> = ReactiveFlow(this).broadcast(
    scope,
    replays,
    keepAlive
)

/**
 * Convenience extension to apply [ReactiveFlow.resilient] directly on a standard [Flow].
 *
 * Note that, unlike the member [ReactiveFlow.resilient] (100ms), this extension's [initialDelay]
 * defaults to [Duration.ZERO], i.e. immediate retries.
 *
 * @see ReactiveFlow.resilient
 */
fun <T> Flow<T>.resilient(
    maxRetries: Int = 3,
    initialDelay: Duration = Duration.ZERO,
    factor: Double = 2.0
): ReactiveFlow<T> = ReactiveFlow(this).resilient(
    maxRetries,
    initialDelay,
    factor
)

/**
 * Convenience extension to apply [ReactiveFlow.spy] directly on a standard [Flow].
 *
 * @see ReactiveFlow.spy
 */
fun <T> Flow<T>.spy(
    tag: String = "Spy",
    logger: (String) -> Unit = ::println
): ReactiveFlow<T> = ReactiveFlow(this).spy(
    tag,
    logger
)
