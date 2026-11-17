package com.hollow.vpn.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hollow.vpn.R
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.ui.ConnectionTelemetryState
import com.hollow.vpn.ui.components.FetchingSpinnerRing
import com.hollow.vpn.ui.components.HollowBlinkingDot
import com.hollow.vpn.ui.components.HollowBrandShieldLogo
import com.hollow.vpn.ui.components.HollowDisconnectedExposureCard
import com.hollow.vpn.ui.components.HollowIndeterminateBar
import com.hollow.vpn.ui.components.HollowRouteCard
import com.hollow.vpn.ui.components.HollowRouteState
import com.hollow.vpn.ui.components.HollowSlideActionPill
import com.hollow.vpn.ui.components.HollowSpeedBarsCard
import com.hollow.vpn.ui.components.HollowStatusBrandRow
import com.hollow.vpn.ui.components.HollowTopSquareButton
import com.hollow.vpn.ui.components.countryCodeToFlagEmoji
import com.hollow.vpn.ui.theme.HollowAmber
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowForest
import com.hollow.vpn.ui.theme.HollowForestCard
import com.hollow.vpn.ui.theme.HollowForestDivider
import com.hollow.vpn.ui.theme.HollowForestMuted
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowLime
import com.hollow.vpn.ui.theme.HollowObsidian
import com.hollow.vpn.ui.theme.HollowObsidianDivider
import com.hollow.vpn.ui.theme.HollowObsidianMuted
import com.hollow.vpn.ui.theme.HollowObsidianSurface
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily
import com.hollow.vpn.ui.theme.TabularMonoBody
import java.util.Locale
import kotlin.math.max

/**
 * Formats internal protocol identifiers cleanly for consumer UI without technical clutter.
 */
internal fun formatConsumerProtocol(raw: String, transport: String? = null): String {
    val cleaned = raw
        .replace("(V2Ray)", "", ignoreCase = true)
        .replace("(Xray)", "", ignoreCase = true)
        .replace("V2Ray · ", "", ignoreCase = true)
        .replace("Xray ", "", ignoreCase = true)
        .trim()
        .ifBlank { "Auto" }

    val cleanTransport = transport?.trim().orEmpty()
    return if (cleanTransport.isNotBlank() && !cleaned.contains(cleanTransport, ignoreCase = true)) {
        "$cleaned · $cleanTransport"
    } else {
        cleaned
    }
}

private fun formatShortProtocolLabel(raw: String): String {
    return raw
        .replace("(V2Ray)", "", ignoreCase = true)
        .replace("(Xray)", "", ignoreCase = true)
        .replace("V2Ray · ", "", ignoreCase = true)
        .replace("Xray ", "", ignoreCase = true)
        .trim()
        .ifBlank { "Auto" }
}

private fun splitSpeedValueAndUnit(bytesPerSec: Long): Pair<String, String> {
    val safe = max(0L, bytesPerSec)
    return when {
        safe < 1024L -> "$safe" to "B/s"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f", safe / 1024.0) to "KB/s"
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f", safe / (1024.0 * 1024.0)) to "MB/s"
        else -> String.format(Locale.US, "%.2f", safe / (1024.0 * 1024.0 * 1024.0)) to "GB/s"
    }
}

