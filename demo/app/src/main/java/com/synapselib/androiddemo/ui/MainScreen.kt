package com.synapselib.androiddemo.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarDefaults.centerAlignedTopAppBarColors
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synapselib.androiddemo.state.FetchTasks
import com.synapselib.androiddemo.state.MainScreenContext
import com.synapselib.androiddemo.state.UpdateAddTaskDialog
import com.synapselib.androiddemo.state.SortChanged
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskFilter
import com.synapselib.androiddemo.state.TaskFilterChanged
import com.synapselib.androiddemo.state.TaskListState
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.arch.base.CreateContext
import com.synapselib.arch.base.LocalSwitchBoard
import com.synapselib.arch.base.addLoggingInterceptors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    CreateContext(MainScreenContext) {
        val scope = rememberCoroutineScope()

        AddTaskDialog()
        DeleteTaskDialog()

        val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection), // Hooks up the scroll behavior
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = context.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    actions = {
                        FilterSortMenu()
                    },
                    scrollBehavior = scrollBehavior
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch { Trigger(UpdateAddTaskDialog()) }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp),
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Add new task") },
                    text = { Text("Add Task", fontWeight = FontWeight.SemiBold) }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
            ) {
                // Added horizontal padding so the cards have some breathing room from the screen edges
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TaskList(TaskListState(filter = TaskFilter.All))
                }
            }
        }
    }
}