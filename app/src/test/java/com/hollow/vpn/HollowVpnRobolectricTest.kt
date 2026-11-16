package com.hollow.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hollow.vpn.data.ActiveTunnelSnapshot
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.data.VpnPreferencesStore
import com.hollow.vpn.ui.VpnViewModel
import com.hollow.vpn.ui.components.decodeQrFromBitmap
import com.hollow.vpn.ui.components.generateQrBitmap
import com.hollow.vpn.vpn.V2RayParserAndFletcher
import com.hollow.vpn.vpn.XrayVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HollowVpnRobolectricTest {

    @Test
    fun `app name matches Hollow VPN`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Hollow VPN", appName)
    }

    @Test
    fun `preferences store persists Phase 2 connection and per-app proxy settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VpnPreferencesStore(context)
        val custom = AppSettingsState(
            protocolPreference = "Trojan (TLS)",
            routingMode = "Bypass LAN",
            dnsMode = "Quad9 9.9.9.9",
            tunMode = false,
            ipv6Enabled = true,
            perAppProxyEnabled = true,
            bypassSelectedApps = true,
            selectedAppPackages = setOf("com.android.chrome", "org.mozilla.firefox")
        )
        store.saveSettings(custom)
        val loaded = store.loadSettings()
        assertEquals("Trojan (TLS)", loaded.protocolPreference)
        assertEquals("Bypass LAN", loaded.routingMode)
        assertEquals("Quad9 9.9.9.9", loaded.dnsMode)
        assertEquals(false, loaded.tunMode)
        assertEquals(true, loaded.ipv6Enabled)
        assertEquals(true, loaded.perAppProxyEnabled)
        assertEquals(true, loaded.bypassSelectedApps)
        assertTrue(loaded.selectedAppPackages.contains("com.android.chrome"))
    }

    @Test
    fun `xray vpn service disconnect clears errors and does not report cancellation failure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disconnectIntent = Intent(context, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_DISCONNECT
        }
        val controller = Robolectric.buildService(XrayVpnService::class.java, disconnectIntent).create()
        controller.startCommand(0, 1)
        assertFalse(XrayVpnService.isServiceRunning.value)
        assertNull(XrayVpnService.lastError.value)
        controller.destroy()
        assertNull(XrayVpnService.lastError.value)
    }

    @Test
    fun `xray vpn service proxy mode skips tun prepare check and validates config`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        XrayVpnService.clearLastError()
        val proxyIntent = Intent(context, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_CONNECT
            putExtra(XrayVpnService.EXTRA_TUN_MODE, false)
            putExtra(XrayVpnService.EXTRA_SERVER_HOST, "")
            putExtra(XrayVpnService.EXTRA_XRAY_JSON, "")
        }
        val controller = Robolectric.buildService(XrayVpnService::class.java, proxyIntent).create()
        controller.startCommand(0, 1)
        assertEquals("The selected server has no usable Xray configuration", XrayVpnService.lastError.value)
        XrayVpnService.clearLastError()
        controller.destroy()
    }

    @Test
    fun `parseOutboundTrafficStats sums proxy traffic and excludes block or optional direct outbounds`() {
        val raw = "proxy,uplink,2048;proxy,downlink,8192;direct,uplink,999;block,downlink,500;"

        val proxyOnly = XrayVpnService.parseOutboundTrafficStats(raw, includeDirect = false)
        assertEquals(2048L to 8192L, proxyOnly)

        val withDirect = XrayVpnService.parseOutboundTrafficStats(raw, includeDirect = true)
        assertEquals(3047L to 8192L, withDirect)

        assertEquals(0L to 0L, XrayVpnService.parseOutboundTrafficStats(""))
    }

    @Test
    fun `preferences store persists and clears active tunnel snapshot for always-on recovery`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VpnPreferencesStore(context)
        val snapshot = ActiveTunnelSnapshot(
            country = "Germany",
            host = "185.216.34.12",
            port = 443,
            protocol = "VLESS (V2Ray)",
            xrayJson = "{\"outbounds\":[{\"tag\":\"proxy\",\"protocol\":\"vless\"}]}",
            tunMode = true,
            enableIpv6 = false,
            dnsMode = "Cloudflare 1.1.1.1",
            routingMode = "Bypass LAN & Domestic",
            perAppProxyEnabled = true,
            bypassSelectedApps = false,
            selectedAppPackages = setOf("com.android.chrome")
        )

        store.saveActiveTunnelSnapshot(snapshot)
        val loaded = store.loadActiveTunnelSnapshot()
        assertEquals(snapshot, loaded)

        store.clearActiveTunnelSnapshot()
        assertNull(store.loadActiveTunnelSnapshot())
    }

    @Test
    fun `generateQrBitmap and decodeQrFromBitmap round-trip shareable VLESS URI`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@185.216.34.12:443?security=reality&pbk=TEST_KEY&sni=www.cloudflare.com#Germany"
        val bitmap = generateQrBitmap(uri, sizePx = 320)
        assertNotNull(bitmap)
        val decoded = decodeQrFromBitmap(bitmap!!)
        assertEquals(uri, decoded)
        bitmap.recycle()
    }

    @Test
    fun `xray service diagnostic log ring buffer appends, caps at 250 entries, and clears`() {
        XrayVpnService.clearLogs()
        assertTrue(XrayVpnService.logs.value.isEmpty())

        for (i in 1..260) {
            XrayVpnService.appendLog("Event #$i")
        }
        val logs = XrayVpnService.logs.value
        assertEquals(250, logs.size)
        assertTrue(logs.first().endsWith("Event #11"))
        assertTrue(logs.last().endsWith("Event #260"))

        XrayVpnService.clearLogs()
        assertTrue(XrayVpnService.logs.value.isEmpty())
    }

    @Test
    fun `buildExportBackupPayload exports URI-backed servers cleanly for re-import`() {
        val parser = V2RayParserAndFletcher()
        val uri1 = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#Germany"
        val uri2 = "trojan://secretpass@nl.example.org:8443?security=tls&type=ws#Netherlands"
        val servers = parser.parseImportPayload("$uri1\n$uri2")

        val exported = VpnViewModel.buildExportBackupPayload(servers, parser)
        val reimported = parser.parseImportPayload(exported)

        assertEquals(2, reimported.size)
        assertEquals("de.example.com", reimported[0].host)
        assertEquals("nl.example.org", reimported[1].host)

        val jsonOnlyServer = listOf(
            ServerEntity(
                countryName = "Finland",
                countryCode = "FI",
                protocolDisplay = "V2Ray · TCP",
                protocolCore = "VLESS (V2Ray)",
                transport = "TCP",
                host = "fi.example.com",
                port = 443,
                uuidOrPassword = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                pingMs = 35
            )
        )
        val exportedJson = VpnViewModel.buildExportBackupPayload(jsonOnlyServer, parser)
        val reparsedJson = parser.parseImportPayload(exportedJson)
        assertEquals(1, reparsedJson.size)
        assertEquals("fi.example.com", reparsedJson.first().host)
    }

    @Test
    fun `default subscription url is empty and legacy public list url is cleared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("", AppSettingsState.DEFAULT_SUBSCRIPTION_URL)
        assertEquals("", AppSettingsState().activeSubscriptionUrl)

        val prefs = context.getSharedPreferences("v2ray_vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                "active_sub_url",
                "https://raw.githubusercontent.com/mirrorrepomagic/free-v2ray-public-list/refs/heads/main/V2Ray-Config-By-EbraSha.txt"
            )
            .apply()

        val loaded = VpnPreferencesStore(context).loadSettings()
        assertEquals("", loaded.activeSubscriptionUrl)
    }

    @Test
    fun `preferences store defaults hasCompletedOnboarding to false and persists completion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("v2ray_vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("has_completed_onboarding").apply()

        val store = VpnPreferencesStore(context)
        assertFalse(store.loadSettings().hasCompletedOnboarding)

        store.saveSettings(store.loadSettings().copy(hasCompletedOnboarding = true))
        assertTrue(store.loadSettings().hasCompletedOnboarding)
    }
}
