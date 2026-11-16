package com.hollow.vpn.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.ui.AddServerTab
import com.hollow.vpn.ui.components.CameraQrScannerDialog
import com.hollow.vpn.ui.components.HollowSwitch
import com.hollow.vpn.ui.components.HollowTopSquareButton
import com.hollow.vpn.ui.components.HollowUnderlinedTabRow
import com.hollow.vpn.ui.components.decodeQrFromUri
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkMuted
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

private val MANUAL_PROTOCOL_PRESETS = listOf("VLESS", "VMess", "Trojan", "Shadowsocks", "Hysteria2")
private val MANUAL_TRANSPORT_PRESETS = listOf("Reality", "WS", "gRPC", "TCP", "Hysteria2")

/**
 * PAGE 5: Hollow Add Server Screen (Subscription / Manual / QR code)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddServerScreen(
    activeTab: AddServerTab,
    settings: AppSettingsState,
    bottomContentPadding: Dp = 0.dp,
    onSelectTab: (AddServerTab) -> Unit,
    onAddAndFetchSubscription: (String) -> Unit,
    onImportClipboardText: (String) -> Unit,
    onAddManualServer: (
        countryName: String,
        host: String,
        port: Int,
        protocol: String,
        transport: String,
        uuid: String,
        sni: String,
        rawJson: String,
        publicKey: String,
        shortId: String,
        wsPath: String,
        wsHost: String,
        grpcServiceName: String
    ) -> Unit,
    onUpdateSettings: ((AppSettingsState) -> AppSettingsState) -> Unit,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    var subscriptionUrl by remember(settings.activeSubscriptionUrl) {
        mutableStateOf(settings.activeSubscriptionUrl)
    }
    var showIntervalPicker by remember { mutableStateOf(false) }

    var manualCountry by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("443") }
    var manualProtocol by remember { mutableStateOf("VLESS") }
    var manualTransport by remember { mutableStateOf("Reality") }
    var manualUuid by remember { mutableStateOf("") }
    var manualSni by remember { mutableStateOf("") }
    var manualPublicKey by remember { mutableStateOf("") }
    var manualShortId by remember { mutableStateOf("") }
    var manualWsPath by remember { mutableStateOf("") }
    var manualWsHost by remember { mutableStateOf("") }
    var manualGrpcServiceName by remember { mutableStateOf("") }
    var manualRawJson by remember { mutableStateOf("") }

    var qrUriPayload by remember { mutableStateOf("") }
    var showCameraQrScanner by remember { mutableStateOf(false) }
    var qrDecodeStatus by remember { mutableStateOf<String?>(null) }

    val qrGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val decoded = decodeQrFromUri(context, uri)
            if (!decoded.isNullOrBlank()) {
                qrUriPayload = decoded
                qrDecodeStatus = null
                onImportClipboardText(decoded)
            } else {
                qrDecodeStatus = "Could not detect a QR code in that image."
            }
        }
    }

    val readClipboardText: () -> String = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    val tabs = remember {
        listOf(
            AddServerTab.SUBSCRIPTION to "Subscription",
            AddServerTab.MANUAL to "Manual",
            AddServerTab.QR_CODE to "QR code"
        )
    }
    val selectedTabIndex = tabs.indexOfFirst { it.first == activeTab }.let { if (it >= 0) it else 0 }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HollowCream)
            .statusBarsPadding()
            .padding(bottom = bottomContentPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Page 5 Top Header: < + "Add server"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Spacer(modifier = Modifier.width(14.dp))

                Text(
                    text = "Add server",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    letterSpacing = (-0.4).sp,
                    color = HollowInk
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Page 5 Underlined Tabs (Subscription, Manual, QR code)
            HollowUnderlinedTabRow(
                tabs = tabs.map { it.second },
                selectedIndex = selectedTabIndex,
                onSelectTab = { idx -> onSelectTab(tabs[idx].first) },
                tabTestTags = tabs.map { "tab_${it.first.name.lowercase()}" }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                when (activeTab) {
                    AddServerTab.SUBSCRIPTION -> {
                        Text(
                            text = "SUBSCRIPTION LINK",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                            color = HollowInkSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Clean dark-bordered monospace input field matching Page 5
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = HollowCream,
                            border = BorderStroke(1.5.dp, HollowInk)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (subscriptionUrl.isEmpty()) {
                                    Text(
                                        text = "https://example.com/sub",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 14.5.sp,
                                        color = HollowInkMuted
                                    )
                                }
                                BasicTextField(
                                    value = subscriptionUrl,
                                    onValueChange = { subscriptionUrl = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 14.5.sp,
                                        color = HollowInk
                                    ),
                                    cursorBrush = SolidColor(HollowOrange),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("subscription_url_input")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Primary CTA: "Add & fetch"
                        Button(
                            onClick = { onAddAndFetchSubscription(subscriptionUrl) },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HollowInk,
                                contentColor = HollowCream
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("btn_add_and_fetch")
                        ) {
                            Text(
                                text = "Add & fetch",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Secondary CTA: "Paste from clipboard"
                        OutlinedButton(
                            onClick = {
                                val pasted = readClipboardText().trim()
                                if (pasted.startsWith("http://", ignoreCase = true) ||
                                    pasted.startsWith("https://", ignoreCase = true)
                                ) {
                                    subscriptionUrl = pasted
                                } else {
                                    onImportClipboardText(pasted)
                                }
                            },
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.2.dp, HollowInk),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = HollowInk
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("card_import_clipboard")
                        ) {
                            Text(
                                text = "Paste from clipboard",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.5.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(34.dp))

                        Text(
                            text = "OPTIONS",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                            color = HollowInkSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 1: Auto update
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable {
                                    onUpdateSettings { it.copy(subAutoUpdate = !settings.subAutoUpdate) }
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto update",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    color = HollowInk
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Refresh in the background",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = HollowInkSecondary
                                )
                            }
                            HollowSwitch(
                                checked = settings.subAutoUpdate,
                                onCheckedChange = { enabled ->
                                    onUpdateSettings { it.copy(subAutoUpdate = enabled) }
                                },
                                modifier = Modifier.testTag("switch_sub_auto_update")
                            )
                        }

                        HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

                        // Row 2: Update interval
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable(role = Role.Button) { showIntervalPicker = true }
                                .padding(vertical = 16.dp)
                                .testTag("row_sub_update_interval"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Update interval",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                ),
                                color = HollowInk,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = settings.subUpdateInterval,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 14.5.sp,
                                    color = HollowInk
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = HollowInkSecondary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

                        // Row 3: Replace existing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable {
                                    onUpdateSettings { it.copy(subReplaceExisting = !settings.subReplaceExisting) }
                                }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Replace existing",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    color = HollowInk
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Remove old servers first",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = HollowInkSecondary
                                )
                            }
                            HollowSwitch(
                                checked = settings.subReplaceExisting,
                                onCheckedChange = { enabled ->
                                    onUpdateSettings { it.copy(subReplaceExisting = enabled) }
                                },
                                modifier = Modifier.testTag("switch_sub_replace_existing")
                            )
                        }

                        HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                    }

                    AddServerTab.MANUAL -> {
                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = HollowInk,
                            unfocusedBorderColor = HollowCreamDivider,
                            focusedLabelColor = HollowInk,
                            unfocusedLabelColor = HollowInkSecondary,
                            cursorColor = HollowOrange,
                            focusedContainerColor = HollowCream,
                            unfocusedContainerColor = HollowCream
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = manualCountry,
                                onValueChange = { manualCountry = it },
                                label = { Text("Server Country / Remark") },
                                singleLine = true,
                                colors = fieldColors,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = manualHost,
                                    onValueChange = { manualHost = it },
                                    label = { Text("Server Address / Host") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(0.68f)
                                )
                                OutlinedTextField(
                                    value = manualPort,
                                    onValueChange = { manualPort = it },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(0.32f)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MANUAL_PROTOCOL_PRESETS.forEach { preset ->
                                    val selected = manualProtocol.equals(preset, ignoreCase = true)
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            manualProtocol = preset
                                            if (preset.equals("Hysteria2", ignoreCase = true)) {
                                                manualTransport = "Hysteria2"
                                            } else if (manualTransport.equals("Hysteria2", ignoreCase = true)) {
                                                manualTransport = "Reality"
                                            }
                                        },
                                        label = { Text(preset) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = HollowInk,
                                            selectedLabelColor = HollowCream,
                                            selectedLeadingIconColor = HollowOrange
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = manualProtocol,
                                    onValueChange = { manualProtocol = it },
                                    label = { Text("Protocol") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = manualTransport,
                                    onValueChange = { manualTransport = it },
                                    label = { Text("Transport") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MANUAL_TRANSPORT_PRESETS.forEach { preset ->
                                    val selected = manualTransport.equals(preset, ignoreCase = true)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { manualTransport = preset },
                                        label = { Text(preset) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = HollowInk,
                                            selectedLabelColor = HollowCream,
                                            selectedLeadingIconColor = HollowOrange
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = manualUuid,
                                onValueChange = { manualUuid = it },
                                label = {
                                    Text(
                                        if (manualProtocol.equals("Hysteria2", ignoreCase = true) ||
                                            manualProtocol.equals("Trojan", ignoreCase = true)
                                        ) "Auth Password" else "UUID / Password"
                                    )
                                },
                                singleLine = true,
                                colors = fieldColors,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = manualSni,
                                onValueChange = { manualSni = it },
                                label = { Text("SNI / Server Name") },
                                singleLine = true,
                                colors = fieldColors,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (manualTransport.equals("Reality", ignoreCase = true) &&
                                !manualProtocol.equals("Hysteria2", ignoreCase = true)
                            ) {
                                OutlinedTextField(
                                    value = manualPublicKey,
                                    onValueChange = { manualPublicKey = it },
                                    label = { Text("Reality Public Key (pbk)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("manual_public_key_input")
                                )
                                OutlinedTextField(
                                    value = manualShortId,
                                    onValueChange = { manualShortId = it },
                                    label = { Text("Reality Short ID (sid)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("manual_short_id_input")
                                )
                            }

                            if (manualTransport.equals("WS", ignoreCase = true) ||
                                manualTransport.equals("WebSocket", ignoreCase = true)
                            ) {
                                OutlinedTextField(
                                    value = manualWsPath,
                                    onValueChange = { manualWsPath = it },
                                    label = { Text("WebSocket Path (e.g. /ws)") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("manual_ws_path_input")
                                )
                                OutlinedTextField(
                                    value = manualWsHost,
                                    onValueChange = { manualWsHost = it },
                                    label = { Text("WebSocket Host Header") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("manual_ws_host_input")
                                )
                            }

                            if (manualTransport.equals("gRPC", ignoreCase = true)) {
                                OutlinedTextField(
                                    value = manualGrpcServiceName,
                                    onValueChange = { manualGrpcServiceName = it },
                                    label = { Text("gRPC Service Name") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("manual_grpc_service_input")
                                )
                            }

                            OutlinedTextField(
                                value = manualRawJson,
                                onValueChange = { manualRawJson = it },
                                label = { Text("Optional Raw Config (JSON)") },
                                textStyle = TextStyle(
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 12.sp,
                                    color = HollowInk
                                ),
                                colors = fieldColors,
                                shape = RoundedCornerShape(14.dp),
                                minLines = 4,
                                maxLines = 8,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("raw_xray_json_input")
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    onAddManualServer(
                                        manualCountry,
                                        manualHost,
                                        manualPort.toIntOrNull() ?: 443,
                                        manualProtocol,
                                        manualTransport,
                                        manualUuid,
                                        manualSni,
                                        manualRawJson,
                                        manualPublicKey,
                                        manualShortId,
                                        manualWsPath,
                                        manualWsHost,
                                        manualGrpcServiceName
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HollowInk,
                                    contentColor = HollowCream
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("btn_save_manual_server")
                            ) {
                                Text(
                                    text = "Save Custom Server & Ping Test",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }

                    AddServerTab.QR_CODE -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = HollowCreamSurface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(HollowInk),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.QrCodeScanner,
                                        contentDescription = null,
                                        tint = HollowCream,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }

                                Text(
                                    text = "Import Server QR Code",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    color = HollowInk
                                )

                                Text(
                                    text = "Scan a QR code with your camera, pick a QR screenshot from gallery, or paste a decoded link below.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = HollowInkSecondary,
                                    textAlign = TextAlign.Center
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { showCameraQrScanner = true },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = HollowInk,
                                            contentColor = HollowCream
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("btn_scan_camera_qr")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.QrCodeScanner,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Scan Camera")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            qrGalleryLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.2.dp, HollowInk),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = HollowInk
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp)
                                            .testTag("btn_pick_qr_image")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PhotoLibrary,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Gallery")
                                    }
                                }

                                if (!qrDecodeStatus.isNullOrBlank()) {
                                    Text(
                                        text = qrDecodeStatus.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = HollowOrange,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                OutlinedTextField(
                                    value = qrUriPayload,
                                    onValueChange = { qrUriPayload = it },
                                    label = { Text("Decoded Link (vless://, vmess://, trojan://, ss://)") },
                                    textStyle = TextStyle(
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 13.sp,
                                        color = HollowInk
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = HollowInk,
                                        unfocusedBorderColor = HollowCreamDivider,
                                        focusedLabelColor = HollowInk,
                                        unfocusedLabelColor = HollowInkSecondary
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    minLines = 3,
                                    maxLines = 5,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_uri_input")
                                )

                                Button(
                                    onClick = { onImportClipboardText(qrUriPayload) },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = HollowInk,
                                        contentColor = HollowCream
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("btn_import_qr_uri")
                                ) {
                                    Text(
                                        text = "Import QR Server & Ping Test",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }

    if (showCameraQrScanner) {
        CameraQrScannerDialog(
            onQrDecoded = { decoded ->
                showCameraQrScanner = false
                qrUriPayload = decoded
                qrDecodeStatus = null
                onImportClipboardText(decoded)
            },
            onDismiss = { showCameraQrScanner = false }
        )
    }

    if (showIntervalPicker) {
        SettingOptionPickerDialog(
            title = "Select Update Interval",
            options = UPDATE_INTERVAL_OPTIONS,
            selectedValue = settings.subUpdateInterval,
            onSelect = { chosen ->
                onUpdateSettings {
                    it.copy(updateIntervalLabel = chosen, subUpdateInterval = chosen)
                }
                showIntervalPicker = false
            },
            onDismiss = { showIntervalPicker = false }
        )
    }
}
