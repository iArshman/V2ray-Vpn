package com.hollow.vpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.ui.components.HollowSwitch
import com.hollow.vpn.ui.components.HollowTopSquareButton
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

data class SettingChoiceOption(
    val value: String,
    val subtitle: String,
    val label: String = value
)

private enum class ActiveSettingDialog {
    UPDATE_INTERVAL,
    PROTOCOL,
    ROUTING_MODE,
    DNS_PROVIDER,
    XRAY_LOGS,
    CLEAR_CACHE_CONFIRM
}

internal val UPDATE_INTERVAL_OPTIONS = listOf(
    SettingChoiceOption("15 min", "High-frequency auto-refresh"),
    SettingChoiceOption("30 min", "Balanced refresh interval"),
    SettingChoiceOption("1 hour", "Recommended standard interval"),
    SettingChoiceOption("6 hours", "Battery-saving interval"),
    SettingChoiceOption("12 hours", "Twice-daily subscription refresh"),
    SettingChoiceOption("24 hours", "Daily subscription refresh")
)

private val PROTOCOL_OPTIONS = listOf(
    SettingChoiceOption("Auto (V2Ray)", "Automatically select the fastest available route", "Auto (Recommended)"),
    SettingChoiceOption("VLESS (Reality)", "Reality TLS stealth camouflage with ultra-low latency", "VLESS · Reality"),
    SettingChoiceOption("VLESS (WS / TCP)", "Lightweight stateless stream transport", "VLESS · WS / TCP"),
    SettingChoiceOption("VMess (WebSocket)", "Encrypted WebSocket tunnel", "VMess · WebSocket"),
    SettingChoiceOption("Trojan (TLS)", "HTTPS-disguised TLS tunnel", "Trojan · TLS"),
    SettingChoiceOption("Hysteria2", "QUIC-based high-throughput stealth tunnel", "Hysteria2"),
    SettingChoiceOption("gRPC", "Multiplexed HTTP/2 stream transport", "gRPC"),
    SettingChoiceOption("Shadowsocks", "Fast stream cipher proxy", "Shadowsocks")
)

private val ROUTING_MODE_OPTIONS = listOf(
    SettingChoiceOption("Global", "Route all device traffic through the VPN tunnel"),
    SettingChoiceOption("Bypass LAN", "Tunnel internet traffic while keeping local LAN (192.168.x.x) direct"),
    SettingChoiceOption("Bypass LAN & Domestic", "Keep local & domestic subnets direct; tunnel international traffic"),
    SettingChoiceOption("Direct Only", "Diagnostic mode with direct connection")
)

private val DNS_PROVIDER_OPTIONS = listOf(
    SettingChoiceOption("Auto (DoH)", "Encrypted DNS-over-HTTPS (1.1.1.1 / 1.0.0.1)"),
    SettingChoiceOption("Cloudflare 1.1.1.1", "Fast privacy-first DNS resolver (1.1.1.1)"),
    SettingChoiceOption("Google 8.8.8.8", "Reliable global DNS resolver (8.8.8.8)"),
    SettingChoiceOption("Quad9 9.9.9.9", "Security & malware-blocking DNS (9.9.9.9)"),
    SettingChoiceOption("AdGuard 94.140.14.14", "Ad-blocking and tracker-filtering DNS (94.140.14.14)")
)

/**
 * PAGE 6: Hollow Editorial Settings Screen
 */
