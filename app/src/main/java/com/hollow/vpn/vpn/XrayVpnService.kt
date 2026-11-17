package com.hollow.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hollow.vpn.MainActivity
import com.hollow.vpn.R
import com.hollow.vpn.data.ActiveTunnelSnapshot
import com.hollow.vpn.data.VpnDatabase
import com.hollow.vpn.data.VpnPreferencesStore
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class TunnelTrafficSnapshot(
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val elapsedSeconds: Long = 0L
)

/**
 * Android VPN service backed by Xray-core with:
 * - Single-threaded worker & generation guards
 * - Graceful stopLoop() teardown
 * - Live Xray outbound traffic & speed telemetry
 * - Android Always-On VPN snapshot recovery
 */
class XrayVpnService : VpnService() {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hollow-vpn-worker")
    }
    private val statsScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hollow-vpn-stats").apply { isDaemon = true }
    }
    private val commandGeneration = AtomicInteger(0)

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var coreController: CoreController? = null

    @Volatile
    private var coreEnvInitialized = false

    @Volatile
    private var statsTask: ScheduledFuture<*>? = null

    @Volatile
    private var activeIncludeDirectStats = false

    @Volatile
    private var smartSwitch: SmartSwitch? = null

    @Volatile
    private var networkMonitor: NetworkMonitor? = null

    @Volatile
    private var activeHost: String = ""

    @Volatile
    private var activePort: Int = 443

    @Volatile
    private var isFailoverInProgress = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            appendLog("Disconnect requested by user")
            val gen = commandGeneration.incrementAndGet()
            clearLastError()
            setConnectStage(0)
            _isServiceRunning.value = false
            _trafficSnapshot.value = TunnelTrafficSnapshot()
            HollowTileService.requestTileUpdate(applicationContext)
            HollowJobScheduler.scheduleCatchUpRefresh(applicationContext)
            enqueueCleanup(expectedGen = gen, stopServiceAfter = true)
            return START_NOT_STICKY
        }

        val isExplicitConnect = intent?.action == ACTION_CONNECT
        val fallbackSnapshot: ActiveTunnelSnapshot? = if (!isExplicitConnect) {
            VpnPreferencesStore(applicationContext).loadActiveTunnelSnapshot()
        } else {
            null
        }

        val country = intent?.getStringExtra(EXTRA_SERVER_COUNTRY)
            ?: fallbackSnapshot?.country
            ?: "Server"
        val host = intent?.getStringExtra(EXTRA_SERVER_HOST)
            ?: fallbackSnapshot?.host.orEmpty()
        val port = intent?.getIntExtra(EXTRA_SERVER_PORT, fallbackSnapshot?.port ?: 443)
            ?: (fallbackSnapshot?.port ?: 443)
        val protocol = intent?.getStringExtra(EXTRA_SERVER_PROTOCOL)
            ?: fallbackSnapshot?.protocol
            ?: "VLESS (V2Ray)"
        val xrayJson = intent?.getStringExtra(EXTRA_XRAY_JSON)
            ?: fallbackSnapshot?.xrayJson.orEmpty()
        val tunMode = intent?.getBooleanExtra(EXTRA_TUN_MODE, fallbackSnapshot?.tunMode ?: true)
            ?: (fallbackSnapshot?.tunMode ?: true)
        val enableIpv6 = intent?.getBooleanExtra(EXTRA_ENABLE_IPV6, fallbackSnapshot?.enableIpv6 ?: false)
            ?: (fallbackSnapshot?.enableIpv6 ?: false)
        val dnsMode = intent?.getStringExtra(EXTRA_DNS_MODE)
            ?: fallbackSnapshot?.dnsMode
            ?: "Auto (DoH)"
        val routingMode = intent?.getStringExtra(EXTRA_ROUTING_MODE)
            ?: fallbackSnapshot?.routingMode
            ?: "Global"
        val perAppProxyEnabled = intent?.getBooleanExtra(
            EXTRA_PER_APP_PROXY_ENABLED,
            fallbackSnapshot?.perAppProxyEnabled ?: false
        ) ?: (fallbackSnapshot?.perAppProxyEnabled ?: false)
        val bypassSelectedApps = intent?.getBooleanExtra(
            EXTRA_BYPASS_SELECTED_APPS,
            fallbackSnapshot?.bypassSelectedApps ?: false
        ) ?: (fallbackSnapshot?.bypassSelectedApps ?: false)
        val selectedAppPackages = intent?.getStringArrayListExtra(EXTRA_SELECTED_APP_PACKAGES)?.toSet()
            ?: fallbackSnapshot?.selectedAppPackages
            ?: emptySet()
        val smartSwitchEnabled = intent?.getBooleanExtra(
            EXTRA_SMART_SWITCH_ENABLED,
            fallbackSnapshot?.smartSwitchEnabled ?: false
        ) ?: (fallbackSnapshot?.smartSwitchEnabled ?: false)
        val serverSpeedMbps = intent?.getDoubleExtra(
            EXTRA_SERVER_SPEED_MBPS,
            fallbackSnapshot?.serverSpeedMbps ?: 0.0
        ) ?: (fallbackSnapshot?.serverSpeedMbps ?: 0.0)

        if (tunMode && prepare(this) != null) {
            fail("VPN permission not granted by Android OS")
            stopSelf()
            return START_NOT_STICKY
        }
        if (host.isBlank() || xrayJson.isBlank()) {
            fail("The selected server has no usable Xray configuration")
            stopSelf()
            return START_NOT_STICKY
        }

        val gen = commandGeneration.incrementAndGet()
        clearLastError()
        setConnectStage(1)
        _isServiceRunning.value = false
        _trafficSnapshot.value = TunnelTrafficSnapshot()
        appendLog(
            "Starting tunnel -> $country ($host:$port) · " +
                "${if (tunMode) "TUN" else "Proxy"} · DNS=$dnsMode · Route=$routingMode"
        )

        val subtitle = if (tunMode) "$protocol · $host" else "Proxy 127.0.0.1:10808 · $host"
        if (!ensureForeground(country, subtitle)) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            worker.execute {
                startTunnelInternal(
                    gen = gen,
                    country = country,
                    host = host,
                    port = port,
                    protocol = protocol,
                    xrayJson = xrayJson,
                    tunMode = tunMode,
                    enableIpv6 = enableIpv6,
                    dnsMode = dnsMode,
                    routingMode = routingMode,
                    perAppProxyEnabled = perAppProxyEnabled,
                    bypassSelectedApps = bypassSelectedApps,
                    selectedAppPackages = selectedAppPackages,
                    smartSwitchEnabled = smartSwitchEnabled,
                    serverSpeedMbps = serverSpeedMbps
                )
            }
        } catch (e: Exception) {
            fail("Could not schedule VPN worker: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun startTunnelInternal(
        gen: Int,
        country: String,
        host: String,
        port: Int,
        protocol: String,
        xrayJson: String,
        tunMode: Boolean,
        enableIpv6: Boolean,
        dnsMode: String,
        routingMode: String,
        perAppProxyEnabled: Boolean,
        bypassSelectedApps: Boolean,
        selectedAppPackages: Set<String>,
        smartSwitchEnabled: Boolean,
        serverSpeedMbps: Double
    ) {
        if (commandGeneration.get() != gen) return
        teardownActiveResources()
        if (commandGeneration.get() != gen) return

        try {
            setConnectStage(2)
            var tunFd = 0
            if (tunMode) {
                val builder = Builder()
                    .setSession("Hollow VPN - $country ($host)")
                    .setMtu(VPN_MTU)
                    .addAddress("10.26.0.1", 30)
                    .addDnsServer(selectedDns(dnsMode))
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:26::1", 126)
                    .addRoute("::", 0)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }
                configurePerAppRouting(builder, perAppProxyEnabled, bypassSelectedApps, selectedAppPackages)

                val established = builder.establish()
                    ?: error("Android OS rejected TUN interface creation")

                if (commandGeneration.get() != gen) {
                    try {
                        established.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                vpnInterface = established
                tunFd = established.fd
                appendLog("TUN interface up (MTU=$VPN_MTU, DNS=${selectedDns(dnsMode)}, IPv6=$enableIpv6)")
            }

            initializeXray()
            if (commandGeneration.get() != gen) {
                teardownActiveResources()
                return
            }

            val core = Libv2ray.newCoreController(CoreCallback(gen))
            coreController = core
            core.startLoop(xrayJson, tunFd)
            appendLog("Xray core loop started (${if (tunMode) "fd=$tunFd" else "SOCKS 10808"})")

            if (commandGeneration.get() != gen) {
                teardownActiveResources()
                return
            }

            setConnectStage(3)
            val isDirectOnly = routingMode.contains("Direct Only", ignoreCase = true)
            if (!isDirectOnly) {
                val verifiedDelay = verifyLiveTunnelDelay(core)
                if (commandGeneration.get() != gen) {
                    teardownActiveResources()
                    return
                }
                if (verifiedDelay <= 0L || !core.isRunning) {
                    fail("Tunnel failed live connectivity verification ($host:$port). Check server or try another node.")
                    teardownActiveResources()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                    stopSelf()
                    return
                }
                appendLog("Tunnel verified live HTTP 204 probe (${verifiedDelay} ms)")
            } else {
                appendLog("Direct Only diagnostic mode active (skipping proxy probe)")
            }

            VpnPreferencesStore(applicationContext).saveActiveTunnelSnapshot(
                ActiveTunnelSnapshot(
                    country = country,
                    host = host,
                    port = port,
                    protocol = protocol,
                    xrayJson = xrayJson,
                    tunMode = tunMode,
                    enableIpv6 = enableIpv6,
                    dnsMode = dnsMode,
                    routingMode = routingMode,
                    perAppProxyEnabled = perAppProxyEnabled,
                    bypassSelectedApps = bypassSelectedApps,
                    selectedAppPackages = selectedAppPackages,
                    smartSwitchEnabled = smartSwitchEnabled,
                    serverSpeedMbps = serverSpeedMbps
                )
            )

            activeHost = host
            activePort = port
            activeIncludeDirectStats = isDirectOnly
            smartSwitch = if (smartSwitchEnabled && !isDirectOnly) {
                SmartSwitch(referenceMbps = serverSpeedMbps)
            } else {
                null
            }
            _lastError.value = null
            _trafficSnapshot.value = TunnelTrafficSnapshot()
            setConnectStage(0)
            _isServiceRunning.value = true
            HollowTileService.requestTileUpdate(applicationContext)
            startTrafficStatsPolling(gen, core)
            startNetworkMonitor(gen)

            val verifiedSubtitle = if (tunMode) {
                if (enableIpv6) "IPv4 + IPv6 · $host" else "Encrypted Tunnel · $host"
            } else {
                "Proxy 127.0.0.1:10808 · $host"
            }
            updateForegroundNotification(country, verifiedSubtitle)
        } catch (e: Throwable) {
            if (commandGeneration.get() == gen) {
                fail("Connection failed: ${e.message ?: "native VPN engine unavailable"}")
                teardownActiveResources()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {
                }
                stopSelf()
            }
        }
    }

    private fun startNetworkMonitor(gen: Int) {
        networkMonitor?.stop()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val monitor = NetworkMonitor(cm) { reason ->
            if (commandGeneration.get() != gen || !_isServiceRunning.value) return@NetworkMonitor
            appendLog("Network handover detected: $reason")
            smartSwitch?.resetSamples()
            VpnPreferencesStore(applicationContext).invalidateBaseline()
            val core = coreController ?: return@NetworkMonitor
            val delay = verifyLiveTunnelDelay(core)
            if (delay > 0L && core.isRunning) {
                appendLog("Tunnel survived handover (${delay} ms)")
            } else {
                appendLog("Tunnel unresponsive after handover — checking reserve")
                triggerReserveFailover(expectedGen = gen, reason = "Network handover ($reason)")
            }
        }
        networkMonitor = monitor
        monitor.start()
    }

    private fun triggerReserveFailover(expectedGen: Int, reason: String) {
        if (isFailoverInProgress || commandGeneration.get() != expectedGen) return
        isFailoverInProgress = true
        serviceScope.launch {
            try {
                val dao = VpnDatabase.getInstance(applicationContext).vpnDao()
                val prefsStore = VpnPreferencesStore(applicationContext)
                val settings = prefsStore.loadSettings()
                val all = dao.getAllServersOnce()

                val failedServer = all.firstOrNull { it.host == activeHost && it.port == activePort }
                if (failedServer != null) {
                    dao.updateServer(failedServer.copy(failCount = failedServer.failCount + 1, isSelected = false))
                }

                val candidates = all
                    .filterNot { it.host == activeHost && it.port == activePort }
                    .sortedWith(
                        compareBy<com.hollow.vpn.data.ServerEntity> { it.failCount }
                            .thenByDescending { it.lastSpeedMbps }
                            .thenBy { it.protocolTier }
                            .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                    )

                val parser = V2RayParserAndFletcher()
                var chosen: com.hollow.vpn.data.ServerEntity? = null
                for (candidate in candidates.take(6)) {
                    val probe = parser.measureRealXrayDelayOrNull(applicationContext, candidate, settings)
                    if (probe is XrayProbeResult.Success) {
                        chosen = candidate.copy(
                            pingMs = probe.delayMs,
                            lastAliveAt = System.currentTimeMillis(),
                            failCount = 0
                        )
                        break
                    }
                }

                if (chosen == null) {
                    _serviceNotice.value = "Failover: no working reserve server found ($reason)"
                    return@launch
                }

                dao.clearSelection()
                dao.updateServer(chosen.copy(isSelected = true))
                val baseJson = chosen.rawConfigJson.ifBlank { parser.generateXrayJsonForServer(chosen) }
                val runtimeJson = parser.applyRuntimeSettingsToXrayJson(baseJson, settings)
                val nextGen = commandGeneration.incrementAndGet()
                appendLog("Smart failover -> ${chosen.countryName} (${chosen.host}:${chosen.port}) [$reason]")
                _serviceNotice.value = "Switched to ${chosen.countryName} ($reason)"

                worker.execute {
                    startTunnelInternal(
                        gen = nextGen,
                        country = chosen.countryName,
                        host = chosen.host,
                        port = chosen.port,
                        protocol = chosen.protocolCore,
                        xrayJson = runtimeJson,
                        tunMode = settings.tunMode,
                        enableIpv6 = settings.ipv6Enabled,
                        dnsMode = settings.dnsMode,
                        routingMode = settings.routingMode,
                        perAppProxyEnabled = settings.perAppProxyEnabled,
                        bypassSelectedApps = settings.bypassSelectedApps,
                        selectedAppPackages = settings.selectedAppPackages,
                        smartSwitchEnabled = settings.smartSwitchEnabled,
                        serverSpeedMbps = chosen.lastSpeedMbps
                    )
                }
            } catch (e: Exception) {
                appendLog("Failover skipped: ${e.message}")
            } finally {
                isFailoverInProgress = false
            }
        }
    }

    private fun startTrafficStatsPolling(gen: Int, core: CoreController) {
        statsTask?.cancel(false)
        statsTask = statsScheduler.scheduleAtFixedRate(
            {
                if (commandGeneration.get() != gen || !_isServiceRunning.value) return@scheduleAtFixedRate
                val raw = try {
                    core.queryAllOutboundTrafficStats().orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val (upDelta, downDelta) = parseOutboundTrafficStats(
                    rawStats = raw,
                    includeDirect = activeIncludeDirectStats
                )
                val prev = _trafficSnapshot.value
                _trafficSnapshot.value = TunnelTrafficSnapshot(
                    uploadBytes = prev.uploadBytes + upDelta,
                    downloadBytes = prev.downloadBytes + downDelta,
                    uploadBytesPerSec = upDelta,
                    downloadBytesPerSec = downDelta,
                    elapsedSeconds = prev.elapsedSeconds + 1L
                )

                val verdict = smartSwitch?.onSample(
                    upBytesPerSec = upDelta,
                    downBytesPerSec = downDelta,
                    nowMillis = System.currentTimeMillis()
                )
                if (verdict is SmartSwitch.Verdict.Switch) {
                    appendLog("Smart Switch triggered: ${verdict.reason}")
                    triggerReserveFailover(expectedGen = gen, reason = verdict.reason)
                }
            },
            1L,
            1L,
            TimeUnit.SECONDS
        )
    }

    private fun verifyLiveTunnelDelay(core: CoreController): Long {
        val primary = try {
            core.measureDelay(VERIFY_URL_PRIMARY)
        } catch (_: Throwable) {
            -1L
        }
        if (primary > 0L) return primary
        return try {
            core.measureDelay(VERIFY_URL_BACKUP)
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun enqueueCleanup(expectedGen: Int, stopServiceAfter: Boolean) {
        try {
            worker.execute {
                if (commandGeneration.get() == expectedGen || stopServiceAfter) {
                    teardownActiveResources()
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                    }
                    if (stopServiceAfter) {
                        stopSelf()
                    }
                }
            }
        } catch (_: Exception) {
            teardownActiveResources()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            if (stopServiceAfter) stopSelf()
        }
    }

    private fun teardownActiveResources() {
        networkMonitor?.stop()
        networkMonitor = null
        smartSwitch = null
        statsTask?.cancel(false)
        statsTask = null
        val coreToStop = coreController
        coreController = null
        if (coreToStop != null) {
            stopCoreWithGrace(coreToStop)
        }
        val fdToClose = vpnInterface
        vpnInterface = null
        if (fdToClose != null) {
            try {
                fdToClose.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopCoreWithGrace(core: CoreController) {
        val stopThread = Thread(
            {
                try {
                    core.stopLoop()
                } catch (e: Throwable) {
                    Log.w(TAG, "stopLoop threw: ${e.message}")
                }
            },
            "xray-stop"
        ).apply {
            isDaemon = true
            start()
        }
        try {
            stopThread.join(STOP_GRACE_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (stopThread.isAlive) {
            Log.w(TAG, "Xray stopLoop exceeded ${STOP_GRACE_MILLIS}ms grace period; detaching")
        }
    }

    /** Mirrors Android VpnService per-app routing without violating allowed/disallowed mutual exclusion. */
    private fun configurePerAppRouting(
        builder: Builder,
        enabled: Boolean,
        bypassSelectedApps: Boolean,
        selectedPackages: Set<String>
    ) {
        val packages = selectedPackages.filter { it != packageName }.toSet()
        if (!enabled || packages.isEmpty()) {
            builder.addDisallowedApplication(packageName)
            return
        }
        packages.forEach { pkg ->
            try {
                if (bypassSelectedApps) builder.addDisallowedApplication(pkg)
                else builder.addAllowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Skipping unavailable per-app package: $pkg")
            }
        }
        if (bypassSelectedApps) {
            builder.addDisallowedApplication(packageName)
        }
    }

    private fun initializeXray() {
        if (coreEnvInitialized) return
        Seq.setContext(applicationContext)
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        val deviceId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest("hollow:$androidId".toByteArray(Charsets.UTF_8))
                .copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP
        )
        val assetPath = (applicationContext.getExternalFilesDir("assets")
            ?: applicationContext.getDir("assets", MODE_PRIVATE)).absolutePath
        Libv2ray.initCoreEnv(assetPath, deviceId)
        coreEnvInitialized = true
    }

    private fun selectedDns(mode: String): String = when {
        mode.contains("8.8.8.8") -> "8.8.8.8"
        mode.contains("9.9.9.9") -> "9.9.9.9"
        mode.contains("94.140.14.14") -> "94.140.14.14"
        else -> "1.1.1.1"
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        appendLog("ERROR: $message")
        setConnectStage(0)
        _isServiceRunning.value = false
        _trafficSnapshot.value = TunnelTrafficSnapshot()
        _lastError.value = message
        HollowTileService.requestTileUpdate(applicationContext)
    }

    override fun onRevoke() {
        appendLog("VPN permission revoked by Android OS")
        val gen = commandGeneration.incrementAndGet()
        setConnectStage(0)
        _isServiceRunning.value = false
        _trafficSnapshot.value = TunnelTrafficSnapshot()
        _lastError.value = "VPN connection was revoked by Android OS"
        HollowTileService.requestTileUpdate(applicationContext)
        enqueueCleanup(expectedGen = gen, stopServiceAfter = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        val gen = commandGeneration.incrementAndGet()
        setConnectStage(0)
        _isServiceRunning.value = false
        _trafficSnapshot.value = TunnelTrafficSnapshot()
        HollowTileService.requestTileUpdate(applicationContext)
        enqueueCleanup(expectedGen = gen, stopServiceAfter = false)
        statsScheduler.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }

    private fun ensureForeground(country: String, subtitle: String): Boolean {
        createNotificationChannel()
        return try {
            startForeground(NOTIFICATION_ID, buildForegroundNotification(country, subtitle))
            true
        } catch (e: Exception) {
            fail("Could not start VPN notification: ${e.message}")
            false
        }
    }

    private fun updateForegroundNotification(country: String, subtitle: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildForegroundNotification(country, subtitle))
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hollow VPN Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(country: String, subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, XrayVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle("Hollow VPN · $country")
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .addAction(0, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private inner class CoreCallback(
        private val generation: Int
    ) : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long {
            Log.w(TAG, "Xray core reported shutdown")
            appendLog("Xray core reported shutdown")
            if (commandGeneration.get() == generation) {
                val expectedGen = commandGeneration.incrementAndGet()
                setConnectStage(0)
                _isServiceRunning.value = false
                _trafficSnapshot.value = TunnelTrafficSnapshot()
                enqueueCleanup(expectedGen = expectedGen, stopServiceAfter = true)
            }
            return 0
        }

        override fun onEmitStatus(level: Long, message: String?): Long {
            if (!message.isNullOrBlank()) {
                Log.d(TAG, "Xray[$level]: $message")
                appendLog("Xray[$level]: ${message.trim()}")
            }
            return 0
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.hollow.vpn.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.hollow.vpn.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER_COUNTRY = "extra_server_country"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PORT = "extra_server_port"
        const val EXTRA_SERVER_PROTOCOL = "extra_server_protocol"
        const val EXTRA_XRAY_JSON = "extra_xray_json"
        const val EXTRA_TUN_MODE = "extra_tun_mode"
        const val EXTRA_ENABLE_IPV6 = "extra_enable_ipv6"
        const val EXTRA_DNS_MODE = "extra_dns_mode"
        const val EXTRA_ROUTING_MODE = "extra_routing_mode"
        const val EXTRA_PER_APP_PROXY_ENABLED = "extra_per_app_proxy_enabled"
        const val EXTRA_BYPASS_SELECTED_APPS = "extra_bypass_selected_apps"
        const val EXTRA_SELECTED_APP_PACKAGES = "extra_selected_app_packages"
        const val EXTRA_SMART_SWITCH_ENABLED = "extra_smart_switch_enabled"
        const val EXTRA_SERVER_SPEED_MBPS = "extra_server_speed_mbps"
        private const val CHANNEL_ID = "v2ray_vpn_tunnel_channel"
        private const val NOTIFICATION_ID = 2048
        private const val VPN_MTU = 1500
        private const val STOP_GRACE_MILLIS = 5_000L
        private const val VERIFY_URL_PRIMARY = "https://www.gstatic.com/generate_204"
        private const val VERIFY_URL_BACKUP = "https://cp.cloudflare.com/generate_204"
        private const val TAG = "XrayVpnService"
        private const val MAX_LOG_ENTRIES = 250

        private val logLock = Any()
        private val logBuffer = ArrayDeque<String>(MAX_LOG_ENTRIES)

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
        private val _connectStage = MutableStateFlow(0)
        val connectStage: StateFlow<Int> = _connectStage.asStateFlow()
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()
        private val _serviceNotice = MutableStateFlow<String?>(null)
        val serviceNotice: StateFlow<String?> = _serviceNotice.asStateFlow()
        private val _trafficSnapshot = MutableStateFlow(TunnelTrafficSnapshot())
        val trafficSnapshot: StateFlow<TunnelTrafficSnapshot> = _trafficSnapshot.asStateFlow()
        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        internal fun setConnectStage(stage: Int) {
            _connectStage.value = stage
        }

        fun clearLastError() {
            _lastError.value = null
        }

        fun clearServiceNotice() {
            _serviceNotice.value = null
        }

        internal fun appendLog(message: String) {
            val clean = message.trim()
            if (clean.isEmpty()) return
            val stamp = synchronized(logLock) {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                if (logBuffer.size >= MAX_LOG_ENTRIES) {
                    logBuffer.removeFirst()
                }
                logBuffer.addLast("[$ts] $clean")
                logBuffer.toList()
            }
            _logs.value = stamp
        }

        fun clearLogs() {
            synchronized(logLock) {
                logBuffer.clear()
            }
            _logs.value = emptyList()
        }

        /**
         * Parses AndroidLibXrayLite's `QueryAllOutboundTrafficStats()` payload
         * (`tag,direction,value;tag,direction,value;`) into `(uplinkBytes, downlinkBytes)`.
         */
        internal fun parseOutboundTrafficStats(
            rawStats: String,
            includeDirect: Boolean = false
        ): Pair<Long, Long> {
            if (rawStats.isBlank()) return 0L to 0L
            var uplink = 0L
            var downlink = 0L
            rawStats.split(';').forEach { entry ->
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) return@forEach
                val parts = trimmed.split(',')
                if (parts.size < 3) return@forEach
                val tag = parts[0].trim().lowercase()
                val direction = parts[1].trim().lowercase()
                val value = parts[2].trim().toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                if (tag == "block" || (tag == "direct" && !includeDirect)) return@forEach
                when (direction) {
                    "uplink" -> uplink += value
                    "downlink" -> downlink += value
                }
            }
            return uplink to downlink
        }
    }
}
