package com.synapselib.core.logic

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

// --- Infix Logic Operators ---

infix fun StateFlow<Boolean>.and(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> args[0] && args[1] }

infix fun StateFlow<Boolean>.or(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> args[0] || args[1] }

infix fun StateFlow<Boolean>.xor(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> args[0] xor args[1] }

infix fun StateFlow<Boolean>.nand(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> !(args[0] && args[1]) }

infix fun StateFlow<Boolean>.nor(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> !(args[0] || args[1]) }

infix fun StateFlow<Boolean>.xnor(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> !(args[0] xor args[1]) }

infix fun StateFlow<Boolean>.imp(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> !args[0] || args[1] }

infix fun StateFlow<Boolean>.nimp(other: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, other) { args -> args[0] && !args[1] }

fun StateFlow<Boolean>.not(): StateFlow<Boolean> =
    DerivedStateFlow(this) { args -> !args[0] }

// --- Vararg Logic Extensions ---

fun StateFlow<Boolean>.and(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> args.all { it } }

fun StateFlow<Boolean>.or(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> args.any { it } }

fun StateFlow<Boolean>.xor(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> args.count { it } % 2 == 1 }

fun StateFlow<Boolean>.nand(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> !args.all { it } }

fun StateFlow<Boolean>.nor(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> !args.any { it } }

fun StateFlow<Boolean>.xnor(vararg others: StateFlow<Boolean>): StateFlow<Boolean> =
    DerivedStateFlow(this, *others) { args -> args.count { it } % 2 == 0 }

// --- Internal Implementation ---

/**
 * A lightweight StateFlow that derives its value from parent flows.
 * It computes .value synchronously and combines updates for collectors.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class DerivedStateFlow(
    private vararg val flows: StateFlow<Boolean>,
    private val transform: (Array<Boolean>) -> Boolean
) : StateFlow<Boolean> {

    override val value: Boolean
        get() = transform(flows.map { it.value }.toTypedArray())

    override val replayCache: List<Boolean>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
        // Combine ensures we emit whenever any parent changes
        combine(flows.toList()) { values ->
            transform(values)
        }.collect(collector)
        // StateFlows are hot and never complete normally
        throw kotlinx.coroutines.CancellationException("DerivedStateFlow should not complete")
    }
}