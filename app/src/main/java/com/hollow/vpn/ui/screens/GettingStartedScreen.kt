package com.hollow.vpn.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.ui.components.CameraQrScannerDialog
import com.hollow.vpn.ui.components.HollowBrandShieldLogo
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowForest
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

private enum class OnboardingImportSheet {
    SUBSCRIPTION,
    PASTE_CONFIG,
    QR_SCANNER
}

@Composable
fun GettingStartedScreen(
    currentStep: Int,
    settings: AppSettingsState,
    serverCount: Int,
    onSelectStep: (Int) -> Unit,
    onFetchSubscription: (String, (Result<Int>) -> Unit) -> Unit,
    onImportConfig: (String, (Result<Int>) -> Unit) -> Unit,
    onSkip: () -> Unit,
    onFinish: (autoConnectIfReady: Boolean) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val step = currentStep.coerceIn(0, 2)
    BackHandler {
        if (step > 0) {
            onSelectStep(step - 1)
        } else {
            onSkip()
        }
    }

    var activeSheet by remember { mutableStateOf<OnboardingImportSheet?>(null) }
    var isImportingQr by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HollowCream)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp)
        ) {
            val isCompact = maxHeight < 660.dp

            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(10.dp))

                // Top 3-segment progress bar + Skip button matching GettingStarted HTML
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (index in 0..2) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (index <= step) HollowInk else HollowCreamDivider
                                    )
                            )
                        }
                    }

                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("onboarding_skip_button")
                    ) {
                        Text(
                            text = "Skip",
                            fontFamily = SpaceGroteskFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = HollowInkSecondary
                        )
                    }
                }

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    modifier = Modifier.weight(1f),
                    label = "getting_started_step"
                ) { activeStep ->
                    when (activeStep) {
                        0 -> GettingStartedWelcomeStep(
                            isCompact = isCompact,
                            onGetStarted = { onSelectStep(1) }
                        )

                        1 -> GettingStartedServersStep(
                            isCompact = isCompact,
                            serverCount = serverCount,
                            isImportingQr = isImportingQr,
                            onOpenSubscription = { activeSheet = OnboardingImportSheet.SUBSCRIPTION },
                            onOpenPasteConfig = { activeSheet = OnboardingImportSheet.PASTE_CONFIG },
                            onOpenQrScanner = { activeSheet = OnboardingImportSheet.QR_SCANNER },
                            onContinue = { onSelectStep(2) }
                        )

                        else -> GettingStartedPermissionsStep(
                            isCompact = isCompact,
                            settings = settings,
                            serverCount = serverCount,
                            onShowMessage = onShowMessage,
                            onAllowAndContinue = {
                                onFinish(serverCount > 0)
                            }
                        )
                    }
                }
            }
        }
    }

    when (activeSheet) {
        OnboardingImportSheet.SUBSCRIPTION -> {
            OnboardingSubscriptionSheet(
                initialUrl = settings.activeSubscriptionUrl,
                onSubmit = onFetchSubscription,
                onDismiss = { activeSheet = null },
                onImported = { count ->
                    activeSheet = null
                    onShowMessage("Added $count verified server(s)")
                }
            )
        }

        OnboardingImportSheet.PASTE_CONFIG -> {
            OnboardingPasteConfigSheet(
                onSubmit = onImportConfig,
                onDismiss = { activeSheet = null },
                onImported = { count ->
                    activeSheet = null
                    onShowMessage("Imported $count verified server(s)")
                }
            )
        }

        OnboardingImportSheet.QR_SCANNER -> {
            CameraQrScannerDialog(
                onQrDecoded = { payload ->
                    activeSheet = null
                    isImportingQr = true
                    onImportConfig(payload) { result ->
                        isImportingQr = false
                        result.fold(
                            onSuccess = { count ->
                                onShowMessage("Imported $count server(s) from QR")
                            },
                            onFailure = { err ->
                                onShowMessage(err.message ?: "Could not import QR payload")
                            }
                        )
                    }
                },
                onDismiss = { activeSheet = null }
            )
        }

        null -> Unit
    }
}

