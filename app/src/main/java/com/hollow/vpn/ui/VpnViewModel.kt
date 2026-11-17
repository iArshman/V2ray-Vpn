package com.hollow.vpn.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hollow.vpn.BuildConfig
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.AppUpdateState
import com.hollow.vpn.data.FetchingProgressState
import com.hollow.vpn.data.FetchingStage
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.data.ServerFilter
import com.hollow.vpn.data.SubscriptionEntity
import com.hollow.vpn.data.VpnDatabase
import com.hollow.vpn.data.VpnPreferencesStore
import com.hollow.vpn.vpn.HollowJobScheduler
import com.hollow.vpn.vpn.SecurityPolicy
import com.hollow.vpn.vpn.V2RayParserAndFletcher
import com.hollow.vpn.vpn.XrayProbeResult
import com.hollow.vpn.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

enum class VpnScreen {
    GETTING_STARTED,
    SPLASH_FETCHING,
    SERVER_LIST,
    CONNECTED_HOME,
    SETTINGS,
    ADD_SERVER
}

enum class AddServerTab(val title: String) {
    SUBSCRIPTION("Subscription"),
    MANUAL("Manual"),
    QR_CODE("QR Code")
}

data class ConnectionTelemetryState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectingStage: Int = 1,
    val elapsedSeconds: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val serverIpLabel: String = "—",
    val realPublicIpLabel: String = "Detecting…",
    val realPublicCountryCode: String = "XX",
    val realIspLabel: String = "Detecting…",
    val preConnectionIp: String = ""
) {
    val formattedTimer: String
        get() {
            val hours = elapsedSeconds / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

    val formattedUpload: String
        get() = formatByteCount(uploadBytes)

    val formattedDownload: String
        get() = formatByteCount(downloadBytes)

    val formattedUploadSpeed: String
        get() = "${formatByteCount(uploadBytesPerSec)}/s"

    val formattedDownloadSpeed: String
        get() = "${formatByteCount(downloadBytesPerSec)}/s"

    companion object {
        fun formatByteCount(bytes: Long): String {
            val safe = max(0L, bytes)
            return when {
                safe < 1024L -> "$safe B"
                safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
                safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = VpnDatabase.getInstance(application).vpnDao()
    private val prefsStore = VpnPreferencesStore(application)
    private val fletcher = V2RayParserAndFletcher()

    private val _settings = MutableStateFlow(prefsStore.loadSettings())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    private val initialScreen = if (_settings.value.hasCompletedOnboarding) {
        VpnScreen.CONNECTED_HOME
    } else {
        VpnScreen.GETTING_STARTED
    }

    private val _screenStack = MutableStateFlow(listOf(initialScreen))
    private val _currentScreen = MutableStateFlow(initialScreen)
    val currentScreen: StateFlow<VpnScreen> = _currentScreen.asStateFlow()

    private val _gettingStartedStep = MutableStateFlow(0)
    val gettingStartedStep: StateFlow<Int> = _gettingStartedStep.asStateFlow()

    private val _selectedFilter = MutableStateFlow(ServerFilter.FASTEST)
    val selectedFilter: StateFlow<ServerFilter> = _selectedFilter.asStateFlow()

    private val _telemetry = MutableStateFlow(ConnectionTelemetryState())
    val telemetry: StateFlow<ConnectionTelemetryState> = _telemetry.asStateFlow()

    private val _fetchingStatusMessage = MutableStateFlow("Auto configuring for you")
    val fetchingStatusMessage: StateFlow<String> = _fetchingStatusMessage.asStateFlow()

    private val _fetchingProgress = MutableStateFlow(FetchingProgressState())
    val fetchingProgress: StateFlow<FetchingProgressState> = _fetchingProgress.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _activeAddTab = MutableStateFlow(AddServerTab.SUBSCRIPTION)
    val activeAddTab: StateFlow<AddServerTab> = _activeAddTab.asStateFlow()

    private val _inspectingJsonServer = MutableStateFlow<ServerEntity?>(null)
    val inspectingJsonServer: StateFlow<ServerEntity?> = _inspectingJsonServer.asStateFlow()

    private val _sharingQrServer = MutableStateFlow<ServerEntity?>(null)
    val sharingQrServer: StateFlow<ServerEntity?> = _sharingQrServer.asStateFlow()

    private val _appUpdateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val appUpdateState: StateFlow<AppUpdateState> = _appUpdateState.asStateFlow()

    val allServers: StateFlow<List<ServerEntity>> = dao.observeAllServers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val filteredServers: StateFlow<List<ServerEntity>> = combine(
        allServers,
        _selectedFilter,
        _settings
    ) { servers, filter, settings ->
        val protocolFiltered = filterByProtocolPreference(servers, settings.protocolPreference)
        when (filter) {
            ServerFilter.FASTEST -> {
                protocolFiltered.sortedWith(
                    compareBy<ServerEntity> { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                        .thenByDescending { it.lastSpeedMbps }
                )
            }
            ServerFilter.ALL -> {
                protocolFiltered.sortedWith(
                    compareByDescending<ServerEntity> { it.isSelected }
                        .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                )
            }
            ServerFilter.RECOMMENDED -> {
                protocolFiltered
                    .sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                    .take(15)
            }
            ServerFilter.LOW_PING -> {
                val minLive = protocolFiltered.filter { it.pingMs > 0 }.minOfOrNull { it.pingMs } ?: 120
                val threshold = max(120, minLive + 45)
                protocolFiltered.filter { it.pingMs in 1..threshold }.sortedBy { it.pingMs }
            }
            ServerFilter.HIGH_SPEED -> {
                protocolFiltered.filter {
                    it.transport.equals("Reality", ignoreCase = true) ||
                        it.transport.equals("VLESS", ignoreCase = true) ||
                        it.transport.equals("gRPC", ignoreCase = true) ||
                        it.transport.equals("WebSocket", ignoreCase = true) ||
                        it.transport.equals("Hysteria2", ignoreCase = true)
                }.sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }.take(15)
            }
            ServerFilter.CUSTOM -> {
                protocolFiltered.filter { it.isCustom }.sortedWith(
                    compareByDescending<ServerEntity> { it.isSelected }
                        .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private fun filterByProtocolPreference(
        servers: List<ServerEntity>,
        preference: String
    ): List<ServerEntity> {
        return when (preference) {
            "VLESS (Reality)" -> servers.filter { it.transport.equals("Reality", ignoreCase = true) }
            "VLESS (WS / TCP)" -> servers.filter {
                it.protocolCore.contains("VLESS", ignoreCase = true) && !it.transport.equals("Reality", ignoreCase = true)
            }
            "VMess (WebSocket)" -> servers.filter { it.protocolCore.contains("VMess", ignoreCase = true) }
            "Trojan (TLS)" -> servers.filter { it.protocolCore.contains("Trojan", ignoreCase = true) }
            "Hysteria2" -> servers.filter { it.protocolCore.contains("Hysteria", ignoreCase = true) }
            "gRPC" -> servers.filter { it.transport.equals("gRPC", ignoreCase = true) }
            "Shadowsocks" -> servers.filter { it.protocolCore.contains("Shadowsocks", ignoreCase = true) }
            else -> servers
        }
    }

    val selectedServer: StateFlow<ServerEntity?> = combine(allServers, _settings) { list, currentSettings ->
        val matching = filterByProtocolPreference(list, currentSettings.protocolPreference)
        matching.firstOrNull { it.isSelected }
            ?: list.firstOrNull { it.isSelected }
            ?: matching.firstOrNull()
            ?: list.firstOrNull()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    private var autoUpdateJob: Job? = null
    private var ipRefreshJob: Job? = null
    private var connectJob: Job? = null
    private var repingJob: Job? = null
    private var fetchSweepJob: Job? = null

    init {
        observeVpnServiceState()
        refreshRealPublicIp()
        initializeLiveServersOnStartup()
        startSubscriptionAutoUpdateLoop()
        HollowJobScheduler.schedulePeriodicMaintenance(getApplication(), _settings.value)
    }

    /**
     * Keeps UI connection state and Xray outbound traffic counters synchronized with XrayVpnService.
     */
    private fun observeVpnServiceState() {
        var wasRunning = false
        viewModelScope.launch {
            XrayVpnService.isServiceRunning.collect { running ->
                if (running) {
                    wasRunning = true
                    fletcher.evictConnectionPools()
                    _telemetry.update {
                        it.copy(
                            isConnected = true,
                            isConnecting = false,
                            connectingStage = 1,
                            elapsedSeconds = 0L,
                            uploadBytes = 0L,
                            downloadBytes = 0L,
                            uploadBytesPerSec = 0L,
                            downloadBytesPerSec = 0L
                        )
                    }
                    // Verify live exit IP through the active tunnel once routes settle
                    ipRefreshJob?.cancel()
                    ipRefreshJob = viewModelScope.launch {
                        delay(1500L)
                        refreshRealPublicIp()
                    }
                } else {
                    _telemetry.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectingStage = 1,
                            uploadBytesPerSec = 0L,
                            downloadBytesPerSec = 0L
                        )
                    }
                    if (wasRunning) {
                        wasRunning = false
                        fletcher.evictConnectionPools()
                        ipRefreshJob?.cancel()
                        ipRefreshJob = viewModelScope.launch {
                            delay(600L)
                            refreshRealPublicIp()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            XrayVpnService.connectStage.collect { stage ->
                if (stage >= 2 && _telemetry.value.isConnecting) {
                    _telemetry.update {
                        it.copy(connectingStage = max(it.connectingStage, stage))
                    }
                }
            }
        }
        viewModelScope.launch {
            XrayVpnService.trafficSnapshot.collect { snapshot ->
                if (_telemetry.value.isConnected && !_telemetry.value.isConnecting) {
                    _telemetry.update {
                        it.copy(
                            elapsedSeconds = snapshot.elapsedSeconds,
                            uploadBytes = snapshot.uploadBytes,
                            downloadBytes = snapshot.downloadBytes,
                            uploadBytesPerSec = snapshot.uploadBytesPerSec,
                            downloadBytesPerSec = snapshot.downloadBytesPerSec
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            XrayVpnService.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    if (!XrayVpnService.isServiceRunning.value) {
                        _telemetry.update {
                            it.copy(
                                isConnecting = false,
                                isConnected = false,
                                connectingStage = 1,
                                uploadBytesPerSec = 0L,
                                downloadBytesPerSec = 0L
                            )
                        }
                    }
                    showMessage(err)
                    XrayVpnService.clearLastError()
                }
            }
        }
        viewModelScope.launch {
            XrayVpnService.serviceNotice.collect { notice ->
                if (!notice.isNullOrBlank()) {
                    showMessage(notice)
                    XrayVpnService.clearServiceNotice()
                }
            }
        }
    }

    fun refreshRealPublicIp() {
        viewModelScope.launch {
            val wasTunnelActive = _telemetry.value.isConnected || _telemetry.value.isConnecting
            val proxyPort = if (_telemetry.value.isConnected && !_settings.value.tunMode) 10808 else null
            val info = fletcher.fetchDevicePublicIpInfo(socksProxyPort = proxyPort)
            _telemetry.update { prev ->
                val savedPreIp = if (!wasTunnelActive && info.ip.isNotBlank() && !info.ip.equals("Detecting…", ignoreCase = true)) {
                    info.ip
                } else {
                    prev.preConnectionIp
                }
                prev.copy(
                    realPublicIpLabel = "${info.ip} · ${info.country}",
                    realPublicCountryCode = info.countryCode,
                    realIspLabel = info.isp,
                    preConnectionIp = savedPreIp
                )
            }
        }
    }

    /**
     * On startup, loads saved servers or refreshes the user's configured subscription URL quietly
     * in the background without blocking the user behind a splash fetching screen.
     */
    private fun initializeLiveServersOnStartup() {
        viewModelScope.launch {
            val existing = dao.getAllServersOnce()
            val subs = dao.getSubscriptionsOnce()
            val configuredSubUrl = subs.firstOrNull()?.url?.takeIf { it.isNotBlank() }
                ?: _settings.value.activeSubscriptionUrl.trim()

            if (!_settings.value.hasCompletedOnboarding) {
                _screenStack.value = listOf(VpnScreen.GETTING_STARTED)
                _currentScreen.value = VpnScreen.GETTING_STARTED
                _gettingStartedStep.value = 0
                return@launch
            }

            val canAutoConnect = _settings.value.startOnAppOpen &&
                (!_settings.value.tunMode || android.net.VpnService.prepare(getApplication()) == null)

            _screenStack.value = listOf(VpnScreen.CONNECTED_HOME)
            _currentScreen.value = VpnScreen.CONNECTED_HOME

            if (configuredSubUrl.isNotBlank() && (existing.isEmpty() || _settings.value.autoConfigure)) {
                runFletcherAutoFetchAndPingSweep(
                    returnScreen = VpnScreen.CONNECTED_HOME,
                    autoConnectAfterFetch = canAutoConnect,
                    showLoadingScreen = false
                )
            } else if (existing.isNotEmpty() && canAutoConnect) {
                val active = existing.firstOrNull { it.isSelected } ?: existing.first()
                connectToServer(active)
            }
        }
    }

    fun setGettingStartedStep(step: Int) {
        _gettingStartedStep.value = step.coerceIn(0, 2)
    }

    fun completeOnboarding(
        targetScreen: VpnScreen = VpnScreen.CONNECTED_HOME,
        autoConnectIfReady: Boolean = false
    ) {
        updateSettings { it.copy(hasCompletedOnboarding = true) }
        _gettingStartedStep.value = 0
        _screenStack.value = listOf(targetScreen)
        _currentScreen.value = targetScreen

        if (autoConnectIfReady) {
            viewModelScope.launch {
                val servers = dao.getAllServersOnce()
                val active = servers.firstOrNull { it.isSelected } ?: servers.firstOrNull()
                if (active != null &&
                    (!_settings.value.tunMode || android.net.VpnService.prepare(getApplication()) == null)
                ) {
                    connectToServer(active)
                }
            }
        }
    }

    fun reopenGettingStarted() {
        _gettingStartedStep.value = if (_settings.value.hasCompletedOnboarding) 2 else 0
        navigateTo(VpnScreen.GETTING_STARTED)
    }

    fun fetchSubscriptionInOnboarding(
        rawUrlOrLinks: String,
        onResult: (Result<Int>) -> Unit
    ) {
        val input = rawUrlOrLinks.trim()
        if (input.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Please enter a valid subscription URL")))
            return
        }
        if (isDirectServerInput(input)) {
            importConfigInOnboarding(input, onResult)
            return
        }
        updateSettings { it.copy(activeSubscriptionUrl = input) }
        viewModelScope.launch {
            dao.deleteSubscriptionByUrl(input)
            dao.insertSubscription(
                SubscriptionEntity(
                    url = input,
                    autoUpdate = _settings.value.subAutoUpdate,
                    replaceExisting = _settings.value.subReplaceExisting
                )
            )
            val result = fletcher.fetchSubscriptionAndRankTop15(
                subscriptionUrl = input,
                context = getApplication(),
                settings = _settings.value
            )
            result.fold(
                onSuccess = { top15Nodes ->
                    if (_settings.value.subReplaceExisting) {
                        dao.deleteFetchedServers()
                    }
                    dao.clearSelection()
                    val marked = top15Nodes.mapIndexed { idx, srv -> srv.copy(isSelected = idx == 0) }
                    dao.insertServers(marked)
                    marked.firstOrNull()?.let { fastest ->
                        val ip = fletcher.resolveServerIpAddress(fastest.host)
                        _telemetry.update { it.copy(serverIpLabel = ip) }
                    }
                    onResult(Result.success(marked.size))
                },
                onFailure = { err ->
                    onResult(Result.failure(err))
                }
            )
        }
    }

    fun importConfigInOnboarding(
        rawText: String,
        onResult: (Result<Int>) -> Unit
    ) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Paste a valid vless://, vmess://, trojan://, ss://, or JSON config")))
            return
        }
        viewModelScope.launch {
            val parsed = fletcher.parseImportPayload(trimmed, isCustom = true)
            if (parsed.isEmpty()) {
                onResult(Result.failure(IllegalArgumentException("No valid server link or JSON config found")))
                return@launch
            }
            val benchmarked = fletcher.benchmarkAndRankTop15(
                servers = parsed,
                context = getApplication(),
                settings = _settings.value
            )
            if (benchmarked.isEmpty()) {
                onResult(Result.failure(IllegalStateException("Servers parsed, but none passed proxy verification")))
                return@launch
            }
            dao.clearSelection()
            val toInsert = benchmarked.mapIndexed { idx, srv -> srv.copy(isSelected = idx == 0) }
            dao.insertServers(toInsert)
            toInsert.firstOrNull()?.let { fastest ->
                val ip = fletcher.resolveServerIpAddress(fastest.host)
                _telemetry.update { it.copy(serverIpLabel = ip) }
            }
            onResult(Result.success(toInsert.size))
        }
    }

    private fun startSubscriptionAutoUpdateLoop() {
        autoUpdateJob?.cancel()
        autoUpdateJob = viewModelScope.launch {
            while (true) {
                delay(60_000L)
                val current = _settings.value
                if (!current.subAutoUpdate) continue
                if (_telemetry.value.isConnecting || _currentScreen.value == VpnScreen.SPLASH_FETCHING) continue

                val intervalMs = parseIntervalMillis(current.updateIntervalLabel)
                val subs = dao.getSubscriptionsOnce()
                val enabledSubs = subs.filter { it.isEnabled && it.url.isNotBlank() }
                val latestSub = enabledSubs.maxByOrNull { it.lastFetchedAt } ?: subs.firstOrNull()
                val lastFetched = latestSub?.lastFetchedAt ?: 0L
                val fallbackUrls = listOfNotNull(current.activeSubscriptionUrl.trim().takeIf { it.isNotBlank() })

                if (lastFetched > 0L && System.currentTimeMillis() - lastFetched >= intervalMs &&
                    (enabledSubs.isNotEmpty() || fallbackUrls.isNotEmpty())
                ) {
                    val result = fletcher.fetchMultipleSubscriptionsAndRankTop15(
                        subscriptions = enabledSubs,
                        fallbackUrls = fallbackUrls,
                        context = getApplication(),
                        settings = current,
                        onSubscriptionStats = { sub, ok, count ->
                            val updatedSub = if (ok) {
                                sub.copy(
                                    successPulls = sub.successPulls + 1,
                                    lastFetchedAt = System.currentTimeMillis(),
                                    serverCount = count
                                )
                            } else {
                                sub.copy(failedPulls = sub.failedPulls + 1)
                            }
                            dao.updateSubscription(updatedSub)
                        }
                    )
                    result.onSuccess { ranked ->
                        if (current.subReplaceExisting) {
                            dao.deleteFetchedServers()
                        }
                        dao.clearSelection()
                        val marked = ranked.mapIndexed { index, srv -> srv.copy(isSelected = index == 0) }
                        dao.insertServers(marked)
                    }
                }
            }
        }
    }

    private fun parseIntervalMillis(label: String): Long {
        val lower = label.lowercase()
        val num = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 1L
        return if (lower.contains("min")) {
            num * 60_000L
        } else {
            num * 3_600_000L
        }
    }

    fun navigateTo(screen: VpnScreen) {
        if (_currentScreen.value == screen) return
        _screenStack.update { it + screen }
        _currentScreen.value = screen
    }

    fun hideFetchingScreen() {
        if (_currentScreen.value == VpnScreen.SPLASH_FETCHING) {
            if (!navigateBack()) {
                val target = if (allServers.value.isNotEmpty()) VpnScreen.SERVER_LIST else VpnScreen.CONNECTED_HOME
                _screenStack.value = listOf(target)
                _currentScreen.value = target
            }
            if (_fetchingProgress.value.isActive) {
                showMessage("Fetching & testing in background")
            }
        }
    }

    fun reopenFetchingScreen() {
        if (_fetchingProgress.value.isActive) {
            navigateTo(VpnScreen.SPLASH_FETCHING)
        }
    }

    fun navigateBack(): Boolean {
        val stack = _screenStack.value
        if (stack.size > 1) {
            val newStack = stack.dropLast(1)
            _screenStack.value = newStack
            _currentScreen.value = newStack.last()
            return true
        }
        return false
    }

    fun setFilter(filter: ServerFilter) {
        _selectedFilter.value = filter
    }

    fun checkForAppUpdates() {
        if (_appUpdateState.value is AppUpdateState.Checking) return
        viewModelScope.launch {
            _appUpdateState.value = AppUpdateState.Checking
            _appUpdateState.value = fletcher.checkAppUpdate(BuildConfig.VERSION_NAME)
        }
    }

    fun setAddServerTab(tab: AddServerTab) {
        _activeAddTab.value = tab
    }

    /** Selects a server without starting the VPN. Used while Android shows its consent dialog. */
    fun selectServer(server: ServerEntity, openConnectedScreen: Boolean = true) {
        connectJob?.cancel()
        connectJob = null
        viewModelScope.launch {
            dao.clearSelection()
            dao.markSelected(server.id)
            if (openConnectedScreen) navigateTo(VpnScreen.CONNECTED_HOME)
        }
    }

    fun selectAndConnectServer(server: ServerEntity, openConnectedScreen: Boolean = true) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            dao.clearSelection()
            dao.markSelected(server.id)
            if (openConnectedScreen) {
                navigateTo(VpnScreen.CONNECTED_HOME)
            }
            connectToServer(server)
        }
    }

    fun toggleConnection() {
        if (_telemetry.value.isConnecting) {
            connectJob?.cancel()
            connectJob = null
            _telemetry.update { it.copy(isConnecting = false, isConnected = false, connectingStage = 1) }
            stopAndroidVpnService()
            return
        }
        val currentlyConnected = _telemetry.value.isConnected
        if (currentlyConnected) {
            connectJob?.cancel()
            connectJob = null
            _telemetry.update {
                it.copy(
                    isConnected = false,
                    isConnecting = false,
                    connectingStage = 1
                )
            }
            stopAndroidVpnService()
            val name = selectedServer.value?.countryName ?: "VPN"
            showMessage("Disconnected from $name")
        } else {
            val active = selectedServer.value
            if (active == null) {
                val configuredSub = _settings.value.activeSubscriptionUrl.trim()
                if (configuredSub.isNotBlank()) {
                    showMessage("Fetching servers from your subscription…")
                    runFletcherAutoFetchAndPingSweep(
                        returnScreen = VpnScreen.CONNECTED_HOME,
                        autoConnectAfterFetch = true
                    )
                } else {
                    navigateTo(VpnScreen.ADD_SERVER)
                    showMessage("Add a subscription URL, server link, or QR code first")
                }
                return
            }
            connectJob?.cancel()
            connectJob = viewModelScope.launch {
                connectToServer(active)
            }
        }
    }

    private suspend fun connectToServer(server: ServerEntity) {
        // Never claim Connected if Android OS VPN consent hasn't been granted yet (when TUN Mode is on)
        if (_settings.value.tunMode && android.net.VpnService.prepare(getApplication()) != null) {
            _telemetry.update { it.copy(isConnecting = false, isConnected = false) }
            showMessage("Tap Connect and accept the Android VPN prompt to start the tunnel")
            return
        }

        if (_settings.value.strictTlsEnabled && !SecurityPolicy.isServerSecure(server)) {
            _telemetry.update { it.copy(isConnecting = false, isConnected = false) }
            showMessage("Strict TLS blocked ${server.countryName}: insecure TLS or plaintext node")
            return
        }

        XrayVpnService.clearLastError()
        _telemetry.update { it.copy(isConnecting = true, connectingStage = 1) }

        val isDirectOnly = _settings.value.routingMode.contains("Direct Only", ignoreCase = true)
        val livePing: Int? = if (isDirectOnly) {
            server.pingMs
        } else {
            when (val probe = fletcher.measureRealXrayDelayOrNull(getApplication(), server, _settings.value)) {
                is XrayProbeResult.Success -> probe.delayMs
                XrayProbeResult.NativeUnavailable -> withContext(Dispatchers.IO) {
                    fletcher.measureRealTcpLatencyOrNull(server.host, server.port, timeoutMs = 2200)
                }
                XrayProbeResult.Unreachable -> null
            }
        }

        currentCoroutineContext().ensureActive()
        if (livePing == null) {
            dao.updateServer(server.copy(failCount = server.failCount + 1))
            _telemetry.update { it.copy(isConnecting = false, isConnected = false, connectingStage = 1) }
            showMessage("${server.countryName} (${server.host}:${server.port}) failed Xray proxy verification")
            return
        }

        val resolvedIp = server.resolvedIp.ifBlank { fletcher.resolveServerIpAddress(server.host) }
        val updatedServer = server.copy(
            pingMs = livePing,
            resolvedIp = resolvedIp,
            isSelected = true,
            lastAliveAt = System.currentTimeMillis(),
            failCount = 0
        )
        dao.updateServer(updatedServer)
        currentCoroutineContext().ensureActive()

        _telemetry.update { prev ->
            prev.copy(
                serverIpLabel = resolvedIp,
                connectingStage = 2
            )
        }
        startAndroidVpnService(updatedServer)
        showMessage("Connecting to ${server.countryName} ($livePing ms)…")
    }

    /**
     * Runs the real Fletcher Auto-Fetcher & Concurrent Xray Ping Ranker, saving the Top 15
     * fastest verified servers to the Room database.
     */
    fun runFletcherAutoFetchAndPingSweep(
        returnScreen: VpnScreen = VpnScreen.SERVER_LIST,
        autoConnectAfterFetch: Boolean = false,
        showLoadingScreen: Boolean = false
    ) {
        fetchSweepJob?.cancel()
        fetchSweepJob = viewModelScope.launch {
            try {
                val subs = dao.getSubscriptionsOnce().filter { it.isEnabled && it.url.isNotBlank() }
                val fallbackUrls = listOfNotNull(_settings.value.activeSubscriptionUrl.trim().takeIf { it.isNotBlank() })

                if (subs.isEmpty() && fallbackUrls.isEmpty()) {
                    val existing = dao.getAllServersOnce()
                    if (existing.isNotEmpty()) {
                        if (showLoadingScreen) {
                            _currentScreen.value = returnScreen
                        }
                        repingAllSavedServers()
                    } else if (showLoadingScreen) {
                        navigateTo(VpnScreen.ADD_SERVER)
                        showMessage("Add a subscription URL, server link, or QR code first")
                    }
                    return@launch
                }

                if (showLoadingScreen) {
                    navigateTo(VpnScreen.SPLASH_FETCHING)
                }
                _fetchingStatusMessage.value = "Fetching your subscription…"
                _fetchingProgress.value = FetchingProgressState(
                    isActive = true,
                    stage = FetchingStage.DOWNLOADING,
                    headline = "Fetching & testing live",
                    detail = "Fetching your subscription…"
                )

                val subResult = fletcher.fetchMultipleSubscriptionsAndRankTop15(
                    subscriptions = subs,
                    fallbackUrls = fallbackUrls,
                    context = getApplication(),
                    settings = _settings.value,
                    onProgressState = { progress ->
                        _fetchingProgress.value = progress
                        _fetchingStatusMessage.value = progress.detail
                    },
                    onProgress = { step -> _fetchingStatusMessage.value = step },
                    onSubscriptionStats = { sub, ok, count ->
                        val updatedSub = if (ok) {
                            sub.copy(
                                successPulls = sub.successPulls + 1,
                                lastFetchedAt = System.currentTimeMillis(),
                                serverCount = count
                            )
                        } else {
                            sub.copy(failedPulls = sub.failedPulls + 1)
                        }
                        dao.updateSubscription(updatedSub)
                    }
                )

                subResult.fold(
                    onSuccess = { rankedTop15 ->
                        if (_settings.value.subReplaceExisting) {
                            dao.deleteFetchedServers()
                        }
                        dao.clearSelection()
                        val withSelected = rankedTop15.mapIndexed { idx, srv ->
                            srv.copy(isSelected = idx == 0)
                        }
                        dao.insertServers(withSelected)

                        if (showLoadingScreen && _currentScreen.value == VpnScreen.SPLASH_FETCHING) {
                            _screenStack.value = listOf(VpnScreen.CONNECTED_HOME, returnScreen).distinct()
                            _currentScreen.value = returnScreen
                        }

                        val fastest = withSelected.firstOrNull()
                        if (autoConnectAfterFetch && fastest != null) {
                            val savedFirst = dao.getAllServersOnce().firstOrNull { it.isSelected } ?: fastest
                            connectToServer(savedFirst)
                        } else if (fastest != null) {
                            val ip = fletcher.resolveServerIpAddress(fastest.host)
                            _telemetry.update { it.copy(serverIpLabel = ip) }
                            val countryCount = withSelected.map { it.countryName }.distinct().size
                            showMessage(
                                "Ranked ${withSelected.size} verified servers across $countryCount countries · #1 ${fastest.countryName} (${fastest.pingMs} ms)"
                            )
                        }
                    },
                    onFailure = { error ->
                        val existing = dao.getAllServersOnce()
                        if (existing.isNotEmpty()) {
                            _fetchingStatusMessage.value = "Re-testing saved servers…"
                            val reRanked = fletcher.benchmarkAndRankTop15(
                                servers = existing,
                                context = getApplication(),
                                settings = _settings.value
                            ) { step ->
                                _fetchingStatusMessage.value = step
                            }
                            if (reRanked.isNotEmpty()) {
                                dao.deleteAllServers()
                                dao.insertServers(
                                    reRanked.mapIndexed { index, srv -> srv.copy(isSelected = index == 0) }
                                )
                            }
                            if (showLoadingScreen && _currentScreen.value == VpnScreen.SPLASH_FETCHING) {
                                _currentScreen.value = returnScreen
                            }
                            showMessage("Updated latency for ${reRanked.size} reachable servers")
                        } else {
                            if (showLoadingScreen && _currentScreen.value == VpnScreen.SPLASH_FETCHING) {
                                _currentScreen.value = VpnScreen.ADD_SERVER
                            }
                            showMessage("Fetch failed: ${error.message ?: "Check subscription URL"}")
                        }
                    }
                )
            } finally {
                _fetchingProgress.value = FetchingProgressState(isActive = false, stage = FetchingStage.IDLE)
            }
        }
    }

    /**
     * Adds a user-provided V2Ray Subscription URL (or inline URI/Base64 payload), streams nodes,
     * and tests reachability + Xray handshakes concurrently in a producer-consumer pipeline.
     */
    fun addAndFetchSubscriptionUrl(rawUrlOrLinks: String) {
        val input = rawUrlOrLinks.trim()
            .replaceFirst(Regex("^https?://(?=https?://)", RegexOption.IGNORE_CASE), "")
        if (input.isBlank()) {
            showMessage("Please enter a valid subscription URL or server link")
            return
        }

        if (isDirectServerInput(input)) {
            importFromTextOrClipboard(input)
            return
        }

        updateSettings { it.copy(activeSubscriptionUrl = input) }
        navigateTo(VpnScreen.SPLASH_FETCHING)

        fetchSweepJob?.cancel()
        fetchSweepJob = viewModelScope.launch {
            try {
                _fetchingStatusMessage.value = "Connecting to subscription feed…"
                _fetchingProgress.value = FetchingProgressState(
                    isActive = true,
                    stage = FetchingStage.DOWNLOADING,
                    headline = "Fetching & testing live",
                    detail = "Connecting to subscription feed…"
                )
                dao.deleteSubscriptionByUrl(input)
                dao.insertSubscription(
                    SubscriptionEntity(
                        url = input,
                        autoUpdate = _settings.value.subAutoUpdate,
                        replaceExisting = _settings.value.subReplaceExisting
                    )
                )

                val fetchResult = fletcher.fetchSubscriptionAndRankTop15(
                    subscriptionUrl = input,
                    context = getApplication(),
                    settings = _settings.value,
                    onProgressState = { progress ->
                        _fetchingProgress.value = progress
                        _fetchingStatusMessage.value = progress.detail
                    },
                    onProgress = { status ->
                        _fetchingStatusMessage.value = status
                    }
                )

                fetchResult.fold(
                    onSuccess = { topNodes ->
                        if (_settings.value.subReplaceExisting) {
                            dao.deleteFetchedServers()
                        }
                        dao.clearSelection()
                        val marked = topNodes.mapIndexed { index, srv ->
                            srv.copy(isSelected = index == 0)
                        }
                        dao.insertServers(marked)
                        val fastest = marked.firstOrNull()
                        if (fastest != null) {
                            val ip = fletcher.resolveServerIpAddress(fastest.host)
                            _telemetry.update { it.copy(serverIpLabel = ip) }
                        }
                        if (_currentScreen.value == VpnScreen.SPLASH_FETCHING) {
                            _screenStack.value = listOf(VpnScreen.CONNECTED_HOME, VpnScreen.SERVER_LIST)
                            _currentScreen.value = VpnScreen.SERVER_LIST
                        }
                        val countryCount = marked.map { it.countryName }.distinct().size
                        showMessage(
                            "Saved ${marked.size} verified servers across $countryCount countries · Fastest: ${fastest?.countryName ?: ""} (${fastest?.pingMs ?: 0} ms)"
                        )
                    },
                    onFailure = { err ->
                        if (_currentScreen.value == VpnScreen.SPLASH_FETCHING) {
                            _currentScreen.value = VpnScreen.ADD_SERVER
                        }
                        showMessage("Could not fetch servers: ${err.message}")
                    }
                )
            } finally {
                _fetchingProgress.value = FetchingProgressState(isActive = false, stage = FetchingStage.IDLE)
            }
        }
    }

    /**
     * Imports V2Ray/Xray servers from clipboard text and runs real Xray outbound latency checks.
     */
    fun importFromTextOrClipboard(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            showMessage("Clipboard is empty — copy a vless://, vmess://, trojan://, ss://, hy2://, or JSON config first")
            return
        }
        viewModelScope.launch {
            val parsed = fletcher.parseImportPayload(trimmed, isCustom = true)
            if (parsed.isEmpty()) {
                showMessage("No valid supported server link or JSON config found")
                return@launch
            }
            val candidatePool = fletcher.selectDiverseCandidatePool(parsed, maxCandidates = 360)
            showMessage("Verifying latency for ${candidatePool.size} imported server(s)…")
            val benchmarked = fletcher.benchmarkAndRankTop15(
                servers = candidatePool,
                context = getApplication(),
                settings = _settings.value
            )
            if (benchmarked.isEmpty()) {
                showMessage("Tested ${candidatePool.size} server(s), but none passed Xray proxy verification")
                return@launch
            }
            dao.clearSelection()
            val toInsert = benchmarked.mapIndexed { idx, server ->
                server.copy(isSelected = idx == 0)
            }
            dao.insertServers(toInsert)
            navigateTo(VpnScreen.SERVER_LIST)
            val best = toInsert.first()
            showMessage("Imported ${toInsert.size} verified server(s) · Fastest: ${best.countryName} (${best.pingMs} ms)")
        }
    }

    /**
     * Validates, verifies via Xray outbound probe, and saves a manual custom V2Ray/Xray server or Raw Xray JSON config.
     */
    fun addManualCustomServer(
        countryName: String,
        host: String,
        port: Int,
        protocol: String,
        transport: String,
        uuidOrPassword: String,
        sni: String,
        rawJsonInput: String,
        publicKey: String = "",
        shortId: String = "",
        wsPath: String = "",
        wsHost: String = "",
        grpcServiceName: String = ""
    ) {
        viewModelScope.launch {
            if (rawJsonInput.trim().startsWith("{")) {
                val parsedFromJson = fletcher.parseRawXrayJson(rawJsonInput.trim(), isCustom = true)
                if (parsedFromJson.isEmpty()) {
                    showMessage("Invalid JSON configuration — check outbounds syntax")
                    return@launch
                }
                val candidate = parsedFromJson.first()
                val verifiedPing = verifyServerLatencyOrNull(candidate)
                if (verifiedPing == null) {
                    showMessage("Server ${candidate.host}:${candidate.port} failed proxy verification")
                    return@launch
                }
                dao.clearSelection()
                val verified = candidate.copy(pingMs = verifiedPing, isSelected = true, isCustom = true)
                dao.insertServer(verified)
                _selectedFilter.value = ServerFilter.CUSTOM
                navigateTo(VpnScreen.SERVER_LIST)
                showMessage("Added custom server (${verified.countryName} · $verifiedPing ms)")
                return@launch
            }

            val cleanHost = host.trim()
            if (cleanHost.isBlank()) {
                showMessage("Please enter a valid server hostname or IP address")
                return@launch
            }
            if (uuidOrPassword.isBlank()) {
                showMessage("Please enter a UUID or password for the server")
                return@launch
            }

            val normalizedProtocol = if (protocol.contains("Hysteria", ignoreCase = true)) {
                "Hysteria2 (Xray)"
            } else if (protocol.contains("Shadowsocks", ignoreCase = true)) {
                "Shadowsocks (Xray)"
            } else {
                "$protocol (V2Ray)"
            }
            val normalizedTransport = when {
                protocol.contains("Hysteria", ignoreCase = true) -> "Hysteria2"
                transport.equals("WS", ignoreCase = true) -> "WebSocket"
                else -> transport
            }

            val baseServer = ServerEntity(
                countryName = countryName.trim(),
                countryCode = "XX",
                protocolDisplay = "V2Ray · $normalizedTransport",
                protocolCore = normalizedProtocol,
                transport = normalizedTransport,
                host = cleanHost,
                port = port.coerceIn(1, 65535),
                uuidOrPassword = uuidOrPassword.trim(),
                sni = sni.trim(),
                publicKey = publicKey.trim(),
                pingMs = 0,
                isRecommendedTop15 = true,
                rankOrder = 1,
                isSelected = true,
                isCustom = true
            )
            val generatedJson = fletcher.generateXrayJsonForServer(
                server = baseServer,
                wsPath = wsPath.trim(),
                wsHost = wsHost.trim(),
                grpcServiceName = grpcServiceName.trim(),
                shortId = shortId.trim()
            )
            val testableServer = baseServer.copy(rawConfigJson = generatedJson)

            showMessage("Verifying proxy connection to $cleanHost:$port…")
            val verifiedPing = verifyServerLatencyOrNull(testableServer)
            if (verifiedPing == null) {
                showMessage("Host $cleanHost:$port failed Xray proxy handshake")
                return@launch
            }

            val enriched = fletcher.enrichServerWithIpAndCountry(
                testableServer.copy(pingMs = verifiedPing),
                rank = 1
            )
            val finalServer = if (countryName.trim().isNotBlank()) {
                enriched.copy(countryName = countryName.trim(), rawConfigJson = generatedJson, isCustom = true)
            } else {
                enriched.copy(rawConfigJson = generatedJson, isCustom = true)
            }
            dao.clearSelection()
            dao.insertServer(finalServer)
            _selectedFilter.value = ServerFilter.CUSTOM
            navigateTo(VpnScreen.SERVER_LIST)
            showMessage("Added ${finalServer.countryName} · ${finalServer.resolvedIp} ($verifiedPing ms)")
        }
    }

    fun pingSingleServer(server: ServerEntity) {
        viewModelScope.launch {
            val livePing = verifyServerLatencyOrNull(server)
            if (livePing != null) {
                dao.updateServer(server.copy(pingMs = livePing))
                showMessage("${server.countryName} verified: $livePing ms")
            } else {
                dao.updateServer(server.copy(pingMs = 0))
                showMessage("${server.countryName} (${server.host}:${server.port}) failed proxy handshake")
            }
        }
    }

    fun repingAllSavedServers() {
        if (repingJob?.isActive == true) {
            showMessage("Speed test already in progress…")
            return
        }
        repingJob = viewModelScope.launch {
            val existing = dao.getAllServersOnce()
            if (existing.isEmpty()) {
                showMessage("No saved servers to test")
                return@launch
            }
            showMessage("Testing ${existing.size} saved server(s)…")
            val semaphore = Semaphore(8)
            val updated = coroutineScope {
                existing.map { server ->
                    async {
                        semaphore.withPermit {
                            val ping = verifyServerLatencyOrNull(server) ?: 0
                            val updatedEntity = server.copy(pingMs = ping)
                            dao.updateServer(updatedEntity)
                            updatedEntity
                        }
                    }
                }.awaitAll()
            }
            val reachable = updated.filter { it.pingMs > 0 }
            val fastest = reachable.minByOrNull { it.pingMs }
            if (fastest != null) {
                showMessage(
                    "Verified ${reachable.size}/${existing.size} servers · Fastest: ${fastest.countryName} (${fastest.pingMs} ms)"
                )
            } else {
                showMessage("Tested ${existing.size} server(s), none passed proxy check")
            }
        }
    }

    private suspend fun verifyServerLatencyOrNull(server: ServerEntity): Int? {
        return when (val probe = fletcher.measureRealXrayDelayOrNull(getApplication(), server, _settings.value)) {
            is XrayProbeResult.Success -> probe.delayMs
            XrayProbeResult.NativeUnavailable -> withContext(Dispatchers.IO) {
                fletcher.measureRealTcpLatencyOrNull(server.host, server.port, 1800)
            }
            XrayProbeResult.Unreachable -> null
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            dao.deleteServerById(server.id)
            showMessage("Removed ${server.countryName}")
        }
    }

    fun deleteServersByIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { dao.deleteServerById(it) }
            showMessage("Removed ${ids.size} server(s)")
        }
    }

    fun deleteDuplicateServers() {
        viewModelScope.launch {
            val all = dao.getAllServersOnce()
            val seen = HashSet<String>()
            val duplicates = mutableListOf<ServerEntity>()
            all.sortedByDescending { it.isSelected }
                .forEach { server ->
                    val key = "${server.host.lowercase()}:${server.port}:${server.protocolCore.lowercase()}:${server.uuidOrPassword}"
                    if (!seen.add(key)) {
                        duplicates += server
                    }
                }
            if (duplicates.isEmpty()) {
                showMessage("No duplicate servers found")
            } else {
                duplicates.forEach { dao.deleteServerById(it.id) }
                showMessage("Removed ${duplicates.size} duplicate server(s)")
            }
        }
    }

    fun deleteInvalidServers() {
        viewModelScope.launch {
            val invalid = dao.getAllServersOnce().filter { it.pingMs <= 0 }
            if (invalid.isEmpty()) {
                showMessage("No invalid or unreachable servers found")
            } else {
                invalid.forEach { dao.deleteServerById(it.id) }
                showMessage("Removed ${invalid.size} invalid server(s)")
            }
        }
    }

    fun openQrShare(server: ServerEntity) {
        val raw = server.rawConfigJson.ifBlank { fletcher.generateXrayJsonForServer(server) }
        _sharingQrServer.value = server.copy(rawConfigJson = raw)
    }

    fun closeQrShare() {
        _sharingQrServer.value = null
    }

    fun buildExportBackupPayload(servers: List<ServerEntity> = allServers.value): String =
        Companion.buildExportBackupPayload(servers, fletcher)

    companion object {
        fun buildExportBackupPayload(
            servers: List<ServerEntity>,
            fletcher: V2RayParserAndFletcher = V2RayParserAndFletcher()
        ): String {
            if (servers.isEmpty()) return ""
            val uris = servers.mapNotNull { it.shareUri.trim().takeIf { u -> u.isNotEmpty() } }
            if (uris.isNotEmpty() && uris.size == servers.size) {
                return uris.joinToString("\n")
            }
            if (uris.isNotEmpty() && servers.none { it.rawConfigJson.isNotBlank() }) {
                return uris.joinToString("\n")
            }
            val outbounds = JSONArray()
            servers.forEach { server ->
                val raw = server.rawConfigJson.ifBlank { fletcher.generateXrayJsonForServer(server) }
                try {
                    val root = JSONObject(raw)
                    val obs = root.optJSONArray("outbounds")
                    if (obs != null && obs.length() > 0) {
                        val primary = obs.optJSONObject(0)
                        if (primary != null) outbounds.put(primary)
                    }
                } catch (_: Exception) {
                }
            }
            return if (outbounds.length() > 0) {
                JSONObject().put("outbounds", outbounds).toString(2)
            } else {
                uris.joinToString("\n")
            }
        }
    }

    fun clearXrayLogs() {
        XrayVpnService.clearLogs()
        showMessage("Cleared Xray diagnostic logs")
    }

    fun openJsonInspector(server: ServerEntity) {
        val json = server.rawConfigJson.ifBlank { fletcher.generateXrayJsonForServer(server) }
        _inspectingJsonServer.value = server.copy(rawConfigJson = json)
    }

    fun closeJsonInspector() {
        _inspectingJsonServer.value = null
    }

    fun saveEditedServerJson(server: ServerEntity, updatedJson: String) {
        val validationError = validateXrayConfigJson(updatedJson)
        if (validationError != null) {
            showMessage(validationError)
            return
        }
        viewModelScope.launch {
            dao.updateServer(server.copy(rawConfigJson = updatedJson.trim()))
            _inspectingJsonServer.value = null
            showMessage("Saved config for ${server.countryName}")
        }
    }

    private fun validateXrayConfigJson(rawJson: String): String? {
        return try {
            val root = JSONObject(rawJson.trim())
            val outbounds: JSONArray? = root.optJSONArray("outbounds")
            if (outbounds == null || outbounds.length() == 0) {
                return "Invalid Xray JSON: missing 'outbounds' array"
            }
            val hasProxyOutbound = (0 until outbounds.length()).any { idx ->
                val proto = outbounds.optJSONObject(idx)?.optString("protocol", "").orEmpty().lowercase()
                proto.isNotBlank() && proto !in listOf("freedom", "blackhole", "dns", "direct", "block")
            }
            if (!hasProxyOutbound) {
                return "Invalid Xray JSON: at least one proxy outbound is required"
            }
            null
        } catch (e: Exception) {
            "Invalid JSON syntax: ${e.message ?: "parse error"}"
        }
    }

    private fun isDirectServerInput(input: String): Boolean {
        val lower = input.trim().lowercase()
        return lower.startsWith("vmess://") ||
            lower.startsWith("vless://") ||
            lower.startsWith("trojan://") ||
            lower.startsWith("ss://") ||
            lower.startsWith("hy2://") ||
            lower.startsWith("hysteria2://") ||
            lower.startsWith("wireguard://") ||
            lower.startsWith("socks://") ||
            lower.startsWith("socks5://") ||
            lower.startsWith("{")
    }

    fun updateSettings(transform: (AppSettingsState) -> AppSettingsState) {
        var subAutoUpdateChanged = false
        var perAppChanged = false
        var latestSettings = _settings.value
        _settings.update { current ->
            val updated = transform(current)
            subAutoUpdateChanged = updated.subAutoUpdate != current.subAutoUpdate
            perAppChanged = updated.perAppProxyEnabled != current.perAppProxyEnabled ||
                updated.bypassSelectedApps != current.bypassSelectedApps ||
                updated.selectedAppPackages != current.selectedAppPackages
            latestSettings = updated
            prefsStore.saveSettings(updated)
            updated
        }
        if (subAutoUpdateChanged) {
            HollowJobScheduler.schedulePeriodicMaintenance(getApplication(), latestSettings)
        }
        if (perAppChanged && (_telemetry.value.isConnected || _telemetry.value.isConnecting)) {
            showMessage("Per-app routing updated — reconnect VPN to apply")
        }
    }

    fun cycleUpdateInterval() {
        val intervals = listOf("15 min", "30 min", "1 hour", "6 hours", "12 hours", "24 hours")
        updateSettings { current ->
            val nextIdx = (intervals.indexOf(current.updateIntervalLabel) + 1) % intervals.size
            val next = intervals[nextIdx]
            current.copy(updateIntervalLabel = next, subUpdateInterval = next)
        }
    }

    fun cycleProtocolPreference() {
        val options = listOf(
            "Auto (V2Ray)",
            "VLESS (Reality)",
            "VLESS (WS / TCP)",
            "VMess (WebSocket)",
            "Trojan (TLS)",
            "Hysteria2",
            "gRPC",
            "Shadowsocks"
        )
        updateSettings { current ->
            val nextIdx = (options.indexOf(current.protocolPreference) + 1) % options.size
            current.copy(protocolPreference = options[nextIdx])
        }
    }

    fun cycleRoutingMode() {
        val options = listOf("Global", "Bypass LAN", "Bypass LAN & Domestic", "Direct Only")
        updateSettings { current ->
            val nextIdx = (options.indexOf(current.routingMode) + 1) % options.size
            current.copy(routingMode = options[nextIdx])
        }
    }

    fun cycleDnsMode() {
        val options = listOf(
            "Auto (DoH)",
            "Cloudflare 1.1.1.1",
            "Google 8.8.8.8",
            "Quad9 9.9.9.9",
            "AdGuard 94.140.14.14"
        )
        updateSettings { current ->
            val nextIdx = (options.indexOf(current.dnsMode) + 1) % options.size
            current.copy(dnsMode = options[nextIdx])
        }
    }

    fun clearServerCacheAndReset() {
        connectJob?.cancel()
        connectJob = null
        viewModelScope.launch {
            stopAndroidVpnService()
            prefsStore.clearActiveTunnelSnapshot()
            _telemetry.value = ConnectionTelemetryState()
            dao.deleteAllServers()
            showMessage("Cleared all cached servers")
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun showMessage(msg: String) {
        _snackbarMessage.value = msg
    }

    private fun startAndroidVpnService(server: ServerEntity) {
        try {
            val ctx = getApplication<Application>()
            val currentSettings = _settings.value
            val baseJson = server.rawConfigJson.ifBlank { fletcher.generateXrayJsonForServer(server) }
            val xrayJson = fletcher.applyRuntimeSettingsToXrayJson(baseJson, currentSettings)
            val intent = Intent(ctx, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_CONNECT
                putExtra(XrayVpnService.EXTRA_SERVER_COUNTRY, server.countryName)
                putExtra(XrayVpnService.EXTRA_SERVER_HOST, server.host)
                putExtra(XrayVpnService.EXTRA_SERVER_PORT, server.port)
                putExtra(XrayVpnService.EXTRA_SERVER_PROTOCOL, server.protocolCore)
                putExtra(XrayVpnService.EXTRA_XRAY_JSON, xrayJson)
                putExtra(XrayVpnService.EXTRA_TUN_MODE, currentSettings.tunMode)
                putExtra(XrayVpnService.EXTRA_ENABLE_IPV6, currentSettings.ipv6Enabled)
                putExtra(XrayVpnService.EXTRA_DNS_MODE, currentSettings.dnsMode)
                putExtra(XrayVpnService.EXTRA_ROUTING_MODE, currentSettings.routingMode)
                putExtra(XrayVpnService.EXTRA_PER_APP_PROXY_ENABLED, currentSettings.perAppProxyEnabled)
                putExtra(XrayVpnService.EXTRA_BYPASS_SELECTED_APPS, currentSettings.bypassSelectedApps)
                putStringArrayListExtra(
                    XrayVpnService.EXTRA_SELECTED_APP_PACKAGES,
                    ArrayList(currentSettings.selectedAppPackages)
                )
                putExtra(XrayVpnService.EXTRA_SMART_SWITCH_ENABLED, currentSettings.smartSwitchEnabled)
                putExtra(XrayVpnService.EXTRA_SERVER_SPEED_MBPS, server.lastSpeedMbps)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            _telemetry.update { it.copy(isConnecting = false, isConnected = false) }
            showMessage("Could not start VPN service: ${e.message}")
        }
    }

    private fun stopAndroidVpnService() {
        try {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, XrayVpnService::class.java).apply {
                action = XrayVpnService.ACTION_DISCONNECT
            }
            ctx.startService(intent)
        } catch (_: Exception) {
        }
    }
}
