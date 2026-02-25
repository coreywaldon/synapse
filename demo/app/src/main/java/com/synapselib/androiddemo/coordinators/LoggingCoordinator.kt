package com.synapselib.androiddemo.coordinators

import android.util.Log
import com.synapselib.androiddemo.data.TaskDao
import com.synapselib.androiddemo.state.FetchTasks
import com.synapselib.androiddemo.state.SortChanged
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskFilterChanged
import com.synapselib.androiddemo.state.TaskListState
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.androiddemo.state.UpdateAddTaskDialog
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.SwitchBoardScope
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoggingCoordinator @Inject constructor(
    @param:SwitchBoardScope val scope: CoroutineScope
) {

    private var coordinator: CoordinatorScope? = null

    fun initialize(switchBoard: DefaultSwitchBoard) {
        if (coordinator == null) {
            coordinator = Coordinator(switchBoard, scope) {
                switchBoard.setGlobalLogger { point, clazz, data ->
                    Log.d("${point.channel} - ${point.direction}", "${clazz.simpleName}: $data")
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