@Composable
private fun GettingStartedWelcomeStep(
    isCompact: Boolean,
    onGetStarted: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "onboarding_radar")
    val ringPulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_ring_pulse"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 10.dp))

            // Radar concentric rings + 3 orbital dots + centered 120dp shield mark
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCompact) 224.dp else 286.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scale = minOf(size.width / 342f, size.height / 300f)
                    val cx = size.width / 2f
                    val cy = size.height / 2f

                    drawCircle(
                        color = HollowCreamDivider,
                        radius = 140f * scale,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                    drawCircle(
                        color = HollowCreamDivider,
                        radius = 104f * scale,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.2.dp.toPx())
                    )

                    val pulseRadius = (64f * (1f + ringPulse * 1.15f)) * scale
                    val pulseAlpha = (1f - ringPulse) * 0.72f
                    drawCircle(
                        color = HollowOrange.copy(alpha = pulseAlpha),
                        radius = pulseRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Three orbital satellite dots matching GettingStarted1 SVG (86,86), (268,98), (250,236)
                    drawCircle(
                        color = HollowInk,
                        radius = 7f * scale,
                        center = Offset(cx + (86f - 171f) * scale, cy + (86f - 150f) * scale)
                    )
                    drawCircle(
                        color = HollowOrange,
                        radius = 7f * scale,
                        center = Offset(cx + (268f - 171f) * scale, cy + (98f - 150f) * scale)
                    )
                    drawCircle(
                        color = HollowInk,
                        radius = 7f * scale,
                        center = Offset(cx + (250f - 171f) * scale, cy + (236f - 150f) * scale)
                    )
                }

                HollowBrandShieldLogo(
                    size = if (isCompact) 104.dp else 120.dp,
                    cornerRadius = if (isCompact) 30.dp else 34.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Private by default.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isCompact) 34.sp else 42.sp,
                lineHeight = if (isCompact) 37.sp else 45.sp,
                letterSpacing = (-1.3).sp,
                color = HollowInk
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Hollow sends your whole device through servers you control. No accounts, no tracking.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = if (isCompact) 16.sp else 17.sp,
                lineHeight = if (isCompact) 23.sp else 25.sp,
                color = HollowInkSecondary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 28.dp)
        ) {
            Button(
                onClick = onGetStarted,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_next_button")
            ) {
                Text(
                    text = "Get started",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
private fun GettingStartedServersStep(
    isCompact: Boolean,
    serverCount: Int,
    isImportingQr: Boolean,
    onOpenSubscription: () -> Unit,
    onOpenPasteConfig: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(if (isCompact) 20.dp else 44.dp))

            Text(
                text = "Bring your own servers.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isCompact) 34.sp else 42.sp,
                lineHeight = if (isCompact) 37.sp else 45.sp,
                letterSpacing = (-1.3).sp,
                color = HollowInk
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Add a server any of these ways. You can change it later.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = if (isCompact) 16.sp else 17.sp,
                lineHeight = if (isCompact) 23.sp else 25.sp,
                color = HollowInkSecondary
            )

            if (serverCount > 0 || isImportingQr) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HollowCreamSurface,
                    border = BorderStroke(1.dp, HollowCreamDivider)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isImportingQr) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = HollowOrange
                            )
                            Text(
                                text = "Verifying scanned server…",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.sp,
                                color = HollowInk
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(HollowForest),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = HollowCream,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = "$serverCount server(s) ready to connect",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 13.sp,
                                color = HollowInk
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            OnboardingServerOptionCard(
                icon = Icons.Rounded.Link,
                title = "Subscription link",
                subtitle = "Paste a link, syncs hourly",
                onClick = onOpenSubscription,
                testTag = "onboarding_card_subscription"
            )

            Spacer(modifier = Modifier.height(12.dp))

            OnboardingServerOptionCard(
                icon = Icons.Outlined.ContentPaste,
                title = "Paste a config",
                subtitle = "vless://, vmess://, ss://",
                onClick = onOpenPasteConfig,
                testTag = "onboarding_card_paste"
            )

            Spacer(modifier = Modifier.height(12.dp))

            OnboardingServerOptionCard(
                icon = Icons.Rounded.QrCodeScanner,
                title = "Scan a QR code",
                subtitle = "Point your camera at a code",
                onClick = onOpenQrScanner,
                testTag = "onboarding_card_qr"
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 28.dp)
        ) {
            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_next_button")
            ) {
                Text(
                    text = "Continue",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
private fun OnboardingServerOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(20.dp),
        color = HollowCreamSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HollowInk),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = HollowCream,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                    letterSpacing = (-0.2).sp,
                    color = HollowInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontFamily = SpaceGroteskFamily,
                    fontSize = 14.sp,
                    color = HollowInkSecondary
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = HollowInkSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun GettingStartedPermissionsStep(
    isCompact: Boolean,
    settings: AppSettingsState,
    serverCount: Int,
    onShowMessage: (String) -> Unit,
    onAllowAndContinue: () -> Unit
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    var pendingCompleteAfterVpnConsent by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshTick++
    }

    val isVpnGranted = remember(refreshTick) {
        try {
            VpnService.prepare(context) == null
        } catch (_: Exception) {
            true
        }
    }

    val isNotificationGranted = remember(refreshTick) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val isBatteryUnrestricted = remember(refreshTick) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        refreshTick++
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingCompleteAfterVpnConsent) {
                pendingCompleteAfterVpnConsent = false
                onAllowAndContinue()
            }
        } else {
            pendingCompleteAfterVpnConsent = false
            onShowMessage("VPN permission is required to tunnel traffic")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        refreshTick++
    }

    val requestVpnPermission = {
        val intent = try {
            VpnService.prepare(context)
        } catch (_: Exception) {
            null
        }
        if (intent != null && context is Activity) {
            vpnPermissionLauncher.launch(intent)
        } else {
            refreshTick++
            if (pendingCompleteAfterVpnConsent) {
                pendingCompleteAfterVpnConsent = false
                onAllowAndContinue()
            }
        }
    }

    val openBatterySettings = {
        val batteryListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val fallbackIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        try {
            if (batteryListIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(batteryListIntent)
            } else {
                context.startActivity(fallbackIntent)
            }
        } catch (_: Exception) {
            try {
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                onShowMessage("Open Android Settings -> Battery to set Hollow to Unrestricted")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(if (isCompact) 20.dp else 44.dp))

            Text(
                text = "One last step.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isCompact) 34.sp else 42.sp,
                lineHeight = if (isCompact) 37.sp else 45.sp,
                letterSpacing = (-1.3).sp,
                color = HollowInk
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Android will ask to set up a VPN. Allow it so Hollow can protect your device.",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Normal,
                fontSize = if (isCompact) 16.sp else 17.sp,
                lineHeight = if (isCompact) 23.sp else 25.sp,
                color = HollowInkSecondary
            )

            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            PermissionChecklistRow(
                title = "VPN connection",
                subtitle = "Required to route traffic",
                badgeText = if (isVpnGranted) "GRANTED" else "REQUIRED",
                badgeColor = HollowOrange,
                isChecked = isVpnGranted,
                isPrimaryAccent = true,
                onClick = {
                    pendingCompleteAfterVpnConsent = false
                    requestVpnPermission()
                },
                testTag = "onboarding_row_vpn"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            PermissionChecklistRow(
                title = "Notifications",
                subtitle = "Show connection status",
                badgeText = if (isNotificationGranted) "GRANTED" else "OPTIONAL",
                badgeColor = if (isNotificationGranted) HollowInk else HollowInkSecondary,
                isChecked = isNotificationGranted,
                isPrimaryAccent = false,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        refreshTick++
                    }
                },
                testTag = "onboarding_row_notifications"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)

            PermissionChecklistRow(
                title = "Unrestricted battery",
                subtitle = "Keep the tunnel alive",
                badgeText = if (isBatteryUnrestricted) "ENABLED" else "ADVISED",
                badgeColor = if (isBatteryUnrestricted) HollowInk else HollowInkSecondary,
                isChecked = isBatteryUnrestricted,
                isPrimaryAccent = false,
                onClick = openBatterySettings,
                testTag = "onboarding_row_battery"
            )
            HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 28.dp)
        ) {
            Button(
                onClick = {
                    val needsVpnPrompt = settings.tunMode &&
                        try {
                            VpnService.prepare(context) != null
                        } catch (_: Exception) {
                            false
                        }
                    if (needsVpnPrompt) {
                        pendingCompleteAfterVpnConsent = true
                        requestVpnPermission()
                    } else {
                        onAllowAndContinue()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("onboarding_allow_button")
            ) {
                val label = if (serverCount > 0 && !isVpnGranted) {
                    "Allow & continue"
                } else {
                    "Allow & continue"
                }
                Text(
                    text = label,
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                )
            }
        }
    }
}

