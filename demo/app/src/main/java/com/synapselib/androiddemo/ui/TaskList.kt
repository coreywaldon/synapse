package com.synapselib.androiddemo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.synapselib.androiddemo.data.Task
import com.synapselib.androiddemo.state.FetchTasks
import com.synapselib.androiddemo.state.ScreenContext
import com.synapselib.androiddemo.state.SortChanged
import com.synapselib.androiddemo.state.SortOrder
import com.synapselib.androiddemo.state.SortType
import com.synapselib.androiddemo.state.TaskFilterChanged
import com.synapselib.androiddemo.state.TaskListState
import com.synapselib.arch.base.ContextScope
import com.synapselib.arch.base.Node
import com.synapselib.core.typed.DataState

@Composable
fun ContextScope<ScreenContext>.TaskList(initialState: TaskListState) {
    Node(initialState) {
        val scrollState = key(state.filter, state.sortType, state.sortOrder) {
            rememberLazyListState()
        }

        val currentIndex = scrollState.firstVisibleItemIndex
        val currentOffset = scrollState.firstVisibleItemScrollOffset

        Request(FetchTasks(state.filter)) { rawTasks ->
            update {
                it.copy(tasks = rawTasks)
            }
        }

        ReactTo<TaskFilterChanged> { filterImpulse ->
            update {
                it.copy(filter = filterImpulse.filter)
            }
        }

        ReactTo<SortChanged> { imp ->
            update { it.copy(sortType = imp.type, sortOrder = imp.order) }
        }

        when (val taskState = state.tasks) {
            is DataState.Success -> {
                val sortedTasks = sortTasks(taskState.data, state.sortType, state.sortOrder)

                remember(sortedTasks) {
                    scrollState.requestScrollToItem(currentIndex, currentOffset)
                    true
                }

                remember(state.filter, state.sortType, state.sortOrder) {
                    scrollState.requestScrollToItem(0, 0)
                    true
                }

                if (sortedTasks.isEmpty()) {
                    EmptyTasksView()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = scrollState,
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "TopScrollAnchor") {
                            Spacer(modifier = Modifier.height(0.dp))
                        }

                        items(sortedTasks, key = { it.id }) { task ->
                            TaskItem(
                                task = task,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            is DataState.Error -> ErrorTasksView(taskState.cause)
            is DataState.Loading -> LoadingTasksView()
            is DataState.Idle -> { /* Do nothing */
            }
        }
    }
}

private fun sortTasks(tasks: List<Task>, type: SortType, order: SortOrder): List<Task> {
    val sorted = when (type) {
        SortType.Alphabetical -> tasks.sortedBy { it.title.lowercase() }
        SortType.Id -> tasks.sortedBy { it.id }
        SortType.Status -> tasks.sortedBy { it.done }
    }
    return if (order == SortOrder.Descending) sorted.reversed() else sorted
}

@Composable
private fun EmptyTasksView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Done,
            contentDescription = "No tasks",
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No tasks here.\nEnjoy your day!",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorTasksView(cause: Throwable?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Error",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Couldn't load tasks",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = cause.toString(),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LoadingTasksView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp
        )
    }
}