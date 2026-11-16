package com.anbhosting.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

class LocalHostingService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: LocalHostingStore
    private var server: LocalStaticServer? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var wifiCallbackRegistered = false
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = reconfigureForWifiChange()
        override fun onLost(network: android.net.Network) = reconfigureForWifiChange()
        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            linkProperties: android.net.LinkProperties,
        ) = reconfigureForWifiChange()
    }

    override fun onCreate() {
        super.onCreate()
        store = LocalHostingStore(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
            wifiCallbackRegistered = true
        } catch (_: Exception) {
            store.recordEvent(null, "server.network_monitor_unavailable", "Wi-Fi changes may require restarting hosting.")
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            executor.execute {
                store.listSites().filter { it.status == LocalHostingStore.STATUS_ACTIVE }
                    .forEach { store.setSiteStatus(it.id, LocalHostingStore.STATUS_STOPPED) }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        executor.execute {
            val activeSites = store.listSites().filter { it.status == LocalHostingStore.STATUS_ACTIVE }
            if (activeSites.isEmpty()) {
                stopSelf(startId)
                return@execute
            }
            if (intent?.action == ACTION_RECONFIGURE) {
                server?.stop()
                server = null
            }
            if (server == null) {
                try {
                    server = LocalStaticServer(this, store).also { it.start(store.isLanSharingEnabled()) }
                } catch (error: Exception) {
                    activeSites.forEach { store.setSiteStatus(it.id, LocalHostingStore.STATUS_STOPPED) }
                    store.recordEvent(null, "server.start_failed", error.message.orEmpty())
                    stopSelf(startId)
                }
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (wifiCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(wifiCallback) }
            wifiCallbackRegistered = false
        }
        server?.stop()
        server = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val activeCount = store.listSites().count { it.status == LocalHostingStore.STATUS_ACTIVE }
        val network = if (store.isLanSharingEnabled()) "Wi-Fi sharing on" else "This phone only"
        val stopIntent = Intent(this, LocalHostingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("ANB Hosting is running")
            .setContentText("$activeCount local website(s) · $network")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop hosting", stopPendingIntent)
            .build()
    }

    private fun reconfigureForWifiChange() {
        if (!::store.isInitialized || !store.isLanSharingEnabled()) return
        runCatching {
            executor.execute {
                if (store.listSites().none { it.status == LocalHostingStore.STATUS_ACTIVE }) return@execute
                server?.stop()
                server = null
                try {
                    server = LocalStaticServer(this, store).also { it.start(true) }
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIFICATION_ID, buildNotification())
                } catch (error: Exception) {
                    store.listSites().filter { it.status == LocalHostingStore.STATUS_ACTIVE }
                        .forEach { store.setSiteStatus(it.id, LocalHostingStore.STATUS_STOPPED) }
                    store.recordEvent(null, "server.network_rebind_failed", error.message.orEmpty())
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(NOTIFICATION_ID)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local website hosting",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows when websites are being hosted on this phone." }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "com.anbhosting.app.action.STOP_LOCAL_HOSTING"
        const val ACTION_RECONFIGURE = "com.anbhosting.app.action.RECONFIGURE_LOCAL_HOSTING"
        private const val CHANNEL_ID = "anb_local_hosting"
        private const val NOTIFICATION_ID = 8787
    }
}
