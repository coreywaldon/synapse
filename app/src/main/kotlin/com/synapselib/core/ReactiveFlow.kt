package com.synapselib.core

import com.synapselib.core.data.buildAutomaton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A specialized wrapper around [Flow] that provides advanced reactive operators for complex event processing.
 *
 * [ReactiveFlow] extends the capabilities of standard Kotlin Coroutine Flows by introducing operators
 * commonly found in functional reactive programming (FRP) libraries but absent from the standard library,
 * such as time-windowed deduplication ([squash]), conditional gating ([gate]), and sequence detection ([combo]).
 *
 * @param T The type of data emitted by the flow.
 * @property upstream The source [Flow] being wrapped.
 * @property timeSource A provider for the current system time in nanoseconds. Used for testing time-dependent operators.
 */
class ReactiveFlow<T>(
    private val upstream: Flow<T>,
    private val timeSource: () -> Long = { System.nanoTime() }
) : Flow<T> {

    override suspend fun collect(collector: FlowCollector<T>) {
        upstream.collect(collector)
    }

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
     *
     * **Use Cases:**
     * - Pausing UI updates when the application is in the background.
     * - Buffering analytics events while the network is offline.
     * - Dropping sensor noise when a device is in a specific physical state.
     *
     * @param condition A [Flow] of Booleans controlling the gate state.
     * @param strategy Determines how data is handled when the gate is closed. Defaults to [GateStrategy.BUFFER].
     * @param bufferCapacity The maximum number of items to hold when using [GateStrategy.BUFFER].
     *                       If the buffer fills, the oldest items are dropped to make room for new ones.
     * @return A [ReactiveFlow] that respects the gating logic.
     */
    fun gate(
        condition: Flow<Boolean>,
        strategy: GateStrategy = GateStrategy.BUFFER,
        bufferCapacity: Int = Int.MAX_VALUE
    ): ReactiveFlow<T> {
        val gatedFlow = channelFlow {
            val buffer = ArrayDeque<T>()
            var latestValue: Any? = null
            var hasLatest = false
            var isGateOpen = false
            var isUpstreamDone = false

            // Helper to check if we can finish the flow safely
            fun shouldTerminate(): Boolean {
                if (!isUpstreamDone) return false
                return when (strategy) {
                    GateStrategy.BUFFER -> buffer.isEmpty()
                    GateStrategy.LATEST -> !hasLatest
                    GateStrategy.DROP -> true
                }
            }

            // Merge upstream and condition to process them sequentially
            merge(
                upstream.map { Event.Data(it) as Event<T> }
                    .onCompletion { cause -> if (cause == null) emit(Event.UpstreamComplete) },
                condition.map { Event.GateState(it) }
            ).collect { event ->
                when (event) {
                    is Event.GateState -> {
                        isGateOpen = event.isOpen
                        if (isGateOpen) {
                            // Flush logic
                            when (strategy) {
                                GateStrategy.BUFFER -> {
                                    while (buffer.isNotEmpty()) send(buffer.removeFirst())
                                }

                                GateStrategy.LATEST -> {
                                    if (hasLatest) {
                                        @Suppress("UNCHECKED_CAST")
                                        send(latestValue as T)
                                        latestValue = null
                                        hasLatest = false
                                    }
                                }

                                GateStrategy.DROP -> { /* Do nothing */
                                }
                            }
                        }

                        if (shouldTerminate()) throw GateFinishedException()
                    }

                    is Event.Data -> {
                        if (isGateOpen) {
                            // If buffer exists (from previous close), flush it first to maintain order
                            if (strategy == GateStrategy.BUFFER && buffer.isNotEmpty()) {
                                while (buffer.isNotEmpty()) send(buffer.removeFirst())
                            }
                            send(event.value)
                        } else {
                            // Gate is closed
                            when (strategy) {
                                GateStrategy.BUFFER -> {
                                    if (buffer.size < bufferCapacity) {
                                        buffer.add(event.value)
                                    } else {
                                        // Handle overflow (drop oldest)
                                        buffer.removeFirst()
                                        buffer.add(event.value)
                                    }
                                }

                                GateStrategy.LATEST -> {
                                    latestValue = event.value
                                    hasLatest = true
                                }

                                GateStrategy.DROP -> { /* Ignore */
                                }
                            }
                        }
                    }
                    is Event.UpstreamComplete -> {
                        isUpstreamDone = true
                        if (shouldTerminate()) throw GateFinishedException()
                    }
                }
            }
        }
        return ReactiveFlow(gatedFlow)
    }

    /**
     * Squashes (deduplicates) identical consecutive signals within a specific time window.
     *
     * Unlike `distinctUntilChanged`, which remembers the last value indefinitely, [squash] forgets the value
     * after the [retentionPeriod] expires. This allows the same value to be re-emitted if enough time has passed.
     *
     * **Mechanism:**
     * 1. When a value is received, it is checked against a cache of "active" values.
     * 2. If the value is active (seen recently), it is dropped.
     * 3. If the value is new or expired, it is emitted and added to the cache with an expiration timestamp.
     * 4. A background check ensures the cache does not grow beyond [maxDistinctValues].
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
        return ReactiveFlow(flow {
            // Queue to track expiration times: (Expiration Time, Value)
            val expirationQueue = ArrayDeque<Pair<Long, T>>()

            // Set for O(1) lookup of currently suppressed values
            val activeValues = HashSet<T>()

            upstream.collect { value ->
                val now = timeSource()

                // 1. Cleanup expired entries
                // Remove items whose retention period has passed
                while (expirationQueue.isNotEmpty() && expirationQueue.first().first <= now) {
                    val (_, expiredValue) = expirationQueue.removeFirst()
                    activeValues.remove(expiredValue)
                }

                // 2. Check if this value is exempt from squashing
                // If the predicate returns false, we pass it through immediately without tracking
                if (!shouldSquash(value)) {
                    emit(value)
                    return@collect
                }

                // 3. Process the value
                if (!activeValues.contains(value)) {
                    // Enforce capacity limit: Evict the oldest entry if full to prevent memory overflow
                    if (activeValues.size >= maxDistinctValues && expirationQueue.isNotEmpty()) {
                        val (_, evictedValue) = expirationQueue.removeFirst()
                        activeValues.remove(evictedValue)
                    }

                    emit(value)

                    // Track the value if we have capacity
                    if (activeValues.size < maxDistinctValues) {
                        activeValues.add(value)
                        val expirationTime = now + retentionPeriod.inWholeNanoseconds
                        expirationQueue.addLast(expirationTime to value)
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
     *
     * **Note:** This is different from `debounce` (which drops items) or `sample` (which picks the latest).
     * [pace] emits *all* items, but spreads them out over time.
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
        return ReactiveFlow(flow {
            // Initialize so the first element emits immediately
            var lastEmissionTime = timeSource() - interval.inWholeNanoseconds - 1

            upstream.collect { value ->
                val now = timeSource()
                val timeSinceLast = (now - lastEmissionTime).toDuration(DurationUnit.NANOSECONDS)

                if (timeSinceLast < interval) {
                    // We are too fast! Wait until the interval has passed.
                    val waitTime = interval - timeSinceLast
                    delay(waitTime)
                }

                emit(value)

                // Update the last emission time to the current time (after the delay)
                lastEmissionTime = timeSource()
            }
        })
    }

    /**
     * Collects incoming values into lists and emits them periodically.
     *
     * This operator groups elements emitted by the upstream flow into a [List] and emits that list
     * every [period]. If the buffer reaches [maxBufferSize] before the time period elapses,
     * the chunk is emitted immediately.
     *
     * **Use Cases:**
     * - Batching database writes to improve transaction performance.
     * - Aggregating log lines before sending them to a remote server.
     *
     * @param period The time duration to wait before emitting the current batch.
     * @param maxBufferSize The maximum number of items to hold in a single batch. Defaults to [Int.MAX_VALUE].
     * @return A [ReactiveFlow] emitting lists of type [T].
     */
    fun chunk(
        period: Duration,
        maxBufferSize: Int = Int.MAX_VALUE
    ): ReactiveFlow<List<T>> {
        return ReactiveFlow(channelFlow {
            val buffer = ArrayList<T>()
            val eventChannel = Channel<Any?>(Channel.UNLIMITED)
            val tickToken = Any()

            val tickerJob = launch {
                try {
                    while (isActive) {
                        delay(period)
                        eventChannel.send(tickToken)
                    }
                } catch (_: ClosedSendChannelException) {
                    // Channel closed by upstream, exit gracefully
                }
            }

            launch {
                try {
                    upstream.collect { value ->
                        eventChannel.send(value)
                    }
                } finally {
                    // Cancel ticker immediately so it doesn't try to send to a closed channel
                    tickerJob.cancelAndJoin()
                    eventChannel.close()
                }
            }

            for (event in eventChannel) {
                if (event === tickToken) {
                    if (buffer.isNotEmpty()) {
                        send(buffer.toList())
                        buffer.clear()
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val item = event as T
                    buffer.add(item)

                    // Safety valve for memory
                    if (buffer.size >= maxBufferSize) {
                        send(buffer.toList())
                        buffer.clear()
                    }
                }
            }

            if (buffer.isNotEmpty()) {
                send(buffer.toList())
            }
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
     *
     * **Use Cases:**
     * - Reading a sensor value only when a user clicks a "Measure" button.
     * - Sampling a high-frequency signal based on a lower-frequency clock.
     *
     * @param trigger The flow that signals when to sample the upstream.
     * @return A [ReactiveFlow] emitting the sampled values.
     */
    fun <R> shutter(trigger: Flow<R>): ReactiveFlow<T> {
        return ReactiveFlow(channelFlow {
            var latestValue: Any? = null
            var hasValue = false

            launch {
                upstream.collect {
                    latestValue = it
                    hasValue = true
                }
            }

            trigger.collect {
                if (hasValue) {
                    @Suppress("UNCHECKED_CAST")
                    send(latestValue as T)
                }
            }
        })
    }

    /**
     * Detects specific sequences of values (combos) emitted within a defined time window.
     *
     * This operator utilizes a Trie-based automaton to efficiently match sequences of inputs against
     * a set of target [sequences].
     *
     * **Timeout Logic:**
     * The detection state resets if the time between two inputs exceeds [timeout].
     *
     * **Greedy vs Non-Greedy:**
     * - **Non-Greedy (`greedy = false`):** The flow emits a match as soon as the shortest valid sequence is completed.
     * - **Greedy (`greedy = true`):** If a match is found, but it is a prefix of a longer potential match,
     *   the flow waits up to [timeout] to see if the user completes the longer sequence.
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
    fun combo(
        sequences: Iterable<Iterable<T>>,
        timeout: Duration,
        greedy: Boolean = false
    ): ReactiveFlow<List<T>> = ReactiveFlow(channelFlow {
        // 1. Build the Automaton (O(N) setup)
        val automaton = buildAutomaton(sequences)

        var currentState = automaton.root
        var lastStepTime = timeSource()

        // For greedy matching
        var pendingMatch: List<T>? = null
        var pendingMatchJob: Job? = null

        upstream.collect { value ->
            val now = timeSource()

            // 2. Timeout Logic
            if ((now - lastStepTime).toDuration(DurationUnit.NANOSECONDS) > timeout) {
                currentState = automaton.root
                pendingMatch = null
                pendingMatchJob?.cancel()
            }
            lastStepTime = now

            // 3. State Transition (O(1))
            currentState = automaton.next(currentState, value)

            // 4. Match Handling
            val matches = currentState.matches
            if (matches.isNotEmpty()) {
                // We only care about the longest match ending here (e.g. "SHE" over "HE")
                val longestMatch = matches.maxByOrNull { it.size }!!

                if (greedy) {
                    // If we can extend further from this node, we wait.
                    // If this node has children, it means a longer combo is theoretically possible.
                    if (currentState.children.isNotEmpty()) {
                        pendingMatchJob?.cancel()
                        pendingMatch = longestMatch

                        // Launch a coroutine to emit this match if no further input comes in time
                        pendingMatchJob = launch {
                            delay(timeout)
                            send(pendingMatch!!)
                            pendingMatch = null
                            // Note: We don't reset state here, allowing the user to continue typing
                            // even after the "partial" greedy match fired.
                        }
                    } else {
                        // Cannot extend further (dead end in Trie), so fire immediately
                        pendingMatchJob?.cancel()
                        pendingMatch = null
                        send(longestMatch)
                        currentState = automaton.root // Reset on final consumption
                    }
                } else {
                    // Non-greedy: Fire immediately
                    send(longestMatch)
                    currentState = automaton.root // Reset on consumption
                }
            } else {
                // No match here.
                // If we were holding a greedy match (e.g. [A, B]) and now typed 'Z' (invalid extension),
                // we should probably fire the pending match because 'Z' broke the chain.
                if (pendingMatch != null) {
                    pendingMatchJob?.cancel()
                    send(pendingMatch!!)
                    pendingMatch = null
                    // We do NOT reset to root here because 'Z' might be the start of a new combo
                    // and the automaton has already transitioned to handle 'Z'.
                }
            }
        }
    })

    /**
     * Automatically retries the upstream flow with exponential backoff if it terminates with an exception.
     *
     * This encapsulates the `retryWhen` logic with a standard exponential backoff algorithm.
     *
     * **Formula:**
     * `delay = initialDelay * (factor ^ attempt)`
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
        return ReactiveFlow(upstream.retryWhen { throwable, attempt ->
            if (attempt < maxRetries && throwable !is CancellationException) {
                delay(initialDelay.inWholeMilliseconds * factor.pow(attempt.toDouble()).toLong())
                true
            } else {
                false
            }
        })
    }

    /**
     * Converts the flow into a hot [kotlinx.coroutines.flow.SharedFlow] that multicasts values to all collectors.
     *
     * This is a convenience wrapper around `shareIn` that simplifies the configuration of the
     * [SharingStarted] strategy.
     *
     * @param scope The [CoroutineScope] in which the shared flow computation is started.
     * @param replay The number of values to replay to new collectors.
     * @param keepAlive The duration to keep the upstream active after the last collector disappears.
     *                  If [Duration.ZERO], it stops immediately (WhileSubscribed).
     * @return A [ReactiveFlow] backed by a SharedFlow.
     */
    fun broadcast(
        scope: CoroutineScope,
        replay: Int = 0,
        keepAlive: Duration = Duration.ZERO
    ): ReactiveFlow<T> {
        val strategy = if (keepAlive == Duration.ZERO) {
            SharingStarted.WhileSubscribed()
        } else {
            SharingStarted.WhileSubscribed(stopTimeoutMillis = keepAlive.inWholeMilliseconds)
        }

        return ReactiveFlow(upstream.shareIn(scope, strategy, replay))
    }

    /**
     * Peeks into the flow lifecycle for debugging purposes without altering the stream.
     *
     * Logs events for: Start, Emission, and Completion (Success or Failure).
     *
     * @param tag A tag to prepend to log messages for identification.
     * @param logger The function used to output the log string. Defaults to [println].
     * @return A [ReactiveFlow] identical to the upstream but with side effect logging.
     */
    fun spy(
        tag: String = "Spy",
        logger: (String) -> Unit = ::println
    ): ReactiveFlow<T> {
        return ReactiveFlow(
            upstream
                .onStart { logger("[$tag] Started") }
                .onEach { logger("[$tag] Emitted: $it") }
                .onCompletion { cause ->
                    if (cause != null) logger("[$tag] Failed: $cause")
                    else logger("[$tag] Completed")
                }
        )
    }

}

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

/**
 * Internal event wrapper used for merging data and control signals in the [ReactiveFlow.gate] operator.
 */
sealed interface Event<out T> {
    data class Data<T>(val value: T) : Event<T>
    data class GateState(val isOpen: Boolean) : Event<Nothing>
    object UpstreamComplete : Event<Nothing>
}

/**
 * Wraps an existing [Flow] into a [ReactiveFlow] to access advanced operators.
 */
fun <T> Flow<T>.asReactive(): ReactiveFlow<T> = ReactiveFlow(this)

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
 * Convenience extension to apply [ReactiveFlow.squash] directly on a standard [Flow].
 *
 * @see ReactiveFlow.squash
 */
fun <T> Flow<T>.squash(
    retentionPeriod: Duration,
    maxDistinctValues: Int = 100,
    shouldSquash: (T) -> Boolean = { true },
    timeSource: () -> Long = { System.nanoTime() }
): ReactiveFlow<T> = ReactiveFlow(this, timeSource).squash(
    retentionPeriod,
    maxDistinctValues,
    shouldSquash
)

/**
 * Convenience extension to apply [ReactiveFlow.pace] directly on a standard [Flow].
 *
 * @see ReactiveFlow.pace
 */
fun <T> Flow<T>.pace(
    interval: Duration,
    timeSource: () -> Long = { System.nanoTime() }
): ReactiveFlow<T> = ReactiveFlow(this, timeSource).pace(interval)

/**
 * Convenience extension to apply [ReactiveFlow.chunk] directly on a standard [Flow].
 *
 * @see ReactiveFlow.chunk
 */
fun <T> Flow<T>.chunk(
    period: Duration
): ReactiveFlow<List<T>> = ReactiveFlow(this).chunk(period)

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
 * @see ReactiveFlow.combo
 */
fun <T> Flow<T>.combo(
    sequences: Iterable<Iterable<T>>,
    interval: Duration,
    greedy: Boolean = false,
    timeSource: () -> Long = { System.nanoTime() }
): ReactiveFlow<List<T>> = ReactiveFlow(this, timeSource).combo(
    sequences,
    interval,
    greedy
)

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