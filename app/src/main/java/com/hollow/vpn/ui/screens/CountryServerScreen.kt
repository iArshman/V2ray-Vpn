package com.hollow.vpn.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.data.ServerFilter
import com.hollow.vpn.ui.components.HollowTopSquareButton
import com.hollow.vpn.ui.components.HollowUnderlinedTabRow
import com.hollow.vpn.ui.components.countryCodeToFlagEmoji
import com.hollow.vpn.ui.theme.HollowCream
import com.hollow.vpn.ui.theme.HollowCreamDivider
import com.hollow.vpn.ui.theme.HollowCreamSurface
import com.hollow.vpn.ui.theme.HollowInk
import com.hollow.vpn.ui.theme.HollowInkMuted
import com.hollow.vpn.ui.theme.HollowInkSecondary
import com.hollow.vpn.ui.theme.HollowObsidianDivider
import com.hollow.vpn.ui.theme.HollowOrange
import com.hollow.vpn.ui.theme.JetBrainsMonoFamily
import com.hollow.vpn.ui.theme.SpaceGroteskFamily

@Composable
fun CountryServerListScreen(
    servers: List<ServerEntity>,
    selectedFilter: ServerFilter = ServerFilter.FASTEST,
    activeProtocolPreference: String = "Auto (V2Ray)",
    bottomContentPadding: Dp = 0.dp,
    onSelectFilter: (ServerFilter) -> Unit = {},
    onResetProtocolFilter: () -> Unit = {},
    onSelectServer: (ServerEntity) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAddServer: () -> Unit,
    onPingServer: (ServerEntity) -> Unit,
    onRepingAll: () -> Unit = {},
    onShowMessage: (String) -> Unit = {},
    onShareQr: (ServerEntity) -> Unit = {},
    onInspectJson: (ServerEntity) -> Unit,
    onDeleteServer: (ServerEntity) -> Unit,
    onDeleteServersByIds: (Set<Long>) -> Unit = {},
    onDeleteDuplicates: () -> Unit = {},
    onDeleteInvalid: () -> Unit = {},
    onExportAllServers: () -> Unit = {},
    onDeleteAllServers: () -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var sortByPingResults by remember { mutableStateOf(true) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    val displayedServers = remember(servers, sortByPingResults) {
        if (sortByPingResults) {
            servers.sortedBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
        } else {
            servers
        }
    }

    val fastestNode = remember(displayedServers) {
        displayedServers.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }
            ?: displayedServers.firstOrNull()
    }

    val rawGroups = remember(displayedServers) {
        displayedServers.groupBy { it.countryName.ifBlank { "Unknown" } }
    }

    val orderedGroups = remember(rawGroups, sortByPingResults) {
        val entries = rawGroups.entries.toList()
        if (sortByPingResults) {
            entries.sortedBy { (_, list) ->
                list.filter { it.pingMs > 0 }.minOfOrNull { it.pingMs } ?: Int.MAX_VALUE
            }
        } else {
            entries.sortedBy { it.key.lowercase() }
        }
    }

    val topFastestServers = remember(displayedServers) {
        displayedServers.take(6)
    }

    val tabs = remember {
        listOf(
            ServerFilter.FASTEST to "Fastest",
            ServerFilter.ALL to "All",
            ServerFilter.LOW_PING to "Low ping"
        )
    }
    val selectedTabIndex = tabs.indexOfFirst { it.first == selectedFilter }.let { if (it >= 0) it else 0 }

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
                .widthIn(max = 620.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Page 4 Top Header: Title + subtitle on left, 3 square buttons on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Servers",
                        fontFamily = SpaceGroteskFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 31.sp,
                        letterSpacing = (-0.6).sp,
                        color = HollowInk
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${displayedServers.size} nodes · ${rawGroups.size} countries",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = HollowInkSecondary
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HollowTopSquareButton(
                        containerColor = HollowCreamSurface,
                        contentColor = HollowInk,
                        contentDescription = "Test all saved servers",
                        onClick = onRepingAll,
                        modifier = Modifier.testTag("country_reping_all")
                    ) { tint ->
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    HollowTopSquareButton(
                        containerColor = HollowCreamSurface,
                        contentColor = HollowInk,
                        contentDescription = "Add server",
                        onClick = onOpenAddServer,
                        modifier = Modifier.testTag("country_add")
                    ) { tint ->
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Box {
                        HollowTopSquareButton(
                            containerColor = if (overflowMenuExpanded) HollowInk else HollowCreamSurface,
                            contentColor = if (overflowMenuExpanded) HollowCream else HollowInk,
                            contentDescription = "More server actions",
                            onClick = { overflowMenuExpanded = true },
                            modifier = Modifier.testTag("country_more_menu")
                        ) { tint ->
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Page 7: Dark Overflow Action Menu
                        DropdownMenu(
                            expanded = overflowMenuExpanded,
                            onDismissRequest = { overflowMenuExpanded = false },
                            offset = DpOffset(x = 0.dp, y = 8.dp),
                            containerColor = HollowInk,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.width(212.dp)
                        ) {
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.Schedule,
                                label = "Update all",
                                color = HollowCream,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onRefresh()
                                }
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.CheckCircle,
                                label = "Select several",
                                color = HollowCream,
                                onClick = {
                                    overflowMenuExpanded = false
                                    isSelectionMode = true
                                    selectedIds = emptySet()
                                }
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Rounded.Tune,
                                label = "Sort by test results",
                                color = HollowCream,
                                onClick = {
                                    overflowMenuExpanded = false
                                    sortByPingResults = true
                                    onRepingAll()
                                }
                            )
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = HollowObsidianDivider,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.DeleteOutline,
                                label = "Delete duplicates",
                                color = HollowOrange,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onDeleteDuplicates()
                                }
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.DeleteOutline,
                                label = "Delete invalid",
                                color = HollowOrange,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onDeleteInvalid()
                                }
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.FileUpload,
                                label = "Export all",
                                color = HollowCream,
                                onClick = {
                                    overflowMenuExpanded = false
                                    onExportAllServers()
                                }
                            )
                            ServerOverflowMenuItem(
                                icon = Icons.Outlined.DeleteOutline,
                                label = "Delete all servers",
                                color = HollowOrange,
                                onClick = {
                                    overflowMenuExpanded = false
                                    showDeleteAllConfirm = true
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Page 4 Underlined Filter Tabs (All, Recommended, Low ping)
            HollowUnderlinedTabRow(
                tabs = tabs.map { it.second },
                selectedIndex = selectedTabIndex,
                onSelectTab = { idx -> onSelectFilter(tabs[idx].first) },
                tabTestTags = tabs.map { "country_filter_${it.first.name.lowercase()}" }
            )

            if (activeProtocolPreference != "Auto (V2Ray)") {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = HollowCreamSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onResetProtocolFilter)
                        .testTag("chip_reset_protocol_filter")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Protocol: ${formatConsumerProtocol(activeProtocolPreference)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = HollowInk
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Reset protocol filter",
                            tint = HollowInkSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            if (isSelectionMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = HollowInk
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            color = HollowCream
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    isSelectionMode = false
                                    selectedIds = emptySet()
                                }
                            ) {
                                Text("Cancel", color = HollowCream.copy(alpha = 0.78f))
                            }
                            Button(
                                onClick = {
                                    if (selectedIds.isNotEmpty()) {
                                        onDeleteServersByIds(selectedIds)
                                    }
                                    isSelectionMode = false
                                    selectedIds = emptySet()
                                },
                                enabled = selectedIds.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HollowOrange,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("server_lazy_column"),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                val isFastestTab = selectedFilter == ServerFilter.FASTEST

                // Dark FASTEST Hero Card (shown ONLY on the Fastest tab)
                if (isFastestTab && fastestNode != null) {
                    item(key = "fastest_hero_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(role = Role.Button) { onSelectServer(fastestNode) }
                                .testTag("server_card_featured"),
                            shape = RoundedCornerShape(20.dp),
                            color = HollowInk
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "FASTEST",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.5.sp,
                                        letterSpacing = 1.4.sp,
                                        color = HollowOrange
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = countryCodeToFlagEmoji(fastestNode.countryCode),
                                            fontSize = 22.sp
                                        )
                                        Text(
                                            text = fastestNode.countryName.ifBlank { "Fastest Node" },
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 21.sp,
                                            color = HollowCream,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val addr = fastestNode.resolvedIp.ifBlank { fastestNode.host }
                                    Text(
                                        text = "$addr:${fastestNode.port}",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 12.5.sp,
                                        color = Color(0xFF9C978E),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = if (fastestNode.pingMs > 0) "${fastestNode.pingMs}" else "—",
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 28.sp,
                                        color = HollowCream
                                    )
                                    if (fastestNode.pingMs > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "ms",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 12.sp,
                                            color = Color(0xFF9C978E),
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                val isEmpty = if (isFastestTab) topFastestServers.isEmpty() else orderedGroups.isEmpty()
                if (isEmpty) {
                    item(key = "empty_state") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = HollowCreamSurface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PublicOff,
                                    contentDescription = null,
                                    tint = HollowInkSecondary,
                                    modifier = Modifier.size(30.dp)
                                )
                                Text(
                                    text = "No matching servers",
                                    fontFamily = SpaceGroteskFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = HollowInk
                                )
                                val emptyMsg = if (activeProtocolPreference != "Auto (V2Ray)") {
                                    "No servers match \"${formatConsumerProtocol(activeProtocolPreference)}\". Reset to Auto or add a server."
                                } else {
                                    "Import a subscription link, server config, or QR code to populate servers."
                                }
                                Text(
                                    text = emptyMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HollowInkSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (activeProtocolPreference != "Auto (V2Ray)") {
                                        TextButton(onClick = onResetProtocolFilter) {
                                            Text("Reset Protocol", color = HollowInk)
                                        }
                                    }
                                    Button(
                                        onClick = onOpenAddServer,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = HollowInk,
                                            contentColor = HollowCream
                                        ),
                                        modifier = Modifier.testTag("empty_add_server_button")
                                    ) {
                                        Text("Add server")
                                    }
                                }
                            }
                        }
                    }
                }

                if (isFastestTab && topFastestServers.isNotEmpty()) {
                    item(key = "fastest_list_header") {
                        Text(
                            text = "TOP ${topFastestServers.size} FASTEST",
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.4.sp,
                            color = HollowInkSecondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    item(key = "fastest_flat_list") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            topFastestServers.forEachIndexed { idx, server ->
                                val isChecked = server.id in selectedIds
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isSelectionMode) {
                                                selectedIds = if (isChecked) {
                                                    selectedIds - server.id
                                                } else {
                                                    selectedIds + server.id
                                                }
                                            } else {
                                                onSelectServer(server)
                                            }
                                        }
                                        .testTag("fastest_server_${server.id}")
                                ) {
                                    ServerItemRowContent(
                                        server = server,
                                        showLightningBadge = idx == 0 || server.isSelected,
                                        onSelect = { onSelectServer(server) },
                                        onPing = { onPingServer(server) },
                                        onInspectJson = { onInspectJson(server) },
                                        onDelete = { onDeleteServer(server) },
                                        onCopyFeedback = onShowMessage,
                                        onShareQr = { onShareQr(server) },
                                        isSelectionMode = isSelectionMode,
                                        isCheckedForBatch = isChecked,
                                        onToggleBatchCheck = {
                                            selectedIds = if (isChecked) {
                                                selectedIds - server.id
                                            } else {
                                                selectedIds + server.id
                                            }
                                        },
                                        showCountryBadge = true
                                    )
                                }
                                if (idx < topFastestServers.lastIndex) {
                                    HorizontalDivider(
                                        thickness = 1.dp,
                                        color = HollowCreamDivider
                                    )
                                }
                            }
                        }
                    }
                } else if (!isFastestTab) {
                    // Country Rows (All / Low ping tabs — without top Fastest hero card)
                    orderedGroups.forEachIndexed { groupIndex, (country, countryServers) ->
                    val orderedServers = countryServers.sortedBy {
                        if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE
                    }
                    val bestPing = orderedServers.firstOrNull { it.pingMs > 0 }?.pingMs ?: 0
                    val hasSelected = orderedServers.any { it.isSelected }
                    val isExpanded = expanded[country] ?: (isSelectionMode || orderedGroups.size == 1)

                    val groupCode = orderedServers.firstOrNull {
                        it.countryCode.length == 2 && !it.countryCode.equals("XX", ignoreCase = true)
                    }?.countryCode

                    item(key = "country_$country") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clickable(role = Role.Button) {
                                        expanded[country] = !isExpanded
                                    }
                                    .padding(vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = countryCodeToFlagEmoji(groupCode),
                                            fontSize = 20.sp
                                        )
                                        Text(
                                            text = country,
                                            fontFamily = SpaceGroteskFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.5.sp,
                                            color = HollowInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (hasSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(HollowOrange)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${orderedServers.size} node${if (orderedServers.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                        color = HollowInkSecondary
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (bestPing > 0) {
                                        Row(verticalAlignment = Alignment.Bottom) {
                                            Text(
                                                text = "$bestPing",
                                                fontFamily = JetBrainsMonoFamily,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.5.sp,
                                                color = HollowInk
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "ms",
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 12.sp,
                                                color = HollowInkSecondary
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "Timeout",
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 12.sp,
                                            color = HollowOrange
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val barFraction = if (bestPing > 0) {
                                        (bestPing / 1500f).coerceIn(0.16f, 1f)
                                    } else {
                                        0.10f
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(64.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(HollowCreamDivider)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(barFraction)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(if (bestPing > 0) HollowInk else HollowOrange)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Icon(
                                    imageVector = if (isExpanded) {
                                        Icons.Rounded.KeyboardArrowDown
                                    } else {
                                        Icons.AutoMirrored.Rounded.KeyboardArrowRight
                                    },
                                    contentDescription = if (isExpanded) "Collapse $country" else "Expand $country",
                                    tint = HollowInkSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            AnimatedVisibility(
                                visible = isExpanded,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = HollowCreamSurface
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        orderedServers.forEachIndexed { idx, server ->
                                            val isChecked = server.id in selectedIds
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (isSelectionMode) {
                                                            selectedIds = if (isChecked) {
                                                                selectedIds - server.id
                                                            } else {
                                                                selectedIds + server.id
                                                            }
                                                        } else {
                                                            onSelectServer(server)
                                                        }
                                                    }
                                                    .testTag("country_server_${server.id}")
                                            ) {
                                                ServerItemRowContent(
                                                    server = server,
                                                    showLightningBadge = server.isSelected,
                                                    onSelect = { onSelectServer(server) },
                                                    onPing = { onPingServer(server) },
                                                    onInspectJson = { onInspectJson(server) },
                                                    onDelete = { onDeleteServer(server) },
                                                    onCopyFeedback = onShowMessage,
                                                    onShareQr = { onShareQr(server) },
                                                    isSelectionMode = isSelectionMode,
                                                    isCheckedForBatch = isChecked,
                                                    onToggleBatchCheck = {
                                                        selectedIds = if (isChecked) {
                                                            selectedIds - server.id
                                                        } else {
                                                            selectedIds + server.id
                                                        }
                                                    }
                                                )
                                            }
                                            if (idx < orderedServers.lastIndex) {
                                                HorizontalDivider(
                                                    thickness = 1.dp,
                                                    color = HollowCreamDivider,
                                                    modifier = Modifier.padding(horizontal = 12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (groupIndex < orderedGroups.lastIndex) {
                                HorizontalDivider(thickness = 1.dp, color = HollowCreamDivider)
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = HollowCream,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "Delete all servers?",
                    fontFamily = SpaceGroteskFamily,
                    fontWeight = FontWeight.Bold,
                    color = HollowInk
                )
            },
            text = {
                Text(
                    text = "This removes all saved servers from your device. You can re-fetch from your subscription anytime.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HollowInkSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAllServers()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HollowOrange,
                        contentColor = Color.White
                    )
                ) {
                    Text("Delete all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel", color = HollowInk)
                }
            }
        )
    }
}

@Composable
private fun ServerOverflowMenuItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = color
                )
            }
        },
        onClick = onClick
    )
}
