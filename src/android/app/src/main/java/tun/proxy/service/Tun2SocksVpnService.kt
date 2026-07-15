package tun.proxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import engine.Engine
import engine.Key
import tun.proxy.BuildConfig
import tun.proxy.MainActivity
import tun.proxy.MyApplication
import tun.proxy.R
import tun.proxy.util.ProxyHealthCheck
import tun.proxy.util.ProxyUrlBuilder
import tun.proxy.widget.VpnWidgetProvider
import tun.utils.Utils
import java.util.concurrent.CountDownLatch

class Tun2SocksVpnService : VpnService() {
    private val TAG = "Tun2SocksVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var vpnThread: Thread? = null
    @Volatile private var isStopping = false
    private var proxyData: String? = null
    private var utils: Utils? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceName: String = "ProxSox"
    private val channelId = "${BuildConfig.APPLICATION_ID}_vpn_channel"
    private val channelName = serviceName
    private var stopSignal = CountDownLatch(1)

    private var networkMonitor: NetworkMonitor? = null
    private var failoverProxy: String? = null
    // Effective Remote DNS for the current connection, resolved in onStartCommand.
    @Volatile private var effectiveRemoteDns: Boolean = true

    companion object {
        const val ACTION_STOP_SERVICE = "${BuildConfig.APPLICATION_ID}.STOP_VPN_SERVICE"
        /** Re-runs just the proxy reachability probe, without restarting the tunnel. */
        const val ACTION_RECHECK_PROXY = "${BuildConfig.APPLICATION_ID}.RECHECK_PROXY"
        /** Intent extra (Boolean): effective Remote DNS for this connection. */
        const val EXTRA_REMOTE_DNS = "remote_dns"
        private const val PREF_LAST_PROXY = "last_proxy_data"
        private const val PREF_FAILOVER_PROXY = "failover_proxy_data"
        private const val PREF_EFFECTIVE_REMOTE_DNS = "effective_remote_dns"
        private const val PREFS_NAME = "vpn_service_prefs"

        // Remote DNS preferences (stored in default SharedPreferences, not encrypted)
        const val PREF_REMOTE_DNS_ENABLED = "remote_dns_enabled"
        const val PREF_FAKE_IP_SUBNET     = "fake_ip_subnet"
        const val DEFAULT_FAKE_IP_SUBNET  = "10.255.0.0/16"
        /** IP address advertised to apps as the DNS server when Remote DNS is on. */
        const val FAKE_DNS_SERVER_IP      = "10.1.10.2"

        @Volatile
        var isActive: Boolean = false
            private set

        /**
         * Masks any credentials embedded in a proxy URL for display/logging,
         * regardless of protocol: user:pass@ (http/socks5/relay/ssh),
         * method:password@ (ss), or a lone userid@ (socks4). Also strips the
         * query string, since SSH URLs carry a private-key file path and
         * passphrase there.
         */
        fun maskProxyUrl(url: String): String {
            val withoutQuery = url.substringBefore("?")
            return withoutQuery.replace("""^([a-zA-Z][a-zA-Z0-9+.\-]*://)([^@/]+)@""".toRegex()) { match ->
                "${match.groupValues[1]}***@"
            }
        }

        /** Extracts host:port from a tun2socks proxy URL, regardless of protocol. */
        fun extractHostPort(url: String): Pair<String, Int>? {
            val withoutQuery = url.substringBefore("?")
            val afterScheme = withoutQuery.substringAfter("://", withoutQuery)
            val hostPort = afterScheme.substringAfterLast("@")
            val idx = hostPort.lastIndexOf(":")
            if (idx <= 0) return null
            val host = hostPort.substring(0, idx)
            val port = hostPort.substring(idx + 1).toIntOrNull() ?: return null
            return host to port
        }
    }

