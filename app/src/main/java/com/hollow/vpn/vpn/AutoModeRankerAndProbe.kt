package com.hollow.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.data.SubscriptionEntity
import com.hollow.vpn.data.VpnPreferencesStore
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.random.Random

/**
 * Protocol & DPI-resilience ranker inspired by v2rayV's AutoModeRanker:
 * - TCP reachability drops dead hosts, NEVER ranks them (avoids Cloudflare CDN edge bias).
 * - Candidates are grouped by protocol tier and shuffled within each tier so every run explores.
 */
object ProtocolRanker {

    fun protocolTier(server: ServerEntity): Int {
        val core = server.protocolCore.lowercase()
        val transport = server.transport.lowercase()
        val raw = server.rawConfigJson.lowercase()
        return when {
            core.contains("vless") && (
                transport == "reality" ||
                    server.publicKey.isNotBlank() ||
                    raw.contains("xtls-rprx-vision")
                ) -> 0
            core.contains("hysteria") -> 1
            core.contains("vless") && (transport == "xhttp" || transport == "splithttp") -> 1
            core.contains("vless") -> 2
            core.contains("trojan") -> 3
            core.contains("vmess") || core.contains("shadowsocks") -> 4
            core.contains("wireguard") -> 5
            core.contains("socks") || core.contains("http") -> 6
            else -> 5
        }
    }

    fun prioritizeCandidates(
        servers: List<ServerEntity>,
        random: Random = Random.Default
    ): List<ServerEntity> {
        return servers
            .map { it.copy(protocolTier = protocolTier(it)) }
            .shuffled(random)
            .sortedBy { it.protocolTier }
    }
}

/**
 * Enforces strict transport security when `strictTlsEnabled` is enabled:
 * rejects `allowInsecure=true`, plaintext VLESS/Trojan/SOCKS, and non-AEAD ciphers.
 */
object SecurityPolicy {

    private val NON_AEAD_MARKERS = setOf("none", "plain", "zero", "rc4", "rc4-md5", "des", "bf-cfb")

    fun isServerSecure(server: ServerEntity): Boolean {
        val core = server.protocolCore.lowercase()
        if (core.contains("socks") || core.contains("http")) return false
        if (core.contains("wireguard") || core.contains("hysteria")) {
            return !hasAllowInsecure(server)
        }
        if (hasAllowInsecure(server)) return false

        val raw = server.rawConfigJson.lowercase()
        val transport = server.transport.lowercase()
        val hasTlsOrReality = transport == "reality" ||
            server.publicKey.isNotBlank() ||
            raw.contains("\"security\": \"tls\"") ||
            raw.contains("\"security\":\"tls\"") ||
            raw.contains("\"security\": \"reality\"") ||
            raw.contains("\"security\":\"reality\"")

        if (core.contains("vless") || core.contains("trojan")) {
            return hasTlsOrReality
        }
        if (core.contains("shadowsocks")) {
            val method = server.uuidOrPassword.substringBefore(":").trim().lowercase()
            return method !in NON_AEAD_MARKERS
        }
        if (core.contains("vmess")) {
            if (hasTlsOrReality) return true
            return !NON_AEAD_MARKERS.any { marker ->
                raw.contains("\"security\": \"$marker\"") || raw.contains("\"security\":\"$marker\"")
            }
        }
        return true
    }

    private fun hasAllowInsecure(server: ServerEntity): Boolean {
        val uri = server.shareUri.lowercase()
        if (uri.contains("allowinsecure=1") || uri.contains("allowinsecure=true") ||
            uri.contains("insecure=1") || uri.contains("insecure=true")
        ) {
            return true
        }
        val raw = server.rawConfigJson.lowercase()
        return raw.contains("\"allowinsecure\": true") || raw.contains("\"allowinsecure\":true")
    }
}

/**
 * Beta/Thompson-inspired ordering for multiple subscription sources so reliable feeds
 * are pulled first without ever starving newly added feeds.
 */
object BetaSourceScorer {

    fun orderSources(
        sources: List<SubscriptionEntity>,
        random: Random = Random.Default
    ): List<SubscriptionEntity> {
        return sources
            .filter { it.isEnabled && it.url.isNotBlank() }
            .sortedByDescending { sub ->
                val alpha = (sub.successPulls + 1).toDouble()
                val beta = (sub.failedPulls + 1).toDouble()
                sampleBeta(alpha, beta, random)
            }
    }

