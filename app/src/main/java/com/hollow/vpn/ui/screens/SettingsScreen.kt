package com.hollow.vpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.hollow.vpn.BuildConfig
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.AppUpdateState
import com.hollow.vpn.ui.components.HollowBrandShieldLogo
import com.hollow.vpn.ui.components.HollowSwitch
import com.hollow.vpn.ui.components.HollowTopSquareButton
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowLime
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

private enum class ActiveSettingDialog {
    UPDATE_INTERVAL,
    PROTOCOL,
    ROUTING_MODE,
    DNS_PROVIDER,
    XRAY_LOGS,
    ABOUT,
    CHECK_UPDATE
}

private val PROTOCOL_OPTIONS = listOf(
    SettingChoiceOption("Auto (V2Ray)", "Selects the fastest & stealthiest protocol automatically", "Auto (Recommended)"),
    SettingChoiceOption("VLESS (Reality)", "Modern TLS-fingerprint camouflage for strict networks", "VLESS (Reality)"),
    SettingChoiceOption("VLESS (WS / TCP)", "Low-overhead tunnel for everyday high-speed browsing", "VLESS (WS / TCP)"),
    SettingChoiceOption("VMess (WebSocket)", "Broadly compatible WebSocket fallback transport", "VMess (WebSocket)"),
    SettingChoiceOption("Trojan (TLS)", "Blends with standard HTTPS web traffic", "Trojan (TLS)"),
    SettingChoiceOption("Hysteria2", "QUIC-based high-throughput mode for streaming", "Hysteria2"),
    SettingChoiceOption("gRPC", "Multiplexed HTTP/2 tunnel for unstable mobile data", "gRPC"),
    SettingChoiceOption("Shadowsocks", "Lightweight AEAD cipher proxy", "Shadowsocks")
)

private val ROUTING_MODE_OPTIONS = listOf(
    SettingChoiceOption("Global", "Route all device internet traffic through the VPN", "Global (Recommended)"),
    SettingChoiceOption("Bypass LAN", "Keep local Wi‑Fi devices like printers & TVs reachable directly", "Bypass LAN"),
    SettingChoiceOption("Direct Only", "Diagnostic mode — sends traffic directly without VPN", "Direct Only (Testing)")
)

private val DNS_PROVIDER_OPTIONS = listOf(
    SettingChoiceOption("Auto (DoH)", "Encrypted private DNS lookups — safest default", "Auto (DoH)"),
    SettingChoiceOption("Cloudflare 1.1.1.1", "Fast privacy-focused DNS by Cloudflare", "Cloudflare 1.1.1.1"),
    SettingChoiceOption("Google 8.8.8.8", "Reliable global DNS by Google", "Google 8.8.8.8"),
    SettingChoiceOption("Quad9 9.9.9.9", "Blocks known malicious and phishing domains", "Quad9 9.9.9.9"),
    SettingChoiceOption("AdGuard 94.140.14.14", "Filters ads and trackers at the DNS level", "AdGuard DNS")
)

private fun formatRoutingSummary(mode: String): String = when {
    mode.equals("Global", ignoreCase = true) -> "Global"
    mode.equals("Bypass LAN", ignoreCase = true) -> "Bypass LAN"
    mode.contains("Direct", ignoreCase = true) -> "Direct Only"
    else -> "Global"
}

private fun formatDnsSummary(dns: String): String = when {
    dns.startsWith("Auto", ignoreCase = true) -> "Auto (DoH)"
    dns.contains("Cloudflare", ignoreCase = true) -> "1.1.1.1"
    dns.contains("Google", ignoreCase = true) -> "8.8.8.8"
    dns.contains("Quad9", ignoreCase = true) -> "9.9.9.9"
    dns.contains("AdGuard", ignoreCase = true) -> "AdGuard"
    else -> dns
}

/**
 * PAGE 6: Hollow Editorial Settings Screen (Original Cream Surface & Palette)
 */
