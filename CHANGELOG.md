# Changelog

All notable changes to the Synapse libraries (`com.synapselib:core`, `arch`, `arch-processor`,
`arch-hilt`, `arch-test`) are documented here. All artifacts share one version.

## 1.1.0

### Breaking changes

#### The KSP processor moved to its own artifact, `arch-processor`

The `@SynapseProvider` processor no longer ships inside `arch`. It is now the compile-only
artifact `com.synapselib:arch-processor` (package `com.synapselib.arch.processor`). Update the
`ksp` coordinate in every module that declares providers:

```kotlin
dependencies {
    implementation("com.synapselib:arch:1.1.0")
    // before: ksp("com.synapselib:arch:1.0.13")
    ksp("com.synapselib:arch-processor:1.1.0")
}
```

`arch` also no longer puts `dagger-compiler`, KSP, AutoService or Compose Foundation on your
classpath. If your app relied on any of them transitively, declare them directly.

#### `NodeScope.Intercept` is now `@Composable`

`Intercept` inside a `Node` now registers its interceptor once (per `point` / `priority` / `key`)
and removes it when the call leaves the composition. Previously every recomposition added another
registration that was only removed when the node was disposed. Since it is now composable, you can't
call it from click handlers, effects or coroutines. Use the new `addInterceptor` there:

```kotlin
Node(initialState = MyState()) {
    // Composable body: unchanged. Now registered once, not once per recomposition.
    Intercept<NetworkRequest>(InterceptPoint.REQUEST_UPSTREAM, Interceptor.transform { it.copy(token = state.token) })

    Button(onClick = {
        // before: Intercept<Analytics>(InterceptPoint.REACTION_UPSTREAM, tagger)
        val registration = addInterceptor<Analytics>(InterceptPoint.REACTION_UPSTREAM, tagger)
        // removed automatically when the node is disposed, or call registration.unregister()
    }) { Text("Tag") }
}
```

#### `DefaultSwitchBoard` owns a child job and can be closed

`DefaultSwitchBoard` now implements `AutoCloseable`. When it is constructed it creates its own
`SupervisorJob` as a child of the `scope` you pass. Everything the board launches (sharing
coroutines, provider jobs) runs under that job.

- `close()` cancels the board's job and disposes its provider jobs. It does **not** cancel your scope.
  After `close()`, `broadcastState`, `triggerImpulse`, `handleRequest`, `stateFlow`, `impulseFlow`,
  `getRawStateFlow` and `getRawImpulseFlow` throw `IllegalStateException`. This also applies to
  collecting a request flow obtained before closing. Flows obtained before `close()` stop receiving
  values but never complete.
- The child job exists from construction, so a scope that waits for its children (for example the
  `TestScope` of `runTest`, or `coroutineScope { }`) does not complete until the board is closed or
  the scope is cancelled:

```kotlin
// Tests: close the board, or give it a scope that is cancelled for you.
@Test fun example() = runTest {
    DefaultSwitchBoard(this, registry).use { board -> /* … */ }
    // or: val board = DefaultSwitchBoard(backgroundScope, registry)
}
```

#### `ProviderManager` fails fast after `dispose()` or once its scope is cancelled

`ProviderManager.dispose()` is now idempotent and permanent. `request()` throws
`IllegalStateException` after `dispose()`; before 1.1.0 the manager kept accepting requests after
`dispose()`. `request()` also throws once the parent scope is cancelled; before, it returned a flow
whose job never ran, so collectors hung. Collecting a flow whose job was cancelled by `dispose()`
also fails instead of hanging. `DefaultSwitchBoard.handleRequest` turns the cancelled-scope case into a
`DataState.Error`. If you use `ProviderManager` directly, create a new one instead of reusing a
disposed one.

#### Provider jobs are torn down when nobody collects them

