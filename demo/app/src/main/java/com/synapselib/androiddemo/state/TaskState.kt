package com.synapselib.androiddemo.state

import com.synapselib.androiddemo.data.Task
import com.synapselib.core.typed.DataState

enum class SortType { Alphabetical, Id, Status }
enum class SortOrder { Ascending, Descending }

data class TaskListState(
    val tasks: DataState<List<Task>> = DataState.Loading,
    val filter: TaskFilter = TaskFilter.All,
    val sortType: SortType = SortType.Id,
    val sortOrder: SortOrder = SortOrder.Ascending
)

sealed interface TaskFilter {
    fun matches(task: Task): Boolean

    data object All : TaskFilter {
        override fun matches(task: Task) = true
    }

    data object Active : TaskFilter {
        override fun matches(task: Task) = !task.done
    }

    data object Completed : TaskFilter {
        override fun matches(task: Task) = task.done
    }

    // Unified filter for both ID and Title
    data class Search(val query: String) : TaskFilter {
        override fun matches(task: Task): Boolean {
            val matchesTitle = task.title.contains(query, ignoreCase = true)
            val matchesId = task.id.toString().contains(query)
            return matchesTitle || matchesId
        }
    }
}

data class DeleteDialogState(val showDialog: Boolean, val task: Task?)

data class AddTaskDialogState(
    val isVisible: Boolean = false, val taskTitle: String = ""
)

data class FilterSortMenuState(
    val expanded: Boolean = false,
    val showSearchDialog: Boolean = false,
    val searchQuery: String = "",
    val currentSort: SortChanged = SortChanged(SortType.Id, SortOrder.Ascending)
)