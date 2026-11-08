package com.synapselib.core.typed

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.reflect.typeOf

// ── Fixtures: classes compiled by the serialization plugin, as in an app ──

@Serializable
data class Item(val id: Int, val label: String)

@Serializable
data class Page<T>(val items: List<T>, val next: String? = null)

/** Every shape of DataState property a screen state might hold. */
@Serializable
data class ScreenState(
    val list: DataState<List<Item>> = DataState.Idle,
    val single: DataState<Item> = DataState.Loading,
    val optional: DataState<Item>? = null,
    val page: DataState<Page<Item>> = DataState.Idle,
    val grouped: DataState<Map<String, List<Item>>> = DataState.Idle,
    val count: DataState<Int> = DataState.Idle,
    val query: String = "",
)

/** A nullable DataState whose default is not null, so an explicit null is encoded. */
@Serializable
data class NullableFirst(val state: DataState<Item>? = DataState.Idle)

/** A generic holder: the plugin passes the type-argument serializer through to DataStateSerializer. */
@Serializable
data class Holder<T : Any>(val state: DataState<T>)

class DataStateSerializationTest {

    private val json = Json

    private val lamp = Item(1, "Lamp")
    private val desk = Item(2, "Desk")
    private val items = listOf(lamp, desk)

    private inline fun <reified S> roundTrip(value: S): S =
        json.decodeFromString<S>(json.encodeToString<S>(value))

    // ── Success / Idle / Loading inside a plugin-compiled class ─────────

