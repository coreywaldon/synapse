# Synapse Arch Test

![Maven Central](https://img.shields.io/maven-central/v/com.synapselib/arch-test)
![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)
![License](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)
[![Build Status](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml/badge.svg)](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml)

Testing utilities for the [Synapse](https://github.com/coreywaldon/synapse) architecture framework. Provides `SynapseTestRule` — a JUnit 4 Rule that eliminates SwitchBoard boilerplate and exposes interceptor-based assertion helpers.

## Installation

```kotlin
// build.gradle.kts
testImplementation("com.synapselib:arch-test:1.0.11")
```

The artifact transitively pulls in `synapse-arch`, `synapse-core`, `kotlinx-coroutines-test`, and `junit:junit:4`.

For Android projects using Robolectric + Compose UI tests, you still need:

```kotlin
testImplementation(platform(libs.androidx.compose.bom))
testImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

## Quick start

### Coordinator test

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

        composeTestRule.setContent {
            CompositionLocalProvider(LocalSwitchBoard provides synapse.switchBoard) {
                CreateContext(appContext) { CheckoutScreen() }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Place Order").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals("addr-1", checkout.assertCaptured().addressId)
    }
}
```

## API reference

### `SynapseTestRule`

The entry point. Declare it as a JUnit Rule.

```kotlin
// No providers needed
@get:Rule
val synapse = SynapseTestRule()

// With providers
@get:Rule
val synapse = SynapseTestRule {
    provide<List<Address>, FetchAddresses> { testAddresses }
    provide<AuthToken, FetchCachedToken> { null }
}
```

#### Constructor parameters

| Parameter | Default | Description |
|---|---|---|
| `stateTimeout` | `3_000` | Timeout (ms) for state channel sharing in the SwitchBoard |
| `impulseTimeout` | `3_000` | Timeout (ms) for impulse channel sharing in the SwitchBoard |
| `configure` | `null` | DSL block to register test providers |

#### Properties

| Property | Type | Description |
|---|---|---|
| `switchBoard` | `DefaultSwitchBoard` | The SwitchBoard instance for the current test |
| `testDispatcher` | `UnconfinedTestDispatcher` | The coroutine dispatcher driving all execution |
| `lifecycleOwner` | `LifecycleOwner` | A lifecycle owner in `RESUMED` state, for coordinator construction |

### Capture helpers

Register interceptors **before** the action under test. Interceptors use `Direction.UPSTREAM` and `priority = Int.MAX_VALUE` so they run last in the interception chain, after all production interceptors.

#### `onImpulse<T>(): Capture<T>`

Captures the most recent impulse of type `T` on the reaction channel.

```kotlin
val login = synapse.onImpulse<LoginRequested>()
synapse.Trigger(LoginRequested("user@test.com", "pass"))
assertEquals("user@test.com", login.assertCaptured().email)
```

#### `onState<T>(): Capture<T>`

Captures the most recent state broadcast of type `T` on the state channel.

```kotlin
val session = synapse.onState<SessionState>()
synapse.Broadcast(SessionState.LoggedOut)
assertTrue(session.assertCaptured() is SessionState.LoggedOut)
```

#### `onAllImpulses<T>(): CaptureAll<T>`

Collects every impulse of type `T`, in order.

```kotlin
val toasts = synapse.onAllImpulses<ShowToast>()
synapse.Trigger(ShowToast("Added to cart"))
synapse.Trigger(ShowToast("Removed from cart"))
toasts.assertCount(2)
assertEquals("Added to cart", toasts.values[0].message)
```

#### `onAllStates<T>(): CaptureAll<T>`

Collects every state broadcast of type `T`, in order.

### `Capture<T>`

Returned by `onImpulse` and `onState`. Holds the most recent value.

| Method | Description |
|---|---|
| `assertCaptured(): T` | Returns the captured value, or throws `AssertionError` |
| `assertNotCaptured()` | Throws if a value was captured |
| `value: T?` | The captured value, or `null` |

### `CaptureAll<T>`

Returned by `onAllImpulses` and `onAllStates`. Collects all values.

| Method / Property | Description |
|---|---|
| `assertCaptured(): List<T>` | Returns all values, or throws if empty |
| `assertNotCaptured()` | Throws if any value was captured |
| `assertCount(n: Int)` | Throws if the count doesn't match `n` |
| `values: List<T>` | All captured values (immutable copy) |
| `count: Int` | Number of captured values |
| `latest: T?` | Most recently captured value |

### Trigger and Broadcast

Fire impulses or broadcast state into the SwitchBoard from test code:

```kotlin
// Fire a reaction impulse
synapse.Trigger(OrderPlaced())

// Broadcast a state value
synapse.Broadcast(SessionState.LoggedOut)
```

### Provider stubbing

Register providers in the `SynapseTestRule` constructor DSL:

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
}
```

### Coordinator helper

Initialize a `CoordinatorScope` that auto-disposes on test teardown:

```kotlin
synapse.Coordinator {
    ReactTo<LoginRequested> {
        launch { Trigger(AuthSuccess()) }
    }
}
```

### `runTest`

Wraps `kotlinx.coroutines.test.runTest` with the rule's dispatcher:

```kotlin
@Test
fun myTest() = synapse.runTest {
    // TestScope — synapse.Trigger, synapse.onImpulse, etc.
}
```

## How it works

`SynapseTestRule` creates a real `DefaultSwitchBoard` with an `UnconfinedTestDispatcher`. Capture helpers register **upstream** interceptors — they fire synchronously during `triggerImpulse()` and `broadcastState()` calls, so no async collection or `setEagerSharing()` is needed.

The interceptors use `priority = Int.MAX_VALUE` to run last, after all production interceptors (e.g., token injection). This means captures see the final, fully-processed values.

On teardown, the rule:
1. Disposes all coordinators created via `Coordinator {}`
2. Unregisters all interceptors
3. Resets `Dispatchers.Main`
