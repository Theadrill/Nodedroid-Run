package com.example.nodedroidrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class NodeService : Service() {

    companion object {
        private const val CHANNEL_ID = "node_service_channel"
        private const val NOTIFICATION_ID = 101
    }

    inner class NodeBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    private val binder = NodeBinder()
    private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ProcessManager.onCountChanged = { count ->
            activeCount = count
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        ProcessManager.onCountChanged = null
    }

    fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlag
        )

        val text = if (activeCount > 0) {
            "$activeCount projeto(s) em execução"
        } else {
            "Serviço em execução em segundo plano."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nodedroid Run")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal do Servidor Node.js",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificações para manter o servidor Node.js rodando em background."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
