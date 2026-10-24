package cz.kubat.auto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import cz.kubat.auto.data.AppLog
import cz.kubat.auto.data.Db
import cz.kubat.auto.data.Settings
import cz.kubat.auto.record.LiveState
import cz.kubat.auto.sync.SyncWorker

class App : Application() {
    lateinit var settings: Settings
        private set
    lateinit var db: Db
        private set
    val live = LiveState()

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        db = Db(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        AppLog.i("App start ${BuildConfigInfo.versionName(this)} on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        SyncWorker.schedulePeriodic(this)
        SyncWorker.runNow(this)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        lateinit var instance: App
            private set
    }
}