private fun formatCompactTotalBytes(bytes: Long): String {
    val safe = max(0L, bytes)
    return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * SCREEN 1: Hollow Startup Auto-Fetch & Ping Ranker Splash Screen
 */
@Composable
fun SplashFetchingScreen(
    statusSubtitle: String,
    onCancelOrSkip: () -> Unit
) {
    BackHandler { onCancelOrSkip() }
    val displaySubtitle = statusSubtitle
        .replace("V2Ray / Xray ", "", ignoreCase = true)
        .replace("V2Ray/Xray ", "", ignoreCase = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HollowCream)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .align(Alignment.Center)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            HollowBrandShieldLogo(size = 86.dp, cornerRadius = 24.dp)

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = stringResource(R.string.app_name),
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                color = HollowInk
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.tagline_fast_private),
                style = MaterialTheme.typography.bodyLarge,
                color = HollowInkSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(36.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = HollowCreamSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FetchingSpinnerRing(size = 60.dp)

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.fetching_servers_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = HollowInk
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HollowInkSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            TextButton(
                onClick = onCancelOrSkip,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(
                    text = "Browse Servers",
                    color = HollowInk,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * PAGES 1, 2, 3: Hollow Home / Connect Dashboard (Disconnected, Connecting, Protected)
 */
@Composable
fun ConnectedHomeScreen(
    selectedServer: ServerEntity?,
    telemetry: ConnectionTelemetryState,
    tunModeEnabled: Boolean = true,
    bottomContentPadding: Dp = 0.dp,
    onToggleConnect: () -> Unit,
    onOpenServerList: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAddServer: () -> Unit,
    onRefreshRealIp: () -> Unit = {}
) {
    val homePhase = when {
        telemetry.isConnecting -> 1
        telemetry.isConnected -> 2
        else -> 0
    }

    val backgroundColor by animateColorAsState(
        targetValue = when (homePhase) {
            1 -> HollowObsidian
            2 -> HollowForest
            else -> HollowCream
        },
        animationSpec = tween(durationMillis = 280),
        label = "home_bg_color"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .padding(bottom = bottomContentPadding)
    ) {
        Crossfade(
            targetState = homePhase,
            animationSpec = tween(durationMillis = 240),
            label = "home_phase_crossfade"
        ) { phase ->
            when (phase) {
                1 -> ConnectingHomeContent(
                    selectedServer = selectedServer,
                    telemetry = telemetry,
                    onCancel = onToggleConnect,
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings
                )
                2 -> ProtectedHomeContent(
                    selectedServer = selectedServer,
                    telemetry = telemetry,
                    onDisconnect = onToggleConnect,
                    onOpenServerList = onOpenServerList,
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings
                )
                else -> DisconnectedHomeContent(
                    selectedServer = selectedServer,
                    telemetry = telemetry,
                    tunModeEnabled = tunModeEnabled,
                    onConnect = onToggleConnect,
                    onOpenServerList = onOpenServerList,
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings,
                    onRefreshRealIp = onRefreshRealIp
                )
            }
        }
    }
}

@Composable
private fun DisconnectedHomeContent(
    selectedServer: ServerEntity?,
    telemetry: ConnectionTelemetryState,
    tunModeEnabled: Boolean,
    onConnect: () -> Unit,
    onOpenServerList: () -> Unit,
    onOpenAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshRealIp: () -> Unit
) {
    val cleanIpDisplay = remember(telemetry.realPublicIpLabel) {
        telemetry.realPublicIpLabel.substringBefore(" · ").ifBlank { telemetry.realPublicIpLabel }
    }
    val originLocationLabel = remember(telemetry.realPublicIpLabel, telemetry.realPublicCountryCode) {
        val afterDot = telemetry.realPublicIpLabel.substringAfter(" · ", "").trim()
        when {
            afterDot.isNotBlank() -> afterDot
            telemetry.realPublicCountryCode.length == 2 &&
                !telemetry.realPublicCountryCode.equals("XX", ignoreCase = true) -> telemetry.realPublicCountryCode.uppercase()
            else -> "Current ISP"
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 600.dp)
            .padding(horizontal = 24.dp)
    ) {
        val screenMaxHeight = maxHeight
        val isCompact = screenMaxHeight < 620.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                HomeTopBar(
                    dotColor = HollowOrange,
                    textColor = HollowInk,
                    buttonContainer = HollowCreamSurface,
                    buttonContent = HollowInk,
                    buttonBorder = null,
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings
                )

                Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HollowBlinkingDot(color = HollowOrange)
                    Text(
                        text = "NOT PROTECTED",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.6.sp,
                        color = HollowOrange
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Your IP is visible.",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompact) 30.sp else 34.sp,
                    lineHeight = if (isCompact) 35.sp else 39.sp,
                    letterSpacing = (-0.8).sp,
                    color = HollowInk,
                    modifier = Modifier.testTag("connection_status_text")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onRefreshRealIp)
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                        .testTag("row_real_ip"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = cleanIpDisplay,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 15.5.sp,
                        color = HollowInkSecondary
                    )
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh public IP",
                        tint = HollowInkSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))

                HollowDisconnectedExposureCard(
                    originCountryCode = telemetry.realPublicCountryCode,
                    originLocationLabel = originLocationLabel,
                    targetCountryCode = selectedServer?.countryCode,
                    targetCountryName = selectedServer?.countryName,
                    tunModeEnabled = tunModeEnabled,
                    ispLabel = telemetry.realIspLabel,
                    mapHeight = (screenMaxHeight - 540.dp).coerceIn(96.dp, 200.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                // Quick Server / Add a Server Card (Page 1)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button) {
                            if (selectedServer != null) onOpenServerList() else onOpenAddServer()
                        }
                        .testTag("active_server_pill"),
                    shape = RoundedCornerShape(20.dp),
                    color = HollowCreamSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(HollowInk),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedServer != null) {
                                Text(
                                    text = countryCodeToFlagEmoji(selectedServer.countryCode),
                                    fontSize = 22.sp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = HollowCream,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedServer?.countryName ?: "Add a server",
                                fontFamily = SpaceGroteskFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = HollowInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val subText = if (selectedServer != null) {
                                val host = selectedServer.resolvedIp.ifBlank { selectedServer.host }
                                "$host:${selectedServer.port}"
                            } else {
                                "Link, config or QR code"
                            }
                            Text(
                                text = subText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = HollowInkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (selectedServer != null) {
                            val pingColor = when {
                                selectedServer.pingMs in 1..150 -> Color(0xFF15803D)
                                selectedServer.pingMs in 151..450 -> HollowInk
                                selectedServer.pingMs > 450 -> HollowOrange
                                else -> HollowInkSecondary
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (selectedServer.pingMs > 0) "${selectedServer.pingMs} ms" else "—",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp,
                                color = pingColor,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        Icon(
                            imageVector = if (selectedServer != null) {
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight
                            } else {
                                Icons.Rounded.Add
                            },
                            contentDescription = if (selectedServer != null) "Choose server" else "Add server",
                            tint = HollowInkSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                HollowSlideActionPill(
                    label = "Slide to connect",
                    trackColor = HollowInk,
                    thumbColor = HollowOrange,
                    arrowColor = Color.White,
                    labelColor = HollowCream,
                    onSlideComplete = onConnect,
                    modifier = Modifier.testTag("bottom_disconnect_connect_button")
                )
            }
        }
    }
}

@Composable
private fun ConnectingHomeContent(
    selectedServer: ServerEntity?,
    telemetry: ConnectionTelemetryState,
    onCancel: () -> Unit,
    onOpenAddServer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeStep = telemetry.connectingStage.coerceIn(1, 3)

    val countryName = selectedServer?.countryName ?: "Germany"
    val protocolShort = selectedServer?.let { formatShortProtocolLabel(it.protocolCore) } ?: "Xray"
    val transportShort = selectedServer?.transport?.ifBlank { "TCP" } ?: "TCP"
    val pingText = selectedServer?.let { if (it.pingMs > 0) "${it.pingMs} ms" else "—" } ?: "—"
    val serverAddr = selectedServer?.let {
        it.resolvedIp.ifBlank { it.host }
    } ?: telemetry.serverIpLabel

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 600.dp)
            .padding(horizontal = 24.dp)
    ) {
        val isCompact = maxHeight < 620.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                HomeTopBar(
                    dotColor = HollowAmber,
                    textColor = HollowCream,
                    buttonContainer = HollowObsidianSurface,
                    buttonContent = HollowCream,
                    buttonBorder = Color(0xFF2B2A27),
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings
                )

                Spacer(modifier = Modifier.height(if (isCompact) 20.dp else 32.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HollowBlinkingDot(color = HollowAmber)
                    Text(
                        text = "CONNECTING",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.6.sp,
                        color = HollowAmber
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Building a\nsecure route…",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (isCompact) 31.sp else 36.sp,
                    lineHeight = if (isCompact) 36.sp else 42.sp,
                    letterSpacing = (-0.8).sp,
                    color = HollowCream,
                    modifier = Modifier.testTag("connection_status_text")
                )

                Spacer(modifier = Modifier.height(14.dp))

                HollowIndeterminateBar(
                    color = HollowAmber,
                    track = HollowObsidianDivider
                )

                Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 24.dp))

                // Step 1: Server reached
                ConnectingStepRow(
                    stepNumber = 1,
                    isCompleted = activeStep > 1,
                    isActive = activeStep == 1,
                    label = "Server reached · $countryName"
                )
                HorizontalDivider(thickness = 1.dp, color = HollowObsidianDivider)

                // Step 2: Handshake
                ConnectingStepRow(
                    stepNumber = 2,
                    isCompleted = activeStep > 2,
                    isActive = activeStep == 2,
                    label = "Handshake · $protocolShort $transportShort"
                )
                HorizontalDivider(thickness = 1.dp, color = HollowObsidianDivider)

                // Step 3: Securing traffic
                ConnectingStepRow(
                    stepNumber = 3,
                    isCompleted = false,
                    isActive = activeStep >= 3,
                    label = "Securing your traffic"
                )
                HorizontalDivider(thickness = 1.dp, color = HollowObsidianDivider)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 16.dp)
            ) {
                ThreeColumnStatsGrid(
                    dividerColor = HollowObsidianDivider,
                    labelColor = HollowObsidianMuted,
                    valueColor = HollowCream,
                    col1Label = "PROTOCOL",
                    col1Value = protocolShort,
                    col2Label = "PING",
                    col2Value = pingText,
                    col3Label = "SERVER",
                    col3Value = serverAddr
                )

                Spacer(modifier = Modifier.height(18.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onCancel)
                        .testTag("bottom_disconnect_connect_button"),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.2.dp, Color(0xFF3A3834))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = HollowCream
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectingStepRow(
    stepNumber: Int,
    isCompleted: Boolean,
    isActive: Boolean,
    label: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(
                    when {
                        isCompleted -> Modifier.background(HollowAmber)
                        isActive -> Modifier.border(2.dp, HollowAmber, CircleShape)
                        else -> Modifier.border(2.dp, HollowObsidianDivider, CircleShape)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isCompleted -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = HollowObsidian,
                    modifier = Modifier.size(18.dp)
                )
                isActive -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = HollowAmber,
                    strokeWidth = 2.dp
                )
                else -> Text(
                    text = stepNumber.toString(),
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = HollowObsidianMuted
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isCompleted || isActive) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 15.5.sp
            ),
            color = if (isCompleted || isActive) HollowCream else HollowObsidianMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProtectedHomeContent(
    selectedServer: ServerEntity?,
    telemetry: ConnectionTelemetryState,
    onDisconnect: () -> Unit,
    onOpenServerList: () -> Unit,
    onOpenAddServer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val countryName = selectedServer?.countryName ?: "VPN"
    val serverIp = selectedServer?.resolvedIp?.ifBlank { selectedServer.host }
        ?: telemetry.serverIpLabel
    val realIpText = remember(telemetry.preConnectionIp, telemetry.realPublicIpLabel) {
        telemetry.preConnectionIp.ifBlank {
            telemetry.realPublicIpLabel.substringBefore(" · ").ifBlank { telemetry.realPublicIpLabel }
        }
    }
    val protocolShort = selectedServer?.let { formatShortProtocolLabel(it.protocolCore) } ?: "Xray"
    val pingText = selectedServer?.let { if (it.pingMs > 0) "${it.pingMs} ms" else "—" } ?: "—"
    val totalText = formatCompactTotalBytes(telemetry.uploadBytes + telemetry.downloadBytes)
    val (dlNum, dlUnit) = splitSpeedValueAndUnit(telemetry.downloadBytesPerSec)
    val (ulNum, ulUnit) = splitSpeedValueAndUnit(telemetry.uploadBytesPerSec)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 600.dp)
            .padding(horizontal = 24.dp)
    ) {
        val isCompact = maxHeight < 700.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                HomeTopBar(
                    dotColor = HollowLime,
                    textColor = HollowCream,
                    buttonContainer = HollowForestCard,
                    buttonContent = HollowCream,
                    buttonBorder = HollowForestDivider,
                    onOpenAddServer = onOpenAddServer,
                    onOpenSettings = onOpenSettings
                )

                Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HollowBlinkingDot(color = HollowLime)
                    Text(
                        text = "PROTECTED",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.6.sp,
                        color = HollowLime,
                        modifier = Modifier.testTag("connection_status_text")
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = telemetry.formattedTimer,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (isCompact) 40.sp else 48.sp,
                    letterSpacing = (-1).sp,
                    color = HollowCream,
                    modifier = Modifier.testTag("connection_timer_text")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button, onClick = onOpenServerList)
                        .testTag("active_server_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "via $countryName · $serverIp",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                        color = HollowForestMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = HollowForestCard,
                        border = BorderStroke(1.dp, HollowForestDivider)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 11.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Switch",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = HollowLime,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "Switch server",
                                tint = HollowLime,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 18.dp))

                HollowRouteCard(
                    state = HollowRouteState.SECURED,
                    serverLabel = countryName,
                    realIp = realIpText.ifBlank { null },
                    exitIp = serverIp.ifBlank { null }
                )

                Spacer(modifier = Modifier.height(12.dp))

                HollowSpeedBarsCard(
                    downloadValue = dlNum,
                    downloadUnit = dlUnit,
                    uploadValue = ulNum,
                    uploadUnit = ulUnit,
                    downloadBytesPerSec = telemetry.downloadBytesPerSec,
                    elapsedSeconds = telemetry.elapsedSeconds
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                ThreeColumnStatsGrid(
                    dividerColor = HollowForestDivider,
                    labelColor = HollowForestMuted,
                    valueColor = HollowCream,
                    col1Label = "PROTOCOL",
                    col1Value = protocolShort,
                    col2Label = "PING",
                    col2Value = pingText,
                    col3Label = "TOTAL",
                    col3Value = totalText
                )

                Spacer(modifier = Modifier.height(14.dp))

                HollowSlideActionPill(
                    label = "Slide to disconnect",
                    trackColor = HollowCream,
                    thumbColor = HollowLime,
                    arrowColor = HollowInk,
                    labelColor = HollowInk,
                    onSlideComplete = onDisconnect,
                    modifier = Modifier.testTag("bottom_disconnect_connect_button")
                )
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    dotColor: Color,
    textColor: Color,
    buttonContainer: Color,
    buttonContent: Color,
    buttonBorder: Color?,
    onOpenAddServer: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HollowStatusBrandRow(
            dotColor = dotColor,
            textColor = textColor
        )

        HollowTopSquareButton(
            containerColor = buttonContainer,
            contentColor = buttonContent,
            borderColor = buttonBorder,
            contentDescription = "Add Server",
            onClick = onOpenAddServer,
            modifier = Modifier.testTag("home_add_server_button")
        ) { tint ->
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun ThreeColumnStatsGrid(
    dividerColor: Color,
    labelColor: Color,
    valueColor: Color,
    col1Label: String,
    col1Value: String,
    col2Label: String,
    col2Value: String,
    col3Label: String,
    col3Value: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            StatGridCell(
                label = col1Label,
                value = col1Value,
                labelColor = labelColor,
                valueColor = valueColor,
                modifier = Modifier
                    .weight(1.35f)
                    .padding(end = 10.dp, top = 14.dp, bottom = 8.dp)
            )
            VerticalDivider(
                thickness = 1.dp,
                color = dividerColor,
                modifier = Modifier.fillMaxHeight()
            )
            StatGridCell(
                label = col2Label,
                value = col2Value,
                labelColor = labelColor,
                valueColor = valueColor,
                modifier = Modifier
                    .weight(0.8f)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .padding(top = 4.dp)
            )
            VerticalDivider(
                thickness = 1.dp,
                color = dividerColor,
                modifier = Modifier.fillMaxHeight()
            )
            StatGridCell(
                label = col3Label,
                value = col3Value,
                labelColor = labelColor,
                valueColor = valueColor,
                modifier = Modifier
                    .weight(1.0f)
                    .padding(start = 10.dp, top = 14.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun StatGridCell(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 1.1.sp,
            color = labelColor,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.5.sp,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Editorial server node row used inside expanded country sections on Page 4.
 */
@Composable
fun ServerItemRowContent(
    server: ServerEntity,
    showLightningBadge: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onInspectJson: () -> Unit,
    onDelete: () -> Unit,
    onCopyFeedback: (String) -> Unit = {},
    onShareQr: () -> Unit = {},
    isSelectionMode: Boolean = false,
    isCheckedForBatch: Boolean = false,
    onToggleBatchCheck: () -> Unit = {},
    showCountryBadge: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val cleanProtocol = remember(server.protocolCore, server.transport) {
        formatConsumerProtocol(server.protocolCore, server.transport)
    }

    val pingColor = when {
        server.pingMs in 1..150 -> Color(0xFF15803D)
        server.pingMs in 151..450 -> HollowInk
        else -> HollowOrange
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isCheckedForBatch,
                onCheckedChange = { onToggleBatchCheck() },
                colors = CheckboxDefaults.colors(
                    checkedColor = HollowInk,
                    checkmarkColor = HollowCream
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showCountryBadge) {
                    Text(
                        text = countryCodeToFlagEmoji(server.countryCode),
                        fontSize = 18.sp
                    )
                }
                Text(
                    text = if (showCountryBadge) {
                        server.countryName.ifBlank { cleanProtocol }
                    } else {
                        cleanProtocol
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    color = HollowInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (server.isSelected) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = HollowInk
                    ) {
                        Text(
                            text = "ACTIVE",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp,
                            color = HollowLime,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else if (showLightningBadge) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = HollowOrange.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "FAST",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp,
                            color = HollowOrange,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            val addrText = "${server.resolvedIp.ifBlank { server.host }}:${server.port}"
            Text(
                text = if (showCountryBadge) "$cleanProtocol · $addrText" else addrText,
                style = TabularMonoBody.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
                color = HollowInkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (server.pingMs > 0) "${server.pingMs} ms" else "Timeout",
                style = TabularMonoBody.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = pingColor
            )
            if (server.lastSpeedMbps > 0.0) {
                Text(
                    text = String.format(Locale.US, "%.1f MB/s", server.lastSpeedMbps),
                    style = TabularMonoBody.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                    color = HollowInkSecondary
                )
            }
        }

        if (!isSelectionMode) {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Server Options for ${server.countryName}",
                        tint = HollowInkSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = HollowInk
                ) {
                    DropdownMenuItem(
                        text = { Text("Connect Now", color = HollowCream) },
                        onClick = {
                            menuExpanded = false
                            onSelect()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            val pingSub = if (server.pingMs > 0) "${server.pingMs} ms" else "Timeout"
                            Text("Ping Test ($pingSub)", color = HollowCream)
                        },
                        onClick = {
                            menuExpanded = false
                            onPing()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Inspect Raw Config", color = HollowCream) },
                        onClick = {
                            menuExpanded = false
                            onInspectJson()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (server.shareUri.isNotBlank()) "Copy Share Link" else "Copy Raw Config",
                                color = HollowCream
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            val payload = server.shareUri.ifBlank { server.rawConfigJson }
                            if (payload.isNotBlank()) {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Hollow VPN Server", payload))
                                onCopyFeedback("Copied server config for ${server.countryName}")
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Show QR Code", color = HollowCream) },
                        onClick = {
                            menuExpanded = false
                            onShareQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Server", color = HollowOrange) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
