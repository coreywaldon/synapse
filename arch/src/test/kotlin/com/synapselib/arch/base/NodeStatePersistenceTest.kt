package com.synapselib.arch.base

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.Snapshot
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import com.synapselib.core.typed.RestoredDataStateException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

// ── Fixtures: the documented Node-state pattern ─────────────────────────
//
// A `@Serializable` state class whose fetched data stays wrapped in `DataState`, exactly as
// arch/README.md recommends. Before DataState was serializable this compiled fine (the plugin
// treated the sealed interface as polymorphic) but crashed with "Serializer for subclass
// 'Success' is not found in the polymorphic scope of 'DataState'" as soon as the Activity saved
// its state.

@Serializable
data class PersistedProduct(val id: Int, val name: String)

@Serializable
data class ProductListState(
    val products: DataState<List<PersistedProduct>> = DataState.Idle,
    val searchQuery: String = "",
)

data class FetchPersistedProducts(val filter: String) : DataImpulse<List<PersistedProduct>>()

private val catalog = listOf(PersistedProduct(1, "Lamp"), PersistedProduct(2, "Desk"))

/** No-op applier: these tests only exercise composition, effects and state. */
private class PersistenceNoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}

/**
 * Drives a headless composition on the JVM under a [SaveableStateRegistry], standing in for the
 * Activity's saved-instance-state registry: [save] is what runs on rotation / backgrounding.
 */
private class SaveableHarness(scope: CoroutineScope, restored: Map<String, List<Any?>>?) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val loop = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    private val composition = Composition(PersistenceNoOpApplier(), recomposer)

    // Android would also check `canBeSavedToBundle`; the Saver output is a String, so it always can.
    val registry: SaveableStateRegistry = SaveableStateRegistry(restored) { true }

    /**
     * Composes [content] under [registry]. `rememberSaveable` keys values by the composite key of
     * their position, which includes the source location of every composable lambda on the way —
     * so a save and its restore must compose through the same call sites ([showRememberedState],
     * [showProductListNode]), never through two different lambdas written in the test.
     */
    private fun setContent(content: @Composable () -> Unit) = composition.setContent {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() }
    }

    fun showRememberedState(onState: (MutableState<ProductListState>) -> Unit) =
        setContent { RememberedProductState(onState) }

    fun showProductListNode(
        context: ContextScope<String>,
        onComposed: (NodeScope<String, ProductListState>) -> Unit,
    ) = setContent { ProductListNode(context, onComposed) }

    /** What the Activity runs on rotation / backgrounding: invokes every registered Saver. */
    fun save(): Map<String, List<Any?>> {
        Snapshot.sendApplyNotifications()
        return registry.performSave().also { assertTrue(it.isNotEmpty(), "rememberSaveable registered the state") }
    }

    fun dispose() {
        try {
            composition.dispose()
        } finally {
            recomposer.cancel()
            loop.cancel()
        }
    }
}

/** Calls [rememberState] from a single source location, as a screen composable would. */
@Composable
private fun RememberedProductState(onState: (MutableState<ProductListState>) -> Unit) {
    onState(rememberState(ProductListState()))
}

/** The documented pattern end to end: a `Node` whose `Request` writes `DataState` into its state. */
@Composable
private fun ProductListNode(
    context: ContextScope<String>,
    onComposed: (NodeScope<String, ProductListState>) -> Unit,
) {
    context.Node(initialState = ProductListState(searchQuery = "lamp")) {
        onComposed(this)
        Request(FetchPersistedProducts("popular")) { result -> update { it.copy(products = result) } }
    }
}