    override fun onCreate() {
        super.onCreate()
        utils = Utils(this)
        createNotificationChannel()
        acquireWakeLock()
        networkMonitor = NetworkMonitor(this) {
            // Network restored — auto-reconnect if VPN was active
            val lastProxy = getPersistedProxy()
            if (lastProxy != null && !isStopping && !isRunning()) {
                Log.d(TAG, "Network restored, reconnecting VPN")
                val reconnectIntent = Intent(this, Tun2SocksVpnService::class.java)
                reconnectIntent.putExtra("data", lastProxy)
                startService(reconnectIntent)
            }
        }
        networkMonitor?.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = "$channelName VPN Service Channel"
                lightColor = Color.BLUE
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
                setSound(null, null)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ProxSox::VpnWakeLock"
        ).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action ${intent?.action} flags ${flags} startId ${startId}")
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "onStartCommand: stop service")
            isStopping = true
            stopVpn()
            utils?.setVpnStatus(false)
            utils?.setProxyName("")
            broadcastState(VpnState.DISCONNECTED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RECHECK_PROXY) {
            Log.d(TAG, "onStartCommand: recheck proxy")
            recheckProxyAsync()
            return START_NOT_STICKY
        }

        // Get proxy data from intent, or recover from saved state after system kill
        var data = intent?.extras?.getString("data")
        if (data == null) {
            data = getPersistedProxy()
        }
        if (data == null) {
            Log.e(TAG, "onStartCommand: no proxy data available")
            broadcastState(VpnState.FAILED, "No proxy data provided")
            stopSelf()
            return START_NOT_STICKY
        }

        proxyData = data
        persistProxy(data)
        effectiveRemoteDns = resolveRemoteDns(intent)

        // Failover proxy (optional)
        val failover = intent?.extras?.getString("failover")
        if (failover != null) {
            failoverProxy = failover
            getEncryptedPrefs().edit().putString(PREF_FAILOVER_PROXY, failover).apply()
        } else {
            failoverProxy = getEncryptedPrefs().getString(PREF_FAILOVER_PROXY, null)
        }

        // Reset state for a fresh start
        isStopping = false
        stopSignal = CountDownLatch(1)

        broadcastState(VpnState.CONNECTING)

        vpnThread = object : Thread("VpnThread") {
            override fun run() {
                try {
                    Log.d(TAG, "VpnThread started")
                    utils?.setVpnStatus(true)
                    startVpn(data)
                    Log.d(TAG, "VpnThread finished")
                } catch (e: Exception) {
                    Log.e(TAG, "vpnThread: fail", e)
                    utils?.setVpnStatus(false)
                    broadcastState(VpnState.FAILED, e.message)
                }
            }
        }
        vpnThread?.start()

        startForeground(1, buildNotification(data))
        Log.d(TAG, "onStartCommand: start sticky")
        return START_STICKY
    }

    private fun buildNotification(@Suppress("UNUSED_PARAMETER") proxy: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, Tun2SocksVpnService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notify_vpn_active))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.btn_disconnect), stopPending)
            .build()
    }

    private fun getEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKey,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun persistProxy(proxy: String) {
        getEncryptedPrefs().edit().putString(PREF_LAST_PROXY, proxy).apply()
    }

    private fun getPersistedProxy(): String? {
        return getEncryptedPrefs().getString(PREF_LAST_PROXY, null)
    }

    private fun clearPersistedProxy() {
        getEncryptedPrefs().edit().remove(PREF_LAST_PROXY).apply()
    }

    private fun startVpn(proxyDetails: String) {
        Log.d(TAG, "startVpn ${proxyDetails}")
        proxyData = proxyDetails

        // Effective Remote DNS was resolved per-connection in onStartCommand
        // (config override -> global default). Fake-IP subnet is still global.
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val remoteDnsEnabled = effectiveRemoteDns
        val fakeSubnet = defaultPrefs.getString(PREF_FAKE_IP_SUBNET, DEFAULT_FAKE_IP_SUBNET)
                         ?: DEFAULT_FAKE_IP_SUBNET

        val builder = Builder()
            .addAddress("10.1.10.1", 32)
            .addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("0:0:0:0:0:0:0:0", 0)
            .setMtu(1500)
            .setSession(getString(R.string.app_name))

        if (remoteDnsEnabled) {
            // Direct all app DNS queries to tun2socks's built-in fake DNS
            // resolver (see Key.fakeDNS* below), which hands out fake IPs
            // and resolves them back to the original hostname when the app
            // later connects, so the proxy — not the local resolver — does
            // the real DNS resolution.
            builder.addDnsServer(FAKE_DNS_SERVER_IP)
        }

        val app = this.application as MyApplication
        if (app.loadVPNMode() == MyApplication.VPNMode.DISALLOW) {
            val disallowedApps = app.loadVPNApplication(MyApplication.VPNMode.DISALLOW)
            for (appPackageName in disallowedApps) {
                builder.addDisallowedApplication(appPackageName)
            }
            MyApplication.getInstance().storeVPNApplication(MyApplication.VPNMode.DISALLOW, disallowedApps)
        } else {
            val allowedApps = app.loadVPNApplication(MyApplication.VPNMode.ALLOW)
            for (appPackageName in allowedApps) {
                builder.addAllowedApplication(appPackageName)
            }
            MyApplication.getInstance().storeVPNApplication(MyApplication.VPNMode.ALLOW, allowedApps)
        }

        builder.addDisallowedApplication(packageName)

        try {
            Log.d(TAG, "startVpn pre establish")
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "vpnInterface: establish failed")
                broadcastState(VpnState.FAILED, "Failed to establish VPN interface")
                return
            }
            Log.d(TAG, "startVpn post establish")

            // Detach the fd so ParcelFileDescriptor no longer owns it —
            // the tun2socks engine takes ownership and will handle cleanup
            val fd = vpnInterface?.detachFd() ?: return
            vpnInterface = null
            Log.d(TAG, "startVpn fd ${fd}")

            val key = Key()
            key.mark = 0
            key.mtu = 1500
            key.device = "fd://$fd"
            key.setInterface("")
            key.logLevel = if (BuildConfig.DEBUG) "debug" else "silent"
            key.proxy = proxyDetails
            key.fakeDNS = remoteDnsEnabled
            key.fakeDNSNetIPv4 = fakeSubnet
            key.fakeDNSListenAddress = "$FAKE_DNS_SERVER_IP:53"
            key.restAPI = ""
            key.tcpSendBufferSize = ""
            key.tcpReceiveBufferSize = ""
            key.tcpModerateReceiveBuffer = false
            Engine.insert(key)
            Log.d(TAG, "startVpn starting engine")
            Engine.start()
            Log.d(TAG, "startVpn engine started")
            utils?.setProxyName(serviceName)
            // The netstack coming up doesn't mean the configured proxy is
            // actually reachable -- Engine.start() succeeds even for a
            // completely dead SOCKS5/HTTP/SSH address, since tun2socks only
            // dials out lazily per-connection. Probe it directly so
            // "Connected" reflects reality instead of just "netstack is up".
            val reachable = probeProxyReachable(proxyDetails)
            broadcastState(if (reachable) VpnState.CONNECTED else VpnState.CONNECTED_UNVERIFIED)
            // Block until stop signal — no timeout, runs indefinitely
            Log.d(TAG, "startVpn is waiting for stop signal")
            stopSignal.await()
            Log.d(TAG, "startVpn catched wait signal")
        } catch (e: Exception) {
            Log.e(TAG, "startEngine: error ${e.message}")
            if (!isStopping) {
                // Attempt failover if available
                val backup = failoverProxy
                if (backup != null && backup != proxyDetails) {
                    Log.d(TAG, "Primary failed, attempting failover")
                    failoverProxy = null // prevent infinite loop
                    getEncryptedPrefs().edit().remove(PREF_FAILOVER_PROXY).apply()
                    broadcastState(VpnState.CONNECTING)
                    persistProxy(backup)
                    proxyData = backup
                    startVpn(backup)
                    return
                }
                broadcastState(VpnState.FAILED, e.message)
            }
        } finally {
            try {
                Engine.stop()
                // Give the engine time to release the fd
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.w(TAG, "Engine.stop() error: ${e.message}")
            }
            // Engine.start() already read it (synchronously, before the
            // netstack comes up) if it was going to -- safe to remove now.
            ProxyUrlBuilder.clearKeyFile(this)
            // vpnInterface was detached (fd ownership transferred to engine),
            // so no close() needed here
            utils?.setVpnStatus(false)
            if (!isStopping) {
                broadcastState(VpnState.DISCONNECTED)
            }
        }
    }

    private fun stopVpn() {
        Log.d(TAG, "stopVpn")
        try {
            stopSignal.countDown()
            val thread = vpnThread
            if (thread != null && thread.isAlive) {
                thread.interrupt()
                thread.join(3000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn: ${e.message}")
        }
        vpnThread = null
        clearPersistedProxy()
        getEncryptedPrefs().edit().remove(PREF_FAILOVER_PROXY).apply()
    }

    fun isRunning(): Boolean {
        return vpnThread?.isAlive == true
    }

    /**
     * Resolves the effective Remote DNS for this start. Prefers the intent
     * extra set by the UI/rotation (config override -> global default already
     * applied there); persists it so restart paths that lack the extra (system
     * relaunch, NetworkMonitor auto-reconnect) reuse the same value. Falls back
     * to the persisted value, then the global default (default on).
     */
    private fun resolveRemoteDns(intent: Intent?): Boolean {
        if (intent != null && intent.hasExtra(EXTRA_REMOTE_DNS)) {
            val v = intent.getBooleanExtra(EXTRA_REMOTE_DNS, true)
            getEncryptedPrefs().edit().putBoolean(PREF_EFFECTIVE_REMOTE_DNS, v).apply()
            return v
        }
        val globalDefault = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_REMOTE_DNS_ENABLED, true)
        return getEncryptedPrefs().getBoolean(PREF_EFFECTIVE_REMOTE_DNS, globalDefault)
    }

    /** Direct-dial reachability check against the configured proxy's host:port. */
    private fun probeProxyReachable(proxyUrl: String): Boolean {
        val (host, port) = extractHostPort(proxyUrl) ?: return true // unparseable -- don't block startup on it
        val result = ProxyHealthCheck.test(host, port)
        Log.d(TAG, "probeProxyReachable $host:$port -> reachable=${result.reachable}")
        return result.reachable
    }

    /** Re-runs the reachability probe against the currently active proxy, without restarting the tunnel. */
    private fun recheckProxyAsync() {
        val data = proxyData
        if (!isRunning() || data == null) return
        Thread({
            val reachable = probeProxyReachable(data)
            broadcastState(if (reachable) VpnState.CONNECTED else VpnState.CONNECTED_UNVERIFIED)
        }, "ProxyRecheck").start()
    }

    private fun broadcastState(state: VpnState, errorMsg: String? = null) {
        isActive = (state == VpnState.CONNECTED || state == VpnState.CONNECTED_UNVERIFIED || state == VpnState.CONNECTING)
        Log.d(TAG, "broadcastState isActive ${isActive}")
        val intent = Intent(ACTION_VPN_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VPN_STATE, state.name)
            errorMsg?.let { putExtra(EXTRA_ERROR_MESSAGE, it) }
        }
        sendBroadcast(intent)
        VpnWidgetProvider.updateAllWidgets(this)
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke")
        isStopping = true
        stopVpn()
        utils?.setVpnStatus(false)
        broadcastState(VpnState.DISCONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        networkMonitor?.stop()
        releaseWakeLock()
        if (isStopping) {
            utils?.setVpnStatus(false)
        }
        super.onDestroy()
    }
}
