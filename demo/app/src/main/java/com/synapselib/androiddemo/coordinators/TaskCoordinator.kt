package com.synapselib.androiddemo.coordinators

import com.synapselib.androiddemo.data.TaskDao
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.SwitchBoardScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskCoordinator @Inject constructor(
    val taskDao: TaskDao,
    @param:SwitchBoardScope val scope: CoroutineScope
) {

    private var coordinator: CoordinatorScope? = null

    fun initialize(switchBoard: SwitchBoard) {
        if (coordinator == null) {
            Coordinator(switchBoard, scope) {
                ReactTo<TaskUpdated> {
                    launch {
                        taskDao.insertTask(it.task)
                    }
                }
                ReactTo<TaskDeleted> {
                    launch {
                        taskDao.deleteTask(it.task)
                    }
                }
            }
        } else {
            coordinator?.dispose()
            coordinator = null
            initialize(switchBoard)
        }
    }

    fun dispose() {
        coordinator?.dispose()
        coordinator = null
    }
}