package com.synapselib.androiddemo.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.synapselib.androiddemo.data.Task
import com.synapselib.androiddemo.state.AddTaskDialogState
import com.synapselib.androiddemo.state.DeleteDialogState
import com.synapselib.androiddemo.state.UpdateAddTaskDialog
import com.synapselib.androiddemo.state.ScreenContext
import com.synapselib.androiddemo.state.TaskDeleted
import com.synapselib.androiddemo.state.TaskUpdated
import com.synapselib.androiddemo.state.UpdateDeleteDialog
import com.synapselib.arch.base.ContextScope
import com.synapselib.arch.base.Node
import kotlinx.coroutines.launch

@Composable
fun ContextScope<ScreenContext>.DeleteTaskDialog() {
    Node(initialState = DeleteDialogState(false, task = null)) {
        ReactTo<UpdateDeleteDialog> { dialogUpdate ->
            update { it.copy(showDialog = dialogUpdate.showDialog, task = dialogUpdate.task) }
        }
        if (state.showDialog) {
            state.task?.let { task ->
                AlertDialog(
                    onDismissRequest = {
                        scope.launch {
                            Trigger(UpdateDeleteDialog(false, task))
                        }
                    },
                    icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    title = { Text("Delete Task") },
                    text = { Text("Are you sure you want to delete '${task.title}'?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    Trigger(UpdateDeleteDialog(false, task))
                                    Trigger(TaskDeleted(task))
                                }
                            }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { scope.launch { Trigger(UpdateDeleteDialog(false, task)) } }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextScope<ScreenContext>.AddTaskDialog() {
    Node(AddTaskDialogState()) {

        ReactTo<UpdateAddTaskDialog> { updateDialog ->
            update { it.copy(isVisible = updateDialog.showDialog) }
        }

        if (state.isVisible) {
            AlertDialog(
                onDismissRequest = {
                    update { it.copy(isVisible = false, taskTitle = "") }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                title = { Text("Add New Task") },
                text = {
                    OutlinedTextField(
                        value = state.taskTitle,
                        onValueChange = { newTitle ->
                            update { it.copy(taskTitle = newTitle) }
                        },
                        placeholder = { Text("Enter task title") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        trailingIcon = {
                            if (state.taskTitle.isNotEmpty()) {
                                IconButton(onClick = { update { it.copy(taskTitle = "") } }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear text")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (state.taskTitle.isNotBlank()) {
                                val newTask = Task(title = state.taskTitle, done = false)

                                scope.launch {
                                    Trigger(TaskUpdated(newTask))
                                }

                                update { it.copy(isVisible = false, taskTitle = "") }
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            update { it.copy(isVisible = false, taskTitle = "") }
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}