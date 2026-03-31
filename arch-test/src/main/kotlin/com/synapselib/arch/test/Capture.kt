package com.synapselib.arch.test

/**
 * A lightweight test assertion wrapper that holds the most recent value
 * captured by an interceptor.
 *
 * Created by [SynapseTestRule.onImpulse] and [SynapseTestRule.onState].
 *
 * @param T the type of value being captured.
 */
class Capture<T : Any> @PublishedApi internal constructor(private val get: () -> T?) {

    /**
     * Returns the captured value, or throws if nothing was captured.
     *
     * ```kotlin
     * val checkout = synapse.onImpulse<CheckoutRequested>()
     * // ... trigger the impulse ...
     * assertEquals("addr-1", checkout.assertCaptured().addressId)
     * ```
     */
    fun assertCaptured(): T =
        get() ?: throw AssertionError("Expected value was not captured")

    /**
     * Asserts that no value was captured.
     *
     * ```kotlin
     * val expired = synapse.onImpulse<SessionExpired>()
     * // ... perform logout ...
     * expired.assertNotCaptured()
     * ```
     */
    fun assertNotCaptured() {
        val value = get()
        if (value != null) throw AssertionError(
            "Value was captured unexpectedly: $value"
        )
    }

    /**
     * Returns the captured value, or `null` if nothing was captured.
     * Useful when you need conditional logic rather than a hard assertion.
     */
    val value: T? get() = get()
}
