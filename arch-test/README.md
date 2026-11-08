# Synapse Arch Test

![Maven Central](https://img.shields.io/maven-central/v/com.synapselib/arch-test)
![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)
![License](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)
[![Build Status](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml/badge.svg)](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml)

Testing utilities for the [Synapse](https://github.com/coreywaldon/synapse) architecture framework. A real `DefaultSwitchBoard` on a test dispatcher, a controllable `LifecycleOwner`, thread-safe capture helpers for every channel, and teardown that closes everything — packaged as:

| Entry point | Framework | Use |
|---|---|---|
| `SynapseTestRule` | JUnit 4 | `@get:Rule val synapse = SynapseTestRule { … }` |
| `SynapseExtension` | JUnit 5 (Jupiter) | `@RegisterExtension val synapse = SynapseExtension { … }` or `@ExtendWith(SynapseExtension::class)` |
| `SynapseTestHarness` | anything else | `SynapseTestHarness { … }.start().use { … }` |

All three share one implementation (`SynapseTestRule` and `SynapseExtension` extend `SynapseTestHarness`), so everything below works identically in each.

## Installation

```kotlin
// build.gradle.kts
testImplementation("com.synapselib:arch-test:1.1.0")
```

The artifact transitively pulls in `synapse-arch`, `synapse-core`, `kotlinx-coroutines-test`, and `junit:junit:4`.

**JUnit 5:** `junit-jupiter-api` is a *compile-only* dependency of `arch-test`, so JUnit 4 users don't get it on their classpath. If you use `SynapseExtension` you already have Jupiter in your test dependencies (e.g. `testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")`) — nothing else is needed.

For Android projects using Robolectric + Compose UI tests, you still need:

```kotlin
testImplementation(platform(libs.androidx.compose.bom))
testImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

## Quick start

### Coordinator test (JUnit 4)

```kotlin
@RunWith(RobolectricTestRunner::class)
class AuthCoordinatorTest {

    @get:Rule
    val synapse = SynapseTestRule {
        provide<AuthToken, FetchCachedToken> { null } // no cached token
    }

    private lateinit var coordinator: AuthCoordinator

    @Before
    fun setup() {
        coordinator = AuthCoordinator(TestAuthApi(), synapse.lifecycleOwner)
        coordinator.initialize(synapse.switchBoard)
    }

    @Test
    fun loginBroadcastsAuthenticatedSession() = synapse.runTest {
        val session = synapse.onState<SessionState>()

        synapse.Trigger(LoginRequested("user@test.com", "password"))

        val auth = session.assertCaptured() as SessionState.Authenticated
        assertEquals("user@test.com", auth.user.email)
    }
}
```

### Coordinator test (JUnit 5)

```kotlin
class AuthCoordinatorTest {

    @JvmField
    @RegisterExtension
    val synapse = SynapseExtension {
        provide<AuthToken, FetchCachedToken> { null }
    }

    @Test
    fun loginBroadcastsAuthenticatedSession() = synapse.runTest {
        val session = synapse.onState<SessionState>()
        synapse.Coordinator(tag = "Auth") { /* … or your own coordinator … */ }

        synapse.Trigger(LoginRequested("user@test.com", "password"))

        session.assertCaptured()
    }
}
```

Or, without a configure block, inject the harness as a parameter:

```kotlin
@ExtendWith(SynapseExtension::class)
class PingTest {
    @Test
    fun ping(synapse: SynapseTestHarness) = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()
        synapse.Trigger(Ping())
        ping.assertCaptured()
    }
}
```

### Node / Compose screen test

```kotlin
@RunWith(RobolectricTestRunner::class)
class CheckoutScreenTest {

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<Address>, FetchAddresses> { testAddresses }
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun placeOrderTriggersCheckoutRequested() = synapse.runTest {
        val checkout = synapse.onImpulse<CheckoutRequested>()
        val addressRequest = synapse.onRequest<FetchAddresses>()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalSwitchBoard provides synapse.switchBoard) {
                CreateContext(appContext) { CheckoutScreen() }
            }
        }
        composeTestRule.waitForIdle()
        addressRequest.assertCaptured()

        composeTestRule.onNodeWithText("Place Order").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("addr-1", checkout.assertCaptured().addressId)
    }
}
```

### Any other framework

```kotlin
val synapse = SynapseTestHarness { provide<List<Item>, FetchItems> { items } }
synapse.start()
try {
    synapse.runTest { /* … */ }
} finally {
    synapse.close()
}

// or
SynapseTestHarness().start().use { synapse -> synapse.runTest { /* … */ } }
```

## API reference

### Constructor parameters

`SynapseTestRule`, `SynapseExtension` and `SynapseTestHarness` take the same parameters:

```kotlin
// No configuration
@get:Rule
val synapse = SynapseTestRule()

