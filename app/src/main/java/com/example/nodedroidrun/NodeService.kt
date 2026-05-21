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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // START_NOT_STICKY: se o sistema matar o serviço, ele NÃO tenta reiniciar automaticamente.
        // Evita loops de crash que corrompem o emulador durante o desenvolvimento.
        // Trocar para START_STICKY apenas quando o app estiver estável em produção.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        
        // Flag compatível com Android 12+ (API 31+) e anteriores
        val pendingIntentFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlag
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Servidor Node.js Ativo")
            .setContentText("Serviço em execução em segundo plano.")
            .setSmallIcon(R.mipmap.ic_launcher) // Ícone padrão do launcher do app
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Torna a notificação persistente
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