Provider jobs now count the collectors of the flows they return. When a job has no collectors for
the idle timeout, it is cancelled and its dedup entry is removed. This covers streaming providers
whose last subscriber left (they used to run until the board's scope died) and requests that were
never collected (they used to leak). A later request starts a fresh provider. Collecting a stale
flow re-requests transparently. On `DefaultSwitchBoard` the timeout is
`SwitchBoardConfig.providerIdleTimeout`, which defaults to `stopTimeout` (3 s). To restore the old
"run forever" behavior:

```kotlin
val board = DefaultSwitchBoard(scope, registry, SwitchBoardConfig {
    providerIdleTimeout = Duration.INFINITE
})
// Direct ProviderManager users: ProviderManager(registry, scope, idleTimeoutMillis = Long.MAX_VALUE)
```

#### `setEagerSharing()` after the first flow throws

The sharing strategy is captured when a type's downstream flow is first created. Calling the
now-deprecated `DefaultSwitchBoard.setEagerSharing()` after any `stateFlow()` / `impulseFlow()`
call throws `IllegalStateException`. It used to half-apply silently. Configure the strategy up front:

```kotlin
// before
val board = DefaultSwitchBoard(scope, registry).apply { setEagerSharing() }
// after
val board = DefaultSwitchBoard(scope, registry, SwitchBoardConfig { eagerSharing() })
```

#### Coordinator lifecycle hooks fire for states the owner has already reached

The `Coordinator(switchboard, owner) { }` builder now attaches its lifecycle observer *after* the
block runs. Up-event hooks (`onCreate`, `onStart`, `onResume`) registered in the block are
therefore replayed, in lifecycle order, when the owner is already past them. A hook registered
later on an already-started coordinator also fires immediately. Previously these hooks never fired
for an owner that was already started or resumed, such as `ProcessLifecycleOwner`. Down-event
hooks (`onPause`, `onStop`, `onDestroy`) are never replayed. If you worked around the old behavior,
remove the workaround:

```kotlin
Coordinator(switchboard, ProcessLifecycleOwner.get()) {
    // before 1.1.0 this never ran for an already-started process; now it runs right after the block
    onStart { launch { Broadcast(SessionActive) } }
}
```

Related: a coordinator created for an owner that is already `DESTROYED` is disposed immediately.
A builder block that throws disposes the coordinator before rethrowing.

#### Lifecycle hooks on a lifecycle-free Coordinator throw

The new scope-bound `Coordinator(switchboard, scope) { }` has no lifecycle. Calling `onCreate`,
`onStart`, `onResume`, `onPause`, `onStop` or `onDestroy` on it throws `IllegalStateException`
rather than silently never firing. Use `onDispose { }` for teardown:

```kotlin
Coordinator(switchboard, applicationScope, tag = "Sync") {
    // onStop { … }        // throws: no LifecycleOwner
    onDispose { engine.close() }
}
```

#### Smaller breaking changes

- **core:** the gate operator's internal event wrapper (`Event`, `Event.Data`, `Event.GateState`,
  `Event.UpstreamComplete` in `com.synapselib.core`) is no longer public.
- **core:** invalid arguments are now rejected up front with `IllegalArgumentException`. This
  covers `gate(bufferCapacity <= 0)`, `chunk(period <= 0)` and `broadcast(keepAlive < 0)`.
- **core:** `DerivedStateFlow` (the result of `and`, `or`, `not`, …) no longer emits equal
  consecutive values, as the `StateFlow` contract requires.
