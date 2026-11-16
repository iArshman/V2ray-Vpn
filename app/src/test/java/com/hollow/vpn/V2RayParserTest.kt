package com.hollow.vpn

import android.util.Base64
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.vpn.V2RayParserAndFletcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class V2RayParserTest {

    private val parser = V2RayParserAndFletcher()

    @Test
    fun `parses VLESS Reality URI with public key, sni, shortId, and flow`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@185.216.34.12:443?security=reality&encryption=none&pbk=TEST_PUBLIC_KEY&headerType=none&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.cloudflare.com&sid=ab12cd34#%F0%9F%87%A9%F0%9F%87%AA%20Germany%20Reality"
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("DE", server.countryCode)
        assertEquals("Germany", server.countryName)
        assertEquals("Reality", server.transport)
        assertEquals("185.216.34.12", server.host)
        assertEquals(443, server.port)

        val json = JSONObject(server.rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", proxy.getString("protocol"))
        val user = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("none", user.getString("encryption"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        assertEquals("TEST_PUBLIC_KEY", stream.getJSONObject("realitySettings").getString("publicKey"))
        assertEquals("ab12cd34", stream.getJSONObject("realitySettings").getString("shortId"))
    }

    @Test
    fun `parses Trojan TLS WebSocket URI into servers array with wsSettings`() {
        val uri = "trojan://secretpass@nl.example.org:8443?security=tls&type=ws&host=cdn.example.org&path=%2Fws-tunnel&sni=nl.example.org#Netherlands%20Trojan"
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("NL", server.countryCode)

        val json = JSONObject(server.rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", proxy.getString("protocol"))
        val serversArr = proxy.getJSONObject("settings").getJSONArray("servers")
        assertEquals("nl.example.org", serversArr.getJSONObject(0).getString("address"))
        assertEquals("secretpass", serversArr.getJSONObject(0).getString("password"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("/ws-tunnel", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example.org", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("Host"))
    }

    @Test
    fun `parses Shadowsocks URI into servers array with method and password`() {
        val creds = Base64.encodeToString("aes-256-gcm:my-ss-pass".toByteArray(), Base64.NO_WRAP)
        val uri = "ss://$creds@sg.example.net:8388#Singapore%20SS"
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val json = JSONObject(servers.first().rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", proxy.getString("protocol"))
        val ssServer = proxy.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("aes-256-gcm", ssServer.getString("method"))
        assertEquals("my-ss-pass", ssServer.getString("password"))
        assertEquals(8388, ssServer.getInt("port"))
    }

    @Test
    fun `parses VMess Base64 URI with vnext users alterId and gRPC serviceName`() {
        val vmessPayload = JSONObject()
            .put("v", "2")
            .put("ps", "Japan gRPC")
            .put("add", "jp.example.com")
            .put("port", "443")
            .put("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .put("net", "grpc")
            .put("path", "my-grpc-svc")
            .put("tls", "tls")
            .put("sni", "jp.example.com")
            .toString()
        val uri = "vmess://" + Base64.encodeToString(vmessPayload.toByteArray(), Base64.NO_WRAP)
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val json = JSONObject(servers.first().rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", proxy.getString("protocol"))
        val user = proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals(0, user.getInt("alterId"))
        assertEquals("auto", user.getString("security"))
        assertEquals("my-grpc-svc", proxy.getJSONObject("streamSettings").getJSONObject("grpcSettings").getString("serviceName"))
    }

    @Test
    fun `applyRuntimeSettingsToXrayJson injects DNS, Bypass LAN CIDR routing, and proxy-only inbounds`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#Germany"
        val baseJson = parser.parseImportPayload(uri).first().rawConfigJson

        val settings = AppSettingsState(
            dnsMode = "Quad9 9.9.9.9",
            routingMode = "Bypass LAN",
            tunMode = false,
            ipv6Enabled = true
        )
        val runtime = JSONObject(parser.applyRuntimeSettingsToXrayJson(baseJson, settings))

        val dnsServers = runtime.getJSONObject("dns").getJSONArray("servers")
        assertEquals("9.9.9.9", dnsServers.getString(0))
        assertEquals("UseIP", runtime.getJSONObject("dns").getString("queryStrategy"))

        val rules = runtime.getJSONObject("routing").getJSONArray("rules")
        assertTrue(rules.length() >= 1)
        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"))
        val ipArr = rules.getJSONObject(0).getJSONArray("ip")
        val ips = (0 until ipArr.length()).map { ipArr.getString(it) }
        assertTrue(ips.contains("192.168.0.0/16"))

        val inbounds = runtime.getJSONArray("inbounds")
        val protocols = (0 until inbounds.length()).map { inbounds.getJSONObject(it).getString("protocol") }
        assertFalse(protocols.contains("tun"))
        assertTrue(protocols.contains("socks"))
        assertTrue(protocols.contains("http"))
        assertNotNull(runtime.optJSONObject("stats"))
    }

    @Test
    fun `parses Hysteria2 URI with salamander obfs and port hop into native Xray hysteria outbound`() {
        val uri = "hy2://my-hy2-secret@de.hy2.example.com:8443?insecure=1&sni=cdn.hy2.example.com&obfs=salamander&obfs-password=salpass&mport=20000-30000#Germany%20Hy2"
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("DE", server.countryCode)
        assertEquals("Hysteria2 (Xray)", server.protocolCore)
        assertEquals("Hysteria2", server.transport)

        val json = JSONObject(server.rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria", proxy.getString("protocol"))
        assertEquals(2, proxy.getJSONObject("settings").getInt("version"))
        assertEquals("de.hy2.example.com", proxy.getJSONObject("settings").getString("address"))
        assertEquals(8443, proxy.getJSONObject("settings").getInt("port"))

        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("hysteria", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        assertEquals("my-hy2-secret", stream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals("h3", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertTrue(stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"))

        val udpMasks = stream.getJSONObject("finalmask").getJSONArray("udp")
        assertEquals(2, udpMasks.length())
        assertEquals("salamander", udpMasks.getJSONObject(0).getString("type"))
        assertEquals("udphop", udpMasks.getJSONObject(1).getString("type"))

        val reparsed = parser.parseRawXrayJson(server.rawConfigJson)
        assertEquals(1, reparsed.size)
        assertEquals("Hysteria2 (Xray)", reparsed.first().protocolCore)
        assertEquals("my-hy2-secret", reparsed.first().uuidOrPassword)
    }

    @Test
    fun `rejects unsupported tuic URIs instead of misrouting as VLESS`() {
        val uri = "tuic://uuid:pass@tuic.example.com:443?alpn=h3#Unsupported"
        val servers = parser.parseImportPayload(uri)
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `applyRuntimeSettingsToXrayJson reorders proxy outbound first and injects IPv6 block, QUIC block, and catch-all`() {
        val invertedJson = JSONObject()
            .put(
                "outbounds",
                org.json.JSONArray()
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                    .put(
                        JSONObject()
                            .put("tag", "my-vless")
                            .put("protocol", "vless")
                            .put("settings", JSONObject())
                    )
            )
            .toString()

        val settings = AppSettingsState(
            routingMode = "Global",
            tunMode = true,
            ipv6Enabled = false
        )
        val runtime = JSONObject(parser.applyRuntimeSettingsToXrayJson(invertedJson, settings))

        val outbounds = runtime.getJSONArray("outbounds")
        assertEquals("my-vless", outbounds.getJSONObject(0).getString("tag"))
        assertEquals("vless", outbounds.getJSONObject(0).getString("protocol"))
        assertEquals("UseIPv4", runtime.getJSONObject("dns").getString("queryStrategy"))

        val rules = runtime.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3, rules.length())
        assertEquals("block", rules.getJSONObject(0).getString("outboundTag"))
        assertEquals("::/0", rules.getJSONObject(0).getJSONArray("ip").getString(0))
        assertEquals("block", rules.getJSONObject(1).getString("outboundTag"))
        assertEquals("443", rules.getJSONObject(1).getString("port"))
        assertEquals("udp", rules.getJSONObject(1).getString("network"))
        assertEquals("my-vless", rules.getJSONObject(2).getString("outboundTag"))
        assertEquals("tcp,udp", rules.getJSONObject(2).getString("network"))
    }

    @Test
    fun `applyRuntimeSettingsToXrayJson injects CGNAT and domestic reserved CIDRs for Bypass LAN & Domestic`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#Germany"
        val baseJson = parser.parseImportPayload(uri).first().rawConfigJson

        val settings = AppSettingsState(routingMode = "Bypass LAN & Domestic")
        val runtime = JSONObject(parser.applyRuntimeSettingsToXrayJson(baseJson, settings))
        val rules = runtime.getJSONObject("routing").getJSONArray("rules")
        assertEquals("direct", rules.getJSONObject(0).getString("outboundTag"))

        val ipArr = rules.getJSONObject(0).getJSONArray("ip")
        val ips = (0 until ipArr.length()).map { ipArr.getString(it) }
        assertTrue(ips.contains("192.168.0.0/16"))
        assertTrue(ips.contains("100.64.0.0/10"))
        assertTrue(ips.contains("198.18.0.0/15"))
    }

    @Test
    fun `selectDiverseCandidatePool preserves distinct CDN endpoints and filters malformed Reality nodes`() {
        val cdnNodeOne = ServerEntity(
            countryName = "Germany",
            countryCode = "DE",
            protocolDisplay = "V2Ray · WebSocket",
            protocolCore = "VLESS (V2Ray)",
            transport = "WebSocket",
            host = "104.16.10.20",
            port = 443,
            uuidOrPassword = "11111111-1111-1111-1111-111111111111",
            sni = "cdn-one.example.org",
            pingMs = 0
        )
        val cdnNodeTwo = cdnNodeOne.copy(
            uuidOrPassword = "22222222-2222-2222-2222-222222222222",
            sni = "cdn-two.example.org"
        )
        val brokenReality = ServerEntity(
            countryName = "Netherlands",
            countryCode = "NL",
            protocolDisplay = "V2Ray · Reality",
            protocolCore = "VLESS (V2Ray)",
            transport = "Reality",
            host = "185.10.20.30",
            port = 443,
            uuidOrPassword = "33333333-3333-3333-3333-333333333333",
            publicKey = "",
            pingMs = 0
        )

        val selected = parser.selectDiverseCandidatePool(
            listOf(cdnNodeOne, cdnNodeOne.copy(), cdnNodeTwo, brokenReality),
            maxCandidates = 150
        )

        assertEquals(2, selected.size)
        assertTrue(selected.any { it.sni == "cdn-one.example.org" })
        assertTrue(selected.any { it.sni == "cdn-two.example.org" })
    }

    @Test
    fun `parses WireGuard URI and round-trips through parseRawXrayJson`() {
        val uri = "wireguard://PRIV_KEY_SECRET@198.51.100.10:51820?publickey=PEER_PUB_KEY&address=172.16.0.2/32&mtu=1380&reserved=1,2,3#Germany%20WG"
        val servers = parser.parseImportPayload(uri)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals("WireGuard (Xray)", server.protocolCore)
        assertEquals("WireGuard", server.transport)
        assertEquals(51820, server.port)

        val json = JSONObject(server.rawConfigJson)
        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("wireguard", proxy.getString("protocol"))
        val wgSettings = proxy.getJSONObject("settings")
        assertEquals("PRIV_KEY_SECRET", wgSettings.getString("secretKey"))
        assertEquals(1380, wgSettings.getInt("mtu"))
        assertEquals("198.51.100.10:51820", wgSettings.getJSONArray("peers").getJSONObject(0).getString("endpoint"))
        assertEquals("PEER_PUB_KEY", wgSettings.getJSONArray("peers").getJSONObject(0).getString("publicKey"))

        val reparsed = parser.parseRawXrayJson(server.rawConfigJson)
        assertEquals(1, reparsed.size)
        assertEquals("WireGuard (Xray)", reparsed.first().protocolCore)
        assertEquals("PRIV_KEY_SECRET", reparsed.first().uuidOrPassword)
    }

    @Test
    fun `parses SOCKS5 URI and VLESS xhttp transport`() {
        val creds = Base64.encodeToString("alice:wonder".toByteArray(), Base64.NO_WRAP)
        val socksUri = "socks://$creds@203.0.113.5:1080#Germany%20Socks"
        val xhttpUri = "vless://11111111-2222-3333-4444-555555555555@nl.example.org:443?security=tls&type=xhttp&host=cdn.nl.example.org&path=%2Fxh#Netherlands%20XHTTP"

        val parsed = parser.parseImportPayload("https://example.com/ignore-me\n$socksUri\n$xhttpUri")
        assertEquals(2, parsed.size)

        val socks = parsed[0]
        assertEquals("SOCKS5 (Xray)", socks.protocolCore)
        assertEquals(1080, socks.port)
        val socksOutbound = JSONObject(socks.rawConfigJson).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", socksOutbound.getString("protocol"))
        val socksUser = socksOutbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0)
        assertEquals("alice", socksUser.getString("user"))
        assertEquals("wonder", socksUser.getString("pass"))

        val xhttp = parsed[1]
        assertEquals("XHTTP", xhttp.transport)
        val xhttpStream = JSONObject(xhttp.rawConfigJson).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        assertEquals("xhttp", xhttpStream.getString("network"))
        assertEquals("/xh", xhttpStream.getJSONObject("xhttpSettings").getString("path"))
    }

    @Test
    fun `applyRuntimeSettingsToXrayJson emits geoip and geosite rules when geo assets exist`() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@de.example.com:443?security=tls&type=tcp#Germany"
        val baseJson = parser.parseImportPayload(uri).first().rawConfigJson

        val irRuntime = JSONObject(
            parser.applyRuntimeSettingsToXrayJson(
                baseJson = baseJson,
                settings = AppSettingsState(routingMode = "Bypass LAN & Domestic", domesticCountryCode = "IR"),
                hasGeoAssets = true
            )
        )
        val routing = irRuntime.getJSONObject("routing")
        assertEquals("IPIfNonMatch", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules")
        val ipList = (0 until rules.getJSONObject(0).getJSONArray("ip").length()).map {
            rules.getJSONObject(0).getJSONArray("ip").getString(it)
        }
        assertTrue(ipList.contains("geoip:private"))
        assertTrue(ipList.contains("geoip:ir"))

        val domainList = (0 until rules.getJSONObject(1).getJSONArray("domain").length()).map {
            rules.getJSONObject(1).getJSONArray("domain").getString(it)
        }
        assertTrue(domainList.contains("geosite:private"))
        assertTrue(domainList.contains("geosite:category-ir"))

        val cnRuntime = JSONObject(
            parser.applyRuntimeSettingsToXrayJson(
                baseJson = baseJson,
                settings = AppSettingsState(routingMode = "Bypass LAN & Domestic", domesticCountryCode = "CN"),
                hasGeoAssets = true
            )
        )
        val cnDomains = cnRuntime.getJSONObject("routing").getJSONArray("rules").getJSONObject(1).getJSONArray("domain")
        val cnDomainList = (0 until cnDomains.length()).map { cnDomains.getString(it) }
        assertTrue(cnDomainList.contains("geosite:cn"))
    }
}
