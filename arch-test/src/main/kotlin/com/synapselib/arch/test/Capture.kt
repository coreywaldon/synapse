package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * A thread-safe test assertion wrapper that holds the **most recent** value of
 * type [T] observed at one [InterceptPoint] of the harness's SwitchBoard.
 *
 * Created by [SynapseTestHarness.onImpulse], [SynapseTestHarness.onState],
 * [SynapseTestHarness.onRequest], [SynapseTestHarness.onRequestResult] and the
 * generic [SynapseTestHarness.capture].
 *
 * Values may be recorded from any thread (e.g. a provider running on another
 * dispatcher); reads always see the latest recorded value.
 *
 * @param T the type of value being captured.
 * @property point the [InterceptPoint] this capture observes.
 * @property type  the type token values must be assignable to.
 */
class Capture<T : Any> internal constructor(
    val point: InterceptPoint,
    val type: KClass<T>,
) {

    private val latest = AtomicReference<T?>(null)

    /** Records [data] (already known to be a [T]); called by the harness's observer. */
    internal fun record(data: Any) {
        latest.set(type.javaObjectType.cast(data))
    }

    /**
     * Returns the captured value, or throws if nothing was captured.
     *
     * ```kotlin
     * val checkout = synapse.onImpulse<CheckoutRequested>()
     * // ... trigger the impulse ...
     * assertEquals("addr-1", checkout.assertCaptured().addressId)
     * ```
     *
     * @throws AssertionError if no value has been captured.
     */
    fun assertCaptured(): T =
        latest.get() ?: throw AssertionError(
            "Expected a ${type.simpleName ?: type} at ${point.describe()}, but nothing was captured"
        )

    /**
     * Asserts that no value was captured.
     *
     * ```kotlin
     * val expired = synapse.onImpulse<SessionExpired>()
     * // ... perform logout ...
     * expired.assertNotCaptured()
     * ```
     *
     * @throws AssertionError if a value has been captured.
     */
    fun assertNotCaptured() {
        val value = latest.get()
        if (value != null) throw AssertionError(
            "Value was captured unexpectedly at ${point.describe()}: $value"
        )
    }

    /**
     * Returns the captured value, or `null` if nothing was captured.
     * Useful when you need conditional logic rather than a hard assertion.
     */
    val value: T? get() = latest.get()

    /** Forgets the captured value, so the capture can be reused for a later phase of a test. */
    fun clear() {
        latest.set(null)
    }

    override fun toString(): String = "Capture<${type.simpleName}>(${point.describe()}, value=${latest.get()})"
}

/** `REACTION/UPSTREAM`-style label used in assertion messages. */
internal fun InterceptPoint.describe(): String = "$channel/$direction"
