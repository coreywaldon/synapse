package com.synapselib.androiddemo

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.synapselib.androiddemo.coordinators.LoggingCoordinator
import com.synapselib.androiddemo.coordinators.TaskCoordinator
import com.synapselib.androiddemo.ui.MainScreen
import com.synapselib.androiddemo.ui.theme.AndroidDemoTheme
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.LocalSwitchBoard
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication: Application() {

    @Inject
    lateinit var switchBoard: DefaultSwitchBoard

    @Inject
    lateinit var taskCoordinator: TaskCoordinator

    @Inject
    lateinit var loggingCoordinator: LoggingCoordinator

    override fun onCreate() {
        super.onCreate()
        taskCoordinator.initialize(switchBoard)
        loggingCoordinator.initialize(switchBoard)
    }

    override fun onTerminate() {
        super.onTerminate()
        taskCoordinator.dispose()
        loggingCoordinator.dispose()
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var switchBoard: DefaultSwitchBoard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidDemoTheme {
                CompositionLocalProvider(LocalSwitchBoard provides switchBoard) {
                    MainScreen()
                }
            }
        }
    }
}