    @Test
    fun `Success round-trips in every property shape`() {
        val state = ScreenState(
            list = DataState.Success(items),
            single = DataState.Success(lamp),
            optional = DataState.Success(desk),
            page = DataState.Success(Page(items, next = "cursor-2")),
            grouped = DataState.Success(mapOf("a" to listOf(lamp), "b" to emptyList())),
            count = DataState.Success(42),
            query = "la",
        )

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `Idle and Loading decode to the same singletons`() {
        val state = ScreenState(
            list = DataState.Loading,
            single = DataState.Idle,
            optional = DataState.Loading,
            page = DataState.Loading,
            grouped = DataState.Loading,
            count = DataState.Loading,
        )

        val restored = roundTrip(state)

        assertSame(DataState.Loading, restored.list)
        assertSame(DataState.Idle, restored.single)
        assertSame(DataState.Loading, restored.optional)
        assertSame(DataState.Loading, restored.page)
        assertSame(DataState.Loading, restored.grouped)
        assertSame(DataState.Loading, restored.count)
    }

    @Test
    fun `default and null values are omitted and restored`() {
        val encoded = json.encodeToString(ScreenState())

        assertEquals("{}", encoded)
        val restored = json.decodeFromString<ScreenState>(encoded)
        assertEquals(ScreenState(), restored)
        assertSame(DataState.Idle, restored.list)
        assertSame(DataState.Loading, restored.single)
        assertNull(restored.optional)
    }

    @Test
    fun `nullable DataState property round-trips null when it is not the default`() {
        assertEquals(NullableFirst(null), roundTrip(NullableFirst(null)))
        assertEquals(NullableFirst(DataState.Success(lamp)), roundTrip(NullableFirst(DataState.Success(lamp))))
    }

    @Test
    fun `generic holder round-trips through the type-argument serializer`() {
        val holder = Holder(DataState.Success(Page(listOf(lamp))))
        assertEquals(holder, roundTrip(holder))

        val listHolder = Holder<List<Item>>(DataState.Loading)
        assertSame(DataState.Loading, roundTrip(listHolder).state)
    }

    // ── Error ───────────────────────────────────────────────────────────

    @Test
    fun `Error with staleData restores message, original type and stale data`() {
        val state = ScreenState(list = DataState.Error(IOException("network down"), staleData = items))

        val restored = assertInstanceOf(DataState.Error::class.java, roundTrip(state).list)

        val cause = assertInstanceOf(RestoredDataStateException::class.java, restored.cause)
        assertEquals("java.io.IOException", cause.originalType)
        assertEquals("network down", cause.message)
        assertEquals(items, restored.staleData)
    }

    @Test
    fun `Error without staleData restores a null staleData`() {
        val state = ScreenState(single = DataState.Error(IllegalStateException("bad")))

        val restored = assertInstanceOf(DataState.Error::class.java, roundTrip(state).single)

        assertEquals(RestoredDataStateException("java.lang.IllegalStateException", "bad"), restored.cause)
        assertNull(restored.staleData)
    }

    @Test
    fun `Error with a null message restores a null message`() {
        val state = ScreenState(count = DataState.Error(RuntimeException(), staleData = 7))

        val restored = assertInstanceOf(DataState.Error::class.java, roundTrip(state).count)

        val cause = assertInstanceOf(RestoredDataStateException::class.java, restored.cause)
        assertEquals("java.lang.RuntimeException", cause.originalType)
        assertNull(cause.message)
        assertEquals(7, restored.staleData)
    }

    @Test
    fun `Error in nested generic and nullable properties round-trips`() {
        val state = ScreenState(
            optional = DataState.Error(IOException("x"), staleData = desk),
            page = DataState.Error(IOException("y"), staleData = Page(items, "n")),
            grouped = DataState.Error(IOException("z"), staleData = mapOf("k" to items)),
        )

        val restored = roundTrip(state)

        assertEquals(DataState.Error(RestoredDataStateException("java.io.IOException", "x"), desk), restored.optional)
        assertEquals(DataState.Error(RestoredDataStateException("java.io.IOException", "y"), Page(items, "n")), restored.page)
        assertEquals(DataState.Error(RestoredDataStateException("java.io.IOException", "z"), mapOf("k" to items)), restored.grouped)
    }

    @Test
    fun `restored Error is stable across repeated save and restore`() {
        val once = roundTrip(ScreenState(list = DataState.Error(IOException("down"), staleData = items)))
        val twice = roundTrip(once)

        assertEquals(once, twice)
        val cause = (twice.list as DataState.Error).cause as RestoredDataStateException
        assertEquals("java.io.IOException", cause.originalType, "re-saving keeps the original type")
    }

    @Test
    fun `restored cause does not preserve the original exception identity`() {
        val original = IOException("down")
        val restored = roundTrip(ScreenState(single = DataState.Error(original))).single as DataState.Error

        assertNotEquals(DataState.Error<Item>(original), restored)
        assertTrue(restored.cause !is IOException)
    }

    // ── Wire format ─────────────────────────────────────────────────────

    @Test
    fun `encodes a small discriminated structure`() {
        val ser = DataStateSerializer(Item.serializer())

        assertEquals("""{"state":"idle"}""", json.encodeToString(ser, DataState.Idle))
        assertEquals("""{"state":"loading"}""", json.encodeToString(ser, DataState.Loading))
        assertEquals(
            """{"state":"success","data":{"id":1,"label":"Lamp"}}""",
            json.encodeToString(ser, DataState.Success(lamp)),
        )
        assertEquals(
            """{"state":"error","message":"timeout","type":"java.io.IOException","staleData":{"id":2,"label":"Desk"}}""",
            json.encodeToString(ser, DataState.Error(IOException("timeout"), staleData = desk)),
        )
        assertEquals(
            """{"state":"error","type":"java.lang.RuntimeException"}""",
            json.encodeToString(ser, DataState.Error(RuntimeException())),
        )
        assertEquals(
            """{"state":"error"}""",
            json.encodeToString(ser, DataState.Error(RestoredDataStateException(null, null))),
        )
    }

    @Test
    fun `decodes elements in any order`() {
        val ser = DataStateSerializer(Item.serializer())

        assertEquals(DataState.Success(lamp), json.decodeFromString(ser, """{"data":{"id":1,"label":"Lamp"},"state":"success"}"""))
        assertEquals(
            DataState.Error(RestoredDataStateException("java.io.IOException", "m"), desk),
            json.decodeFromString(ser, """{"staleData":{"id":2,"label":"Desk"},"type":"java.io.IOException","message":"m","state":"error"}"""),
        )
    }

    @Test
    fun `rejects a missing or unknown state and a success without data`() {
        val ser = DataStateSerializer(Item.serializer())

        assertThrows(SerializationException::class.java) { json.decodeFromString(ser, """{}""") }
        assertThrows(SerializationException::class.java) { json.decodeFromString(ser, """{"state":"done"}""") }
        assertThrows(SerializationException::class.java) { json.decodeFromString(ser, """{"state":"success"}""") }
    }

    // ── Serializer lookup ───────────────────────────────────────────────

    @Test
    fun `runtime serializer lookup works for the state class and for DataState itself`() {
        // Reflective lookup by KType — what `serializer<S>()` resolves to when the call is inlined
        // from a module compiled without the plugin (arch's rememberState).
        @Suppress("UNCHECKED_CAST")
        val stateSer = serializer(typeOf<ScreenState>()) as KSerializer<ScreenState>
        val state = ScreenState(list = DataState.Success(items), single = DataState.Error(IOException("e")))
        val restored = json.decodeFromString(stateSer, json.encodeToString(stateSer, state))
        assertEquals(state.list, restored.list)
        assertEquals(DataState.Error<Item>(RestoredDataStateException("java.io.IOException", "e")), restored.single)

        @Suppress("UNCHECKED_CAST")
        val dataSer = serializer(typeOf<DataState<List<Item>>>()) as KSerializer<DataState<List<Item>>>
        assertEquals(DataState.Success(items), json.decodeFromString(dataSer, json.encodeToString(dataSer, DataState.Success(items))))

        // The plugin-generated companion on DataState.
        val viaCompanion = DataState.serializer(ListSerializer(Item.serializer()))
        assertEquals("""{"state":"success","data":[{"id":1,"label":"Lamp"}]}""", json.encodeToString(viaCompanion, DataState.Success(listOf(lamp))))
    }

    @Test
    fun `reified serializer for DataState encodes like DataStateSerializer`() {
        val value: DataState<Item> = DataState.Success(lamp)

        assertEquals(json.encodeToString(DataStateSerializer(Item.serializer()), value), json.encodeToString(value))
        assertEquals(value, json.decodeFromString<DataState<Item>>(json.encodeToString(value)))
    }

    // ── Unchanged semantics ─────────────────────────────────────────────

    @Test
    fun `equality and helpers are unchanged`() {
        val cause = IOException("same")

        assertEquals(DataState.Success(lamp), DataState.Success(lamp))
        assertEquals(DataState.Error(cause, lamp), DataState.Error(cause, lamp))
        assertNotEquals(DataState.Error<Item>(IOException("a")), DataState.Error<Item>(IOException("a")))
        assertEquals(lamp, DataState.Success(lamp).dataOrNull)
        val loading: DataState<Item> = DataState.Loading
        assertNull(loading.dataOrNull)
        assertTrue(DataState.Loading.isLoading)
    }

    @Test
    fun `RestoredDataStateException equality and toString`() {
        val a = RestoredDataStateException("java.io.IOException", "m")

        assertEquals(a, RestoredDataStateException("java.io.IOException", "m"))
        assertEquals(a.hashCode(), RestoredDataStateException("java.io.IOException", "m").hashCode())
        assertNotEquals(a, RestoredDataStateException("java.io.IOException", "other"))
        assertNotEquals(a, RestoredDataStateException(null, "m"))
        assertEquals(
            "com.synapselib.core.typed.RestoredDataStateException(originalType=java.io.IOException): m",
            a.toString(),
        )
        assertEquals(
            "com.synapselib.core.typed.RestoredDataStateException(originalType=null)",
            RestoredDataStateException(null, null).toString(),
        )
    }
}
