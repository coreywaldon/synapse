package com.synapselib.androiddemo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.synapselib.androiddemo.state.FilterSortMenuState
import com.synapselib.arch.base.ContextScope
import com.synapselib.arch.base.Node
import com.synapselib.androiddemo.state.ScreenContext
import com.synapselib.androiddemo.state.SortChanged
import com.synapselib.androiddemo.state.SortOrder
import com.synapselib.androiddemo.state.SortType
import com.synapselib.androiddemo.state.TaskFilter
import com.synapselib.androiddemo.state.TaskFilterChanged
import kotlinx.coroutines.launch

@Composable
fun ContextScope<ScreenContext>.FilterSortMenu() {
    Node(FilterSortMenuState()) {
        Box {
            IconButton(onClick = { update { it.copy(expanded = true) } }) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Filter and Sort")
            }

            fun updateSort(newSort: SortChanged) = scope.launch {
                update { it.copy(currentSort = newSort, expanded = false) }
                Trigger(newSort)
            }

            DropdownMenu(
                expanded = state.expanded,
                onDismissRequest = { update { it.copy(expanded = false) } }
            ) {
                Text(
                    text = "SORT BY",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                SortMenuItem(
                    text = "Name",
                    icon = Icons.Default.SortByAlpha,
                    isSelected = state.currentSort.type == SortType.Alphabetical,
                    onClick = { updateSort(state.currentSort.copy(type = SortType.Alphabetical)) }
                )

                SortMenuItem(
                    text = "Status",
                    icon = Icons.Default.Info,
                    isSelected = state.currentSort.type == SortType.Status,
                    onClick = { updateSort(state.currentSort.copy(type = SortType.Status)) }
                )

                SortMenuItem(
                    text = "ID",
                    icon = Icons.Default.Numbers,
                    isSelected = state.currentSort.type == SortType.Id,
                    onClick = { updateSort(state.currentSort.copy(type = SortType.Id)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "SORT ORDER",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                SortMenuItem(
                    text = "Ascending",
                    icon = Icons.Default.ArrowUpward,
                    isSelected = state.currentSort.order == SortOrder.Ascending,
                    onClick = { updateSort(state.currentSort.copy(order = SortOrder.Ascending)) }
                )

                SortMenuItem(
                    text = "Descending",
                    icon = Icons.Default.ArrowDownward,
                    isSelected = state.currentSort.order == SortOrder.Descending,
                    onClick = { updateSort(state.currentSort.copy(order = SortOrder.Descending)) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                DropdownMenuItem(
                    text = { Text("Search by Title") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    onClick = {
                        update { it.copy(showSearchDialog = true, expanded = false) }
                    }
                )
            }
        }

        if (state.showSearchDialog) {
            AlertDialog(
                onDismissRequest = { update { it.copy(showSearchDialog = false) } },
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                title = { Text("Filter Tasks") },
                text = {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { newQuery -> update { it.copy(searchQuery = newQuery) } },
                        placeholder = { Text("Enter title") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { update { it.copy(searchQuery = "") } }) {
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
                            scope.launch { Trigger(TaskFilterChanged(TaskFilter.Search(state.searchQuery))) }
                            update { it.copy(showSearchDialog = false) }
                        }
                    ) {
                        Text("Apply")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            scope.launch { Trigger(TaskFilterChanged(TaskFilter.All)) }
                            update { it.copy(showSearchDialog = false, searchQuery = "") }
                        }
                    ) {
                        Text("Clear Filter")
                    }
                }
            )
        }
    }
}

@Composable
private fun SortMenuItem(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        onClick = onClick
    )
}