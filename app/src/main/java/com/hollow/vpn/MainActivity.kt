package com.hollow.vpn

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hollow.vpn.ui.VpnScreen
import com.hollow.vpn.ui.VpnViewModel
import com.hollow.vpn.ui.components.HollowBottomNavigationBar
import com.hollow.vpn.ui.components.HollowBottomTab
import com.hollow.vpn.ui.components.ServerQrShareDialog
import com.hollow.vpn.ui.screens.AddServerScreen
import com.hollow.vpn.ui.screens.ConnectedHomeScreen
import com.hollow.vpn.ui.screens.CountryServerListScreen
import com.hollow.vpn.ui.screens.GettingStartedScreen
import com.hollow.vpn.ui.screens.RawXrayJsonEditorDialog
import com.hollow.vpn.ui.screens.SettingsScreen
import com.hollow.vpn.ui.screens.SplashFetchingScreen
import com.hollow.vpn.ui.theme.HollowAmber
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowForest
import com.hollow.vpn.ui.theme.HollowForestDivider
import com.hollow.vpn.ui.theme.HollowForestMuted
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowLime
import com.hollow.vpn.ui.theme.HollowObsidian
import com.hollow.vpn.ui.theme.HollowObsidianDivider
import com.hollow.vpn.ui.theme.HollowObsidianMuted
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.HollowVpnTheme
import com.hollow.vpn.vpn.XrayVpnService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HollowVpnTheme {
                HollowVpnApp()
            }
        }
    }
}

