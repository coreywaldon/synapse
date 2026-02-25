package com.synapselib.androiddemo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.synapselib.arch.base.SwitchBoardScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@Database(entities = [Task::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
}

class AppDatabaseCallback @Inject constructor(
    private val databaseProvider: Provider<AppDatabase>,
    @param:SwitchBoardScope private val applicationScope: CoroutineScope
) : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        applicationScope.launch(Dispatchers.IO) {
            populateDatabase()
        }
    }

    private suspend fun populateDatabase() {
        val taskDao = databaseProvider.get().taskDao()

        val defaultTasks = listOf(
            Task(title = "Buy groceries", done = false),
            Task(title = "Walk the dog", done = false),
            Task(title = "Read a book", done = true),
            Task(title = "Do laundry", done = false),
            Task(title = "Clean the kitchen", done = false),
            Task(title = "Pay electricity bill", done = true),
            Task(title = "Call mom", done = false),
            Task(title = "Schedule dentist appointment", done = false),
            Task(title = "Water the plants", done = false),
            Task(title = "Take out the trash", done = true),
            Task(title = "Meal prep for the week", done = false),
            Task(title = "Vacuum the living room", done = false),
            Task(title = "Reply to emails", done = false),
            Task(title = "Go for a run", done = true),
            Task(title = "Buy birthday gift for Sarah", done = false),
            Task(title = "Wash the car", done = false),
            Task(title = "Organize the closet", done = false),
            Task(title = "Renew gym membership", done = true),
            Task(title = "Pick up dry cleaning", done = false),
            Task(title = "Fix the leaky faucet", done = false),
            Task(title = "Buy stamps", done = false),
            Task(title = "Return library books", done = true),
            Task(title = "Plan weekend trip", done = false),
            Task(title = "Backup computer files", done = false),
            Task(title = "Clean out the fridge", done = false),
            Task(title = "Buy coffee beans", done = true),
            Task(title = "Check tire pressure", done = false),
            Task(title = "Meditate for 10 minutes", done = false),
            Task(title = "Write journal entry", done = false)
        )

        taskDao.insertTasks(defaultTasks)
    }
}