// With providers and board options
@get:Rule
val synapse = SynapseTestRule {
    provide<List<Address>, FetchAddresses> { testAddresses }
    provide<AuthToken, FetchCachedToken> { null }
    switchBoard { maxConcurrentJobs = 2 }
}
```

| Parameter | Default | Description |
|---|---|---|
| `stateTimeout` | `3_000` | `SwitchBoardConfig.stopTimeout` (ms): how long downstream flows stay shared after their last subscriber leaves. Also the default provider idle timeout. |
| `impulseTimeout` | `3_000` | `SwitchBoardConfig.replayExpiration` (ms): how long a downstream flow keeps its replay cache after sharing stops. |
| `configure` | `null` | `SynapseTestConfig` DSL block, run lazily at the start of **every** test (so it may reference fields declared after the rule). |

### Configuration DSL (`SynapseTestConfig`)

| Member | Description |
|---|---|
| `provide<Need, I> { impulse -> value? }` | Inline provider emitting one value; `null` emits nothing. |
| `provideFlow<Need, I> { impulse -> flow }` | Inline provider returning a custom `Flow` (multiple values, delays, streams). |
| `include(registry)` | Adds every provider of an existing `ProviderRegistry` (production or KSP-generated). Duplicates fail fast. |
| `switchBoard { … }` | Customises the board's `SwitchBoardConfig.Builder`. Applied **after** the harness defaults, so it can override them. May be called multiple times. |
| `emitterTag = "Test"` | Traces the harness's own `Trigger` / `Broadcast` / `Request` with this `TraceContext.emitterTag`. Default `null` (untraced). |
| `initialLifecycleState = …` | State the lifecycle owner starts in. Default `RESUMED`. Use `INITIALIZED` / `CREATED` to drive it yourself with `moveTo`. Must not be `DESTROYED`. |

The board is built as:

```kotlin
DefaultSwitchBoard(coroutineScope, registry, SwitchBoardConfig {
    workerContext = testDispatcher
    stopTimeout = stateTimeout.milliseconds
    replayExpiration = impulseTimeout.milliseconds
    /* your switchBoard { } blocks */
})
```

#### Provider stubbing

```kotlin
@get:Rule
val synapse = SynapseTestRule {
    // Single-value provider
    provide<List<Address>, FetchAddresses> { testAddresses }

    // Provider that emits nothing (empty flow)
    provide<AuthToken, FetchCachedToken> { null }

    // Multi-emission / streaming provider
    provideFlow<List<Product>, FetchProducts> { impulse ->
        flow {
            emit(cachedProducts)
            delay(100)
            emit(freshProducts)
        }
    }

    // Real providers, alongside the stubs
    include(UserProviders.registry(FakeUserApi()))
}
```

### Properties

| Property | Type | Description |
|---|---|---|
| `switchBoard` | `DefaultSwitchBoard` | The SwitchBoard for the current test |
| `switchBoardConfig` | `SwitchBoardConfig` | The configuration the board was built with |
| `testDispatcher` | `TestDispatcher` | The `UnconfinedTestDispatcher` driving all execution (also installed as `Dispatchers.Main`) |
| `coroutineScope` | `CoroutineScope` | The board's parent scope (`SupervisorJob() + testDispatcher`), cancelled on teardown |
| `lifecycleOwner` | `LifecycleOwner` | A lifecycle owner, `RESUMED` by default, for coordinator construction |
| `lifecycleState` | `Lifecycle.State` | Current state of `lifecycleOwner` |
| `isStarted` | `Boolean` | `true` while a test is running (between `start()` and `close()`) |

Accessing `switchBoard`, `coroutineScope` or `lifecycleOwner` outside a test throws an `IllegalStateException` explaining that the harness is not started.

### Shared verbs: `Trigger`, `Broadcast`, `ListenFor`, `ReactTo`, `Request`

The harness **is a** `SynapseScope` — the same base class as `ContextScope`, `NodeScope`, `CoordinatorScope` and `ProviderScope` — so the production verbs are available directly:

```kotlin
synapse.Trigger(OrderPlaced())                       // reaction channel
synapse.Broadcast(SessionState.LoggedOut)            // state channel
val config = synapse.ListenFor<AppConfig>().first()  // downstream state flow
synapse.ReactTo<ShowToast>()                         // downstream impulse flow
synapse.Request(FetchItems("q")).first { it is DataState.Success }
```

Helpers you write against `SynapseScope` work in tests unchanged:

```kotlin
suspend fun SynapseScope.announceLogin(user: User) {
    Broadcast(SessionState.Authenticated(user))
    Trigger(NavigateTo("home"))
}