- **arch:** `DefaultSwitchBoard`'s 5-parameter `@Inject` constructor is now a secondary
  constructor. Hilt injection and call sites are unchanged, and the `arch` jar still ships the
  Dagger-generated `DefaultSwitchBoard_Factory` (Dagger now runs over `arch` via KSP at build
  time only — no Dagger artifact is added to `arch`'s runtime dependencies).

### Added

- **`com.synapselib:arch-processor`**: the standalone, compile-only KSP processor.
- **`SwitchBoardConfig`**: immutable board configuration with a builder DSL
  (`SwitchBoardConfig { … }`), `copy { }`, `newBuilder()` and `SwitchBoardConfig.DEFAULT`. Options:
  `stopTimeout`, `replayExpiration`, `sharingStarted` / `eagerSharing()`,
  `reactionBufferCapacity`, `reactionOverflow`, `maxConcurrentJobs`, `providerIdleTimeout` and
  `workerContext`. Values are validated on build.
- `DefaultSwitchBoard(scope, registry, config)` primary constructor and the `config` property.
- `DefaultSwitchBoard.close()` / `isClosed` (`AutoCloseable`).
- **`SwitchBoardObserver`** and `SwitchBoard.addObserver(observer): Registration`. Any number of
  type-agnostic observers can be registered; they receive the `TraceContext` and are notified of
  downstream delivery failures through `onDeliveryFailure`. The interface method has a default
  that throws `UnsupportedOperationException`, so third-party `SwitchBoard` implementations keep
  compiling.
- `InterceptPoint.of(channel, direction)` (non-allocating) and `InterceptPoint.ALL`.
- **`SynapseScope`**: common base class of `ContextScope`, `NodeScope`, `CoordinatorScope` and
  `ProviderScope`. It declares the shared verbs once: `Broadcast`, `Trigger`, and the
  flow-returning `ListenFor<O>()`, `ReactTo<A>()` and `Request(impulse)`. `ContextScope` and
  `NodeScope` gain the flow-returning verbs, and `NodeScope.switchboard` is now public.
- Tracing helpers: `TraceContext.child()`, `TraceContext.childOf(parent, tag)`,
  `currentTraceContext()` and `withTrace(tag) { }`.
- `ProviderScope(switchboard, coroutineScope, tag = null)`: providers can trace their own emissions.
- **Lifecycle-free coordinators**: `Coordinator(switchboard, scope, tag) { }` and
  `CoordinatorScope(switchboard, scope, tag)`, bound to the scope's `Job`.
- `CoordinatorScope.onDispose { }`, `CoordinatorScope.isDisposed` and `CoordinatorScope.lifecycleOwner`.
- `NodeScope.addInterceptor(point, interceptor, priority)`, `NodeScope.isDisposed`, and a `key`
  parameter on `NodeScope.Intercept`.
- `ProviderManager(…, idleTimeoutMillis)`, `ProviderManager.isDisposed` and
  `ProviderManager.DEFAULT_IDLE_TIMEOUT_MILLIS`.
- `ProviderRegistry.Builder(name)` origin label, `ProviderRegistry.name`,
  `ProviderRegistry.of(vararg registries)`, the `ProviderRegistry + ProviderRegistry` operator and
  `ProviderRegistryContribution.name`.
- **core:** `ReactiveFlow(upstream, timeSource: TimeSource)` (primary) and `ReactiveFlow(upstream)`
  constructors, `Flow.asReactive(timeSource)`, and `timeSource: TimeSource` parameters on the
  `Flow.squash`, `Flow.pace` and `Flow.combo` extensions. Pass `TestScope.testTimeSource` in tests.
- **core:** `Flow.gate(condition: Flow<Boolean>, …)` overload (any boolean flow, not only a
  `StateFlow`), `Flow.chunk(period, maxBufferSize)` overload, and `!flow` (`not` is now an
  `operator`).
- **core:** `DataState` is `@Serializable` (`@Serializable(with = DataStateSerializer::class)`), so a
  `@Serializable` class can hold a `DataState<T>` property whenever `T` is serializable — this is
  what makes `@Serializable` Node state holding `DataState` save correctly (see Fixed). New public
  types in `com.synapselib.core.typed`:
  - `DataStateSerializer<T : Any>(dataSerializer: KSerializer<T>)`: encodes each variant as a small
    structure discriminated by `state` — `{"state":"idle"}`, `{"state":"loading"}`,
    `{"state":"success","data":…}`, `{"state":"error","message":"…","type":"java.io.IOException","staleData":…}`.
    `Idle` / `Loading` decode to the same singletons. The serialization plugin also generates
    `DataState.serializer(typeSerial0)` (on the new `DataState.Companion`).
  - `RestoredDataStateException(originalType: String?, message: String?)`: the `cause` of a
    `DataState.Error` after it is decoded. A `Throwable` is not serializable, so only the original
    cause's class name and message survive a save/restore; its identity, type and stack trace do
    not. `staleData` is preserved. A live `Request` re-emits the real state after restoration.
    Instances with the same `originalType` and `message` are equal, and re-encoding one writes the
    original `type`, so repeated save/restore cycles are stable.
- CI workflow for pull requests and non-`main` pushes.

### Changed

- **Publishing to Maven Central now refuses to run without `GPG_SIGNING_KEY`** (new
  `requireSigningKey` task guarding every `*MavenCentral*` publish task), instead of uploading
  unsigned artifacts that Central rejects after the fact. `publishToMavenLocal` needs no key.
- The demo app now depends on `arch-processor` (KSP) and `arch-hilt` (registry aggregation).
- **POM license is now MPL 2.0**, matching the repository's `LICENSE` file. 1.0.x POMs wrongly
  declared Apache 2.0.
- `arch` now exposes `core`, `kotlinx-coroutines-core`, Compose runtime, `lifecycle-common` and
  `javax.inject` as `api` dependencies, because their types appear in its public API.
  `javax.inject:javax.inject:1` is declared explicitly.
- **core now depends on `org.jetbrains.kotlinx:kotlinx-serialization-core`** (1.8.1, `api`), because
  `DataState` is `@Serializable` and `DataStateSerializer` is public API. core is compiled with the
  kotlinx.serialization compiler plugin. Apps using `arch` already had it at runtime (through
  `arch`'s `kotlinx-serialization-json`); it is now also on their compile classpath.
- **Tracing:**
  - When a tagged scope emits inside a coroutine that already carries a `TraceContext`, the new
    trace's `parentTraceId` is set to that ambient `traceId`. It was always `null` before.
  - Tagged `Request` (both the flow and the handler overloads) now runs the request pipeline inside
    a trace.
  - Untagged scopes are transparent: an ambient trace is reported unchanged.
  - Traces are still not carried across the SwitchBoard hop to listeners or into providers.
- `setGlobalLogger` / `setTraceListener` are now single-slot shims on top of observers and keep
  their previous semantics. Order on every stage: global logger, then trace listener, then
  `addObserver` observers in registration order. With no observers registered, the board does no
  coroutine-context lookup at all.
- A downstream interceptor or observer that throws while a state/reaction value is delivered now
  drops that single value. `onDeliveryFailure` is invoked and the error goes to the scope's
  `CoroutineExceptionHandler`. The channel stays alive.
- The interceptor pipeline pre-builds an immutable chain for each cached snapshot, so a steady-state
  call allocates no chain objects. An interceptor registered for a supertype also applies to
  subtypes, merged by priority. `Registration.unregister()` is now truly idempotent.
- State and reaction channels are stored in per-type lanes, and the interceptor registries in a
  fixed 6-slot array.
- Provider dedup lookup-or-create is atomic. The losing candidate never invokes its
  `ProviderFactory`.
- The shared provider job is typed by the *registered* `Need` type. Callers may request an impulse
  with any super-type (e.g. `Any`) and still share the job. Type-mismatch messages name both types.
- `maxConcurrentJobs` counts live jobs with an atomic permit counter.
- Providers run in a nested `coroutineScope`. A failing coroutine launched from `ProviderScope`
  becomes `DataState.Error` instead of escaping to the parent's exception handler. A
  `CancellationException` that the provider itself throws while active (e.g. from `withTimeout`) is
  reported as `DataState.Error`.
- Duplicate-registration errors from `ProviderRegistry.Builder.register` / `mergeFrom` name both
  conflicting factories and the registries they came from.
- Coordinator registrations, lifecycle hooks and `dispose()` are thread-safe. `Intercept` after
  `dispose()` unregisters immediately. If observer removal is refused off the main thread, the
  observer detaches on its next event.
- `ContextScope`'s default `coroutineScope` is now `CoroutineScope(SupervisorJob() +
  Dispatchers.Default)`. `CreateContext` always passes a composition-bound scope.
- **core:** the time source now propagates through chained `ReactiveFlow` operators. Every
  operator used to reset it to `System.nanoTime`.
- **core:** `shutter` completes when the trigger completes. After the upstream completes, its last
  value can still be sampled. Errors from either side propagate.
- **core:** `chunk` errors are deterministic on multi-threaded dispatchers: the partial chunk is
  dropped and the error rethrown.
- **core:** `spy` logs `Cancelled` (not `Failed`) when collection is cancelled, e.g. by `take()`.
- **core:** typed-flow cast errors name the adapter/slot and both fully-qualified types.
- Build: convention plugins (`synapse.kotlin-library`, `synapse.publish`), a version catalog, JDK
  21 for publishing. Signing is only configured when `GPG_SIGNING_KEY` is set.

### Deprecated

| Deprecated | Replacement |
|---|---|
| `DefaultSwitchBoard.setGlobalLogger(logger)` | `addObserver { point, clazz, data, _ -> logger(point, clazz, data) }` |
| `DefaultSwitchBoard.setTraceListener(listener)` | `addObserver { point, clazz, data, trace -> if (trace != null) listener(trace, point, clazz, data) }` |
| `DefaultSwitchBoard.setEagerSharing()` | `DefaultSwitchBoard(scope, registry, SwitchBoardConfig { eagerSharing() })` |
| `ReactiveFlow(upstream, timeSource: () -> Long)` | `ReactiveFlow(upstream, TimeSource.Monotonic)` (or `TestScope.testTimeSource`) |
| `Flow.squash(…, timeSource: () -> Long)` | `Flow.squash(…, timeSource = TimeSource.Monotonic)` |
| `Flow.pace(interval, timeSource: () -> Long)` | `Flow.pace(interval, TimeSource.Monotonic)` |
| `Flow.combo(…, timeSource: () -> Long)` | `Flow.combo(…, timeSource = TimeSource.Monotonic)` |
| `GateFinishedException` | none: `gate` no longer throws it, so remove any handling |

Hidden (`DeprecationLevel.HIDDEN`) bridges keep the original JVM signatures of the lambda-clock
`squash`, `pace` and `combo` extensions binary-compatible.

### Fixed

**arch-processor**
- The generated Koin module declared each provider as `factory { get<Provider>() }`, which
  resolves itself recursively; it now uses `factoryOf(::Provider)` so constructor
  dependencies are resolved from Koin.
- Generated Hilt and Koin registries are labelled with the KSP module name
  (`ProviderRegistry.Builder(name = "<Module>")`), so a duplicate `DataImpulse` across Gradle
  modules fails at startup naming both modules.
- The generated Hilt module's testing KDoc used `@BindValue ProviderRegistry`, which clashes
  with the aggregator binding; it now shows `@BindValueIntoSet ProviderRegistryContribution`.
- Stray `}` in the "DataImpulse must be a data class" error message.

**arch**
- Saving `@Serializable` Node state that holds a `DataState` crashed the app on rotation or
  backgrounding with `SerializationException: Serializer for subclass 'Success' is not found in the
  polymorphic scope of 'DataState'` (likewise `Loading` / `Error`) — the pattern the README
  documents (`val products: DataState<List<Product>> = DataState.Idle`). `DataState` was a plain
  sealed interface, so the serialization plugin compiled such properties as polymorphic.
  `DataState` is now serializable (see Added) and `rememberState` saves and restores every variant.
- Binary compatibility with libraries compiled against 1.0.x: `CoordinatorScope.getRegistrations()`,
  `getLifecycleRegistrations()`, `addLifecycleEventRegistration(...)`, `NodeScope.getRegistrations()`,
  the 1.0.x non-composable `NodeScope.Intercept(point, interceptor, priority)` (now hidden,
  binary-only) and the `ProviderScope(SwitchBoard, CoroutineScope)` JVM constructor are restored;
  1.0.x-compiled inline call sites previously failed with `NoSuchMethodError`. Registrations added
  through the restored list after disposal are unregistered immediately.
- The composable `NodeScope.Intercept` registers synchronously during composition again, so a
  `Request` / `ListenFor` / `ReactTo` declared before it in the same `Node` sees the interceptor
  from its first emission (still once per key, released on leave or abandon).
- `NoProviderException` told users to annotate with `@Provider`; the annotation is
  `@SynapseProvider`.
- `NodeScope.Intercept` leaked one interceptor registration per recomposition.
- Coordinator `onCreate` / `onStart` / `onResume` hooks never fired when the owner was already
  past that state.
- A Coordinator created for a `DESTROYED` owner was never disposed.
- A Coordinator whose builder block threw stayed live until `ON_DESTROY`.
- An interceptor registered on a Coordinator or Node concurrently with or after disposal could leak.
- A throwing downstream interceptor killed that type's shared state/reaction channel permanently.
- Primitive types (`Int`, `Long`, …) failed with `ClassCastException` in
  `stateFlow` / `impulseFlow`, in interceptors and as a provider's `Need` type.
- Two concurrent equal requests could start two provider jobs.
- A finishing provider job could evict a newer job for an equal impulse from the dedup table.
- A request that was never collected leaked its provider job forever.
- The `maxConcurrentJobs` gate counted dedup-table entries instead of live jobs.
- The first caller's `Need` type poisoned dedup for callers requesting a super-type or the exact type.
- A coroutine launched from `ProviderScope` that failed escaped to the parent scope's exception
  handler.
- A `CancellationException` thrown by a provider (e.g. from `withTimeout`) left collectors stuck
  in `Loading`.
- Collectors already running when `ProviderManager.dispose()` was called (or its parent scope was
  cancelled) hung forever; they now fail with `IllegalStateException`, as new collectors already
  did. Through `DefaultSwitchBoard.handleRequest`, a collection running when the board is closed
  completes normally instead; calling or collecting `handleRequest` after `close()` still throws.
- A zero provider idle timeout (`stopTimeout = Duration.ZERO`, `SynapseTestRule(stateTimeout = 0)`,
  or `ProviderManager(…, idleTimeoutMillis = 0)`) caused a `StackOverflowError` on an unconfined
  worker. A job is now torn down as soon as its last collector leaves, never before its first
  collector arrives.
- Binary compatibility of the 1.0 `ProviderManager(ProviderRegistry, CoroutineScope,
  CoroutineContext, Int)` constructor (and its default-arguments variant) is restored; pass
  `idleTimeoutMillis` by name to choose a timeout.
- Once the parent scope was cancelled, requests silently returned flows that never ran.
- Calling `Registration.unregister()` twice on an interceptor needlessly invalidated the
  interceptor caches again.
- Tagged scopes never set `TraceContext.parentTraceId`, and tagged `Request`s were not traced.

**core**
- `gate` ended by throwing `GateFinishedException`, which leaked to collectors as a spurious
  `CancellationException` (e.g. from `toList()`).
- `gate(bufferCapacity = 0)` crashed with `NoSuchElementException`.
- `shutter` had a data race on the latest value and hung forever when the upstream never completed.
- `combo` (greedy) fired a held match on an intermediate non-matching step of a longer combo.
- `combo` (greedy) dropped a held match when a different combo completed.
- `combo` (greedy) did not flush a held match when the upstream completed.
- `combo` treated an empty sequence as matching every element.
- `combo` with an infinite timeout overflowed.
- `combo` shared mutable state between its collector and a timer coroutine.
- The custom time source was reset to `System.nanoTime` by every chained `ReactiveFlow` operator.
- `resilient` truncated fractional backoff factors (`1.5^n` became `floor`) and could overflow
  into a hot retry loop.
- `chunk(period = 0)` spun forever.
- `chunk` errors raced with the partial chunk on multi-threaded dispatchers.
- `spy` reported downstream cancellation as `Failed`.
- `DerivedStateFlow` emitted equal consecutive values.

**build**
- The POM license (Apache 2.0) did not match the repository `LICENSE` (MPL 2.0).
- `core` and `arch` applied a bogus `application` plugin / `mainClass`.
