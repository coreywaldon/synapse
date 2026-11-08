package com.synapselib.arch.compat

// Deliberately imports only the *types* (as the demo app does) — the shared
// verbs must resolve as members, without importing `Trigger`, `Broadcast`, etc.
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.synapselib.arch.base.Channel
import com.synapselib.arch.base.ContextScope
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.Direction
import com.synapselib.arch.base.Impulse
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.Node
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private data class Item(val id: Int)
private data class ItemSaved(val item: Item) : Impulse()
private data class ItemsLoaded(val count: Int)
private data class FetchItems(val filter: String) : DataImpulse<List<Item>>()
private data class ListState(val items: DataState<List<Item>> = DataState.Loading)

private class FetchItemsProvider : Provider<FetchItems, List<Item>>() {
    override fun ProviderScope.produce(impulse: FetchItems): Flow<List<Item>> = flow {
        launch { Trigger(ItemSaved(Item(-1))) } // CoroutineScope + Trigger inside produce
        val loaded = ListenFor<ItemsLoaded>().map { it.count }
        emit(List(loaded.first()) { Item(it) })
    }
}

// Mirrors demo/app/.../ui/TaskItem.kt and TaskList.kt — compile-time checks only.
@Suppress("unused")
@Composable
private fun ContextScope<String>.ItemRow(scope: CoroutineScope, item: Item) {
    scope.launch { Trigger(ItemSaved(item)) }
}

@Suppress("unused")
@Composable
private fun ContextScope<String>.ItemList() {
    Node(ListState()) {
        Request(FetchItems(context)) { result -> update { it.copy(items = result) } }
        ReactTo<ItemSaved> { scope.launch { Trigger(it) ; Broadcast(ItemsLoaded(1)) } }
        ListenFor<ItemsLoaded> { }
        Intercept<Item>(InterceptPoint(Channel.STATE, Direction.UPSTREAM), Interceptor.read { })
        val flow: Flow<DataState<List<Item>>> = Request(FetchItems("flow"))
        val impulses = ReactTo<ItemSaved>()
        val states = ListenFor<ItemsLoaded>()
        check(flow !== impulses && impulses !== states)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DslSourceCompatTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun owner(): LifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `demo-style coordinator compiles and runs without verb imports`() = runTest(dispatcher) {
        val switchBoard: SwitchBoard = DefaultSwitchBoard(
            CoroutineScope(dispatcher + SupervisorJob()),
            ProviderRegistry.Builder().register<List<Item>, FetchItems> { FetchItemsProvider() }.build(),
            dispatcher,
        )
        val saved = mutableListOf<Item>()
        val coordinator: CoordinatorScope = Coordinator(switchBoard, owner()) {
            ReactTo<ItemSaved> { launch { saved += it.item } }
            Intercept<Any>(
                InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
                Interceptor.read { },
            )
            onStart { launch { Broadcast(ItemsLoaded(2)) } }
        }

        val result = coordinator.Request(FetchItems("all")).first { it is DataState.Success }
        assertEquals(DataState.Success(listOf(Item(0), Item(1))), result)
        coordinator.Trigger(ItemSaved(Item(7)))
        assertEquals(listOf(Item(-1), Item(7)), saved)
        coordinator.dispose()
    }
}