@Test
fun login() = synapse.runTest { synapse.announceLogin(testUser) }
```

### Capture helpers

Create captures **before** the action under test (they may even be created as fields, before the rule starts).

| Helper | Returns | Channel / direction | Captures |
|---|---|---|---|
| `onImpulse<T>()` | `Capture<T>` | `REACTION / UPSTREAM` | latest impulse triggered |
| `onAllImpulses<T>()` | `CaptureAll<T>` | `REACTION / UPSTREAM` | every impulse, in order |
| `onState<T>()` | `Capture<T>` | `STATE / UPSTREAM` | latest state broadcast |
| `onAllStates<T>()` | `CaptureAll<T>` | `STATE / UPSTREAM` | every state, in order |
| `onRequest<I>()` | `Capture<I>` | `REQUEST / UPSTREAM` | latest request impulse (as the provider receives it) |
| `onAllRequests<I>()` | `CaptureAll<I>` | `REQUEST / UPSTREAM` | every request impulse, in order |
| `onRequestResult<Need>()` | `Capture<Need>` | `REQUEST / DOWNSTREAM` | latest result delivered to a requester |
| `onAllRequestResults<Need>()` | `CaptureAll<Need>` | `REQUEST / DOWNSTREAM` | every result delivered, in order |
| `capture<T>(point)` | `Capture<T>` | any `InterceptPoint` | latest value at that point |
| `captureAll<T>(point)` | `CaptureAll<T>` | any `InterceptPoint` | every value at that point |

```kotlin
val login = synapse.onImpulse<LoginRequested>()
synapse.Trigger(LoginRequested("user@test.com", "pass"))
assertEquals("user@test.com", login.assertCaptured().email)

val toasts = synapse.onAllImpulses<ShowToast>()
synapse.Trigger(ShowToast("Added to cart"))
synapse.Trigger(ShowToast("Removed from cart"))
toasts.assertCount(2)
assertEquals("Added to cart", toasts.values[0].message)

val fetch = synapse.onRequest<FetchAddresses>()
val addresses = synapse.onAllRequestResults<List<Address>>()
// … screen loads …
assertEquals("user-1", fetch.assertCaptured().userId)
assertEquals(testAddresses, addresses.latest)

// Downstream (as delivered to listeners, after downstream interceptors)
val delivered = synapse.captureAll<Counter>(InterceptPoint.STATE_DOWNSTREAM)
```

Notes:

- **Request captures** are recorded per *collection* of a request flow (before provider deduplication). Results are recorded for each `DataState.Success` and for the `staleData` of a `DataState.Error`; a request that fails without data records no result.
- **Downstream state/reaction points** only carry values while something collects the corresponding flow (a coordinator's `ListenFor`, a composable, `synapse.ListenFor<T>().collect { }`).
- **Type matching** follows interceptor rules: a capture for `T` sees values whose *type token* (the reified type at the `Broadcast` / `Trigger` / `Request` call site, or the result type) is `T` or a subtype. `onState<SessionState>()` sees `Broadcast(SessionState.LoggedOut)`, but `onState<SessionState.LoggedOut>()` does **not** see `Broadcast<SessionState>(SessionState.LoggedOut)` — exactly like a `ListenFor<SessionState.LoggedOut>` listener.

### `Capture<T>`

Holds the most recent value. Thread-safe.

| Member | Description |
|---|---|
| `assertCaptured(): T` | Returns the captured value, or throws `AssertionError` (naming the type and point) |
| `assertNotCaptured()` | Throws if a value was captured |
| `value: T?` | The captured value, or `null` |
| `clear()` | Forgets the captured value |
| `point`, `type` | The `InterceptPoint` and type this capture observes |

### `CaptureAll<T>`

Collects all values, in order. Thread-safe; every read is a consistent snapshot.

| Member | Description |
|---|---|
| `assertCaptured(): List<T>` | Returns all values, or throws if empty |
| `assertNotCaptured()` | Throws if any value was captured |
| `assertCount(n: Int)` | Throws if the count doesn't match `n` |
| `values: List<T>` | All captured values (immutable snapshot) |
| `count: Int` | Number of captured values |
| `latest: T?` | Most recently captured value |
| `clear()` | Forgets all captured values |
| `point`, `type` | The `InterceptPoint` and type this capture observes |

### Coordinator helpers

Create a `CoordinatorScope` on the test board that is disposed automatically on teardown:

```kotlin
// Bound to synapse.lifecycleOwner
synapse.Coordinator(tag = "Auth") {
    ReactTo<LoginRequested> {
        launch { Trigger(AuthSuccess()) }
    }
}

// Bound to your own LifecycleOwner
synapse.Coordinator(myOwner) { /* … */ }

