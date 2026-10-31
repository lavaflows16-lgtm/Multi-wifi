package app.multiwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Shared state the UI polls. */
object BondState {
    @Volatile var running = false
    @Volatile var error: String? = null
    @Volatile var lines: List<String> = emptyList()
}

class BondService : Service() {

    private var pool: NetworkPool? = null
    private var proxy: Socks5Proxy? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification())
        if (!BondState.running) start(intent)
        return START_NOT_STICKY
    }

    private fun start(intent: Intent?) {
        val ssids = intent?.getStringArrayListExtra(EXTRA_SSIDS).orEmpty()
        val passwords = intent?.getStringArrayListExtra(EXTRA_PASSWORDS).orEmpty()
        val useCellular = intent?.getBooleanExtra(EXTRA_CELLULAR, false) ?: false

        val p = NetworkPool(this)
        p.onChange = { publish(p) }
        ssids.forEachIndexed { i, ssid -> if (ssid.isNotBlank()) p.requestWifi(ssid.trim(), passwords.getOrNull(i)) }
        p.trackSystemWifi()
        if (useCellular) p.requestCellular()

        try {
            val s = Socks5Proxy(p)
            s.start()
            proxy = s
            BondState.error = null
        } catch (e: Exception) {
            BondState.error = "Could not start proxy: ${e.message}"
        }
        pool = p
        BondState.running = true
        publish(p)
    }

    private fun publish(p: NetworkPool) {
        BondState.lines = p.snapshot().map {
            "${it.label}  ·  ${it.inflight.get()} active  ·  ${it.totalConnections} total"
        }
    }

    private fun shutdown() {
        proxy?.stop(); proxy = null
        pool?.stop(); pool = null
        BondState.running = false
        BondState.lines = emptyList()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "MultiWifi", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("MultiWifi is running")
            .setContentText("SOCKS5 proxy on 127.0.0.1:1080")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "app.multiwifi.STOP"
        const val EXTRA_SSIDS = "ssids"
        const val EXTRA_PASSWORDS = "passwords"
        const val EXTRA_CELLULAR = "cellular"
        private const val CHANNEL = "multiwifi"
    }
}
