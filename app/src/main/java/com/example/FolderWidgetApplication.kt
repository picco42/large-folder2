package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.FolderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FolderWidgetApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: FolderRepository by lazy { FolderRepository(this, database) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        appScope.launch {
            repository.purgeLegacyDefaultFoldersIfNeeded()
            repository.notifyAllWidgets()
        }
    }

    companion object {
        lateinit var instance: FolderWidgetApplication
            private set
    }
}
