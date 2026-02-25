# Synapse

![Maven Central](https://img.shields.io/maven-central/v/com.synapselib/synapse-lib)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.0-blue.svg?logo=kotlin)
![License](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)
![Build Status](https://img.shields.io/badge/build-passing-brightgreen)

**Advanced Reactive Operators for Kotlin Coroutines.**

Synapse extends the capabilities of standard Kotlin `Flow` by introducing a suite of powerful, time-aware, and stateful operators commonly found in functional reactive programming (FRP) but missing from the standard library.

It bridges the gap between simple Coroutines and complex event processing, handling scenarios like time-windowed deduplication, conditional gating, and sequence detection with ease.

---

## 🚀 Why Synapse?

Standard Kotlin Flows are excellent for asynchronous data, but they often lack the high-level control flow operators found in libraries like RxJava. Synapse fills this void without the overhead of a full Rx migration.

*   **Declarative Control:** Pause and resume streams without complex `Job` management.
*   **Time-Aware:** Built-in operators for pacing, squashing, and windowing events.
*   **Zero Boilerplate:** Clean, chainable extension functions that fit right into your existing Coroutines codebase.

## 📦 Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.synapselib:synapse-lib:1.0.1")
}
```

## ⚡ Quick Start

You can wrap any existing `Flow` using `.asReactive()` or use the provided extension functions directly.

```kotlin
// Example: A robust analytics tracker that buffers when offline
// and prevents spamming the server.
analyticsEvents
    .gate(isNetworkAvailable, strategy = GateStrategy.BUFFER) // Buffer when offline
    .squash(500.milliseconds)                                 // Remove duplicates within 500ms
    .pace(100.milliseconds)                                   // Cap emission rate
    .collect { event ->
        serverApi.track(event)
    }
```

## 🧠 State Logic

Synapse treats `StateFlow<Boolean>` as a first-class citizen. You can combine multiple state flows using standard infix logic gates without the boilerplate of `combine`.

**Available Operators:** `and`, `or`, `xor`, `nand`, `nor`, `xnor`, `imp`, `nimp`, `not`.

```kotlin
val isFormValid: StateFlow<Boolean> = ...
val isNetworkAvailable: StateFlow<Boolean> = ...
val isSubmitting: StateFlow<Boolean> = ...

// Combine states declaratively
val canSubmit = (isFormValid and isNetworkAvailable) and isSubmitting.not()
```

## 📚 Core Operators

### `gate`
**Control Flow Valve**

Acts as a controllable valve for your data stream. You can pause execution based on a secondary boolean flow.

| Strategy | Description | Use Case |
| :--- | :--- | :--- |
| `BUFFER` | Queues events when closed, emits them in order when opened. | Analytics, offline syncing. |
| `LATEST` | Keeps only the most recent event when closed. | UI State updates. |
| `DROP` | Ignores all events when closed. | Sensor noise, temporary muting. |

<details>
<summary><b>View Examples & Visuals</b></summary>

#### Strategy: LATEST
Perfect for UI states where intermediate values don't matter.
![Gate Latest](/viz/gate-latest.gif)

#### Strategy: BUFFER
Ideal for analytics or critical data that cannot be lost while offline.
![Gate Buffer](/viz/gate-buffer.gif)

#### Strategy: DROP
Useful for ignoring sensor noise during specific physical states.
![Gate Drop](/viz/gate-drop.gif)

```kotlin
analyticsFlow.gate(isNetworkAvailable, GateStrategy.BUFFER)
    .collect { sendToServer(it) }
```
</details>

---

### `squash`
**Time-Windowed Deduplication**

Unlike `distinctUntilChanged`, `squash` forgets the value after a specific retention period, allowing it to be re-emitted if it occurs again later.

> **Use Case:** Preventing double-clicks or spammy error logs that need to be reported occasionally, but not continuously.

```kotlin
buttonClicks
    .squash(retentionPeriod = 500.milliseconds)
    .collect { handleClick() }
```

---

### `pace`
**Rate Limiting (Smoothing)**

Limits the rate of emissions by ensuring a minimum time `interval` elapses between items. Unlike `debounce`, this does not drop items; it delays them to smooth out the stream.

> **Use Case:** Smoothing out complex rendering updates to prevent UI jank.

```kotlin
renderRequests
    .pace(interval = 16.milliseconds) // Cap at ~60fps
    .collect { renderFrame(it) }
```

---

### `combo`
**Sequence Detection**

Detects specific sequences of values emitted within a defined time window. It utilizes a Trie-based automaton to efficiently match sequences of inputs against a set of target sequences.

```kotlin
val konamiCode = listOf(UP, UP, DOWN, DOWN, LEFT, RIGHT, LEFT, RIGHT, B, A)

inputEvents
    .combo(sequences = listOf(konamiCode), timeout = 2.seconds)
    .collect { println("Cheat Code Activated!") }
```

---

### `chunk`
**Bundling signals**

Take an input value, then waits until a duration is up. Combines all values in that period into a list, then emits it.

```kotlin
// Example: Batching log entries every 5 seconds or when 100 items are reached
logFlow
    .chunk(period = 5.seconds, maxBufferSize = 100)
    .collect { batch ->
        cloudLogger.sendBatch(batch)
    }
```

---

### `shutter`
**Trigger-Based Sampling**

Samples the latest value from the upstream flow only when a secondary trigger flow emits. Acts like a camera shutter.

```kotlin
// Read sensor value only when user clicks "Measure"
sensorReadings
    .shutter(trigger = measureButtonClick)
    .collect { recordMeasurement(it) }
```

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

Synapse is available under the Mozilla Public License 2.0. See the [LICENSE](LICENSE) file for more info.