/**
 * Regression tests for saving `@Serializable` Node state that holds a [DataState].
 *
 * `rememberState` saves state through a `Saver` that runs `Json.encodeToString(serializer, state)`;
 * these tests run that logic directly and through a real `rememberSaveable` + [SaveableStateRegistry].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeStatePersistenceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stateSerializer(): KSerializer<ProductListState> {
        val ser = serializerOrNull<ProductListState>()
        assertNotNull(ser, "a @Serializable state class must get a Saver")
        return ser!!
    }

    /** Exactly the Saver `rememberState` builds: `save` then `restore`. */
    private fun saveAndRestore(state: ProductListState): ProductListState {
        val ser = stateSerializer()
        val saved: String = Json.encodeToString(ser, state)
        return Json.decodeFromString(ser, saved)
    }

    // ── The Saver logic used by rememberState ──────────────────────────

    @Test
    fun `serializerOrNull finds a serializer for state holding DataState`() {
        stateSerializer()
    }

    @Test
    fun `Saver round-trips Success state`() {
        val state = ProductListState(products = DataState.Success(catalog), searchQuery = "la")
        assertEquals(state, saveAndRestore(state))
    }

    @Test
    fun `Saver round-trips Loading and Idle as the same singletons`() {
        assertSame(DataState.Loading, saveAndRestore(ProductListState(products = DataState.Loading)).products)
        assertSame(DataState.Idle, saveAndRestore(ProductListState(products = DataState.Idle)).products)
    }

    @Test
    fun `Saver round-trips Error with its message and stale data`() {
        val state = ProductListState(products = DataState.Error(IOException("network down"), staleData = catalog))

        val restored = assertInstanceOf(DataState.Error::class.java, saveAndRestore(state).products)

        // The Throwable itself cannot be saved: it comes back as a stand-in with its type and message.
        val cause = assertInstanceOf(RestoredDataStateException::class.java, restored.cause)
        assertEquals("java.io.IOException", cause.originalType)
        assertEquals("network down", cause.message)
        assertEquals(catalog, restored.staleData)
    }

    // ── Through rememberSaveable (what the Activity actually runs) ─────

    @Test
    fun `rememberState saves and restores Success through the saveable registry`() = testScope.runTest {
        lateinit var holder: MutableState<ProductListState>
        val first = SaveableHarness(this, restored = null)
        first.showRememberedState { holder = it }
        holder.value = ProductListState(products = DataState.Success(catalog), searchQuery = "desk")

        val saved = first.save() // threw SerializationException before the fix
        first.dispose()

        lateinit var restored: MutableState<ProductListState>
        val second = SaveableHarness(this, restored = saved)
        second.showRememberedState { restored = it }

        assertEquals(ProductListState(products = DataState.Success(catalog), searchQuery = "desk"), restored.value)
        second.dispose()
    }

    @Test
    fun `rememberState saves and restores Error through the saveable registry`() = testScope.runTest {
        lateinit var holder: MutableState<ProductListState>
        val first = SaveableHarness(this, restored = null)
        first.showRememberedState { holder = it }
        holder.value = ProductListState(products = DataState.Error(IOException("offline"), staleData = catalog))

        val saved = first.save()
        first.dispose()

        lateinit var restored: MutableState<ProductListState>
        val second = SaveableHarness(this, restored = saved)
        second.showRememberedState { restored = it }

        val error = assertInstanceOf(DataState.Error::class.java, restored.value.products)
        assertEquals("offline", error.cause.message)
        assertEquals(catalog, error.staleData)
        second.dispose()
    }

    @Test
    fun `Node with a Request result survives save and restore`() = testScope.runTest {
        val registry = ProviderRegistry.Builder()
            .register<List<PersistedProduct>, FetchPersistedProducts> {
                object : Provider<FetchPersistedProducts, List<PersistedProduct>>() {
                    override fun ProviderScope.produce(impulse: FetchPersistedProducts): Flow<List<PersistedProduct>> =
                        flow { emit(catalog) }
                }
            }
            .build()
        val board = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = registry,
            workerContext = testDispatcher,
        )
        val context = ContextScope("ctx", board, CoroutineScope(testDispatcher + SupervisorJob()))

        lateinit var node: NodeScope<String, ProductListState>
        val first = SaveableHarness(this, restored = null)
        first.showProductListNode(context) { node = it }
        testScheduler.advanceUntilIdle()
        assertEquals(DataState.Success(catalog), node.state.products, "the Request result reached the Node state")

        val saved = first.save() // the Activity's onSaveInstanceState — crashed here before the fix
        first.dispose()

        // Recreate: the first composition reads the restored state before the Request re-runs.
        val composedStates = mutableListOf<ProductListState>()
        val second = SaveableHarness(this, restored = saved)
        second.showProductListNode(context) { composedStates += it.state }

        assertEquals(ProductListState(products = DataState.Success(catalog), searchQuery = "lamp"), composedStates.first())
        second.dispose()
        board.close()
    }
}