@Composable
fun SettingsScreen(
    settings: AppSettingsState,
    xrayLogs: List<String> = emptyList(),
    serverCount: Int = 0,
    onUpdateSettings: ((AppSettingsState) -> AppSettingsState) -> Unit,
    onOpenAddSubscription: () -> Unit,
    onOpenGettingStarted: () -> Unit = {},
    onClearXrayLogs: () -> Unit = {},
    onExportServers: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onClearCache: () -> Unit,
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
                text = "Tunnel, routing & privacy",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = HollowInkSecondary
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Section 1: CONFIGURATION
            SettingsSectionHeader(title = "CONFIGURATION")

            SettingsToggleRow(
                icon = Icons.Outlined.CloudQueue,
                title = "Auto configure",
                subtitle = "Fetch and optimize servers automatically",
                checked = settings.autoConfigure,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(autoConfigure = enabled) }
                },
                testTag = "switch_auto_configure"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Refresh,
                title = "Update interval",
                subtitle = "Refresh server list automatically",
                valueText = settings.updateIntervalLabel,
                onClick = { activeDialog = ActiveSettingDialog.UPDATE_INTERVAL },
                testTag = "row_update_interval"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Link,
                title = "Custom subscription",
                subtitle = "Add your server subscription link",
                valueText = null,
                onClick = onOpenAddSubscription,
                testTag = "row_custom_subscription"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Section 2: CONNECTION
            SettingsSectionHeader(title = "CONNECTION")

            SettingsValueNavigationRow(
                icon = Icons.Rounded.AccountTree,
                title = "Protocol",
                subtitle = null,
                valueText = formatConsumerProtocol(settings.protocolPreference),
                onClick = { activeDialog = ActiveSettingDialog.PROTOCOL },
                testTag = "row_protocol"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Rounded.Route,
                title = "Routing mode",
                subtitle = null,
                valueText = settings.routingMode,
                onClick = { activeDialog = ActiveSettingDialog.ROUTING_MODE },
                testTag = "row_routing_mode"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Shield,
                title = "Per-app proxy",
                subtitle = "Proxy only selected apps, or bypass them",
                checked = settings.perAppProxyEnabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(perAppProxyEnabled = enabled) }
                },
                testTag = "switch_per_app_proxy"
            )
            if (settings.perAppProxyEnabled) {
                HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                SettingsValueNavigationRow(
                    icon = Icons.Outlined.Apps,
                    title = "Choose apps",
                    subtitle = "Select packages affected by the VPN",
                    valueText = "${settings.selectedAppPackages.size} selected",
                    onClick = { showPerAppPicker = true },
                    testTag = "row_per_app_selection"
                )
                HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                SettingsToggleRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = "Bypass selected apps",
                    subtitle = "Selected apps stay on the normal connection",
                    checked = settings.bypassSelectedApps,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(bypassSelectedApps = enabled) }
                    },
                    testTag = "switch_bypass_selected_apps"
                )
            }
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Dns,
                title = "DNS",
                subtitle = null,
                valueText = settings.dnsMode,
                onClick = { activeDialog = ActiveSettingDialog.DNS_PROVIDER },
                testTag = "row_dns"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Shield,
                title = "TUN mode",
                subtitle = "Route all apps through VPN",
                checked = settings.tunMode,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(tunMode = enabled) }
                },
                testTag = "switch_tun_mode"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.Language,
                title = "IPv6",
                subtitle = "Enable IPv6 support",
                checked = settings.ipv6Enabled,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(ipv6Enabled = enabled) }
                },
                testTag = "switch_ipv6"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Lock,
                title = "Always-on & kill switch",
                subtitle = "Block connections without VPN",
                valueText = "Android",
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

            Spacer(modifier = Modifier.height(28.dp))

            // Section 3: APP
            SettingsSectionHeader(title = "APP")

            SettingsToggleRow(
                icon = Icons.Outlined.PlayArrow,
                title = "Start on app open",
                subtitle = "Auto fetch servers and connect",
                checked = settings.startOnAppOpen,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(startOnAppOpen = enabled) }
                },
                testTag = "switch_start_on_open"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsToggleRow(
                icon = Icons.Outlined.VisibilityOff,
                title = "Hide from recent apps",
                subtitle = "Stay private in app switcher",
                checked = settings.hideFromRecentApps,
                onCheckedChange = { enabled ->
                    onUpdateSettings { it.copy(hideFromRecentApps = enabled) }
                },
                testTag = "switch_hide_recents"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Terminal,
                title = "Diagnostic logs",
                subtitle = "View live Xray core status & events",
                valueText = if (xrayLogs.isEmpty()) "Empty" else "${xrayLogs.size}",
                onClick = { activeDialog = ActiveSettingDialog.XRAY_LOGS },
                testTag = "row_xray_logs"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.FileUpload,
                title = "Export servers",
                subtitle = "Copy all saved configs to clipboard",
                valueText = "$serverCount",
                onClick = onExportServers,
                testTag = "row_export_servers"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.Info,
                title = "Getting started",
                subtitle = "Review setup & permissions guide",
                valueText = null,
                onClick = onOpenGettingStarted,
                testTag = "row_getting_started"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            SettingsValueNavigationRow(
                icon = Icons.Outlined.DeleteOutline,
                title = "Clear cache",
                subtitle = "Remove cached servers",
                valueText = null,
                isDestructive = true,
                onClick = { activeDialog = ActiveSettingDialog.CLEAR_CACHE_CONFIRM },
                testTag = "row_clear_cache"
            )

            Spacer(modifier = Modifier.height(32.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }

    when (activeDialog) {
        ActiveSettingDialog.UPDATE_INTERVAL -> {
            SettingOptionPickerDialog(
                title = "Select Update Interval",
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
                title = "Connection Protocol",
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
                title = "Select Routing Mode",
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
                title = "Select DNS Provider",
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
                        cm?.setPrimaryClip(ClipData.newPlainText("Hollow VPN Xray Logs", joined))
                        onShowMessage("Copied ${xrayLogs.size} log line(s)")
                    }
                },
                onClearLogs = onClearXrayLogs,
                onDismiss = { activeDialog = null }
            )
        }

        ActiveSettingDialog.CLEAR_CACHE_CONFIRM -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = HollowCream,
                shape = RoundedCornerShape(24.dp),
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = HollowOrange
                    )
                },
                title = {
                    Text(
                        text = "Clear cached servers?",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        color = HollowInk
                    )
                },
                text = {
                    Text(
                        text = "This removes all currently cached servers from your device. You can re-fetch fresh nodes anytime from your subscription link.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HollowInkSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = null
                            onClearCache()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HollowOrange,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Clear cache")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text("Cancel", color = HollowInk)
                    }
                }
            )
        }

        null -> Unit
    }

    if (showPerAppPicker) {
        PerAppProxyPickerDialog(
            selectedPackages = settings.selectedAppPackages,
            onSave = { packages ->
                onUpdateSettings { it.copy(selectedAppPackages = packages) }
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
    isDestructive: Boolean = false,
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
            tint = if (isDestructive) HollowOrange else HollowInkSecondary,
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
                color = if (isDestructive) HollowOrange else HollowInk
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
private fun XrayDiagnosticLogsDialog(
    logs: List<String>,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HollowCream,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = HollowInk
            )
        },
        title = {
            Text(
                text = "Diagnostic logs",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                color = HollowInk
            )
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                shape = RoundedCornerShape(16.dp),
                color = HollowInk
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No diagnostic events captured yet.\nConnect to a server to inspect live Xray core status and tunnel verification logs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9E998E)
                        )
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                style = TextStyle(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = if (line.contains("ERROR:", ignoreCase = true)) {
                                        HollowOrange
                                    } else {
                                        HollowCream
                                    }
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (logs.isNotEmpty()) {
                    TextButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("btn_clear_xray_logs")
                    ) {
                        Text("Clear", color = HollowOrange)
                    }
                    Button(
                        onClick = onCopyLogs,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HollowInk,
                            contentColor = HollowCream
                        ),
                        modifier = Modifier.testTag("btn_copy_xray_logs")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Copy")
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = HollowInk)
                    }
                }
            }
        },
        dismissButton = {
            if (logs.isNotEmpty()) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = HollowInk)
                }
            }
        }
    )
}