@Composable
private fun PermissionChecklistRow(
    title: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    isChecked: Boolean,
    isPrimaryAccent: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 18.dp)
            .testTag(testTag)
            .semantics { contentDescription = "$title, $subtitle, $badgeText" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val circleBg = when {
            isChecked -> if (isPrimaryAccent) HollowOrange else HollowInk
            else -> Color.Transparent
        }
        val circleBorder = when {
            isChecked -> circleBg
            isPrimaryAccent -> HollowOrange
            else -> HollowCreamDivider
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(circleBg)
                .border(2.dp, circleBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isChecked) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            } else if (isPrimaryAccent) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(HollowOrange)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = HollowInk
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontFamily = SpaceGroteskFamily,
                fontSize = 14.sp,
                color = HollowInkSecondary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = badgeText,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = badgeColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingSubscriptionSheet(
    initialUrl: String,
    onSubmit: (String, (Result<Int>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var urlInput by remember(initialUrl) { mutableStateOf(initialUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = sheetState,
        containerColor = HollowCream,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Subscription link",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = HollowInk
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Paste an Xray / V2Ray subscription URL. Hollow will verify and rank your fastest servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = HollowInkSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    errorMessage = null
                },
                label = { Text("Subscription URL") },
                placeholder = { Text("https://example.com/sub") },
                singleLine = true,
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HollowInk,
                    unfocusedBorderColor = HollowCreamDivider,
                    focusedTextColor = HollowInk,
                    unfocusedTextColor = HollowInk,
                    focusedLabelColor = HollowInk
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_sub_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) {
                        urlInput = clip
                        errorMessage = null
                    } else {
                        errorMessage = "Clipboard is empty"
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HollowCreamDivider)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = HollowInk
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Paste from clipboard", color = HollowInk)
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = HollowOrange
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    onSubmit(urlInput) { result ->
                        isLoading = false
                        result.fold(
                            onSuccess = { count -> onImported(count) },
                            onFailure = { err ->
                                errorMessage = err.message ?: "Failed to fetch subscription"
                            }
                        )
                    }
                },
                enabled = !isLoading && urlInput.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_sub_submit")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = HollowCream,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Fetching & verifying…")
                } else {
                    Text(
                        text = "Add & fetch",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingPasteConfigSheet(
    onSubmit: (String, (Result<Int>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onImported: (Int) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var configInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheetState = sheetState,
        containerColor = HollowCream,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Paste a config",
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = HollowInk
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Supports vless://, vmess://, trojan://, ss://, hy2:// links or raw Xray JSON.",
                style = MaterialTheme.typography.bodyMedium,
                color = HollowInkSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = configInput,
                onValueChange = {
                    configInput = it
                    errorMessage = null
                },
                label = { Text("Server link or JSON") },
                placeholder = { Text("vless://...") },
                minLines = 3,
                maxLines = 6,
                enabled = !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HollowInk,
                    unfocusedBorderColor = HollowCreamDivider,
                    focusedTextColor = HollowInk,
                    unfocusedTextColor = HollowInk,
                    focusedLabelColor = HollowInk
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_paste_input")
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) {
                        configInput = clip
                        errorMessage = null
                    } else {
                        errorMessage = "Clipboard is empty"
                    }
                },
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HollowCreamDivider)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = HollowInk
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Paste from clipboard", color = HollowInk)
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = HollowOrange
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    onSubmit(configInput) { result ->
                        isLoading = false
                        result.fold(
                            onSuccess = { count -> onImported(count) },
                            onFailure = { err ->
                                errorMessage = err.message ?: "Could not verify config"
                            }
                        )
                    }
                },
                enabled = !isLoading && configInput.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HollowInk,
                    contentColor = HollowCream
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_paste_submit")
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = HollowCream,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Verifying server…")
                } else {
                    Text(
                        text = "Verify & import",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
