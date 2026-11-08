package com.synapselib.arch.test

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.Impulse

class Ping(val value: String = "ping") : Impulse()
class Pong(val value: String = "pong") : Impulse()
data class Counter(val count: Int)
data class FetchItems(val query: String) : DataImpulse<List<String>>()

sealed class Session {
    data object LoggedOut : Session()
    data class LoggedIn(val user: String) : Session()
}

/** A request type no test registers a provider for. */
data class FetchNothing(val id: Int) : DataImpulse<String>()