@Composable
fun SettingsScreen(
    settings: AppSettingsState,
    xrayLogs: List<String> = emptyList(),
    serverCount: Int = 0,
    appUpdateState: AppUpdateState = AppUpdateState.Idle,
    bottomContentPadding: Dp = 0.dp,
    onUpdateSettings: ((AppSettingsState) -> AppSettingsState) -> Unit,
    onOpenAddSubscription: () -> Unit,
    onOpenGettingStarted: () -> Unit = {},
    onClearXrayLogs: () -> Unit = {},
    onExportServers: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<ActiveSettingDialog?>(null) }
    var showPerAppPicker by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HollowCream)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            HollowTopSquareButton(
                containerColor = HollowCreamSurface,
                contentColor = HollowInk,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier.testTag("btn_navigate_back")
            ) { tint ->
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Settings",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                letterSpacing = (-0.7).sp,
                color = HollowInk
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Subscriptions, tunnel engine & routing controls",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = HollowInkSecondary
            )

            Spacer(modifier = Modifier.height(26.dp))

            // 1. SUBSCRIPTIONS (Top priority)
            SettingsSectionHeader(title = "SUBSCRIPTIONS")

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Link,
                title = "Servers & subscriptions",
                subtitle = "Add subscription links, keys, or QR codes",
                valueText = if (serverCount > 0) "$serverCount saved" else "Add",
                onClick = onOpenAddSubscription,
                testTag = "row_custom_subscription"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.CloudQueue,
                title = "Auto-update servers",
                subtitle = "Periodically refresh & rank fastest nodes",
                checked = settings.autoConfigure,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(autoConfigure = enabled) }
                },
                testTag = "switch_auto_configure"
            )
            if (settings.autoConfigure) {
                HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                SettingsValueNavigationRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Update interval",
                    subtitle = "Background refresh frequency",
                    valueText = settings.updateIntervalLabel,
                    onClick = { activeDialog = ActiveSettingDialog.UPDATE_INTERVAL },
                    testTag = "row_update_interval"
                )
            }
            Spacer(modifier = Modifier.height(28.dp))

            // 2. PER-APP ROUTING
            SettingsSectionHeader(title = "PER-APP ROUTING")

            SettingsToggleRow(
                icon = Icons.Outlined.Apps,
                title = "Per-app routing",
                subtitle = when {
                    !settings.perAppProxyEnabled -> "All apps use VPN — turn on to exclude apps"
                    settings.bypassSelectedApps -> "${settings.selectedAppPackages.size} app(s) skip VPN"
                    else -> "Only ${settings.selectedAppPackages.size} app(s) use VPN"
                },
                checked = settings.perAppProxyEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings {
                        it.copy(
                            perAppProxyEnabled = enabled,
                            bypassSelectedApps = if (enabled && !it.perAppProxyEnabled) true else it.bypassSelectedApps
                        )
                    }
                },
                testTag = "switch_per_app_proxy"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Apps,
                title = if (!settings.perAppProxyEnabled || settings.bypassSelectedApps) {
                    "Choose apps to skip VPN"
                } else {
                    "Choose apps to use VPN"
                },
                subtitle = if (!settings.perAppProxyEnabled || settings.bypassSelectedApps) {
                    "Selected apps use normal internet (e.g. banking apps)"
                } else {
                    "All other apps use normal internet"
                },
                valueText = if (settings.selectedAppPackages.isEmpty()) {
                    "None"
                } else {
                    "${settings.selectedAppPackages.size} apps"
                },
                onClick = { showPerAppPicker = true },
                testTag = "row_per_app_selection"
            )

            if (settings.perAppProxyEnabled) {
                HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                PerAppInlineModeRow(
                    bypassSelectedApps = settings.bypassSelectedApps,
                    onSelectBypass = { bypass ->
                        onUpdateSettings { it.copy(bypassSelectedApps = bypass) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 3. CONNECTION (Startup -> Protocol -> Failover -> Security)
            SettingsSectionHeader(title = "CONNECTION")

            SettingsToggleRow(
                icon = Icons.Outlined.PlayArrow,
                title = "Start on app open",
                subtitle = "Auto-connect when launching Hollow",
                checked = settings.startOnAppOpen,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(startOnAppOpen = enabled) }
                },
                testTag = "switch_start_on_open"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Rounded.AccountTree,
                title = "Protocol",
                subtitle = "Preferred transport & stealth core",
                valueText = formatConsumerProtocol(settings.protocolPreference),
                onClick = { activeDialog = ActiveSettingDialog.PROTOCOL },
                testTag = "row_protocol"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Refresh,
                title = "Auto-switch slow servers",
                subtitle = "Switch to a faster server when throughput stalls",
                checked = settings.smartSwitchEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(smartSwitchEnabled = enabled) }
                },
                testTag = "switch_smart_switch"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Lock,
                title = "Strict TLS verification",
                subtitle = "Reject insecure or unencrypted server configs",
                checked = settings.strictTlsEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(strictTlsEnabled = enabled) }
                },
                testTag = "switch_strict_tls"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 4. ROUTING (Traffic scope, DNS & IPv6)
            SettingsSectionHeader(title = "ROUTING")

            SettingsValueNavigationRow(
                icon = Icons.Rounded.Route,
                title = "Routing mode",
                subtitle = "Control whether local LAN devices bypass VPN",
                valueText = formatRoutingSummary(settings.routingMode),
                onClick = { activeDialog = ActiveSettingDialog.ROUTING_MODE },
                testTag = "row_routing_mode"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Dns,
                title = "Private DNS",
                subtitle = "Encrypted domain lookups",
                valueText = formatDnsSummary(settings.dnsMode),
                onClick = { activeDialog = ActiveSettingDialog.DNS_PROVIDER },
                testTag = "row_dns"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Language,
                title = "IPv6 routing",
                subtitle = "Enable dual-stack IPv6 traffic through tunnel",
                checked = settings.ipv6Enabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(ipv6Enabled = enabled) }
                },
                testTag = "switch_ipv6"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 5. SYSTEM & PRIVACY
            SettingsSectionHeader(title = "SYSTEM & PRIVACY")

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Lock,
                title = "Always-on VPN & kill switch",
                subtitle = "Configure system-level leak protection",
                valueText = "System",
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                },
                testTag = "row_system_vpn_settings"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.VisibilityOff,
                title = "Hide from recent apps",
                subtitle = "Mask Hollow preview in Android's app switcher",
                checked = settings.hideFromRecentApps,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(hideFromRecentApps = enabled) }
                },
                testTag = "switch_hide_recents"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 6. DIAGNOSTICS & BACKUP
            SettingsSectionHeader(title = "DIAGNOSTICS & BACKUP")

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Info,
                title = "Setup & permissions guide",
                subtitle = "Verify VPN, kill switch, notifications & battery",
                valueText = null,
                onClick = onOpenGettingStarted,
                testTag = "row_getting_started"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Terminal,
                title = "Xray diagnostic logs",
                subtitle = "Inspect live tunnel core status & errors",
                valueText = if (xrayLogs.isEmpty()) "Empty" else "${xrayLogs.size} lines",
                onClick = { activeDialog = ActiveSettingDialog.XRAY_LOGS },
                testTag = "row_xray_logs"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.FileUpload,
                title = "Export server backup",
                subtitle = "Copy all saved server configs to clipboard",
                valueText = "$serverCount",
                onClick = onExportServers,
                testTag = "row_export_servers"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 7. UPDATES & ABOUT
            SettingsSectionHeader(title = "ABOUT")

            val updateSubtitle = when (appUpdateState) {
                AppUpdateState.Idle -> "Check GitHub Releases for newer builds"
                AppUpdateState.Checking -> "Checking for latest release…"
                is AppUpdateState.UpToDate -> "Up to date · ${appUpdateState.latestVersion}"
                is AppUpdateState.UpdateAvailable -> "New version ${appUpdateState.latestVersion} available"
                is AppUpdateState.Error -> "Could not check — tap to retry"
            }
            val updateBadge = when (appUpdateState) {
                AppUpdateState.Idle -> "Check"
                AppUpdateState.Checking -> "Checking"
                is AppUpdateState.UpToDate -> "Latest"
                is AppUpdateState.UpdateAvailable -> appUpdateState.latestVersion
                is AppUpdateState.Error -> "Retry"
            }

            SettingsValueNavigationRow(
                icon = Icons.Outlined.SystemUpdate,
                title = "Check for updates",
                subtitle = updateSubtitle,
                valueText = updateBadge,
                onClick = {
                    activeDialog = ActiveSettingDialog.CHECK_UPDATE
                    onCheckForUpdates()
                },
                testTag = "row_check_updates"
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(role = Role.Button) { activeDialog = ActiveSettingDialog.ABOUT }
                    .testTag("card_about_hollow"),
                shape = RoundedCornerShape(22.dp),
                color = HollowInk
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val center = Offset(size.width * 0.88f, size.height * 0.24f)
                        drawCircle(
                            color = HollowOrange.copy(alpha = 0.14f),
                            radius = 58.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawCircle(
                            color = HollowLime.copy(alpha = 0.08f),
                            radius = 92.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(Color(0xFF23221E)),
                                contentAlignment = Alignment.Center
                            ) {
                                HollowBrandShieldLogo(
                                    strokeColor = HollowCream,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hollow VPN",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    color = HollowCream
                                )
                                Text(
                                    text = "Private Xray Multi-Protocol Tunnel",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = Color(0xFFA7A298),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = HollowOrange
                            ) {
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(thickness = 1.dp, color = Color(0xFF2C2B27))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AboutCardStat(label = "ENGINE", value = "Xray Core")
                            AboutCardStat(label = "BUILD", value = "#${BuildConfig.VERSION_CODE}")
                            AboutCardStat(label = "PROTOCOLS", value = "Reality · HY2")
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "Open About Hollow",
                                tint = Color(0xFFA7A298),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp + bottomContentPadding))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }

    when (activeDialog) {
        ActiveSettingDialog.UPDATE_INTERVAL -> {
            SettingOptionPickerDialog(
                title = "Update interval",
                options = UPDATE_INTERVAL_OPTIONS,
                selectedValue = settings.updateIntervalLabel,
                onSelect = { chosen ->
                    onUpdateSettings {
                        it.copy(updateIntervalLabel = chosen, subUpdateInterval = chosen)
                    }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.PROTOCOL -> {
            SettingOptionPickerDialog(
                title = "Protocol",
                options = PROTOCOL_OPTIONS,
                selectedValue = settings.protocolPreference,
                onSelect = { chosen ->
                    onUpdateSettings { it.copy(protocolPreference = chosen) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.ROUTING_MODE -> {
            SettingOptionPickerDialog(
                title = "Routing mode",
                options = ROUTING_MODE_OPTIONS,
                selectedValue = settings.routingMode,
                onSelect = { chosen ->
                    onUpdateSettings { it.copy(routingMode = chosen) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.DNS_PROVIDER -> {
            SettingOptionPickerDialog(
                title = "Private DNS",
                options = DNS_PROVIDER_OPTIONS,
                selectedValue = settings.dnsMode,
                onSelect = { chosen ->
                    onUpdateSettings { it.copy(dnsMode = chosen) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.XRAY_LOGS -> {
            XrayDiagnosticLogsDialog(
                logs = xrayLogs,
                onCopyLogs = {
                    val joined = xrayLogs.joinToString("\n")
                    if (joined.isNotBlank()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Hollow VPN Connection Logs", joined))
                        onShowMessage("Copied ${xrayLogs.size} log line(s)")
                    }
                },
                onClearLogs = onClearXrayLogs,
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.ABOUT -> {
            AboutHollowSheet(
                onCopyBuildInfo = onShowMessage,
                onCheckUpdates = {
                    activeDialog = ActiveSettingDialog.CHECK_UPDATE
                    onCheckForUpdates()
                },
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.CHECK_UPDATE -> {
            AppUpdateCheckDialog(
                updateState = appUpdateState,
                onRetry = onCheckForUpdates,
                onDismiss = { activeDialog = null }
            )
        }

        null -> Unit
    }

    if (showPerAppPicker) {
        PerAppProxyPickerDialog(
            selectedPackages = settings.selectedAppPackages,
            bypassSelectedApps = !settings.perAppProxyEnabled || settings.bypassSelectedApps,
            onSave = { packages, bypassMode ->
                onShowMessage(
                    if (packages.isEmpty()) "Per-app routing disabled (all apps use VPN)"
                    else "Per-app routing saved for ${packages.size} app(s)"
                )
                onUpdateSettings {
                    it.copy(
                        perAppProxyEnabled = packages.isNotEmpty(),
                        bypassSelectedApps = bypassMode,
                        selectedAppPackages = packages
                    )
                }
                showPerAppPicker = false
            },
            onDismiss = { showPerAppPicker = false }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        color = HollowOrange,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
internal fun SettingsGroupCard(
    sectionTitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSectionHeader(title = sectionTitle.uppercase())
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 15.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HollowInkSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = HollowInk
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = HollowInkSecondary
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        HollowSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingsValueNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    valueText: String?,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HollowInkSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = HollowInk
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = HollowInkSecondary
                )
            }
        }
        if (!valueText.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = HollowCreamSurface
            ) {
                Text(
                    text = valueText,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.5.sp,
                    color = HollowInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = HollowInkSecondary,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun PerAppInlineModeRow(
    bypassSelectedApps: Boolean,
    onSelectBypass: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp)
            .testTag("switch_bypass_selected_apps")
    ) {
        Text(
            text = "App routing mode",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            ),
            color = HollowInk
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = HollowCreamSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                InlineModeSegment(
                    label = "Skip VPN",
                    selected = bypassSelectedApps,
                    onClick = { onSelectBypass(true) },
                    modifier = Modifier.weight(1f)
                )
                InlineModeSegment(
                    label = "VPN only",
                    selected = !bypassSelectedApps,
                    onClick = { onSelectBypass(false) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InlineModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) HollowInk else Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = if (selected) HollowCream else HollowInkSecondary
            )
        }
    }
}

@Composable
private fun AboutCardStat(
    label: String,
    value: String
) {
    Column {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 9.5.sp,
            letterSpacing = 1.sp,
            color = Color(0xFF9C978E)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            color = HollowCream
        )
    }
}
