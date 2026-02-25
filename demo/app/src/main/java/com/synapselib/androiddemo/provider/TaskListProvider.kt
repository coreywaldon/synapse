package com.synapselib.androiddemo.provider

import com.synapselib.androiddemo.data.Task
import com.synapselib.androiddemo.data.TaskDao
import com.synapselib.androiddemo.state.FetchTasks
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.arch.base.SwitchBoardScope
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.arch.base.provider.SynapseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@SynapseProvider
class TaskListProvider @Inject constructor(
    private val taskDao: TaskDao
) : Provider<FetchTasks, List<Task>>() {

    override fun ProviderScope.produce(impulse: FetchTasks): Flow<List<Task>> {
        return taskDao.getAllTasksFlow().map { tasks ->
            tasks.filter { impulse.filter.matches(it) }
        }
    }
}