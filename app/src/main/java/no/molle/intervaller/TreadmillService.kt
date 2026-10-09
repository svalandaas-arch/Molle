package no.molle.intervaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock

/**
 * Holder appen i live i bakgrunnen mens den sender fart til Zwift eller har kontakt med pulsmåler,
 * og viser status i varslingsfeltet.
 */
class TreadmillService : Service() {

    companion object {
        private const val CHANNEL = "okt"
        private const val ID = 1
        @Volatile private var instance: TreadmillService? = null

        fun ensure(c: Context) {
            val want = BleHub.needsService
            val intent = Intent(c, TreadmillService::class.java)
            try {
                if (want && instance == null) c.startForegroundService(intent)
                else if (!want && instance != null) c.stopService(intent)
                else refresh()
            } catch (_: Exception) {
            }
        }

        fun refresh() {
            instance?.update(false)
        }
    }

    private var lastUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Økt og Bluetooth", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun update(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUpdate < 1000) return
        lastUpdate = now
        try {
            getSystemService(NotificationManager::class.java).notify(ID, build())
        } catch (_: Exception) {
        }
    }

    private fun build(): Notification {
        val title = if (BleHub.sessionActive && BleHub.label.isNotEmpty())
            "${BleHub.label} · ${BleHub.remain} igjen"
        else
            "Mølleintervaller"
        val parts = mutableListOf<String>()
        if (BleHub.rscOn) {
            parts.add(if (BleHub.zwiftConnected) "Zwift: ${BleHub.rscSpeedText()} km/t" else "Venter på Zwift")
        }
        if (BleHub.hrConnected) parts.add(if (BleHub.hrBpm > 0) "Puls ${BleHub.hrBpm}" else "Pulsmåler tilkoblet")
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · ").ifEmpty { "Klar" })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .build()
    }
}
