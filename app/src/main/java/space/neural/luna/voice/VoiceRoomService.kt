package space.neural.luna.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import space.neural.luna.MainActivity
import space.neural.luna.R

/**
 * Serviço em primeiro plano que mantém o usuário na sala de voz com o app
 * minimizado. Termina quando o usuário sai da sala ou fecha o app
 * (remove das tarefas recentes): isso derruba a ligação.
 */
class VoiceRoomService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            VoiceRoomManager.get(this).leave()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundTypes())
        return START_NOT_STICKY
    }

    /** Fechar o app (tirar das recentes) encerra a ligação. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        VoiceRoomManager.get(this).leave()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.voice_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(getString(R.string.voice_notification_title))
        .setContentText(getString(R.string.voice_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            0, getString(R.string.voice_leave),
            PendingIntent.getService(
                this, 1, Intent(this, VoiceRoomService::class.java).setAction(ACTION_LEAVE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    companion object {
        private const val CHANNEL_ID = "voice_room"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_LEAVE = "space.neural.luna.voice.LEAVE"

        /** Precisa ser chamado com o app em primeiro plano (exigência do Android 14+). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceRoomService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceRoomService::class.java))
        }
    }
}
