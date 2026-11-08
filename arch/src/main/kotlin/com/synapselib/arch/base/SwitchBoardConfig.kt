package com.synapselib.arch.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Immutable configuration for a [DefaultSwitchBoard].
 *
 * Instances are created through the [SwitchBoardConfig] builder function and
 * are safe to share between boards and threads:
 *
 * ```kotlin
 * // All defaults
 * val board = DefaultSwitchBoard(scope, registry)
 *
 * // Customised
 * val board = DefaultSwitchBoard(scope, registry, SwitchBoardConfig {
 *     stopTimeout = 5.seconds
 *     reactionBufferCapacity = 256
 *     maxConcurrentJobs = 8
 *     workerContext = Dispatchers.Default
 * })
 *
 * // Tests: keep downstream flows hot regardless of subscribers
 * val board = DefaultSwitchBoard(scope, registry, SwitchBoardConfig {
 *     eagerSharing()
 *     workerContext = testDispatcher
 * })
 * ```
 *
 * Derive a variant of an existing configuration with [copy]:
 *
 * ```kotlin
 * val testConfig = productionConfig.copy { eagerSharing() }
 * ```
 *
 * The class deliberately is **not** a `data class`: new options can be added
 * to the [Builder] (with defaults) without breaking source or binary
 * compatibility of existing callers.
 *
 * @property stopTimeout            how long a downstream-intercepted flow keeps
 *                                  its sharing coroutine alive after the last
 *                                  subscriber disappears. Ignored when a custom
 *                                  [sharingStarted] strategy is supplied.
 * @property replayExpiration       how long a downstream-intercepted flow keeps
 *                                  its replay cache after sharing stops. Ignored
 *                                  when a custom [sharingStarted] strategy is
 *                                  supplied.
 * @property sharingStarted         the resolved [SharingStarted] strategy used for
 *                                  every downstream-intercepted flow. Defaults to
 *                                  `WhileSubscribed(stopTimeout, replayExpiration)`.
 * @property reactionBufferCapacity extra buffer capacity of each reaction
 *                                  (impulse) channel, absorbing bursts when
 *                                  collectors are slower than emitters.
 * @property reactionOverflow       what happens when a reaction channel's buffer
 *                                  is full. [BufferOverflow.DROP_OLDEST] (default)
 *                                  never suspends the emitter;
 *                                  [BufferOverflow.SUSPEND] applies back-pressure.
 * @property maxConcurrentJobs      maximum number of simultaneously active
 *                                  provider jobs. `0` means unbounded.
 * @property workerContext          the [CoroutineContext] provider jobs run in.
 */
class SwitchBoardConfig private constructor(builder: Builder) {

    val stopTimeout: Duration = builder.stopTimeout
    val replayExpiration: Duration = builder.replayExpiration
    val sharingStarted: SharingStarted =
        builder.sharingStarted ?: SharingStarted.WhileSubscribed(stopTimeout, replayExpiration)
    val reactionBufferCapacity: Int = builder.reactionBufferCapacity
    val reactionOverflow: BufferOverflow = builder.reactionOverflow
    val maxConcurrentJobs: Int = builder.maxConcurrentJobs
    val workerContext: CoroutineContext = builder.workerContext

    /** The explicitly configured strategy, kept so [newBuilder] round-trips faithfully. */
    private val customSharingStarted: SharingStarted? = builder.sharingStarted

    init {
        require(!stopTimeout.isNegative()) { "stopTimeout must be >= 0, was $stopTimeout" }
        require(!replayExpiration.isNegative()) { "replayExpiration must be >= 0, was $replayExpiration" }
        require(reactionBufferCapacity >= 0) {
            "reactionBufferCapacity must be >= 0, was $reactionBufferCapacity"
        }
        require(reactionBufferCapacity > 0 || reactionOverflow == BufferOverflow.SUSPEND) {
            "reactionBufferCapacity = 0 requires reactionOverflow = SUSPEND " +
                "(a reaction channel without a buffer cannot drop values), was $reactionOverflow"
        }
        require(maxConcurrentJobs >= 0) { "maxConcurrentJobs must be >= 0, was $maxConcurrentJobs" }
    }

    /** Returns a [Builder] pre-populated with this configuration's values. */
    fun newBuilder(): Builder = Builder().also {
        it.stopTimeout = stopTimeout
        it.replayExpiration = replayExpiration
        it.sharingStarted = customSharingStarted
        it.reactionBufferCapacity = reactionBufferCapacity
        it.reactionOverflow = reactionOverflow
        it.maxConcurrentJobs = maxConcurrentJobs
        it.workerContext = workerContext
    }