private data class ProxyAppChoice(val packageName: String, val label: String)

@Composable
private fun PerAppProxyPickerDialog(
    selectedPackages: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val apps = remember(context) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { ProxyAppChoice(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
    var searchQuery by remember { mutableStateOf("") }
    var workingSelection by remember(selectedPackages) { mutableStateOf(selectedPackages) }
    val filteredApps = remember(apps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HollowCream,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "Choose apps",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                color = HollowInk
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search installed apps") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${workingSelection.size} selected",
                        color = HollowInkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (workingSelection.isNotEmpty()) {
                        TextButton(onClick = { workingSelection = emptySet() }) {
                            Text("Clear", color = HollowOrange)
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    filteredApps.forEach { app ->
                        val checked = app.packageName in workingSelection
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    workingSelection = if (checked) workingSelection - app.packageName
                                    else workingSelection + app.packageName
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (checked) HollowCreamSurface else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        workingSelection = if (value) workingSelection + app.packageName
                                        else workingSelection - app.packageName
                                    }
                                )
                                Column(modifier = Modifier.padding(start = 6.dp)) {
                                    Text(
                                        text = app.label,
                                        color = HollowInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        color = HollowInkSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(workingSelection) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HollowInk)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingOptionPickerDialog(
    title: String,
    options: List<SettingChoiceOption>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HollowCream,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = title,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = HollowInk
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option.value == selectedValue
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { onSelect(option.value) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) HollowInk else HollowCreamSurface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelect(option.value) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = HollowOrange,
                                    unselectedColor = HollowInkSecondary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSelected) HollowCream else HollowInk
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) {
                                        HollowCream.copy(alpha = 0.78f)
                                    } else {
                                        HollowInkSecondary
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowCreamSurface,
                    contentColor = HollowInk
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Close")
            }
        }
    }
}

@Composable
fun RawXrayJsonEditorDialog(
    server: ServerEntity,
    onDismiss: () -> Unit,
    onSaveJson: (String) -> Unit
) {
    var jsonText by remember(server) { mutableStateOf(server.rawConfigJson) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HollowCream,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "${server.countryName} · Raw Config",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                color = HollowInk
            )
        },
        text = {
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                textStyle = TextStyle(
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 12.sp,
                    color = HollowInk
                ),
                shape = RoundedCornerShape(14.dp),
                minLines = 10,
                maxLines = 15,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onSaveJson(jsonText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                )
            ) {
                Text("Save Config")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HollowInk)
            }
        }
    )
}
