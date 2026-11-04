package com.acme.remotesupport

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.acme.remotesupport.data.local.AppDatabase
import com.acme.remotesupport.ui.AppCoordinator

class RemoteApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "remote.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppCoordinator.presenceObserver)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CH_CAPTURE, getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_CONTROL, getString(R.string.control_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_UPDATE, getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    companion object {
        const val CH_CAPTURE = "capture"
        const val CH_CONTROL = "control"
        const val CH_UPDATE = "update"

        lateinit var instance: RemoteApp
            private set
    }
}
