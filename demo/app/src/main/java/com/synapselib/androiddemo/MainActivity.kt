package com.synapselib.androiddemo

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.synapselib.androiddemo.coordinators.TaskCoordinator
import com.synapselib.androiddemo.ui.MainScreen
import com.synapselib.androiddemo.ui.theme.AndroidDemoTheme
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.LocalSwitchBoard
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.cancel
import javax.inject.Inject

@HiltAndroidApp
class MainApplication: Application()

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var switchBoard: DefaultSwitchBoard

    @Inject
    lateinit var taskCoordinator: TaskCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        taskCoordinator.initialize(switchBoard)
        setContent {
            AndroidDemoTheme {
                CompositionLocalProvider(LocalSwitchBoard provides switchBoard) {
                    MainScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        taskCoordinator.dispose()
    }
}