    private fun sampleBeta(alpha: Double, beta: Double, random: Random): Double {
        val x = sampleGamma(alpha, random)
        val y = sampleGamma(beta, random)
        val sum = x + y
        return if (sum <= 0.0) 0.5 else x / sum
    }

    private fun sampleGamma(shape: Double, random: Random): Double {
        if (shape < 1.0) {
            val u = random.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
            return sampleGamma(shape + 1.0, random) * Math.pow(u, 1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / kotlin.math.sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                val u1 = random.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
                val u2 = random.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
                x = kotlin.math.sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v = v * v * v
            val u = random.nextDouble().coerceIn(1e-9, 1.0 - 1e-9)
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }
}

/**
 * Single-stream throughput probe & baseline tracker.
 * Both the user's direct line baseline and each candidate server use the exact same probe
 * so `serverMbps / baselineMbps` is an apples-to-apples ratio.
 */
object ThroughputProbe {

    private const val PROBE_URL = "https://speed.cloudflare.com/__down?bytes=1500000"
    private const val MAX_PROBE_MILLIS = 2_600L
    const val ACCEPT_BASELINE_FRACTION = 0.70

    fun currentNetworkKey(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val active = cm.activeNetwork ?: return "offline"
            val caps = cm.getNetworkCapabilities(active) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    fun measureOrLoadBaselineMbps(context: Context): Double {
        val store = VpnPreferencesStore(context)
        val key = currentNetworkKey(context)
        val cached = store.loadBaselineSpeed(key)
        if (cached > 0.0) return cached

        val measured = measureSingleStreamMbps(socksPort = null)
        if (measured > 0.0) {
            store.saveBaselineSpeed(key, measured)
        }
        return measured
    }

    /**
     * Starts a throwaway Xray core bound to a random local SOCKS5 port and runs one
     * single-stream download through it, then cleanly stops the core.
     */
    fun measureServerThroughputMbps(
        context: Context,
        server: ServerEntity,
        parser: V2RayParserAndFletcher,
        settings: AppSettingsState
    ): Double {
        val socksPort = findFreePort() ?: return 0.0
        val baseJson = server.rawConfigJson.ifBlank { parser.generateXrayJsonForServer(server) }
        val socksConfig = buildSocksProbeConfig(
            baseJson = parser.applyRuntimeSettingsToXrayJson(baseJson, settings.copy(tunMode = false)),
            socksPort = socksPort
        ) ?: return 0.0

        val controller = try {
            Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(level: Long, message: String?): Long = 0
            })
        } catch (_: Throwable) {
            return 0.0
        }

        return try {
            controller.startLoop(socksConfig, 0)
            var waited = 0
            while (!controller.isRunning && waited < 1500) {
                Thread.sleep(50)
                waited += 50
            }
            if (!controller.isRunning) return 0.0
            measureSingleStreamMbps(socksPort = socksPort)
        } catch (_: Throwable) {
            0.0
        } finally {
            try {
                controller.stopLoop()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun measureSingleStreamMbps(socksPort: Int?): Double {
        val builder = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(MAX_PROBE_MILLIS + 1500L, TimeUnit.MILLISECONDS)
        if (socksPort != null) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        }
        val client = builder.build()
        val request = Request.Builder()
            .url(PROBE_URL)
            .header("User-Agent", "HollowVPN/1.0")
            .build()

        return try {
            val startNs = System.nanoTime()
            var totalBytes = 0L
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return 0.0
                val stream = resp.body?.byteStream() ?: return 0.0
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    if (elapsedMs >= MAX_PROBE_MILLIS) break
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    totalBytes += read
                }
            }
            val elapsedSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            if (totalBytes < 16 * 1024) return 0.0
            // Return MB/s (megabytes per second) matching SmartSwitch's referenceMbps units
            (totalBytes / 1_048_576.0) / elapsedSec
        } catch (_: Exception) {
            0.0
        }
    }

    private fun findFreePort(): Int? = try {
        ServerSocket(0).use { it.localPort }
    } catch (_: Exception) {
        null
    }

    private fun buildSocksProbeConfig(baseJson: String, socksPort: Int): String? {
        return try {
            val root = JSONObject(baseJson)
            root.remove("stats")
            root.remove("policy")
            val socksInbound = JSONObject()
                .put("tag", "socks-probe")
                .put("port", socksPort)
                .put("listen", "127.0.0.1")
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            root.put("inbounds", JSONArray().put(socksInbound))
            root.toString()
        } catch (_: Exception) {
            null
        }
    }
}