// Lifecycle-free, bound to a CoroutineScope (e.g. the harness scope)
synapse.Coordinator(synapse.coroutineScope, tag = "Sync") {
    onDispose { /* … */ }
}
```

Lifecycle hooks registered in the block **fire for states the owner has already reached**, right after the block and in lifecycle order. With the default `RESUMED` owner, `onCreate`, `onStart` and `onResume` all run immediately:

```kotlin
val events = mutableListOf<String>()
synapse.Coordinator {
    onResume { events += "resume" }
    onCreate { events += "create" }
    onStart { events += "start" }
}
assertEquals(listOf("create", "start", "resume"), events)
```

(Lifecycle hooks on a lifecycle-free coordinator throw `IllegalStateException`; use `onDispose`.)

### Driving the lifecycle: `moveTo`

`moveTo(state)` moves `lifecycleOwner` to `state`, synchronously dispatching every intermediate event:

```kotlin
synapse.Coordinator {
    onStop { launch { Broadcast(SessionInactive) } }
}
val inactive = synapse.onState<SessionInactive>()

synapse.moveTo(Lifecycle.State.CREATED)   // ON_PAUSE, ON_STOP
inactive.assertCaptured()

synapse.moveTo(Lifecycle.State.RESUMED)   // ON_START, ON_RESUME
synapse.moveTo(Lifecycle.State.DESTROYED) // ON_PAUSE, ON_STOP, ON_DESTROY → coordinators auto-dispose
```

To test start-up ordering, begin before `RESUMED`:

```kotlin
@get:Rule
val synapse = SynapseTestRule { initialLifecycleState = Lifecycle.State.CREATED }
```

`DESTROYED` is terminal (moving out of it throws), and `INITIALIZED → DESTROYED` is rejected by `LifecycleRegistry`. The owner does not enforce the main thread, so `moveTo` may be called from any thread.

### `runTest`

Wraps `kotlinx.coroutines.test.runTest` with the harness's dispatcher:

```kotlin
@Test
fun myTest() = synapse.runTest {
    // TestScope — synapse.Trigger, synapse.onImpulse, etc.
}
```

### JUnit 5 specifics (`SynapseExtension`)

- `beforeEach` starts the harness, `afterEach` closes it (after your `@AfterEach` methods).
- Parameters of type `SynapseExtension`, `SynapseTestHarness`, `SynapseScope`, `SwitchBoard` and `DefaultSwitchBoard` are resolved in test and lifecycle methods. `SynapseTestHarness` can also be injected into the test class constructor (it is resolved before `beforeEach`, so only use the board inside tests); `SwitchBoard` / `DefaultSwitchBoard` cannot.
- `@RegisterExtension` on an instance field gives every test its own extension (Jupiter's default per-method lifecycle). `@ExtendWith(SynapseExtension::class)` shares one instance across the class, restarted per test — fine sequentially, but not with concurrent test execution.

## How it works

The harness creates a real `DefaultSwitchBoard` on an `UnconfinedTestDispatcher`. Captures are fed by a **single `SwitchBoardObserver`** the harness registers on the board. Observers run synchronously during `triggerImpulse()`, `broadcastState()` and request collection, so no async collection or eager sharing is needed.

Observers are notified **after all interceptors of a stage** have run. Captures therefore see the final, fully processed value at their point — even relative to interceptors registered later or with `Int.MAX_VALUE` priority — and they never participate in, reorder or short-circuit the production interceptor chain. Captures are thread-safe, so values recorded from providers or coordinators running on other threads are never lost.

On teardown (`close()`), the harness — running every step even if an earlier one fails, and rethrowing the first failure with the rest suppressed:
1. Disposes all coordinators created via the `Coordinator` helpers (running their `onDispose` callbacks)
2. Moves the lifecycle owner to `DESTROYED`, which also disposes coordinators you built yourself against `lifecycleOwner`
3. Detaches all captures (their values stay readable)
4. Closes the `SwitchBoard` (cancelling provider jobs and sharing coroutines) and cancels `coroutineScope`
5. Resets `Dispatchers.Main`

`SynapseTestRule` reports a teardown failure as a suppressed exception of the test's own failure when both fail.

Because the harness replaces the global `Dispatchers.Main`, don't run Synapse tests in parallel within one JVM.

## Migrating from earlier versions

- The rule now **closes its `SwitchBoard`** after each test; flows and boards must not be reused across tests.
- Captures are observer-based instead of `Int.MAX_VALUE` interceptors, so they now also see changes made by interceptors registered after the capture.
- `Trigger` / `Broadcast` are inherited from `SynapseScope`; positional calls are unchanged, but the parameter names are now `event` / `data` (previously `impulse` / `state`).
- `switchBoard.setTraceListener` / `setGlobalLogger` are deprecated in `arch`; use `synapse.switchBoard.addObserver { point, clazz, data, trace -> … }`.