@Composable
fun HollowVpnApp(
    vpnViewModel: VpnViewModel = viewModel()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val currentScreen by vpnViewModel.currentScreen.collectAsStateWithLifecycle()
    val gettingStartedStep by vpnViewModel.gettingStartedStep.collectAsStateWithLifecycle()
    val allServers by vpnViewModel.allServers.collectAsStateWithLifecycle()
    val filteredServers by vpnViewModel.filteredServers.collectAsStateWithLifecycle()
    val selectedFilter by vpnViewModel.selectedFilter.collectAsStateWithLifecycle()
    val selectedServer by vpnViewModel.selectedServer.collectAsStateWithLifecycle()
    val telemetry by vpnViewModel.telemetry.collectAsStateWithLifecycle()
    val settings by vpnViewModel.settings.collectAsStateWithLifecycle()
    val fetchingStatus by vpnViewModel.fetchingStatusMessage.collectAsStateWithLifecycle()
    val snackbarMessage by vpnViewModel.snackbarMessage.collectAsStateWithLifecycle()
    val activeAddTab by vpnViewModel.activeAddTab.collectAsStateWithLifecycle()
    val inspectingJsonServer by vpnViewModel.inspectingJsonServer.collectAsStateWithLifecycle()
    val sharingQrServer by vpnViewModel.sharingQrServer.collectAsStateWithLifecycle()
    val geoAssetsStatus by vpnViewModel.geoAssetsStatus.collectAsStateWithLifecycle()
    val xrayLogs by XrayVpnService.logs.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val isDarkHomePhase = currentScreen == VpnScreen.CONNECTED_HOME &&
        (telemetry.isConnecting || telemetry.isConnected)

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !isDarkHomePhase
        insetsController.isAppearanceLightNavigationBars = !isDarkHomePhase
    }

    LaunchedEffect(settings.hideFromRecentApps) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.firstOrNull()?.setExcludeFromRecents(settings.hideFromRecentApps)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vpnViewModel.clearSnackbar()
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnViewModel.toggleConnection()
        } else {
            vpnViewModel.showMessage("VPN permission was denied")
        }
    }

    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var notificationPromptedThisSession by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    val runWithNotificationPermission: (() -> Unit) -> Unit = { action ->
        val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPromptedThisSession &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (shouldRequest) {
            notificationPromptedThisSession = true
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val startOrToggleVpnConsent = {
        val prepareIntent = try {
            if (settings.tunMode && !telemetry.isConnected && !telemetry.isConnecting) {
                VpnService.prepare(context)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (prepareIntent != null && context is Activity) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            vpnViewModel.toggleConnection()
        }
    }

    val requestOrToggleVpn = {
        if (telemetry.isConnected || telemetry.isConnecting) {
            vpnViewModel.toggleConnection()
        } else {
            runWithNotificationPermission(startOrToggleVpnConsent)
        }
    }

    val exportServersToClipboard: () -> Unit = {
        val servers = allServers
        val payload = vpnViewModel.buildExportBackupPayload(servers)
        if (payload.isBlank()) {
            vpnViewModel.showMessage("No saved servers to export")
        } else {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Hollow VPN Servers", payload))
            vpnViewModel.showMessage("Exported ${servers.size} server(s) to clipboard")
        }
    }

    // Bottom navigation is shown on Connect (Pages 1-3), Servers (Pages 4 & 7), and Add Server (Page 5)
    val showBottomNav = currentScreen == VpnScreen.CONNECTED_HOME ||
        currentScreen == VpnScreen.SERVER_LIST ||
        currentScreen == VpnScreen.ADD_SERVER

    val selectedBottomTab = when (currentScreen) {
        VpnScreen.CONNECTED_HOME -> HollowBottomTab.CONNECT
        VpnScreen.SERVER_LIST, VpnScreen.ADD_SERVER -> HollowBottomTab.SERVERS
        VpnScreen.SETTINGS -> HollowBottomTab.SETTINGS
        VpnScreen.GETTING_STARTED, VpnScreen.SPLASH_FETCHING -> HollowBottomTab.CONNECT
    }

    val navBackgroundColor = when {
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnecting -> HollowObsidian
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnected -> HollowForest
        else -> HollowCream
    }
    val navBorderColor = when {
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnecting -> HollowObsidianDivider
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnected -> HollowForestDivider
        else -> HollowCreamDivider
    }
    val navIndicatorColor = when {
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnecting -> HollowAmber
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnected -> HollowLime
        else -> HollowOrange
    }
    val navActiveTextColor = when {
        currentScreen == VpnScreen.CONNECTED_HOME && (telemetry.isConnecting || telemetry.isConnected) -> HollowCream
        else -> HollowInk
    }
    val navInactiveTextColor = when {
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnecting -> HollowObsidianMuted
        currentScreen == VpnScreen.CONNECTED_HOME && telemetry.isConnected -> HollowForestMuted
        else -> HollowInkSecondary
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = showBottomNav && maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail(
                    containerColor = navBackgroundColor,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.weight(1f))
                    val railColors = NavigationRailItemDefaults.colors(
                        selectedIconColor = navActiveTextColor,
                        selectedTextColor = navActiveTextColor,
                        unselectedIconColor = navInactiveTextColor,
                        unselectedTextColor = navInactiveTextColor,
                        indicatorColor = navIndicatorColor.copy(alpha = 0.22f)
                    )
                    NavigationRailItem(
                        selected = selectedBottomTab == HollowBottomTab.CONNECT,
                        onClick = { vpnViewModel.navigateTo(VpnScreen.CONNECTED_HOME) },
                        icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Connect") },
                        label = { Text("Connect") },
                        colors = railColors
                    )
                    NavigationRailItem(
                        selected = selectedBottomTab == HollowBottomTab.SERVERS,
                        onClick = { vpnViewModel.navigateTo(VpnScreen.SERVER_LIST) },
                        icon = { Icon(Icons.Outlined.Dns, contentDescription = "Servers") },
                        label = { Text("Servers") },
                        colors = railColors
                    )
                    NavigationRailItem(
                        selected = selectedBottomTab == HollowBottomTab.SETTINGS,
                        onClick = { vpnViewModel.navigateTo(VpnScreen.SETTINGS) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = railColors
                    )
                    Spacer(Modifier.weight(1f))
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(16.dp),
                            containerColor = HollowInk,
                            contentColor = HollowCream
                        )
                    }
                },
                bottomBar = {
                    if (showBottomNav && !useNavigationRail) {
                        HollowBottomNavigationBar(
                            selectedTab = selectedBottomTab,
                            backgroundColor = navBackgroundColor,
                            borderColor = navBorderColor,
                            activeIndicatorColor = navIndicatorColor,
                            activeContentColor = navActiveTextColor,
                            inactiveContentColor = navInactiveTextColor,
                            onSelectConnect = { vpnViewModel.navigateTo(VpnScreen.CONNECTED_HOME) },
                            onSelectServers = { vpnViewModel.navigateTo(VpnScreen.SERVER_LIST) },
                            onSelectSettings = { vpnViewModel.navigateTo(VpnScreen.SETTINGS) }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = currentScreen,
                        animationSpec = tween(durationMillis = 220),
                        label = "vpn_screen_crossfade"
                    ) { screen ->
                        when (screen) {
                            VpnScreen.GETTING_STARTED -> {
                                GettingStartedScreen(
                                    currentStep = gettingStartedStep,
                                    settings = settings,
                                    serverCount = allServers.size,
                                    onSelectStep = vpnViewModel::setGettingStartedStep,
                                    onFetchSubscription = vpnViewModel::fetchSubscriptionInOnboarding,
                                    onImportConfig = vpnViewModel::importConfigInOnboarding,
                                    onSkip = {
                                        vpnViewModel.completeOnboarding(
                                            targetScreen = VpnScreen.CONNECTED_HOME,
                                            autoConnectIfReady = false
                                        )
                                    },
                                    onFinish = { autoConnect ->
                                        vpnViewModel.completeOnboarding(
                                            targetScreen = VpnScreen.CONNECTED_HOME,
                                            autoConnectIfReady = autoConnect
                                        )
                                    },
                                    onShowMessage = vpnViewModel::showMessage
                                )
                            }

                            VpnScreen.SPLASH_FETCHING -> {
                                SplashFetchingScreen(
                                    statusSubtitle = fetchingStatus,
                                    onCancelOrSkip = {
                                        vpnViewModel.navigateTo(VpnScreen.SERVER_LIST)
                                    }
                                )
                            }

                            VpnScreen.SERVER_LIST -> {
                                CountryServerListScreen(
                                    servers = filteredServers,
                                    selectedFilter = selectedFilter,
                                    activeProtocolPreference = settings.protocolPreference,
                                    bottomContentPadding = innerPadding.calculateBottomPadding(),
                                    onSelectFilter = vpnViewModel::setFilter,
                                    onResetProtocolFilter = {
                                        vpnViewModel.updateSettings { it.copy(protocolPreference = "Auto (V2Ray)") }
                                    },
                                    onSelectServer = { server ->
                                        runWithNotificationPermission {
                                            val prepareIntent = try {
                                                if (settings.tunMode) VpnService.prepare(context) else null
                                            } catch (_: Exception) {
                                                null
                                            }
                                            if (prepareIntent != null && context is Activity) {
                                                vpnViewModel.selectServer(server, openConnectedScreen = true)
                                                vpnPermissionLauncher.launch(prepareIntent)
                                            } else {
                                                vpnViewModel.selectAndConnectServer(
                                                    server = server,
                                                    openConnectedScreen = true
                                                )
                                            }
                                        }
                                    },
                                    onRefresh = {
                                        vpnViewModel.runFletcherAutoFetchAndPingSweep(
                                            returnScreen = VpnScreen.SERVER_LIST
                                        )
                                    },
                                    onOpenSettings = {
                                        vpnViewModel.navigateTo(VpnScreen.SETTINGS)
                                    },
                                    onOpenAddServer = {
                                        vpnViewModel.navigateTo(VpnScreen.ADD_SERVER)
                                    },
                                    onPingServer = vpnViewModel::pingSingleServer,
                                    onRepingAll = vpnViewModel::repingAllSavedServers,
                                    onShowMessage = vpnViewModel::showMessage,
                                    onShareQr = vpnViewModel::openQrShare,
                                    onInspectJson = vpnViewModel::openJsonInspector,
                                    onDeleteServer = vpnViewModel::deleteServer,
                                    onDeleteServersByIds = vpnViewModel::deleteServersByIds,
                                    onDeleteDuplicates = vpnViewModel::deleteDuplicateServers,
                                    onDeleteInvalid = vpnViewModel::deleteInvalidServers,
                                    onExportAllServers = exportServersToClipboard,
                                    onDeleteAllServers = vpnViewModel::clearServerCacheAndReset,
                                    onBack = {
                                        if (!vpnViewModel.navigateBack()) {
                                            vpnViewModel.navigateTo(VpnScreen.CONNECTED_HOME)
                                        }
                                    }
                                )
                            }

                            VpnScreen.CONNECTED_HOME -> {
                                ConnectedHomeScreen(
                                    selectedServer = selectedServer,
                                    telemetry = telemetry,
                                    tunModeEnabled = settings.tunMode,
                                    bottomContentPadding = innerPadding.calculateBottomPadding(),
                                    onToggleConnect = requestOrToggleVpn,
                                    onOpenServerList = {
                                        vpnViewModel.navigateTo(VpnScreen.SERVER_LIST)
                                    },
                                    onOpenSettings = {
                                        vpnViewModel.navigateTo(VpnScreen.SETTINGS)
                                    },
                                    onOpenAddServer = {
                                        vpnViewModel.navigateTo(VpnScreen.ADD_SERVER)
                                    },
                                    onRefreshRealIp = vpnViewModel::refreshRealPublicIp
                                )
                            }

                            VpnScreen.SETTINGS -> {
                                SettingsScreen(
                                    settings = settings,
                                    xrayLogs = xrayLogs,
                                    serverCount = allServers.size,
                                    geoAssetsStatus = geoAssetsStatus,
                                    onUpdateSettings = vpnViewModel::updateSettings,
                                    onOpenAddSubscription = {
                                        vpnViewModel.navigateTo(VpnScreen.ADD_SERVER)
                                    },
                                    onOpenGettingStarted = vpnViewModel::reopenGettingStarted,
                                    onClearXrayLogs = vpnViewModel::clearXrayLogs,
                                    onExportServers = exportServersToClipboard,
                                    onDownloadGeoAssets = vpnViewModel::downloadGeoAssets,
                                    onShowMessage = vpnViewModel::showMessage,
                                    onClearCache = vpnViewModel::clearServerCacheAndReset,
                                    onBack = {
                                        if (!vpnViewModel.navigateBack()) {
                                            vpnViewModel.navigateTo(VpnScreen.CONNECTED_HOME)
                                        }
                                    }
                                )
                            }

                            VpnScreen.ADD_SERVER -> {
                                AddServerScreen(
                                    activeTab = activeAddTab,
                                    settings = settings,
                                    bottomContentPadding = innerPadding.calculateBottomPadding(),
                                    onSelectTab = vpnViewModel::setAddServerTab,
                                    onAddAndFetchSubscription = vpnViewModel::addAndFetchSubscriptionUrl,
                                    onImportClipboardText = vpnViewModel::importFromTextOrClipboard,
                                    onAddManualServer = vpnViewModel::addManualCustomServer,
                                    onUpdateSettings = vpnViewModel::updateSettings,
                                    onBack = {
                                        if (!vpnViewModel.navigateBack()) {
                                            vpnViewModel.navigateTo(VpnScreen.SERVER_LIST)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    inspectingJsonServer?.let { server ->
                        RawXrayJsonEditorDialog(
                            server = server,
                            onDismiss = vpnViewModel::closeJsonInspector,
                            onSaveJson = { updatedJson ->
                                vpnViewModel.saveEditedServerJson(server, updatedJson)
                            }
                        )
                    }

                    sharingQrServer?.let { server ->
                        ServerQrShareDialog(
                            server = server,
                            onCopyPayload = { payload ->
                                if (payload.isNotBlank()) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Hollow VPN Server", payload))
                                    vpnViewModel.showMessage("Copied server config for ${server.countryName}")
                                    vpnViewModel.closeQrShare()
                                }
                            },
                            onDismiss = vpnViewModel::closeQrShare
                        )
                    }
                }
            }
        }
    }
}
