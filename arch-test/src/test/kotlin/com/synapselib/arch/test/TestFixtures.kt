package com.synapselib.arch.test

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.Impulse

class Ping(val value: String = "ping") : Impulse()
class Pong(val value: String = "pong") : Impulse()
data class Counter(val count: Int)
data class FetchItems(val query: String) : DataImpulse<List<String>>()
