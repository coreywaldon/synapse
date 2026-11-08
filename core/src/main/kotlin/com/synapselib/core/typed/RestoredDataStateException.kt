package com.synapselib.core.typed

/**
 * The [cause][DataState.Error.cause] of a [DataState.Error] restored by [DataStateSerializer].
 *
 * A [Throwable] cannot be serialized, so saving a `DataState.Error` keeps only its cause's class
 * name and message; on restore they come back here. Its [message] is the original cause's message,
 * so UI that shows `error.cause.message` renders the same text after a configuration change or
 * process death. The original exception's identity, type and stack trace are **not** preserved —
 * do not branch on `cause is IOException` for restored state; compare [originalType] if you must.
 * A live `Request` re-emits the real state once it runs again, replacing the restored error.
 *
 * Two instances are equal when their [originalType] and [message] are, so a restored
 * `DataState.Error` compares equal to the same error restored again.
 *
 * @property originalType the JVM class name of the original cause (e.g. `"java.io.IOException"`),
 *                        or `null` if it was not recorded.
 * @param message         the original cause's [Throwable.message].
 */
class RestoredDataStateException(
    val originalType: String?,
    message: String?,
) : RuntimeException(message) {

    override fun equals(other: Any?): Boolean =
        other is RestoredDataStateException && originalType == other.originalType && message == other.message

    override fun hashCode(): Int = 31 * (originalType?.hashCode() ?: 0) + (message?.hashCode() ?: 0)

    override fun toString(): String = buildString {
        append(RestoredDataStateException::class.java.name)
        append("(originalType=").append(originalType).append(')')
        message?.let { append(": ").append(it) }
    }
}
