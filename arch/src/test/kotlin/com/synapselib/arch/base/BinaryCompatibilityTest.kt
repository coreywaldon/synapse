package com.synapselib.arch.base

import androidx.compose.runtime.Composer
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.synapselib.arch.base.provider.ProviderScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation

/**
 * Guards the **binary** (JVM) API that code compiled against the published
 * 1.0.13 (commit `70f8a77`) links against.
 *
 * Kotlin `inline` functions are copied into the caller's bytecode, so the
 * `@PublishedApi internal` members their bodies touch are part of the ABI under
 * their *unmangled* JVM names. Making such a member plain `internal` (mangled to
 * `getX$arch()`) or removing a constructor overload compiles fine here but makes
 * a library precompiled against 1.0.x fail with `NoSuchMethodError` at runtime —
 * e.g. the 1.0.x inline `CoordinatorScope.Intercept` calls
 * `getRegistrations().add(registration)`.
 *
 * The signature checks below were derived from `javap -p` of the 1.0.13 jar.
 * The behavioral checks invoke the ABI-only members **reflectively, by their
 * 1.0.x JVM names**, i.e. exactly as precompiled consumer bytecode does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BinaryCompatibilityTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var switchBoard: DefaultSwitchBoard

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        switchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ResumedOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Asserts a public method [name]([params]) returning [returns] is resolvable on [owner]
     * (including inherited members — the same lookup the JVM performs when linking).
     * Reified-inline and hidden members are ACC_SYNTHETIC, as they were in 1.0.x, so
     * synthetic-ness is deliberately not checked.
     */
    private fun assertMethod(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>): Method {
        val method = owner.getMethod(name, *params)
        assertEquals(returns, method.returnType, "$owner.$name return type")
        assertTrue(Modifier.isPublic(method.modifiers), "$owner.$name must be public on the JVM")
        return method
    }

    private fun assertStatic(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>) {
        val method = assertMethod(owner, name, returns, *params)
        assertTrue(Modifier.isStatic(method.modifiers), "$owner.$name must be static")
    }

    private fun assertConstructor(owner: Class<*>, vararg params: Class<*>) {
        val ctor = owner.getConstructor(*params)
        assertTrue(Modifier.isPublic(ctor.modifiers), "$owner(${params.joinToString { it.simpleName }}) must be public")
    }

    private val int = Int::class.javaPrimitiveType!!
    private val void = Void.TYPE
    private val obj = Any::class.java
    private val fn0 = Function0::class.java
    private val fn1 = Function1::class.java
    private val fn2 = Function2::class.java
    private val fn3 = Function3::class.java
    private val list = java.util.List::class.java
    private val map = java.util.Map::class.java
    private val string = String::class.java
    private val cont = Continuation::class.java
    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    // ── CoordinatorScope / Coordinator ──────────────────────────────────

    @Test
    fun `CoordinatorScope keeps its 1_0 JVM signatures`() {
        val c = CoordinatorScope::class.java
        assertConstructor(c, SwitchBoard::class.java, LifecycleOwner::class.java, string)
        assertConstructor(c, SwitchBoard::class.java, LifecycleOwner::class.java, string, int, marker)

        // @PublishedApi in 1.0.x; referenced by the old inline Intercept body.
        assertMethod(c, "getRegistrations", list)
        assertMethod(c, "getLifecycleRegistrations", map)
        assertMethod(c, "addLifecycleEventRegistration", void, Lifecycle.Event::class.java, fn0)

        assertMethod(c, "getSwitchboard", SwitchBoard::class.java)
        assertMethod(c, "getTag", string)
        assertMethod(c, "Intercept", Registration::class.java, InterceptPoint::class.java, Interceptor::class.java, int)
        assertStatic(c, "Intercept\$default", Registration::class.java, c, InterceptPoint::class.java, Interceptor::class.java, int, int, obj)
        assertMethod(c, "Broadcast", obj, obj, cont)
        assertMethod(c, "Trigger", obj, Impulse::class.java, cont)
        assertMethod(c, "ListenFor", kotlinx.coroutines.flow.SharedFlow::class.java)
        assertMethod(c, "ReactTo", kotlinx.coroutines.flow.SharedFlow::class.java)
        assertMethod(c, "Request", kotlinx.coroutines.flow.Flow::class.java, DataImpulse::class.java)
        assertMethod(c, "ListenFor", kotlinx.coroutines.Job::class.java, fn2)
        assertMethod(c, "ReactTo", kotlinx.coroutines.Job::class.java, fn2)
        assertMethod(c, "Request", kotlinx.coroutines.Job::class.java, DataImpulse::class.java, fn2)
        for (hook in listOf("onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy")) {
            assertMethod(c, hook, void, fn0)
        }
        assertMethod(c, "dispose", void)

        val kt = Class.forName("com.synapselib.arch.base.CoordinatorKt")
        assertStatic(kt, "Coordinator", c, SwitchBoard::class.java, LifecycleOwner::class.java, string, fn1)
        assertStatic(kt, "Coordinator\$default", c, SwitchBoard::class.java, LifecycleOwner::class.java, string, fn1, int, obj)
    }

    @Test
    fun `old inline Coordinator Intercept bytecode - getRegistrations add is tracked and cleaned up`() = runTest(testDispatcher) {
        val coord = CoordinatorScope(switchBoard, ResumedOwner())
        val hits = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        val regs = CoordinatorScope::class.java.getMethod("getRegistrations").invoke(coord) as MutableList<Registration>

        // What 1.0.x `Intercept` compiled into: addInterceptor, then getRegistrations().add(reg).
        val reg = switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, TestBroadcast::class, Interceptor.read { hits.incrementAndGet() }, 0)
        assertTrue(regs.add(reg))
        assertEquals(1, regs.size)
        assertSame(reg, regs[0])

        switchBoard.broadcastState(TestBroadcast("a"))
        assertEquals(1, hits.get())

        coord.dispose()
        switchBoard.broadcastState(TestBroadcast("b"))
        assertEquals(1, hits.get(), "unregistered on dispose")
        assertTrue(regs.isEmpty())
    }

    @Test
    fun `old inline Coordinator Intercept bytecode - add after dispose unregisters immediately`() = runTest(testDispatcher) {
        val coord = CoordinatorScope(switchBoard, ResumedOwner())
        coord.dispose()
        val hits = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        val regs = CoordinatorScope::class.java.getMethod("getRegistrations").invoke(coord) as MutableList<Registration>

        val reg = switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, TestBroadcast::class, Interceptor.read { hits.incrementAndGet() }, 0)
        assertFalse(regs.add(reg), "not tracked after dispose")
        switchBoard.broadcastState(TestBroadcast("late"))
        assertEquals(0, hits.get(), "late registration must not leak")
        assertTrue(regs.isEmpty())
    }

    @Test
    fun `addLifecycleEventRegistration and getLifecycleRegistrations work by their 1_0 JVM names`() {
        val coord = CoordinatorScope(switchBoard, ResumedOwner())
        var started = 0
        CoordinatorScope::class.java
            .getMethod("addLifecycleEventRegistration", Lifecycle.Event::class.java, fn0)
            .invoke(coord, Lifecycle.Event.ON_START, { started++ })
        assertEquals(1, started, "up-event replayed for an already-started owner")

        val snapshot = CoordinatorScope::class.java.getMethod("getLifecycleRegistrations").invoke(coord) as Map<*, *>
        assertEquals(1, (snapshot[Lifecycle.Event.ON_START] as List<*>).size)

        coord.dispose()
        assertTrue((CoordinatorScope::class.java.getMethod("getLifecycleRegistrations").invoke(coord) as Map<*, *>).isEmpty())
    }

    // ── NodeScope / ContextScope / Node.kt top level ────────────────────

    @Test
    fun `NodeScope keeps its 1_0 JVM signatures`() {
        val n = NodeScope::class.java
        assertConstructor(n, ContextScope::class.java, MutableState::class.java, CoroutineScope::class.java)
        assertMethod(n, "getContextScope", ContextScope::class.java)
        assertMethod(n, "getStateHolder", MutableState::class.java)
        assertMethod(n, "getScope", CoroutineScope::class.java)
        assertMethod(n, "getContext", obj)
        assertMethod(n, "getState", obj)
        assertMethod(n, "getSwitchboard", SwitchBoard::class.java)
        assertMethod(n, "getRegistrations", list)
        assertMethod(n, "dispose", void)
        assertMethod(n, "update", void, fn1)
        // The 1.0.x non-composable Intercept (now a hidden shim) and its $default bridge.
        assertMethod(n, "Intercept", void, InterceptPoint::class.java, Interceptor::class.java, int)
        assertStatic(n, "Intercept\$default", void, n, InterceptPoint::class.java, Interceptor::class.java, int, int, obj)
        assertMethod(n, "Broadcast", obj, obj, cont)
        assertMethod(n, "Trigger", obj, Impulse::class.java, cont)
        assertMethod(n, "Request", void, DataImpulse::class.java, obj, fn1, Composer::class.java, int, int)
        assertMethod(n, "ReactTo", void, obj, fn1, Composer::class.java, int, int)
        assertMethod(n, "ListenFor", void, obj, fn1, Composer::class.java, int, int)
    }

    @Test
    fun `ContextScope and Node top-level functions keep their 1_0 JVM signatures`() {
        val cs = ContextScope::class.java
        assertConstructor(cs, obj, SwitchBoard::class.java, CoroutineScope::class.java, string)
        assertConstructor(cs, obj, SwitchBoard::class.java, CoroutineScope::class.java, string, int, marker)
        assertMethod(cs, "getContext", obj)
        assertMethod(cs, "getSwitchboard", SwitchBoard::class.java)
        assertMethod(cs, "getCoroutineScope", CoroutineScope::class.java)
        assertMethod(cs, "getTag", string)
        assertMethod(cs, "Broadcast", obj, obj, cont)
        assertMethod(cs, "Trigger", obj, Impulse::class.java, cont)

        val kt = Class.forName("com.synapselib.arch.base.NodeKt")
        assertStatic(kt, "CreateContext", void, obj, string, fn3, Composer::class.java, int, int)
        assertStatic(kt, "Node", NodeScope::class.java, ContextScope::class.java, obj, fn3, Composer::class.java, int)
        assertStatic(kt, "getSerializerCache", java.util.concurrent.ConcurrentHashMap::class.java)
        assertStatic(kt, "serializerOrNull", kotlinx.serialization.KSerializer::class.java)
        assertStatic(kt, "rememberState", MutableState::class.java, obj, Composer::class.java, int)

        val noSerializer = Class.forName("com.synapselib.arch.base.NoSerializer")
        val instance = noSerializer.getField("INSTANCE")
        assertTrue(Modifier.isStatic(instance.modifiers) && Modifier.isPublic(instance.modifiers))
    }

    @Test
    fun `old inline NodeScope Intercept bytecode - getRegistrations add is tracked and cleaned up on dispose`() = runTest(testDispatcher) {
        val node = NodeScope(ContextScope("ctx", switchBoard), SimpleMutableState(TestState()), this)
        val hits = AtomicInteger()
        @Suppress("UNCHECKED_CAST")
        val regs = NodeScope::class.java.getMethod("getRegistrations").invoke(node) as MutableList<Registration>

        regs.add(switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, TestBroadcast::class, Interceptor.read { hits.incrementAndGet() }, 0))
        switchBoard.broadcastState(TestBroadcast("a"))
        assertEquals(1, hits.get())

        NodeScope::class.java.getMethod("dispose").invoke(node)
        switchBoard.broadcastState(TestBroadcast("b"))
        assertEquals(1, hits.get(), "unregistered on dispose")

        // A late add (old bytecode racing a dispose) is unregistered immediately.
        assertFalse(regs.add(switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, TestBroadcast::class, Interceptor.read { hits.incrementAndGet() }, 0)))
        switchBoard.broadcastState(TestBroadcast("c"))
        assertEquals(1, hits.get(), "late registration must not leak")
    }

    @Test
    fun `registrations view addAll and indexed add also go through the tracker`() = runTest(testDispatcher) {
        val node = NodeScope(ContextScope("ctx", switchBoard), SimpleMutableState(TestState()), this)
        val hits = AtomicInteger()
        fun reg() = switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, TestBroadcast::class, Interceptor.read { hits.incrementAndGet() }, 0)

        node.registrations.addAll(listOf(reg(), reg()))
        node.registrations.add(0, reg())
        assertEquals(3, node.registrations.size)
        node.dispose()

        node.registrations.addAll(listOf(reg(), reg()))
        node.registrations.add(0, reg())
        switchBoard.broadcastState(TestBroadcast("x"))
        assertEquals(0, hits.get(), "everything unregistered, including adds after dispose")
        assertTrue(node.registrations.isEmpty())
    }

    // ── ProviderScope ───────────────────────────────────────────────────

    @Test
    fun `ProviderScope keeps the 1_0 two-argument JVM constructor`() {
        val p = ProviderScope::class.java
        assertConstructor(p, SwitchBoard::class.java, CoroutineScope::class.java)
        assertConstructor(p, SwitchBoard::class.java, CoroutineScope::class.java, string)
        assertMethod(p, "getSwitchboard", SwitchBoard::class.java)
        assertMethod(p, "getCoroutineContext", kotlin.coroutines.CoroutineContext::class.java)
        assertMethod(p, "ListenFor", kotlinx.coroutines.flow.SharedFlow::class.java)
        assertMethod(p, "ReactTo", kotlinx.coroutines.flow.SharedFlow::class.java)
        assertMethod(p, "Request", kotlinx.coroutines.flow.Flow::class.java, DataImpulse::class.java)
        assertMethod(p, "Broadcast", obj, obj, cont)
        assertMethod(p, "Trigger", obj, Impulse::class.java, cont)

        val scope = CoroutineScope(testDispatcher + SupervisorJob())
        val viaOldCtor = p.getConstructor(SwitchBoard::class.java, CoroutineScope::class.java)
            .newInstance(switchBoard, scope)
        assertSame(switchBoard, viaOldCtor.switchboard)
        assertNull(viaOldCtor.tag, "two-arg constructor means no tracing tag")
        assertNotNull(viaOldCtor.coroutineContext)
    }
}