    /**
     * Returns a new configuration with this one's values, modified by [block].
     *
     * @param block builder mutations to apply on top of the current values.
     * @return the derived configuration; `this` is left unchanged.
     */
    fun copy(block: Builder.() -> Unit): SwitchBoardConfig = newBuilder().apply(block).build()

    override fun equals(other: Any?): Boolean =
        other is SwitchBoardConfig &&
            stopTimeout == other.stopTimeout &&
            replayExpiration == other.replayExpiration &&
            customSharingStarted == other.customSharingStarted &&
            reactionBufferCapacity == other.reactionBufferCapacity &&
            reactionOverflow == other.reactionOverflow &&
            maxConcurrentJobs == other.maxConcurrentJobs &&
            workerContext == other.workerContext

    override fun hashCode(): Int {
        var result = stopTimeout.hashCode()
        result = 31 * result + replayExpiration.hashCode()
        result = 31 * result + (customSharingStarted?.hashCode() ?: 0)
        result = 31 * result + reactionBufferCapacity
        result = 31 * result + reactionOverflow.hashCode()
        result = 31 * result + maxConcurrentJobs
        result = 31 * result + workerContext.hashCode()
        return result
    }

    override fun toString(): String =
        "SwitchBoardConfig(stopTimeout=$stopTimeout, replayExpiration=$replayExpiration, " +
            "sharingStarted=$sharingStarted, reactionBufferCapacity=$reactionBufferCapacity, " +
            "reactionOverflow=$reactionOverflow, maxConcurrentJobs=$maxConcurrentJobs, " +
            "workerContext=$workerContext)"

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Mutable builder for [SwitchBoardConfig]. Every property starts at the
     * library default; validation happens in [build].
     */
    class Builder {
        /** See [SwitchBoardConfig.stopTimeout]. Default: 3 seconds. */
        var stopTimeout: Duration = DEFAULT_STOP_TIMEOUT

        /** See [SwitchBoardConfig.replayExpiration]. Default: 3 seconds. */
        var replayExpiration: Duration = DEFAULT_REPLAY_EXPIRATION

        /**
         * Overrides the sharing strategy of downstream-intercepted flows.
         * `null` (default) derives `WhileSubscribed(stopTimeout, replayExpiration)`.
         */
        var sharingStarted: SharingStarted? = null

        /** See [SwitchBoardConfig.reactionBufferCapacity]. Default: 64. */
        var reactionBufferCapacity: Int = DEFAULT_REACTION_BUFFER_CAPACITY

        /** See [SwitchBoardConfig.reactionOverflow]. Default: [BufferOverflow.DROP_OLDEST]. */
        var reactionOverflow: BufferOverflow = BufferOverflow.DROP_OLDEST

        /** See [SwitchBoardConfig.maxConcurrentJobs]. Default: `0` (unbounded). */
        var maxConcurrentJobs: Int = 0

        /** See [SwitchBoardConfig.workerContext]. Default: [Dispatchers.IO]. */
        var workerContext: CoroutineContext = Dispatchers.IO

        /**
         * Shares every downstream-intercepted flow with [SharingStarted.Eagerly]:
         * the sharing coroutine starts immediately and never stops while the
         * board is open. Mostly useful in tests, where it removes timing
         * dependencies on subscriber counts.
         */
        fun eagerSharing() {
            sharingStarted = SharingStarted.Eagerly
        }

        /**
         * Validates the values and creates an immutable [SwitchBoardConfig].
         *
         * @throws IllegalArgumentException if any value is out of range.
         */
        fun build(): SwitchBoardConfig = SwitchBoardConfig(this)
    }

    companion object {
        /** Default [stopTimeout]. */
        val DEFAULT_STOP_TIMEOUT: Duration = 3.seconds

        /** Default [replayExpiration]. */
        val DEFAULT_REPLAY_EXPIRATION: Duration = 3.seconds

        /** Default [reactionBufferCapacity]. */
        const val DEFAULT_REACTION_BUFFER_CAPACITY: Int = 64

        /** A configuration with every option at its default value. */
        @JvmField
        val DEFAULT: SwitchBoardConfig = Builder().build()
    }
}

/**
 * Creates a [SwitchBoardConfig], starting from defaults and applying [block].
 *
 * `SwitchBoardConfig()` (no block) yields a configuration equal to
 * [SwitchBoardConfig.DEFAULT].
 *
 * @param block builder mutations to apply.
 * @return the validated, immutable configuration.
 * @throws IllegalArgumentException if any value is out of range.
 */
fun SwitchBoardConfig(block: SwitchBoardConfig.Builder.() -> Unit = {}): SwitchBoardConfig =
    SwitchBoardConfig.Builder().apply(block).build()
