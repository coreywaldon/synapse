package com.synapselib.core.typed

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/**
 * The [KSerializer] behind `@Serializable` [DataState].
 *
 * You rarely reference it directly: the kotlinx.serialization plugin picks it up for any
 * `DataState<T>` property of a `@Serializable` class and constructs it with the serializer of `T`
 * (`DataStateSerializer(ListSerializer(Product.serializer()))` for a
 * `DataState<List<Product>>`). Construct it yourself only to serialize a `DataState` outside such
 * a class, e.g. `Json.encodeToString(DataStateSerializer(Product.serializer()), state)`.
 *
 * ## Encoding
 *
 * A structure discriminated by its `state` element; the other elements are written only when
 * they apply (shown as JSON):
 *
 * | Variant              | Encoded as                                                                 |
 * |----------------------|----------------------------------------------------------------------------|
 * | [DataState.Idle]     | `{"state":"idle"}`                                                         |
 * | [DataState.Loading]  | `{"state":"loading"}`                                                      |
 * | [DataState.Success]  | `{"state":"success","data":<T>}`                                           |
 * | [DataState.Error]    | `{"state":"error","message":"…","type":"java.io.IOException","staleData":<T>}` |
 *
 * For an error, `message` is the cause's [Throwable.message] and `type` its JVM class name; both
 * are omitted when `null`, as is `staleData`.
 *
 * ## Decoding
 *
 * - `idle` / `loading` decode to the [DataState.Idle] / [DataState.Loading] singletons.
 * - `error` decodes to a [DataState.Error] whose [cause][DataState.Error.cause] is a
 *   [RestoredDataStateException] carrying the saved `type` and `message`, with
 *   [staleData][DataState.Error.staleData] restored. The original [Throwable]'s identity, class and
 *   stack trace are **not** preserved. Re-encoding a restored error writes the original `type`,
 *   not `RestoredDataStateException`, so repeated save/restore cycles are stable.
 * - Elements may arrive in any order. A missing or unknown `state`, or a `success` without `data`,
 *   fails with a [SerializationException].
 *
 * @param T the type of the fetched data.
 * @param dataSerializer the serializer for `T`, used for both `data` and `staleData`.
 */
class DataStateSerializer<T : Any>(
    private val dataSerializer: KSerializer<T>,
) : KSerializer<DataState<T>> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(SERIAL_NAME, dataSerializer.descriptor) {
            element(STATE_NAME, String.serializer().descriptor)
            element(DATA_NAME, dataSerializer.descriptor, isOptional = true)
            element(MESSAGE_NAME, String.serializer().descriptor, isOptional = true)
            element(TYPE_NAME, String.serializer().descriptor, isOptional = true)
            element(STALE_DATA_NAME, dataSerializer.descriptor, isOptional = true)
        }

    // ── Encoding ────────────────────────────────────────────────────────

    override fun serialize(encoder: Encoder, value: DataState<T>) {
        encoder.encodeStructure(descriptor) {
            when (value) {
                is DataState.Idle -> encodeStringElement(descriptor, STATE, IDLE)
                is DataState.Loading -> encodeStringElement(descriptor, STATE, LOADING)
                is DataState.Success -> {
                    encodeStringElement(descriptor, STATE, SUCCESS)
                    encodeSerializableElement(descriptor, DATA, dataSerializer, value.data)
                }
                is DataState.Error -> {
                    encodeStringElement(descriptor, STATE, ERROR)
                    val cause = value.cause
                    // A restored error re-saves what it was restored from, not its own class name.
                    val type = if (cause is RestoredDataStateException) cause.originalType else cause.javaClass.name
                    cause.message?.let { encodeStringElement(descriptor, MESSAGE, it) }
                    type?.let { encodeStringElement(descriptor, TYPE, it) }
                    value.staleData?.let { encodeSerializableElement(descriptor, STALE_DATA, dataSerializer, it) }
                }
            }
        }
    }

    // ── Decoding ────────────────────────────────────────────────────────

    override fun deserialize(decoder: Decoder): DataState<T> = decoder.decodeStructure(descriptor) {
        var state: String? = null
        var data: T? = null
        var message: String? = null
        var type: String? = null
        var staleData: T? = null

        // `data` decodes independently of `state`, so element order does not matter.
        while (true) {
            when (val index = decodeElementIndex(descriptor)) {
                STATE -> state = decodeStringElement(descriptor, STATE)
                DATA -> data = decodeSerializableElement(descriptor, DATA, dataSerializer)
                MESSAGE -> message = decodeStringElement(descriptor, MESSAGE)
                TYPE -> type = decodeStringElement(descriptor, TYPE)
                STALE_DATA -> staleData = decodeSerializableElement(descriptor, STALE_DATA, dataSerializer)
                CompositeDecoder.DECODE_DONE -> break
                else -> throw SerializationException("Unexpected element index $index in $SERIAL_NAME")
            }
        }

        when (state) {
            IDLE -> DataState.Idle
            LOADING -> DataState.Loading
            SUCCESS -> DataState.Success(
                data ?: throw SerializationException("$SERIAL_NAME with state '$SUCCESS' is missing '$DATA_NAME'"),
            )
            ERROR -> DataState.Error(RestoredDataStateException(type, message), staleData)
            null -> throw SerializationException("$SERIAL_NAME is missing '$STATE_NAME'")
            else -> throw SerializationException(
                "Unknown $SERIAL_NAME state '$state'; expected one of '$IDLE', '$LOADING', '$SUCCESS', '$ERROR'",
            )
        }
    }
}

// ── Wire format ─────────────────────────────────────────────────────────

private const val SERIAL_NAME = "com.synapselib.core.typed.DataState"

// Element indices, in descriptor order.
private const val STATE = 0
private const val DATA = 1
private const val MESSAGE = 2
private const val TYPE = 3
private const val STALE_DATA = 4

private const val STATE_NAME = "state"
private const val DATA_NAME = "data"
private const val MESSAGE_NAME = "message"
private const val TYPE_NAME = "type"
private const val STALE_DATA_NAME = "staleData"

// `state` values.
private const val IDLE = "idle"
private const val LOADING = "loading"
private const val SUCCESS = "success"
private const val ERROR = "error"
