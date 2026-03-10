package com.synapselib.androiddemo.coordinators

import androidx.lifecycle.LifecycleOwner
import com.synapselib.androiddemo.data.TaskDao
import com.synapselib.androiddemo.state.SortChanged
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.arch.base.Channel
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.Direction
import com.synapselib.arch.base.Impulse
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.SwitchBoard
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskCoordinator @Inject constructor(
    val taskDao: TaskDao,
    val lifecycleOwner: LifecycleOwner,
) {

    private var coordinator: CoordinatorScope? = null

    fun initialize(switchBoard: SwitchBoard) {
        if (coordinator == null) {
            coordinator = Coordinator(switchBoard, lifecycleOwner) {
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

                Intercept<Any>(InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
                    Interceptor.read {
                        println("Intercepted ${it::class.simpleName}")
                    })
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

