package com.synapselib.androiddemo.coordinators

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.CoordinatorScope
import com.synapselib.arch.base.DefaultSwitchBoard
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoggingCoordinator @Inject constructor(
    val lifecycleOwner: LifecycleOwner,
) {

    private var coordinator: CoordinatorScope? = null

    fun initialize(switchBoard: DefaultSwitchBoard) {
        if (coordinator == null) {
            coordinator = Coordinator(switchBoard, lifecycleOwner) {
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