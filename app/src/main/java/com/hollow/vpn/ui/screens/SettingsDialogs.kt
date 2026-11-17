package com.hollow.vpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.hollow.vpn.BuildConfig
import com.hollow.vpn.data.AppUpdateState
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.ui.components.HollowBrandShieldLogo
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkMuted
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowLime
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SettingChoiceOption(
    val value: String,
    val subtitle: String,
    val label: String = value
)

internal val UPDATE_INTERVAL_OPTIONS = listOf(
    SettingChoiceOption("15 min", "Checks often — best if servers drop frequently", "Every 15 min"),
    SettingChoiceOption("30 min", "Checks twice an hour for faster servers", "Every 30 min"),
    SettingChoiceOption("1 hour", "Balanced speed and battery usage", "Every 1 hour (Recommended)"),
    SettingChoiceOption("6 hours", "Light background usage to save battery", "Every 6 hours"),
    SettingChoiceOption("12 hours", "Checks twice a day", "Every 12 hours"),
    SettingChoiceOption("24 hours", "Checks once a day", "Once a day")
)

private data class ProxyAppChoice(val packageName: String, val label: String)

@Composable
internal fun XrayDiagnosticLogsDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PerAppProxyPickerDialog(
    selectedPackages: Set<String>,
    bypassSelectedApps: Boolean = true,
    onSave: (Set<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val apps = remember(context) {
        val pm = context.packageManager
        val discovered = linkedMapOf<String, ProxyAppChoice>()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(launcherIntent, 0).forEach { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                val label = resolveInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                discovered.putIfAbsent(pkg, ProxyAppChoice(pkg, label))
            }
        }
        runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .forEach { appInfo ->
                    val label = pm.getApplicationLabel(appInfo).toString().ifBlank { appInfo.packageName }
                    discovered.putIfAbsent(appInfo.packageName, ProxyAppChoice(appInfo.packageName, label))
                }
        }
        discovered.values.sortedWith(
            compareByDescending<ProxyAppChoice> { it.packageName in selectedPackages }
                .thenBy { it.label.lowercase() }
        )
    }

    var searchQuery by remember { mutableStateOf("") }
    var workingSelection by remember(selectedPackages) { mutableStateOf(selectedPackages) }
    var workingBypassMode by remember(bypassSelectedApps) { mutableStateOf(bypassSelectedApps) }
    var showSelectedOnly by remember { mutableStateOf(false) }

    val filteredApps = remember(apps, searchQuery, showSelectedOnly, workingSelection) {
        val q = searchQuery.trim()
        apps.filter { app ->
            val matchesQuery = q.isEmpty() ||
                app.label.contains(q, ignoreCase = true) ||
                app.packageName.contains(q, ignoreCase = true)
            val matchesFilter = !showSelectedOnly || app.packageName in workingSelection
            matchesQuery && matchesFilter
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HollowCream,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Per-app routing",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = HollowInk
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (workingBypassMode) {
                            "Checked apps skip VPN and use normal internet"
                        } else {
                            "Only checked apps go through the VPN tunnel"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = HollowInkSecondary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = HollowCreamSurface,
                    border = BorderStroke(1.dp, HollowCreamDivider)
                ) {
                    Text(
                        text = "${workingSelection.size} apps",
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (workingSelection.isNotEmpty()) HollowOrange else HollowInkSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 2-Segment Mode Switcher
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = HollowCreamSurface,
                border = BorderStroke(1.dp, HollowCreamDivider)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PerAppModeChip(
                        label = "Skip VPN",
                        selected = workingBypassMode,
                        onClick = { workingBypassMode = true },
                        modifier = Modifier.weight(1f)
                    )
                    PerAppModeChip(
                        label = "VPN only",
                        selected = !workingBypassMode,
                        onClick = { workingBypassMode = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search installed apps", color = HollowInkMuted) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = HollowInkSecondary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Clear search",
                                tint = HollowInkSecondary
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = HollowCreamSurface,
                    unfocusedContainerColor = HollowCreamSurface,
                    focusedBorderColor = HollowInk,
                    unfocusedBorderColor = HollowCreamDivider,
                    focusedTextColor = HollowInk,
                    unfocusedTextColor = HollowInk
                ),
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
                FilterChip(
                    selected = showSelectedOnly,
                    onClick = { showSelectedOnly = !showSelectedOnly },
                    label = {
                        Text(
                            text = "Selected (${workingSelection.size})",
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HollowInk,
                        selectedLabelColor = HollowCream,
                        containerColor = HollowCreamSurface,
                        labelColor = HollowInkSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = showSelectedOnly,
                        borderColor = HollowCreamDivider
                    )
                )
                if (workingSelection.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            workingSelection = emptySet()
                            showSelectedOnly = false
                        }
                    ) {
                        Text(
                            text = "Clear all",
                            color = HollowOrange,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (filteredApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (showSelectedOnly) "No apps selected yet" else "No matching apps found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HollowInkSecondary
                            )
                        }
                    }
                } else {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in workingSelection
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Checkbox) {
                                    workingSelection = if (checked) {
                                        workingSelection - app.packageName
                                    } else {
                                        workingSelection + app.packageName
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = if (checked) HollowCreamSurface else Color.Transparent,
                            border = if (checked) BorderStroke(1.dp, HollowCreamDivider) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                InstalledAppIcon(
                                    packageName = app.packageName,
                                    appLabel = app.label
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label,
                                        color = HollowInk,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        fontFamily = JetBrainsMonoFamily,
                                        color = HollowInkSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        workingSelection = if (value) {
                                            workingSelection + app.packageName
                                        } else {
                                            workingSelection - app.packageName
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = HollowOrange,
                                        uncheckedColor = HollowInkSecondary,
                                        checkmarkColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HollowCreamSurface,
                        contentColor = HollowInk
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { onSave(workingSelection, workingBypassMode) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HollowInk,
                        contentColor = HollowCream
                    ),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (workingSelection.isEmpty()) "Save (Off)" else "Save (${workingSelection.size})",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun PerAppModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
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
private fun InstalledAppIcon(
    packageName: String,
    appLabel: String
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(width = 80, height = 80)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = appLabel,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(10.dp),
            color = HollowCream,
            border = BorderStroke(1.dp, HollowCreamDivider)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    tint = HollowInkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
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
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option.value == selectedValue
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { onSelect(option.value) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) HollowInk else HollowCreamSurface,
                        border = if (isSelected) null else BorderStroke(1.dp, HollowCreamDivider)
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
                Text("Close", fontWeight = FontWeight.SemiBold)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutHollowSheet(
    onCopyBuildInfo: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val versionSummary = "Hollow VPN v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${BuildConfig.APPLICATION_ID}"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HollowCream,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 26.dp)
        ) {
            // Obsidian Hero Showcase Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = HollowInk
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val center = Offset(size.width * 0.88f, size.height * 0.25f)
                        drawCircle(
                            color = HollowOrange.copy(alpha = 0.14f),
                            radius = 68.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawCircle(
                            color = HollowLime.copy(alpha = 0.09f),
                            radius = 104.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(Color(0xFF23221E)),
                                contentAlignment = Alignment.Center
                            ) {
                                HollowBrandShieldLogo(
                                    strokeColor = HollowCream,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hollow VPN",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = HollowCream
                                )
                                Text(
                                    text = "Stealth Xray Multi-Protocol Tunnel",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                    color = Color(0xFFA7A298)
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
                                    fontSize = 11.5.sp,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Built for fast, resilient encrypted routing across VLESS Reality, VMess, Trojan, Shadowsocks, and Hysteria2 with zero tracking and strict local control.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            ),
                            color = Color(0xFFD0CBC0)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "ARCHITECTURE & PRIVACY",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
                color = HollowOrange
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = HollowCreamSurface,
                border = BorderStroke(1.dp, HollowCreamDivider)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AboutFeatureRow(
                        icon = Icons.Outlined.Shield,
                        title = "Xray-core Tunnel Engine",
                        detail = "Real Xray outbound latency verification & high-speed multi-transport routing"
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = HollowCreamDivider,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    AboutFeatureRow(
                        icon = Icons.Outlined.Lock,
                        title = "Zero-Log Local State",
                        detail = "All servers, subscriptions, and routing preferences stay strictly on-device"
                    )
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = HollowCreamDivider,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    AboutFeatureRow(
                        icon = Icons.Outlined.Dns,
                        title = "Encrypted DNS & Split Apps",
                        detail = "DoH DNS protection with per-app bypass and LAN routing controls"
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "BUILD METADATA",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
                color = HollowInkSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = HollowCreamSurface,
                border = BorderStroke(1.dp, HollowCreamDivider)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AboutMetaRow(label = "VERSION", value = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                    AboutMetaRow(label = "PACKAGE", value = BuildConfig.APPLICATION_ID)
                    AboutMetaRow(label = "ANDROID SDK", value = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    AboutMetaRow(label = "REPOSITORY", value = "github.com/irexanon/V2ray")
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Hollow VPN Build Info", versionSummary))
                        onCopyBuildInfo("Build info copied")
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.2.dp, HollowInk),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HollowInk),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Copy info",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onCheckUpdates,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HollowInk,
                        contentColor = HollowCream
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_about_check_updates")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Check update",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutFeatureRow(
    icon: ImageVector,
    title: String,
    detail: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HollowInk),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = HollowCream,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp
                ),
                color = HollowInk
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = HollowInkSecondary
            )
        }
    }
}

@Composable
private fun AboutMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 0.8.sp,
            color = HollowInkSecondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = HollowInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AppUpdateCheckDialog(
    updateState: AppUpdateState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HollowCream,
        shape = RoundedCornerShape(24.dp),
        icon = {
            when (updateState) {
                AppUpdateState.Checking, AppUpdateState.Idle -> {
                    CircularProgressIndicator(
                        color = HollowOrange,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp)
                    )
                }
                is AppUpdateState.UpToDate -> {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF15803D).copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF15803D),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                is AppUpdateState.UpdateAvailable -> {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(HollowOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = HollowOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                is AppUpdateState.Error -> {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(HollowOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = HollowOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        title = {
            Text(
                text = when (updateState) {
                    AppUpdateState.Idle, AppUpdateState.Checking -> "Checking for updates…"
                    is AppUpdateState.UpToDate -> "You're up to date"
                    is AppUpdateState.UpdateAvailable -> "Update available"
                    is AppUpdateState.Error -> "Update check failed"
                },
                fontFamily = SpaceGroteskFamily,
                fontWeight = FontWeight.Bold,
                color = HollowInk
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (updateState) {
                    AppUpdateState.Idle, AppUpdateState.Checking -> {
                        Text(
                            text = "Contacting GitHub Releases for Hollow VPN v${BuildConfig.VERSION_NAME}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HollowInkSecondary
                        )
                    }
                    is AppUpdateState.UpToDate -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = HollowCreamSurface,
                            border = BorderStroke(1.dp, HollowCreamDivider)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AboutMetaRow("INSTALLED", "v${updateState.currentVersion}")
                                AboutMetaRow("LATEST RELEASE", updateState.latestVersion)
                            }
                        }
                        Text(
                            text = "You are already running the latest available release.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HollowInkSecondary
                        )
                    }
                    is AppUpdateState.UpdateAvailable -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = HollowInk
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "v${updateState.currentVersion}  →  ${updateState.latestVersion}",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp,
                                    color = HollowLime
                                )
                                Text(
                                    text = updateState.releaseNotes.take(260),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HollowCream.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                    is AppUpdateState.Error -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HollowInkSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is AppUpdateState.UpdateAvailable -> {
                    val targetUrl = updateState.apkDownloadUrl ?: updateState.releaseUrl
                    Button(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HollowInk,
                            contentColor = HollowCream
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (updateState.apkDownloadUrl != null) "Download APK" else "Open Release")
                    }
                }
                is AppUpdateState.Error -> {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HollowInk,
                            contentColor = HollowCream
                        )
                    ) {
                        Text("Retry")
                    }
                }
                is AppUpdateState.UpToDate -> {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HollowInk,
                            contentColor = HollowCream
                        )
                    ) {
                        Text("Done")
                    }
                }
                else -> {
                    TextButton(onClick = onDismiss) {
                        Text("Hide", color = HollowInk)
                    }
                }
            }
        },
        dismissButton = {
            if (updateState is AppUpdateState.UpdateAvailable || updateState is AppUpdateState.Error) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = HollowInk)
                }
            } else if (updateState is AppUpdateState.UpToDate) {
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(updateState.releaseUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }
                ) {
                    Text("Releases", color = HollowInkSecondary)
                }
            }
        }
    )
}
