package com.synapselib.androiddemo.state

import com.synapselib.androiddemo.data.Task
import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.Impulse

data class FetchTasks(val filter: TaskFilter) : DataImpulse<List<Task>>()
data class TaskFilterChanged(val filter: TaskFilter) : Impulse()
data class SortChanged(val type: SortType, val order: SortOrder) : Impulse()
data class TaskUpdated(val task: Task) : Impulse()
data class TaskDeleted(val task: Task) : Impulse()
data class UpdateDeleteDialog(val showDialog: Boolean, val task: Task) : Impulse()
data class UpdateAddTaskDialog(val showDialog: Boolean = true) : Impulse()