package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.FolderWidgetApplication

class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED
        ) {
            val app = context.applicationContext as? FolderWidgetApplication
            app?.repository?.notifyAllWidgets()
        }
    }
}
