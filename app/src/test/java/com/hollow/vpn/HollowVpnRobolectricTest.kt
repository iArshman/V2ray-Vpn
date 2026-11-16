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

    @Test
    fun `preferences store persists smart switch, mirrors, strict tls, domestic region, and speed baseline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VpnPreferencesStore(context)
        val custom = AppSettingsState(
            smartSwitchEnabled = true,
            mirrorsEnabled = true,
            strictTlsEnabled = true,
            domesticCountryCode = "TR"
        )
        store.saveSettings(custom)
        val loaded = store.loadSettings()
        assertTrue(loaded.smartSwitchEnabled)
        assertTrue(loaded.mirrorsEnabled)
        assertTrue(loaded.strictTlsEnabled)
        assertEquals("TR", loaded.domesticCountryCode)

        store.saveBaselineSpeed("wifi", 12.5, nowMillis = 1_000_000L)
        assertEquals(12.5, store.loadBaselineSpeed("wifi", nowMillis = 2_000_000L), 0.1)
        assertEquals(0.0, store.loadBaselineSpeed("cellular", nowMillis = 2_000_000L), 0.01)
        store.invalidateBaseline()
        assertEquals(0.0, store.loadBaselineSpeed("wifi", nowMillis = 2_000_000L), 0.01)
    }

    @Test
    fun `smart switch ignores idle traffic, triggers after 8 stalled ticks, and enforces cooldown and hourly cap`() {
        val switcher = com.hollow.vpn.vpn.SmartSwitch(referenceMbps = 10.0)
        var t = 1_000_000L

        // Idle traffic (low uplink) must never trigger switch
        repeat(20) {
            t += 1_000L
            assertTrue(switcher.onSample(500L, 0L, t) is com.hollow.vpn.vpn.SmartSwitch.Verdict.Stay)
        }

        // Active requests with 0 downlink -> triggers on 8th tick
        for (i in 1..7) {
            t += 1_000L
            assertTrue(switcher.onSample(5_000L, 1_000L, t) is com.hollow.vpn.vpn.SmartSwitch.Verdict.Stay)
        }
        t += 1_000L
        val firstVerdict = switcher.onSample(5_000L, 1_000L, t)
        assertTrue(firstVerdict is com.hollow.vpn.vpn.SmartSwitch.Verdict.Switch)

        // During 45s cooldown, must stay even on stall
        repeat(10) {
            t += 1_000L
            assertTrue(switcher.onSample(5_000L, 0L, t) is com.hollow.vpn.vpn.SmartSwitch.Verdict.Stay)
        }

        // Consume remaining 3 switches within an hour (spaced past 45s cooldown), then 5th must be blocked
        repeat(3) {
            t += 50_000L
            repeat(7) {
                t += 1_000L
                switcher.onSample(5_000L, 100L, t)
            }
            t += 1_000L
            assertTrue(switcher.onSample(5_000L, 100L, t) is com.hollow.vpn.vpn.SmartSwitch.Verdict.Switch)
        }

        t += 50_000L
        repeat(10) {
            t += 1_000L
            assertTrue(switcher.onSample(5_000L, 100L, t) is com.hollow.vpn.vpn.SmartSwitch.Verdict.Stay)
        }
    }

    @Test
    fun `protocol ranker and security policy enforce DPI tiers and strict TLS rules`() {
        val parser = V2RayParserAndFletcher()
        val reality = parser.parseImportPayload("vless://11111111-1111-1111-1111-111111111111@1.1.1.1:443?security=reality&pbk=PK&sni=cf.com#R").first()
        val hy2 = parser.parseImportPayload("hy2://pass@2.2.2.2:443?insecure=1#H").first()
        val trojan = parser.parseImportPayload("trojan://pass@3.3.3.3:443?security=tls#T").first()
        val plainVless = parser.parseImportPayload("vless://11111111-1111-1111-1111-111111111111@4.4.4.4:80?security=none#P").first()
        val socks = parser.parseImportPayload("socks://5.5.5.5:1080#S").first()

        val ordered = com.hollow.vpn.vpn.ProtocolRanker.prioritizeCandidates(listOf(socks, trojan, hy2, reality))
        assertEquals(listOf("Reality", "Hysteria2", "Trojan", "SOCKS5"), ordered.map { it.transport })

        assertTrue(com.hollow.vpn.vpn.SecurityPolicy.isServerSecure(reality))
        assertTrue(com.hollow.vpn.vpn.SecurityPolicy.isServerSecure(trojan))
        assertFalse(com.hollow.vpn.vpn.SecurityPolicy.isServerSecure(hy2))
        assertFalse(com.hollow.vpn.vpn.SecurityPolicy.isServerSecure(plainVless))
        assertFalse(com.hollow.vpn.vpn.SecurityPolicy.isServerSecure(socks))
    }

    @Test
    fun `resilient fetcher builds direct-only or github cdn mirror candidates`() {
        val fetcher = com.hollow.vpn.vpn.ResilientFetcher(okhttp3.OkHttpClient())
        val githubRaw = "https://raw.githubusercontent.com/user/repo/refs/heads/main/sub.txt"

        val directOnly = fetcher.buildRouteCandidates(githubRaw, mirrorsEnabled = false)
        assertEquals(1, directOnly.size)
        assertEquals("direct", directOnly[0].first)

        val withMirrors = fetcher.buildRouteCandidates(githubRaw, mirrorsEnabled = true)
        assertEquals(3, withMirrors.size)
        assertEquals("https://cdn.jsdelivr.net/gh/user/repo@main/sub.txt", withMirrors[1].second)
        assertEquals("https://raw.githack.com/user/repo/main/sub.txt", withMirrors[2].second)
    }
}
