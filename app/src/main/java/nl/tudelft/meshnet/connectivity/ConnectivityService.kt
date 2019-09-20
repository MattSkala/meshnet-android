package nl.tudelft.meshnet.connectivity

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class ConnectivityService : Service() {
    private val binder = ConnectivityBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    inner class ConnectivityBinder : Binder() {
        fun getService(): ConnectivityService = this@ConnectivityService
    }
}