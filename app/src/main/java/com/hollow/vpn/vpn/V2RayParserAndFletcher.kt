package com.hollow.vpn.vpn

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.AppUpdateState
import com.hollow.vpn.data.FetchingProgressState
import com.hollow.vpn.data.FetchingStage
import com.hollow.vpn.data.ServerEntity
import com.hollow.vpn.data.SubscriptionEntity
import go.Seq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.system.measureTimeMillis

sealed class XrayProbeResult {
    data class Success(val delayMs: Int) : XrayProbeResult()
    data object NativeUnavailable : XrayProbeResult()
    data object Unreachable : XrayProbeResult()
}

data class PublicIpInfo(
    val ip: String,
    val country: String,
    val countryCode: String,
    val isp: String
)

/**
 * Production V2Ray / Xray multi-protocol parser and Fletcher Auto-Fetcher.
 * Uses ResilientFetcher (direct -> optional CDN mirrors -> disk cache), Beta source scoring,
 * TCP reachability filtering (drop-only, never ranked), DPI-resilient protocol tier ranking,
 * real Xray delay + serialized single-stream throughput probing, and HTTPS-only GeoIP enrichment.
 */
class V2RayParserAndFletcher {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val geoHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val resilientFetcher = ResilientFetcher(httpClient)
    private val resolvedIpCache = ConcurrentHashMap<String, String>()
    private val geoIpCache = ConcurrentHashMap<String, Pair<String, String>>()

    @Volatile
    private var coreEnvInitialized = false

    fun evictConnectionPools() {
        try {
            httpClient.connectionPool.evictAll()
            geoHttpClient.connectionPool.evictAll()
        } catch (_: Exception) {
        }
    }

    suspend fun fetchSubscriptionAndRankTop15(
        subscriptionUrl: String,
        context: Context? = null,
        settings: AppSettingsState = AppSettingsState(),
        onProgressState: (FetchingProgressState) -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): Result<List<ServerEntity>> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = subscriptionUrl.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Subscription link must start with https:// or http://"
            }

            runStreamingFetchAndBenchmarkPipeline(
                urls = listOf(cleanUrl),
                subscriptions = emptyList(),
                context = context,
                settings = settings,
                maxCandidates = 340,
                onProgressState = onProgressState,
                onProgress = onProgress
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMultipleSubscriptionsAndRankTop15(
        subscriptions: List<SubscriptionEntity>,
        fallbackUrls: List<String> = emptyList(),
        context: Context? = null,
        settings: AppSettingsState = AppSettingsState(),
        onProgressState: (FetchingProgressState) -> Unit = {},
        onProgress: (String) -> Unit = {},
        onSubscriptionStats: suspend (SubscriptionEntity, Boolean, Int) -> Unit = { _, _, _ -> }
    ): Result<List<ServerEntity>> = withContext(Dispatchers.IO) {
        try {
            val orderedSubs = BetaSourceScorer.orderSources(subscriptions).take(8)
            val fallback = if (orderedSubs.isEmpty()) fallbackUrls.distinct().take(8) else emptyList()

            runStreamingFetchAndBenchmarkPipeline(
                urls = fallback,
                subscriptions = orderedSubs,
                context = context,
                settings = settings,
                maxCandidates = 380,
                onProgressState = onProgressState,
                onProgress = onProgress,
                onSubscriptionStats = onSubscriptionStats
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Concurrent Producer-Consumer Pipeline:
     * - Producer(s) stream/download feeds in parallel and immediately push subnet-diverse nodes into [rawChannel].
     * - 48 TCP pre-filter workers immediately test reachability and stream live survivors into [xrayChannel].
     * - 24 Xray workers verify real proxy handshakes simultaneously while feeds are still downloading.
     * - Speed testing (top 3) + GeoIP resolution (Semaphore 20 + in-memory cache) execute concurrently at the end.
     */
    private suspend fun runStreamingFetchAndBenchmarkPipeline(
        urls: List<String>,
        subscriptions: List<SubscriptionEntity>,
        context: Context?,
        settings: AppSettingsState,
        maxCandidates: Int,
        onProgressState: (FetchingProgressState) -> Unit,
        onProgress: (String) -> Unit,
        onSubscriptionStats: suspend (SubscriptionEntity, Boolean, Int) -> Unit = { _, _, _ -> }
    ): Result<List<ServerEntity>> = coroutineScope {
        val baselineJob = if (context != null) {
            async(Dispatchers.IO) { ThroughputProbe.measureOrLoadBaselineMbps(context) }
        } else null

        val rawChannel = Channel<ServerEntity>(capacity = 512)
        val xrayChannel = Channel<ServerEntity>(capacity = 256)

        val parsedCount = AtomicInteger(0)
        val queuedCount = AtomicInteger(0)
        val probedCount = AtomicInteger(0)
        val reachableCount = AtomicInteger(0)
        val xrayQueuedCount = AtomicInteger(0)
        val testedCount = AtomicInteger(0)
        val workingCount = AtomicInteger(0)
        val fastestPing = AtomicInteger(0)
        val lastEmitMs = AtomicLong(0L)
        val nativeMissing = AtomicBoolean(false)
        val fetchDone = AtomicBoolean(false)

        val seenKeys = ConcurrentHashMap.newKeySet<String>()
        val subnetCounts = ConcurrentHashMap<String, AtomicInteger>()
        val countryHintCounts = ConcurrentHashMap<String, AtomicInteger>()
        val cfSniSeen = ConcurrentHashMap.newKeySet<String>()
        val workingServers = ConcurrentLinkedQueue<ServerEntity>()
        val discoveredCountries = ConcurrentHashMap<String, String>()

        fun updateDiscoveredCountry(code: String?, name: String?) {
            val cleanCode = code?.trim()?.uppercase().orEmpty()
            if (cleanCode.length == 2 && cleanCode != "XX") {
                val resolvedName = name?.takeIf { it.isNotBlank() && !it.startsWith("Resolving", ignoreCase = true) }
                    ?: countryNameFromIsoCode(cleanCode)
                discoveredCountries.putIfAbsent(cleanCode, resolvedName)
            }
        }

        fun emitProgress(
            stage: FetchingStage,
            headline: String,
            detail: String,
            force: Boolean = false
        ) {
            val nowMs = System.currentTimeMillis()
            val prev = lastEmitMs.get()
            if (!force && nowMs - prev < 110L) return
            if (!force && !lastEmitMs.compareAndSet(prev, nowMs)) return
            if (force) lastEmitMs.set(nowMs)

            val countriesSnapshot = discoveredCountries.entries
                .take(10)
                .map { it.key to it.value }

            val targetTotal = maxOf(
                xrayQueuedCount.get(),
                queuedCount.get().coerceAtMost(maxCandidates)
            ).coerceAtLeast(1)

            onProgress(detail)
            onProgressState(
                FetchingProgressState(
                    isActive = true,
                    stage = stage,
                    headline = headline,
                    detail = detail,
                    parsedCount = parsedCount.get(),
                    probedCount = probedCount.get(),
                    reachableCount = reachableCount.get(),
                    testedCount = testedCount.get(),
                    workingCount = workingCount.get(),
                    targetTotalCount = targetTotal,
                    countryCount = discoveredCountries.size,
                    fastestPingMs = fastestPing.get(),
                    discoveredCountries = countriesSnapshot
                )
            )
        }

        emitProgress(
            stage = FetchingStage.DOWNLOADING,
            headline = "Fetching & testing live",
            detail = "Connecting to subscription feed…",
            force = true
        )

        var lastFetchError: Throwable? = null
        val feedItems: List<Pair<String, SubscriptionEntity?>> = if (subscriptions.isNotEmpty()) {
            subscriptions.map { it.url to it }
        } else {
            urls.map { it to null }
        }

        suspend fun processStreamedBatch(batchText: String, subUrl: String): Int {
            val rawParsed = parseImportPayload(
                rawInput = batchText,
                subscriptionUrl = subUrl,
                isCustom = false
            )
            if (rawParsed.isEmpty()) return 0

            val validNodes = rawParsed.filter { server ->
                server.host.isNotBlank() &&
                    !server.host.contains(" ") &&
                    server.port in 1..65535 &&
                    server.uuidOrPassword.isNotBlank() &&
                    !(server.transport.equals("Reality", ignoreCase = true) && server.publicKey.isBlank())
            }
            if (validNodes.isEmpty()) return 0

            val policyFiltered = if (settings.strictTlsEnabled) {
                validNodes.filter { SecurityPolicy.isServerSecure(it) }.ifEmpty { validNodes }
            } else {
                validNodes
            }

            val prioritized = ProtocolRanker.prioritizeCandidates(policyFiltered)
            var acceptedInBatch = 0

            for (candidate in prioritized) {
                parsedCount.incrementAndGet()
                val key = "${candidate.protocolCore.lowercase()}:${candidate.host.lowercase()}:${candidate.port}:${candidate.uuidOrPassword}:${candidate.sni.lowercase()}"
                if (!seenKeys.add(key)) continue

                if (queuedCount.get() >= maxCandidates) continue

                val isCf = isCloudflareAnycastHost(candidate.host)
                if (isCf) {
                    val cfKey = "${candidate.sni.ifBlank { candidate.host }}:${candidate.uuidOrPassword}"
                    if (!cfSniSeen.add(cfKey)) continue
                    if (cfSniSeen.size > 36) continue
                } else {
                    val parts = candidate.host.split(".")
                    val subnetKey = if (parts.size == 4 && parts[0].toIntOrNull() != null) {
                        "${parts[0]}.${parts[1]}"
                    } else {
                        candidate.host.takeLast(8).lowercase()
                    }
                    val countForSubnet = subnetCounts.computeIfAbsent(subnetKey) { AtomicInteger(0) }.incrementAndGet()
                    if (countForSubnet > 8) continue
                }

                val hintCode = candidate.countryCode.takeIf { it.length == 2 && it != "XX" }
                    ?: detectExplicitCountryCode("${candidate.countryName} ${candidate.sni} ${candidate.host}")
                if (hintCode != null) {
                    val hintCount = countryHintCounts.computeIfAbsent(hintCode) { AtomicInteger(0) }.incrementAndGet()
                    if (hintCount > 18) continue
                }

                queuedCount.incrementAndGet()
                acceptedInBatch++
                rawChannel.send(candidate)
            }

            emitProgress(
                stage = if (testedCount.get() > 0) FetchingStage.TESTING_PIPELINE else FetchingStage.DOWNLOADING,
                headline = if (testedCount.get() > 0) "Fetching & testing simultaneously" else "Streaming server list…",
                detail = "Parsed ${parsedCount.get()} · Testing ${queuedCount.get()} · ${workingCount.get()} ready"
            )
            return acceptedInBatch
        }

        val producerJob = launch(Dispatchers.IO) {
            try {
                val feedSem = Semaphore(4)
                feedItems.map { (feedUrl, subEntity) ->
                    async(Dispatchers.IO) {
                        feedSem.withPermit {
                            val outcome = resilientFetcher.fetchSubscriptionStreaming(
                                url = feedUrl,
                                context = context,
                                mirrorsEnabled = settings.mirrorsEnabled,
                                onRouteSelected = { route ->
                                    val msg = if (route == "local-cache") {
                                        "Loaded cached feed · testing live nodes…"
                                    } else {
                                        "Streaming feed ($route) · testing in parallel…"
                                    }
                                    emitProgress(FetchingStage.TESTING_PIPELINE, "Fetching & testing live", msg)
                                },
                                onBatchText = { batch ->
                                    processStreamedBatch(batch, feedUrl)
                                }
                            )
                            outcome.fold(
                                onSuccess = { fetched ->
                                    if (subEntity != null) {
                                        onSubscriptionStats(subEntity, true, parsedCount.get())
                                    }
                                    if (fetched.fromCache) {
                                        emitProgress(
                                            FetchingStage.TESTING_PIPELINE,
                                            "Testing cached servers",
                                            "Using cached list · verifying ${queuedCount.get()} servers…"
                                        )
                                    }
                                },
                                onFailure = { err ->
                                    lastFetchError = err
                                    if (subEntity != null) {
                                        onSubscriptionStats(subEntity, false, 0)
                                    }
                                }
                            )
                        }
                    }
                }.awaitAll()
            } finally {
                fetchDone.set(true)
                rawChannel.close()
            }
        }

        // Consumer Pool 1: 48 parallel TCP pre-filter workers feed live survivors straight into xrayChannel
        val tcpWorkersJob = launch(Dispatchers.IO) {
            try {
                val tcpWorkers = List(48) {
                    launch(Dispatchers.IO) {
                        for (server in rawChannel) {
                            val isUdp = server.protocolCore.contains("Hysteria", ignoreCase = true) ||
                                server.protocolCore.contains("WireGuard", ignoreCase = true)

                            if (isUdp) {
                                val done = probedCount.incrementAndGet()
                                val alive = reachableCount.incrementAndGet()
                                if (xrayQueuedCount.incrementAndGet() <= 160 && workingCount.get() < 90) {
                                    xrayChannel.send(server)
                                }
                                if (done % 8 == 0) {
                                    emitProgress(
                                        FetchingStage.TESTING_PIPELINE,
                                        "Fetching & testing live",
                                        "Probed $done · $alive reachable · ${workingCount.get()} verified"
                                    )
                                }
                                continue
                            }

                            val livePing = measureRealTcpLatencyOrNull(
                                host = server.host,
                                port = server.port,
                                timeoutMs = 850
                            )
                            val done = probedCount.incrementAndGet()
                            if (livePing != null) {
                                val alive = reachableCount.incrementAndGet()
                                val qIdx = xrayQueuedCount.incrementAndGet()
                                if (qIdx <= 160 && workingCount.get() < 90) {
                                    xrayChannel.send(server.copy(pingMs = livePing))
                                }
                                if (done % 10 == 0) {
                                    emitProgress(
                                        FetchingStage.TESTING_PIPELINE,
                                        "Fetching & testing live",
                                        "Probed $done · $alive reachable · ${workingCount.get()} verified"
                                    )
                                }
                            } else if (done % 15 == 0) {
                                emitProgress(
                                    FetchingStage.TESTING_PIPELINE,
                                    "Fetching & testing live",
                                    "Probed $done · ${reachableCount.get()} reachable · ${workingCount.get()} verified"
                                )
                            }
                        }
                    }
                }
                tcpWorkers.forEach { it.join() }
            } finally {
                xrayChannel.close()
            }
        }

        // Consumer Pool 2: 24 parallel Xray handshake workers run simultaneously with download & TCP probing
        val now = System.currentTimeMillis()
        val xrayWorkersJob = launch(Dispatchers.IO) {
            val xrayWorkers = List(24) {
                launch(Dispatchers.IO) {
                    for (server in xrayChannel) {
                        if (workingCount.get() >= 90) {
                            testedCount.incrementAndGet()
                            continue
                        }

                        if (context == null) {
                            testedCount.incrementAndGet()
                            if (server.pingMs > 0) {
                                workingServers.add(server.copy(lastAliveAt = now, failCount = 0))
                                workingCount.incrementAndGet()
                                updateDiscoveredCountry(
                                    server.countryCode.takeIf { it != "XX" } ?: detectExplicitCountryCode(server.sni.ifBlank { server.host }),
                                    server.countryName
                                )
                            }
                            continue
                        }

                        if (nativeMissing.get()) {
                            testedCount.incrementAndGet()
                            if (server.pingMs > 0) {
                                workingServers.add(server.copy(lastAliveAt = now))
                                workingCount.incrementAndGet()
                            }
                            continue
                        }

                        val result = measureRealXrayDelayOrNull(
                            context = context,
                            server = server,
                            settings = settings,
                            tryBackupOnFailure = false
                        )
                        val tested = testedCount.incrementAndGet()
                        when (result) {
                            is XrayProbeResult.Success -> {
                                val working = workingCount.incrementAndGet()
                                val verifiedEntity = server.copy(
                                    pingMs = result.delayMs,
                                    lastAliveAt = now,
                                    failCount = 0
                                )
                                workingServers.add(verifiedEntity)

                                // Track fastest ping live
                                while (true) {
                                    val curFast = fastestPing.get()
                                    if (curFast != 0 && curFast <= result.delayMs) break
                                    if (fastestPing.compareAndSet(curFast, result.delayMs)) break
                                }

                                val hintCode = server.countryCode.takeIf { it.length == 2 && it != "XX" }
                                    ?: detectExplicitCountryCode("${server.countryName} ${server.sni} ${server.host}")
                                updateDiscoveredCountry(hintCode, server.countryName)

                                emitProgress(
                                    stage = FetchingStage.TESTING_PIPELINE,
                                    headline = if (fetchDone.get()) "Verifying Xray handshakes" else "Fetching & testing simultaneously",
                                    detail = "Verified $working working ($tested tested) · Fastest ${fastestPing.get()} ms"
                                )
                            }

                            XrayProbeResult.NativeUnavailable -> {
                                nativeMissing.set(true)
                                if (server.pingMs > 0) {
                                    workingServers.add(server.copy(lastAliveAt = now))
                                    workingCount.incrementAndGet()
                                }
                            }

                            XrayProbeResult.Unreachable -> {
                                if (tested % 8 == 0) {
                                    emitProgress(
                                        stage = FetchingStage.TESTING_PIPELINE,
                                        headline = if (fetchDone.get()) "Verifying Xray handshakes" else "Fetching & testing simultaneously",
                                        detail = "Tested $tested nodes · ${workingCount.get()} working"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            xrayWorkers.forEach { it.join() }
        }

        producerJob.join()
        tcpWorkersJob.join()
        xrayWorkersJob.join()

        if (parsedCount.get() == 0) {
            return@coroutineScope Result.failure(
                lastFetchError ?: IllegalArgumentException("No valid server nodes found at this URL.")
            )
        }

        val verifiedCandidates = workingServers
            .toList()
            .distinctBy { "${it.protocolCore.lowercase()}:${it.host.lowercase()}:${it.port}:${it.uuidOrPassword}:${it.sni.lowercase()}" }
            .sortedWith(
                compareBy<ServerEntity> { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                    .thenBy { it.protocolTier }
            )
            .take(120)

        if (verifiedCandidates.isEmpty()) {
            return@coroutineScope Result.failure(
                IllegalStateException(
                    "Tested ${queuedCount.get()} nodes from subscription, but none passed proxy connectivity checks."
                )
            )
        }

        emitProgress(
            stage = FetchingStage.SPEED_AND_GEO,
            headline = "Checking speed & locations",
            detail = "Resolving flags & measuring speed for ${verifiedCandidates.size} working servers…",
            force = true
        )

        val finalRanked = enrichSpeedAndGeoConcurrently(
            verified = verifiedCandidates,
            baselineJob = baselineJob,
            nativeUnavailable = nativeMissing.get(),
            context = context,
            settings = settings,
            maxPerCountry = 4,
            now = now
        )

        finalRanked.forEach { updateDiscoveredCountry(it.countryCode, it.countryName) }
        emitProgress(
            stage = FetchingStage.FINALIZING,
            headline = "All servers ranked",
            detail = "${finalRanked.size} verified servers across ${discoveredCountries.size.coerceAtLeast(1)} countries",
            force = true
        )

        Result.success(finalRanked)
    }

    /**
     * Filters malformed nodes, preserves distinct CDN endpoints sharing an Anycast IP:port
     * (distinguished by core/credential/SNI), and samples across /16 prefixes & domains.
     */
    internal fun selectDiverseCandidatePool(
        allParsed: List<ServerEntity>,
        maxCandidates: Int = 150
    ): List<ServerEntity> {
        val validNodes = allParsed.filter { server ->
            server.host.isNotBlank() &&
                !server.host.contains(" ") &&
                server.port in 1..65535 &&
                server.uuidOrPassword.isNotBlank() &&
                !(server.transport.equals("Reality", ignoreCase = true) && server.publicKey.isBlank())
        }
        val uniqueByEndpoint = validNodes.distinctBy {
            "${it.protocolCore.lowercase()}:${it.host.lowercase()}:${it.port}:${it.uuidOrPassword}:${it.sni.lowercase()}"
        }
        if (uniqueByEndpoint.size <= maxCandidates) return uniqueByEndpoint

        // Separate dedicated servers from Cloudflare Anycast CDN IP ranges
        val (cloudflareNodes, dedicatedNodes) = uniqueByEndpoint.partition { isCloudflareAnycastHost(it.host) }

        // Group dedicated nodes by network prefix (first 2 octets or domain TLD) for maximum geographic spread
        val bySubnet = dedicatedNodes.groupBy { server ->
            val parts = server.host.split(".")
            if (parts.size == 4 && parts[0].toIntOrNull() != null) {
                "${parts[0]}.${parts[1]}"
            } else {
                server.host.takeLast(8).lowercase()
            }
        }.values.toList()

        val selected = mutableListOf<ServerEntity>()
        var round = 0
        while (selected.size < maxCandidates) {
            var addedInRound = false
            for (bucket in bySubnet) {
                if (round < bucket.size) {
                    selected.add(bucket[round])
                    addedInRound = true
                    if (selected.size >= maxCandidates) break
                }
            }
            if (!addedInRound) break
            round++
        }

        // Fill any remaining slots with Cloudflare nodes that have distinct SNI/credential combinations
        if (selected.size < maxCandidates && cloudflareNodes.isNotEmpty()) {
            val remaining = maxCandidates - selected.size
            selected.addAll(
                cloudflareNodes
                    .distinctBy { "${it.sni.ifBlank { it.host }}:${it.uuidOrPassword}" }
                    .take(remaining)
            )
        }

        return selected
    }

    private fun isCloudflareAnycastHost(host: String): Boolean {
        val trimmed = host.trim().lowercase()
        if (trimmed.contains("cloudflare")) return true
        val parts = trimmed.split(".")
        if (parts.size != 4) return false
        val o1 = parts[0].toIntOrNull() ?: return false
        val o2 = parts[1].toIntOrNull() ?: return false
        return (o1 == 104 && o2 in 16..31) ||
            (o1 == 172 && o2 in 64..71) ||
            (o1 == 162 && o2 in 158..159) ||
            (o1 == 188 && o2 == 114) ||
            (o1 == 198 && o2 == 41)
    }

    /**
     * Benchmarks candidate servers:
     * 1. Measures user's line baseline (if needed) under TCP reachability probing.
     * 2. Uses TCP ping strictly to DROP unreachable nodes (never to rank, avoiding CDN edge bias).
     * 3. Orders survivors by DPI-resilient protocol tier (shuffled within tier) & optional strict TLS.
     * 4. Verifies live Xray outbound HTTP 204 delay (`Libv2ray.measureOutboundDelay`).
     * 5. Runs serialized one-at-a-time throughput verification on top candidates so winners are truly fast.
     * 6. Resolves real IPs + HTTPS-only GeoIP country flags, returning a country-diverse Top 15.
     */
    /**
     * Benchmarks candidate servers using pipelined TCP -> Xray verification (no awaitAll barrier)
     * followed by concurrent throughput + GeoIP enrichment.
     */
    suspend fun benchmarkAndRankTop15(
        servers: List<ServerEntity>,
        context: Context? = null,
        settings: AppSettingsState = AppSettingsState(),
        maxPerCountry: Int = 4,
        onProgress: (String) -> Unit = {}
    ): List<ServerEntity> = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext emptyList()

        val securityFiltered = if (settings.strictTlsEnabled) {
            servers.filter { SecurityPolicy.isServerSecure(it) }.ifEmpty { servers }
        } else {
            servers
        }

        val prioritizedInput = ProtocolRanker.prioritizeCandidates(
            securityFiltered.distinctBy {
                "${it.protocolCore.lowercase()}:${it.host.lowercase()}:${it.port}:${it.uuidOrPassword}:${it.sni.lowercase()}"
            }
        )

        val baselineJob = if (context != null) {
            async { ThroughputProbe.measureOrLoadBaselineMbps(context) }
        } else null

        val rawChannel = Channel<ServerEntity>(capacity = 256)
        val xrayChannel = Channel<ServerEntity>(capacity = 256)
        val completedCounter = AtomicInteger(0)
        val liveCounter = AtomicInteger(0)
        val xrayQueued = AtomicInteger(0)
        val xrayDone = AtomicInteger(0)
        val xrayOk = AtomicInteger(0)
        val nativeMissing = AtomicBoolean(false)
        val working = ConcurrentLinkedQueue<ServerEntity>()
        val now = System.currentTimeMillis()
        val totalInput = prioritizedInput.size

        launch {
            for (item in prioritizedInput) rawChannel.send(item)
            rawChannel.close()
        }

        val tcpJob = launch {
            try {
                val workers = List(48.coerceAtMost(totalInput.coerceAtLeast(1))) {
                    launch {
                        for (server in rawChannel) {
                            val isUdp = server.protocolCore.contains("Hysteria", ignoreCase = true) ||
                                server.protocolCore.contains("WireGuard", ignoreCase = true)
                            if (isUdp) {
                                completedCounter.incrementAndGet()
                                liveCounter.incrementAndGet()
                                if (xrayQueued.incrementAndGet() <= 150 && xrayOk.get() < 85) {
                                    xrayChannel.send(server)
                                }
                                continue
                            }
                            val livePing = measureRealTcpLatencyOrNull(
                                host = server.host,
                                port = server.port,
                                timeoutMs = 850
                            )
                            val done = completedCounter.incrementAndGet()
                            if (livePing != null) {
                                val alive = liveCounter.incrementAndGet()
                                if (done % 10 == 0 || done == totalInput) {
                                    onProgress("Probed $done/$totalInput · $alive reachable…")
                                }
                                if (xrayQueued.incrementAndGet() <= 150 && xrayOk.get() < 85) {
                                    xrayChannel.send(server.copy(pingMs = livePing))
                                }
                            } else if (done % 10 == 0 || done == totalInput) {
                                onProgress("Probed $done/$totalInput · ${liveCounter.get()} reachable…")
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
            } finally {
                xrayChannel.close()
            }
        }

        val xrayJob = launch {
            val workers = List(24.coerceAtMost(totalInput.coerceAtLeast(1))) {
                launch {
                    for (server in xrayChannel) {
                        if (xrayOk.get() >= 85) {
                            xrayDone.incrementAndGet()
                            continue
                        }
                        if (context == null || nativeMissing.get()) {
                            xrayDone.incrementAndGet()
                            if (server.pingMs > 0) {
                                working.add(server.copy(lastAliveAt = now, failCount = 0))
                                xrayOk.incrementAndGet()
                            }
                            continue
                        }
                        val result = measureRealXrayDelayOrNull(
                            context = context,
                            server = server,
                            settings = settings,
                            tryBackupOnFailure = false
                        )
                        val done = xrayDone.incrementAndGet()
                        when (result) {
                            is XrayProbeResult.Success -> {
                                val ok = xrayOk.incrementAndGet()
                                if (done % 6 == 0) {
                                    onProgress("Verified $ok working ($done tested)…")
                                }
                                working.add(server.copy(pingMs = result.delayMs, lastAliveAt = now, failCount = 0))
                            }
                            XrayProbeResult.NativeUnavailable -> {
                                nativeMissing.set(true)
                                if (server.pingMs > 0) {
                                    working.add(server.copy(lastAliveAt = now))
                                    xrayOk.incrementAndGet()
                                }
                            }
                            XrayProbeResult.Unreachable -> {
                                if (done % 10 == 0) {
                                    onProgress("Verified ${xrayOk.get()} working ($done tested)…")
                                }
                            }
                        }
                    }
                }
            }
            workers.forEach { it.join() }
        }

        tcpJob.join()
        xrayJob.join()

        val verifiedList = working.toList()
            .sortedWith(
                compareBy<ServerEntity> { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                    .thenBy { it.protocolTier }
            )
            .take(120)

        if (verifiedList.isEmpty()) return@withContext emptyList()

        onProgress("Resolving Country Flags & Speed for ${verifiedList.size} servers…")
        enrichSpeedAndGeoConcurrently(
            verified = verifiedList,
            baselineJob = baselineJob,
            nativeUnavailable = nativeMissing.get(),
            context = context,
            settings = settings,
            maxPerCountry = maxPerCountry,
            now = now
        )
    }

    private suspend fun enrichSpeedAndGeoConcurrently(
        verified: List<ServerEntity>,
        baselineJob: kotlinx.coroutines.Deferred<Double>?,
        nativeUnavailable: Boolean,
        context: Context?,
        settings: AppSettingsState,
        maxPerCountry: Int,
        now: Long
    ): List<ServerEntity> = coroutineScope {
        val dnsSem = Semaphore(32)
        val geoTask = async(Dispatchers.IO) {
            val withResolvedIps = verified.map { server ->
                async(Dispatchers.IO) {
                    dnsSem.withPermit {
                        val lookupHost = resolveServerIpAddress(server.host)
                        server.copy(resolvedIp = lookupHost)
                    }
                }
            }.awaitAll()

            val ipList = withResolvedIps.map { it.resolvedIp }.distinct()
            val batchGeoMap = fetchBatchGeoIpMap(ipList)
            withResolvedIps to batchGeoMap
        }

        val speedTask = async(Dispatchers.IO) {
            val speedMap = mutableMapOf<String, Double>()
            if (context != null && verified.isNotEmpty() && !nativeUnavailable) {
                val baselineMbps = baselineJob?.await() ?: 0.0
                val acceptTargetMbps = if (baselineMbps > 0.0) {
                    baselineMbps * ThroughputProbe.ACCEPT_BASELINE_FRACTION
                } else {
                    0.0
                }
                val speedTargetCount = verified.size.coerceAtMost(3)
                var fastFound = 0
                for (candidate in verified.take(speedTargetCount)) {
                    val speed = ThroughputProbe.measureServerThroughputMbps(
                        context = context,
                        server = candidate,
                        parser = this@V2RayParserAndFletcher,
                        settings = settings
                    )
                    val key = "${candidate.host}:${candidate.port}:${candidate.uuidOrPassword}"
                    speedMap[key] = speed
                    if (acceptTargetMbps > 0.0 && speed >= acceptTargetMbps) {
                        fastFound++
                        if (fastFound >= 2) break
                    }
                }
            }
            speedMap
        }

        val (withResolvedIps, batchGeoMap) = geoTask.await()
        val speedMap = speedTask.await()

        val enrichedCandidates = withResolvedIps.map { server ->
            val key = "${server.host}:${server.port}:${server.uuidOrPassword}"
            val measuredSpeed = speedMap[key] ?: server.lastSpeedMbps
            val explicitCode = server.countryCode.takeIf { it.length == 2 && it != "XX" }
            val geoResult = batchGeoMap[server.resolvedIp]

            val finalCode = geoResult?.second
                ?: explicitCode
                ?: detectExplicitCountryCode(server.sni.ifBlank { server.host })
                ?: "XX"
            val finalName = geoResult?.first?.takeIf { it.isNotBlank() }
                ?: countryNameFromIsoCode(finalCode)

            server.copy(
                countryName = finalName,
                countryCode = finalCode,
                lastSpeedMbps = measuredSpeed,
                lastAliveAt = now,
                protocolDisplay = "V2Ray · ${server.transport} · ${server.resolvedIp}"
            )
        }

        val groupedByCountry = enrichedCandidates
            .groupBy { it.countryCode.ifBlank { "XX" }.uppercase() }
            .values
            .map { group ->
                group.sortedWith(
                    compareByDescending<ServerEntity> { it.lastSpeedMbps }
                        .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                        .thenBy { it.protocolTier }
                ).take(maxPerCountry.coerceAtLeast(1))
            }

        groupedByCountry
            .flatten()
            .sortedWith(
                compareByDescending<ServerEntity> { it.lastSpeedMbps }
                    .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
                    .thenBy { it.protocolTier }
            )
            .mapIndexed { index, item ->
                item.copy(
                    isRecommendedTop15 = index < 15,
                    rankOrder = index + 1
                )
            }
    }

    /**
     * Fetches the user's real current public IP, Country, and ISP from live HTTPS-first GeoIP endpoints.
     * When [socksProxyPort] is provided (Proxy Mode), probes exit IP directly through 127.0.0.1:[socksProxyPort].
     */
    suspend fun fetchDevicePublicIpInfo(socksProxyPort: Int? = null): PublicIpInfo = withContext(Dispatchers.IO) {
        evictConnectionPools()
        val client = if (socksProxyPort != null) {
            geoHttpClient.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksProxyPort)))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        } else {
            geoHttpClient
        }

        // 1. Primary HTTPS endpoint: ipwho.is
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (json.optBoolean("success", true)) {
                        val ip = json.optString("ip", "")
                        val country = json.optString("country", "")
                        val code = json.optString("country_code", "").uppercase()
                        val conn = json.optJSONObject("connection")
                        val isp = conn?.optString("isp", "").orEmpty()
                            .ifBlank { conn?.optString("org", "").orEmpty() }
                            .ifBlank { json.optString("isp", "") }
                            .ifBlank { "Unknown ISP" }
                        if (ip.isNotBlank() && code.length == 2) {
                            return@withContext PublicIpInfo(
                                ip = ip,
                                country = country.ifBlank { countryNameFromIsoCode(code) },
                                countryCode = code,
                                isp = isp
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Backup HTTPS endpoint: ipapi.co
        try {
            val req = Request.Builder()
                .url("https://ipapi.co/json/")
                .header("Accept", "application/json")
                .header("User-Agent", "HollowVPN/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val ip = json.optString("ip", "")
                    val country = json.optString("country_name", "")
                    val code = json.optString("country_code", "").uppercase()
                    val isp = json.optString("org", "")
                        .ifBlank { json.optString("asn", "") }
                        .ifBlank { "Unknown ISP" }
                    if (ip.isNotBlank() && code.length == 2) {
                        return@withContext PublicIpInfo(
                            ip = ip,
                            country = country.ifBlank { countryNameFromIsoCode(code) },
                            countryCode = code,
                            isp = isp
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Backup HTTPS endpoint: api.ip.sb
        try {
            val req = Request.Builder()
                .url("https://api.ip.sb/geoip")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val ip = json.optString("ip", "")
                    val country = json.optString("country", "")
                    val code = json.optString("country_code", "").uppercase()
                    val isp = json.optString("isp", "")
                        .ifBlank { json.optString("organization", "") }
                        .ifBlank { json.optString("asn_organization", "") }
                        .ifBlank { "Unknown ISP" }
                    if (ip.isNotBlank() && code.length == 2) {
                        return@withContext PublicIpInfo(
                            ip = ip,
                            country = country.ifBlank { countryNameFromIsoCode(code) },
                            countryCode = code,
                            isp = isp
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }

        PublicIpInfo("Unavailable", "Unknown", "XX", "Unavailable")
    }

    /**
     * Checks live GitHub Releases / Tags for a newer Hollow VPN build without crashing if no release exists yet.
     */
    suspend fun checkAppUpdate(currentVersion: String): AppUpdateState = withContext(Dispatchers.IO) {
        val cleanCurrent = currentVersion.trim().ifBlank { "1.0" }
        try {
            val releaseReq = Request.Builder()
                .url("https://api.github.com/repos/irexanon/V2ray/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HollowVPN/$cleanCurrent")
                .build()
            httpClient.newCall(releaseReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val tagName = json.optString("tag_name", "").trim()
                    val htmlUrl = json.optString("html_url", "https://github.com/irexanon/V2ray/releases")
                    val body = json.optString("body", "").trim()
                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            val dl = asset.optString("browser_download_url", "")
                            if (name.endsWith(".apk", ignoreCase = true) && dl.isNotBlank()) {
                                apkUrl = dl
                                break
                            }
                        }
                    }
                    if (tagName.isNotBlank()) {
                        return@withContext if (compareSemVer(tagName, cleanCurrent) > 0) {
                            AppUpdateState.UpdateAvailable(
                                currentVersion = cleanCurrent,
                                latestVersion = tagName,
                                releaseNotes = body.ifBlank { "New build $tagName is available." },
                                releaseUrl = htmlUrl,
                                apkDownloadUrl = apkUrl
                            )
                        } else {
                            AppUpdateState.UpToDate(
                                currentVersion = cleanCurrent,
                                latestVersion = tagName,
                                releaseUrl = htmlUrl
                            )
                        }
                    }
                } else if (resp.code == 404) {
                    // Fallback to repository tags if no formal GitHub Release has been published yet
                    val tagsReq = Request.Builder()
                        .url("https://api.github.com/repos/irexanon/V2ray/tags?per_page=1")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "HollowVPN/$cleanCurrent")
                        .build()
                    httpClient.newCall(tagsReq).execute().use { tagResp ->
                        if (tagResp.isSuccessful) {
                            val arr = JSONArray(tagResp.body?.string().orEmpty())
                            val firstTag = arr.optJSONObject(0)?.optString("name", "")?.trim().orEmpty()
                            if (firstTag.isNotBlank() && compareSemVer(firstTag, cleanCurrent) > 0) {
                                return@withContext AppUpdateState.UpdateAvailable(
                                    currentVersion = cleanCurrent,
                                    latestVersion = firstTag,
                                    releaseNotes = "New tag $firstTag published on repository.",
                                    releaseUrl = "https://github.com/irexanon/V2ray/releases"
                                )
                            }
                            return@withContext AppUpdateState.UpToDate(
                                currentVersion = cleanCurrent,
                                latestVersion = firstTag.ifBlank { "v$cleanCurrent" },
                                releaseUrl = "https://github.com/irexanon/V2ray/releases"
                            )
                        }
                    }
                }
                AppUpdateState.Error("Update server returned HTTP ${resp.code}. Please try again later.")
            }
        } catch (e: Exception) {
            AppUpdateState.Error("Unable to reach update server. Check your internet connection and retry.")
        }
    }

    private fun compareSemVer(remote: String, local: String): Int {
        val rParts = remote.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val lParts = local.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-").split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val len = maxOf(rParts.size, lParts.size)
        for (i in 0 until len) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        return 0
    }

    /**
     * Enriches a single ServerEntity with its resolved IP and real GeoIP Country Name & Flag.
     */
    suspend fun enrichServerWithIpAndCountry(
        server: ServerEntity,
        rank: Int = server.rankOrder
    ): ServerEntity = withContext(Dispatchers.IO) {
        val ip = resolveServerIpAddress(server.host)
        val explicitCode = server.countryCode.takeIf { it.length == 2 && it != "XX" }
        val geoPair = fetchBatchGeoIpMap(listOf(ip))[ip] ?: lookupSingleGeoCountryForIp(ip)

        val finalCountryCode = geoPair?.second
            ?: explicitCode
            ?: detectExplicitCountryCode(server.sni.ifBlank { server.host })
            ?: "XX"
        val baseCountryName = geoPair?.first?.takeIf { it.isNotBlank() }
            ?: countryNameFromIsoCode(finalCountryCode)

        server.copy(
            countryName = baseCountryName,
            countryCode = finalCountryCode,
            resolvedIp = ip,
            protocolDisplay = "V2Ray · ${server.transport} · $ip",
            isRecommendedTop15 = true,
            rankOrder = rank
        )
    }

    /**
     * Resolves country codes for a list of IPv4 addresses strictly over HTTPS (`ipwho.is` / `api.ip.sb`),
     * avoiding cleartext HTTP GeoIP calls.
     */
    private suspend fun fetchBatchGeoIpMap(ips: List<String>): Map<String, Pair<String, String>> = coroutineScope {
        val validIps = ips.filter { it.isNotBlank() && it.contains(".") }.distinct().take(120)
        if (validIps.isEmpty()) return@coroutineScope emptyMap()
        val sem = Semaphore(20)
        validIps.map { ip ->
            async(Dispatchers.IO) {
                geoIpCache[ip]?.let { return@async ip to it }
                sem.withPermit {
                    lookupSingleGeoCountryForIp(ip)?.also { resolved ->
                        geoIpCache[ip] = resolved
                    }?.let { ip to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun lookupSingleGeoCountryForIp(ip: String): Pair<String, String>? {
        if (ip.isBlank() || ip.contains(":")) return null
        try {
            val req = Request.Builder()
                .url("https://ipwho.is/$ip")
                .header("Accept", "application/json")
                .build()
            geoHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (json.optBoolean("success", true)) {
                        val code = json.optString("country_code", "").uppercase().trim()
                        val name = json.optString("country", "").trim()
                        if (code.length == 2) {
                            return (name.ifBlank { countryNameFromIsoCode(code) }) to code
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val req = Request.Builder()
                .url("https://api.ip.sb/geoip/$ip")
                .header("Accept", "application/json")
                .build()
            geoHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val code = json.optString("country_code", "").uppercase().trim()
                    val name = json.optString("country", "").trim()
                    if (code.length == 2) {
                        return (name.ifBlank { countryNameFromIsoCode(code) }) to code
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun countryNameFromIsoCode(code: String): String {
        return when (code.uppercase().trim()) {
            "DE" -> "Germany"
            "US" -> "United States"
            "GB", "UK" -> "United Kingdom"
            "NL" -> "Netherlands"
            "SG" -> "Singapore"
            "FR" -> "France"
            "FI" -> "Finland"
            "CA" -> "Canada"
            "JP" -> "Japan"
            "KR" -> "South Korea"
            "IN" -> "India"
            "AU" -> "Australia"
            "CH" -> "Switzerland"
            "SE" -> "Sweden"
            "BR" -> "Brazil"
            "TR" -> "Turkey"
            "RU" -> "Russia"
            "HK" -> "Hong Kong"
            "TW" -> "Taiwan"
            "PL" -> "Poland"
            "IT" -> "Italy"
            "ES" -> "Spain"
            "AT" -> "Austria"
            "UA" -> "Ukraine"
            "IE" -> "Ireland"
            "NO" -> "Norway"
            "DK" -> "Denmark"
            "BE" -> "Belgium"
            "CZ" -> "Czechia"
            "RO" -> "Romania"
            "AE" -> "United Arab Emirates"
            "IR" -> "Iran"
            "XX" -> "Unknown"
            else -> {
                try {
                    Locale("", code.uppercase()).getDisplayCountry(Locale.ENGLISH)
                        .takeIf { it.isNotBlank() && it != code.uppercase() }
                        ?: "Unknown"
                } catch (_: Exception) {
                    "Unknown"
                }
            }
        }
    }

    fun measureRealTcpLatencyOrNull(
        host: String,
        port: Int,
        timeoutMs: Int = 1200
    ): Int? {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) return null
        return try {
            val rtt = measureTimeMillis {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(cleanHost, port), timeoutMs)
                }
            }.toInt()
            rtt.coerceIn(1, timeoutMs)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun measureRealXrayDelayOrNull(
        context: Context,
        server: ServerEntity,
        settings: AppSettingsState = AppSettingsState(),
        tryBackupOnFailure: Boolean = true
    ): XrayProbeResult = withContext(Dispatchers.IO) {
        try {
            ensureCoreEnvInitialized(context)
            val baseConfig = server.rawConfigJson.ifBlank { generateXrayJsonForServer(server) }
            val testConfig = applyRuntimeSettingsToXrayJson(baseConfig, settings.copy(tunMode = false))
            val primaryDelay = try {
                Libv2ray.measureOutboundDelay(testConfig, DELAY_PROBE_PRIMARY)
            } catch (e: UnsatisfiedLinkError) {
                throw e
            } catch (e: NoClassDefFoundError) {
                throw e
            } catch (_: Throwable) {
                -1L
            }
            if (primaryDelay > 0L) {
                return@withContext XrayProbeResult.Success(primaryDelay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            if (!tryBackupOnFailure) {
                return@withContext XrayProbeResult.Unreachable
            }
            val backupDelay = try {
                Libv2ray.measureOutboundDelay(testConfig, DELAY_PROBE_BACKUP)
            } catch (e: UnsatisfiedLinkError) {
                throw e
            } catch (e: NoClassDefFoundError) {
                throw e
            } catch (_: Throwable) {
                -1L
            }
            if (backupDelay > 0L) {
                XrayProbeResult.Success(backupDelay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } else {
                XrayProbeResult.Unreachable
            }
        } catch (_: UnsatisfiedLinkError) {
            XrayProbeResult.NativeUnavailable
        } catch (_: NoClassDefFoundError) {
            XrayProbeResult.NativeUnavailable
        } catch (_: Throwable) {
            XrayProbeResult.Unreachable
        }
    }

    private fun ensureCoreEnvInitialized(context: Context) {
        if (coreEnvInitialized) return
        val app = context.applicationContext
        Seq.setContext(app)
        val androidId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val deviceId = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest("hollow:$androidId".toByteArray(Charsets.UTF_8))
                .copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP
        )
        val assetPath = (app.getExternalFilesDir("assets") ?: app.getDir("assets", 0)).absolutePath
        Libv2ray.initCoreEnv(assetPath, deviceId)
        coreEnvInitialized = true
    }

    suspend fun resolveServerIpAddress(host: String): String = withContext(Dispatchers.IO) {
        val trimmed = host.trim()
        resolvedIpCache[trimmed]?.let { return@withContext it }
        try {
            val addresses = InetAddress.getAllByName(trimmed)
            val ipv4 = addresses.firstOrNull { it.hostAddress?.contains(".") == true }?.hostAddress
            val resolved = ipv4 ?: addresses.firstOrNull()?.hostAddress ?: trimmed
            if (resolved.isNotBlank()) {
                resolvedIpCache[trimmed] = resolved
            }
            resolved
        } catch (_: Exception) {
            trimmed
        }
    }

    fun parseImportPayload(
        rawInput: String,
        subscriptionUrl: String = "",
        isCustom: Boolean = true
    ): List<ServerEntity> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonParsed = parseRawXrayJson(trimmed, isCustom = isCustom)
            if (jsonParsed.isNotEmpty()) return jsonParsed
        }

        val decodedText = if (!trimmed.contains("://")) {
            tryDecodeBase64(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val results = mutableListOf<ServerEntity>()
        decodedText.lineSequence()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("#") &&
                    !it.startsWith("//") &&
                    !it.startsWith("http://", ignoreCase = true) &&
                    !it.startsWith("https://", ignoreCase = true)
            }
            .forEach { line ->
                parseSingleUriLine(line, subscriptionUrl, isCustom)?.let { results.add(it) }
            }

        return results
    }

    private fun parseSingleUriLine(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        val clean = uriLine.trim()
        val lower = clean.lowercase()
        return when {
            lower.startsWith("vmess://") -> parseVmessUri(clean, subscriptionUrl, isCustom)
            lower.startsWith("vless://") -> parseFlexibleV2RayUri(clean, "VLESS", subscriptionUrl, isCustom)
            lower.startsWith("trojan://") -> parseFlexibleV2RayUri(clean, "Trojan", subscriptionUrl, isCustom)
            lower.startsWith("ss://") -> parseShadowsocksUri(clean, subscriptionUrl, isCustom)
            lower.startsWith("hy2://") || lower.startsWith("hysteria2://") ->
                parseHysteria2Uri(clean, subscriptionUrl, isCustom)
            lower.startsWith("wireguard://") -> parseWireGuardUri(clean, subscriptionUrl, isCustom)
            lower.startsWith("socks://") || lower.startsWith("socks5://") ->
                parseSocksUri(clean, subscriptionUrl, isCustom)
            // TUIC outbounds are not supported by Xray-core / AndroidLibXrayLite.
            lower.startsWith("tuic://") -> null
            else -> null
        }
    }

    private data class TransportBuildParams(
        val security: String = "",
        val network: String = "",
        val wsPath: String = "",
        val wsHost: String = "",
        val grpcServiceName: String = "",
        val fingerprint: String = "chrome",
        val shortId: String = "",
        val spiderX: String = "",
        val flow: String = "",
        val alpn: List<String> = emptyList(),
        val allowInsecure: Boolean = false,
        val obfs: String = "",
        val obfsPassword: String = "",
        val mport: String = ""
    )

    private fun parseHysteria2Uri(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val withoutScheme = uriLine.substringAfter("://")
            val rawRemark = if (withoutScheme.contains("#")) {
                safeUrlDecode(withoutScheme.substringAfterLast("#"))
            } else ""
            val beforeFragment = withoutScheme.substringBefore("#")
            val rawQuery = if (beforeFragment.contains("?")) beforeFragment.substringAfter("?") else ""
            val authority = beforeFragment.substringBefore("?")
            if (!authority.contains("@")) return null

            val password = safeUrlDecode(authority.substringBeforeLast("@")).trim()
            if (password.isBlank()) return null

            val hostPortPart = authority.substringAfterLast("@").trim()
            val (host, port) = extractHostAndPort(hostPortPart, defaultPort = 443)
            if (host.isBlank() || host.contains(" ")) return null

            val queryParams = parseQueryParams(rawQuery)
            val sni = (queryParams["sni"] ?: queryParams["peer"] ?: "").trim()
            val allowInsecure = queryParams["insecure"] == "1" ||
                queryParams["insecure"].equals("true", ignoreCase = true) ||
                queryParams["allowInsecure"] == "1" ||
                queryParams["allowInsecure"].equals("true", ignoreCase = true)
            val obfs = queryParams["obfs"].orEmpty().trim()
            val obfsPassword = queryParams["obfs-password"].orEmpty().trim()
            val mport = queryParams["mport"].orEmpty().trim()
            val alpn = queryParams["alpn"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
                .ifEmpty { listOf("h3") }

            val (countryName, countryCode) = inferCountryFromRemarkOrHost("$rawRemark $sni", host)
            val entity = ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · Hysteria2 · $host",
                protocolCore = "Hysteria2 (Xray)",
                transport = "Hysteria2",
                host = host,
                resolvedIp = host,
                port = port,
                uuidOrPassword = password,
                sni = sni,
                shareUri = uriLine,
                pingMs = 0,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )
            val params = TransportBuildParams(
                security = "tls",
                network = "hysteria",
                alpn = alpn,
                allowInsecure = allowInsecure,
                obfs = obfs,
                obfsPassword = obfsPassword,
                mport = mport
            )
            entity.copy(rawConfigJson = generateXrayJsonForServer(entity, params))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmessUri(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val payload = uriLine.substringAfter("://").trim()
            val decoded = tryDecodeBase64(payload.substringBefore("#"))
                ?: return parseFlexibleV2RayUri(uriLine, "VMess", subscriptionUrl, isCustom)
            val json = JSONObject(decoded)
            val host = json.optString("add").ifBlank { json.optString("host", "") }.trim()
            if (host.isBlank() || host.contains(" ")) return null
            val port = json.optString("port", "443").toIntOrNull()
                ?: json.optInt("port", 443)
            val uuid = json.optString("id", "").trim()
            if (uuid.isBlank()) return null
            val net = json.optString("net", "tcp").lowercase()
            val tls = json.optString("tls", "").lowercase()
            val hostHeader = json.optString("host", "").trim()
            val path = json.optString("path", "").trim()
            val sni = json.optString("sni").ifBlank { hostHeader }.trim()
            val fp = json.optString("fp", "chrome").ifBlank { "chrome" }
            val alpn = json.optString("alpn", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val ps = json.optString("ps").ifBlank { host }

            val normalizedNetwork = when (net) {
                "ws", "websocket" -> "ws"
                "grpc" -> "grpc"
                "h2", "http" -> "h2"
                "httpupgrade" -> "httpupgrade"
                else -> "tcp"
            }
            val transportLabel = when (normalizedNetwork) {
                "ws" -> "WebSocket"
                "grpc" -> "gRPC"
                "h2" -> "HTTP/2"
                "httpupgrade" -> "WebSocket"
                else -> if (tls == "reality") "Reality" else "TCP"
            }
            val securityValue = when (tls) {
                "reality" -> "reality"
                "tls" -> "tls"
                else -> "none"
            }
            val (countryName, countryCode) = inferCountryFromRemarkOrHost("$ps $sni", host)

            val entity = ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · $transportLabel · $host",
                protocolCore = "VMess (V2Ray)",
                transport = transportLabel,
                host = host,
                resolvedIp = host,
                port = port.coerceIn(1, 65535),
                uuidOrPassword = uuid,
                sni = sni,
                shareUri = uriLine,
                pingMs = 0,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )
            val params = TransportBuildParams(
                security = securityValue,
                network = normalizedNetwork,
                wsPath = path,
                wsHost = hostHeader,
                grpcServiceName = if (normalizedNetwork == "grpc") path else "",
                fingerprint = fp,
                alpn = alpn,
                allowInsecure = json.optString("skip-cert-verify") == "true" || json.optBoolean("allowInsecure", false)
            )
            entity.copy(rawConfigJson = generateXrayJsonForServer(entity, params))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlexibleV2RayUri(
        uriLine: String,
        protocolName: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val withoutScheme = uriLine.substringAfter("://")
            val rawRemark = if (withoutScheme.contains("#")) {
                safeUrlDecode(withoutScheme.substringAfterLast("#"))
            } else ""

            val beforeFragment = withoutScheme.substringBefore("#")
            val rawQuery = if (beforeFragment.contains("?")) {
                beforeFragment.substringAfter("?")
            } else ""

            val authority = beforeFragment.substringBefore("?")
            if (!authority.contains("@")) return null
            val userInfo = safeUrlDecode(authority.substringBeforeLast("@")).trim()
            if (userInfo.isBlank()) return null

            val hostPortPart = authority.substringAfterLast("@").trim()
            val (host, port) = extractHostAndPort(hostPortPart)
            if (host.isBlank() || host.contains(" ")) return null

            val queryParams = parseQueryParams(rawQuery)
            val rawSecurity = queryParams["security"]?.lowercase().orEmpty()
            val rawType = (queryParams["type"] ?: queryParams["net"])?.lowercase().orEmpty()
            val hostHeader = queryParams["host"].orEmpty().trim()
            val sni = (queryParams["sni"] ?: queryParams["peer"] ?: queryParams["host"] ?: "").trim()
            val pbk = queryParams["pbk"].orEmpty().trim()
            val wsPath = queryParams["path"].orEmpty().trim()
            val grpcServiceName = queryParams["serviceName"].orEmpty().trim()
            val fp = queryParams["fp"].orEmpty().ifBlank { "chrome" }
            val sid = queryParams["sid"].orEmpty().trim()
            val spx = queryParams["spx"].orEmpty().trim()
            val flow = queryParams["flow"].orEmpty().trim()
            val alpn = queryParams["alpn"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val allowInsecure = queryParams["allowInsecure"] == "1" ||
                queryParams["allowInsecure"].equals("true", ignoreCase = true)

            val normalizedNetwork = when (rawType) {
                "ws", "websocket" -> "ws"
                "httpupgrade" -> "httpupgrade"
                "xhttp", "splithttp" -> "xhttp"
                "grpc" -> "grpc"
                "h2", "http" -> "h2"
                else -> "tcp"
            }
            val resolvedSecurity = when {
                rawSecurity == "reality" || pbk.isNotBlank() -> "reality"
                rawSecurity == "tls" -> "tls"
                rawSecurity == "none" -> "none"
                protocolName.equals("Trojan", ignoreCase = true) -> "tls"
                else -> "none"
            }

            val transportLabel = when {
                resolvedSecurity == "reality" -> "Reality"
                normalizedNetwork == "xhttp" -> "XHTTP"
                normalizedNetwork == "grpc" -> "gRPC"
                normalizedNetwork == "ws" || normalizedNetwork == "httpupgrade" -> "WebSocket"
                normalizedNetwork == "h2" -> "HTTP/2"
                protocolName.equals("Trojan", ignoreCase = true) -> "Trojan"
                protocolName.equals("VLESS", ignoreCase = true) -> "VLESS"
                else -> "TCP"
            }

            val (countryName, countryCode) = inferCountryFromRemarkOrHost("$rawRemark $sni", host)

            val entity = ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · $transportLabel · $host",
                protocolCore = "$protocolName (V2Ray)",
                transport = transportLabel,
                host = host,
                resolvedIp = host,
                port = port,
                uuidOrPassword = userInfo,
                sni = sni,
                publicKey = pbk,
                shareUri = uriLine,
                pingMs = 0,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )
            val params = TransportBuildParams(
                security = resolvedSecurity,
                network = normalizedNetwork,
                wsPath = wsPath,
                wsHost = hostHeader,
                grpcServiceName = grpcServiceName,
                fingerprint = fp,
                shortId = sid,
                spiderX = spx,
                flow = flow,
                alpn = alpn,
                allowInsecure = allowInsecure
            )
            entity.copy(rawConfigJson = generateXrayJsonForServer(entity, params))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseShadowsocksUri(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val withoutScheme = uriLine.substringAfter("://")
            val remark = if (withoutScheme.contains("#")) {
                safeUrlDecode(withoutScheme.substringAfterLast("#"))
            } else ""
            val mainPart = withoutScheme.substringBefore("#").substringBefore("?")
            val decodedMain = if (!mainPart.contains("@")) {
                tryDecodeBase64(mainPart) ?: mainPart
            } else {
                val userPart = mainPart.substringBeforeLast("@")
                val hostPart = mainPart.substringAfterLast("@")
                "${tryDecodeBase64(userPart) ?: userPart}@$hostPart"
            }
            if (!decodedMain.contains("@")) return null
            val userInfo = safeUrlDecode(decodedMain.substringBeforeLast("@")).trim()
            val hostPort = decodedMain.substringAfterLast("@").trim()
            val (host, port) = extractHostAndPort(hostPort, defaultPort = 8388)
            if (host.isBlank() || host.contains(" ")) return null

            val (countryName, countryCode) = inferCountryFromRemarkOrHost(remark, host)
            val entity = ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · Shadowsocks · $host",
                protocolCore = "Shadowsocks (Xray)",
                transport = "TCP",
                host = host,
                resolvedIp = host,
                port = port,
                uuidOrPassword = userInfo,
                shareUri = uriLine,
                pingMs = 0,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )
            val params = TransportBuildParams(security = "none", network = "tcp")
            entity.copy(rawConfigJson = generateXrayJsonForServer(entity, params))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWireGuardUri(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val withoutScheme = uriLine.substringAfter("://")
            val remark = if (withoutScheme.contains("#")) {
                safeUrlDecode(withoutScheme.substringAfterLast("#"))
            } else ""
            val beforeFragment = withoutScheme.substringBefore("#")
            val rawQuery = if (beforeFragment.contains("?")) beforeFragment.substringAfter("?") else ""
            val authority = beforeFragment.substringBefore("?")
            if (!authority.contains("@")) return null

            val secretKey = safeUrlDecode(authority.substringBeforeLast("@")).trim()
            if (secretKey.isBlank()) return null
            val hostPort = authority.substringAfterLast("@").trim()
            val (host, port) = extractHostAndPort(hostPort, defaultPort = 51820)
            if (host.isBlank() || host.contains(" ")) return null

            val query = parseQueryParams(rawQuery)
            val peerPublicKey = query["publickey"].orEmpty().trim()
            if (peerPublicKey.isBlank()) return null
            val localAddresses = (query["address"] ?: "172.16.0.2/32")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val mtu = query["mtu"]?.toIntOrNull()?.coerceIn(1200, 1500) ?: 1420
            val reserved = query["reserved"].orEmpty()
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }

            val (countryName, countryCode) = inferCountryFromRemarkOrHost(remark, host)
            val entity = ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · WireGuard · $host",
                protocolCore = "WireGuard (Xray)",
                transport = "WireGuard",
                host = host,
                resolvedIp = host,
                port = port,
                uuidOrPassword = secretKey,
                publicKey = peerPublicKey,
                shareUri = uriLine,
                pingMs = 0,
                protocolTier = 5,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )

            val peerObj = JSONObject()
                .put("endpoint", "$host:$port")
                .put("publicKey", peerPublicKey)
            val wgSettings = JSONObject()
                .put("secretKey", secretKey)
                .put("address", JSONArray(localAddresses))
                .put("peers", JSONArray().put(peerObj))
                .put("mtu", mtu)
            if (reserved.size == 3) {
                wgSettings.put("reserved", JSONArray(reserved))
            }

            val root = JSONObject()
                .put("log", JSONObject().put("loglevel", "warning"))
                .put(
                    "inbounds",
                    JSONArray().put(
                        JSONObject()
                            .put("tag", "tun-in")
                            .put("protocol", "tun")
                            .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    )
                )
                .put(
                    "outbounds",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("tag", "proxy")
                                .put("protocol", "wireguard")
                                .put("settings", wgSettings)
                        )
                        .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                        .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                )
            entity.copy(rawConfigJson = root.toString(2))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSocksUri(
        uriLine: String,
        subscriptionUrl: String,
        isCustom: Boolean
    ): ServerEntity? {
        return try {
            val withoutScheme = uriLine.substringAfter("://")
            val remark = if (withoutScheme.contains("#")) {
                safeUrlDecode(withoutScheme.substringAfterLast("#"))
            } else ""
            val mainPart = withoutScheme.substringBefore("#").substringBefore("?")
            val (userInfo, hostPort) = if (mainPart.contains("@")) {
                val rawUser = mainPart.substringBeforeLast("@")
                val decodedUser = tryDecodeBase64(rawUser) ?: safeUrlDecode(rawUser)
                decodedUser to mainPart.substringAfterLast("@")
            } else {
                "" to mainPart
            }
            val (host, port) = extractHostAndPort(hostPort.trim(), defaultPort = 1080)
            if (host.isBlank() || host.contains(" ")) return null

            val (countryName, countryCode) = inferCountryFromRemarkOrHost(remark, host)
            val serverObj = JSONObject()
                .put("address", host)
                .put("port", port)
            if (userInfo.contains(":")) {
                val user = userInfo.substringBefore(":")
                val pass = userInfo.substringAfter(":")
                if (user.isNotBlank()) {
                    serverObj.put(
                        "users",
                        JSONArray().put(JSONObject().put("user", user).put("pass", pass))
                    )
                }
            }

            val root = JSONObject()
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", JSONArray())
                .put(
                    "outbounds",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("tag", "proxy")
                                .put("protocol", "socks")
                                .put("settings", JSONObject().put("servers", JSONArray().put(serverObj)))
                        )
                        .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                        .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
                )

            ServerEntity(
                countryName = countryName,
                countryCode = countryCode,
                protocolDisplay = "V2Ray · SOCKS5 · $host",
                protocolCore = "SOCKS5 (Xray)",
                transport = "SOCKS5",
                host = host,
                resolvedIp = host,
                port = port,
                uuidOrPassword = userInfo.ifBlank { "noauth" },
                rawConfigJson = root.toString(2),
                shareUri = uriLine,
                pingMs = 0,
                protocolTier = 6,
                isCustom = isCustom,
                subscriptionUrl = subscriptionUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHostAndPort(hostPort: String, defaultPort: Int = 443): Pair<String, Int> {
        val cleaned = hostPort.substringBefore("/")
        return if (cleaned.startsWith("[")) {
            val host = cleaned.substringAfter("[").substringBefore("]")
            val portStr = cleaned.substringAfter("]:", "")
            val port = portStr.toIntOrNull() ?: defaultPort
            host to port.coerceIn(1, 65535)
        } else if (cleaned.contains(":")) {
            val host = cleaned.substringBeforeLast(":")
            val port = cleaned.substringAfterLast(":").toIntOrNull() ?: defaultPort
            host to port.coerceIn(1, 65535)
        } else {
            cleaned to defaultPort
        }
    }

    private fun safeUrlDecode(input: String): String {
        return try {
            URLDecoder.decode(input.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    fun parseRawXrayJson(rawJson: String, isCustom: Boolean = true): List<ServerEntity> {
        return try {
            val root = JSONObject(rawJson)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray().put(root)
            val servers = mutableListOf<ServerEntity>()

            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val protocol = outbound.optString("protocol", "").lowercase()
                if (protocol.isBlank() || protocol in listOf("freedom", "blackhole", "dns", "direct", "block")) continue

                val tag = outbound.optString("tag", "")
                val settings = outbound.optJSONObject("settings") ?: JSONObject()
                val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
                val network = streamSettings.optString("network", "tcp").lowercase()
                val security = streamSettings.optString("security", "none").lowercase()

                var host = ""
                var port = 443
                var credential = ""

                val vnext = settings.optJSONArray("vnext")
                val serversArr = settings.optJSONArray("servers")
                val peersArr = settings.optJSONArray("peers")
                if (protocol == "hysteria" || protocol == "hysteria2") {
                    host = settings.optString("address", "").trim()
                    port = settings.optInt("port", 443)
                    credential = streamSettings.optJSONObject("hysteriaSettings")?.optString("auth").orEmpty()
                } else if (protocol == "wireguard" && peersArr != null && peersArr.length() > 0) {
                    val endpoint = peersArr.getJSONObject(0).optString("endpoint", "").trim()
                    val (wgHost, wgPort) = extractHostAndPort(endpoint, defaultPort = 51820)
                    host = wgHost
                    port = wgPort
                    credential = settings.optString("secretKey", "")
                } else if (vnext != null && vnext.length() > 0) {
                    val first = vnext.getJSONObject(0)
                    host = first.optString("address", "").trim()
                    port = first.optInt("port", 443)
                    val users = first.optJSONArray("users")
                    if (users != null && users.length() > 0) {
                        credential = users.getJSONObject(0).optString("id", "")
                    }
                } else if (serversArr != null && serversArr.length() > 0) {
                    val first = serversArr.getJSONObject(0)
                    host = first.optString("address", "").trim()
                    port = first.optInt("port", 443)
                    credential = first.optString("password", "").ifBlank { "noauth" }
                }

                if (host.isBlank()) continue

                val realitySettings = streamSettings.optJSONObject("realitySettings")
                val tlsSettings = streamSettings.optJSONObject("tlsSettings")
                val sni = realitySettings?.optString("serverName")
                    ?: tlsSettings?.optString("serverName")
                    ?: ""
                val pbk = realitySettings?.optString("publicKey")
                    ?: peersArr?.optJSONObject(0)?.optString("publicKey")
                    ?: ""

                val transportLabel = when {
                    protocol == "hysteria" || protocol == "hysteria2" -> "Hysteria2"
                    protocol == "wireguard" -> "WireGuard"
                    protocol == "socks" -> "SOCKS5"
                    security == "reality" -> "Reality"
                    network == "xhttp" || network == "splithttp" -> "XHTTP"
                    network == "grpc" -> "gRPC"
                    network == "ws" -> "WebSocket"
                    protocol == "trojan" -> "Trojan"
                    protocol == "vless" -> "VLESS"
                    else -> "TCP"
                }

                val protocolCore = when (protocol) {
                    "vmess" -> "VMess (V2Ray)"
                    "trojan" -> "Trojan (V2Ray)"
                    "shadowsocks" -> "Shadowsocks (Xray)"
                    "hysteria", "hysteria2" -> "Hysteria2 (Xray)"
                    "wireguard" -> "WireGuard (Xray)"
                    "socks" -> "SOCKS5 (Xray)"
                    else -> "VLESS (V2Ray)"
                }

                val (countryName, countryCode) = inferCountryFromRemarkOrHost("$tag $sni", host)

                servers.add(
                    ServerEntity(
                        countryName = countryName,
                        countryCode = countryCode,
                        protocolDisplay = "V2Ray · $transportLabel · $host",
                        protocolCore = protocolCore,
                        transport = transportLabel,
                        host = host,
                        resolvedIp = host,
                        port = port,
                        uuidOrPassword = credential,
                        sni = sni,
                        publicKey = pbk,
                        rawConfigJson = buildSingleServerConfig(root, outbound).toString(2),
                        pingMs = 0,
                        isCustom = isCustom
                    )
                )
            }
            servers
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Give each saved row an isolated outbound; Shadowsocks rejects multiple servers in one outbound. */
    private fun buildSingleServerConfig(root: JSONObject, sourceOutbound: JSONObject): JSONObject {
        val config = JSONObject()
        root.optJSONObject("log")?.let { config.put("log", JSONObject(it.toString())) }

        val inbounds = root.optJSONArray("inbounds")?.let { JSONArray(it.toString()) } ?: JSONArray()
        if ((0 until inbounds.length()).none { inbounds.optJSONObject(it)?.optString("protocol") == "tun" }) {
            inbounds.put(
                JSONObject()
                    .put("tag", "tun-in")
                    .put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls").put("quic")))
            )
        }
        config.put("inbounds", inbounds)

        val outbound = JSONObject(sourceOutbound.toString())
        val settings = outbound.optJSONObject("settings")
        if (settings != null) {
            settings.optJSONArray("servers")?.let { servers ->
                if (servers.length() > 0) settings.put("servers", JSONArray().put(JSONObject(servers.getJSONObject(0).toString())) )
            }
            settings.optJSONArray("vnext")?.let { vnext ->
                if (vnext.length() > 0) settings.put("vnext", JSONArray().put(JSONObject(vnext.getJSONObject(0).toString())) )
            }
            outbound.put("settings", settings)
        }
        config.put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        return config
    }

    fun generateXrayJsonForServer(server: ServerEntity): String {
        return generateXrayJsonForServer(server, params = null)
    }

    fun generateXrayJsonForServer(
        server: ServerEntity,
        wsPath: String = "",
        wsHost: String = "",
        grpcServiceName: String = "",
        shortId: String = "",
        flow: String = ""
    ): String {
        return generateXrayJsonForServer(
            server = server,
            params = TransportBuildParams(
                wsPath = wsPath,
                wsHost = wsHost,
                grpcServiceName = grpcServiceName,
                shortId = shortId,
                flow = flow
            )
        )
    }

    private fun generateXrayJsonForServer(
        server: ServerEntity,
        params: TransportBuildParams?
    ): String {
        if (server.rawConfigJson.isNotBlank()) return server.rawConfigJson
        val protocolLower = when {
            server.protocolCore.contains("Hysteria", ignoreCase = true) -> "hysteria"
            server.protocolCore.contains("VMess", ignoreCase = true) -> "vmess"
            server.protocolCore.contains("Trojan", ignoreCase = true) -> "trojan"
            server.protocolCore.contains("Shadowsocks", ignoreCase = true) -> "shadowsocks"
            else -> "vless"
        }
        val networkLower = if (protocolLower == "hysteria") {
            "hysteria"
        } else {
            params?.network?.ifBlank { null } ?: when (server.transport.lowercase()) {
                "websocket", "ws" -> "ws"
                "xhttp", "splithttp" -> "xhttp"
                "grpc" -> "grpc"
                "http/2", "h2" -> "h2"
                else -> "tcp"
            }
        }
        val securityLower = when {
            protocolLower == "hysteria" -> "tls"
            !params?.security.isNullOrBlank() -> params?.security!!
            server.transport.equals("Reality", ignoreCase = true) || server.publicKey.isNotBlank() -> "reality"
            protocolLower == "shadowsocks" -> "none"
            protocolLower == "trojan" -> "tls"
            server.sni.isNotBlank() -> "tls"
            else -> "none"
        }

        val inbounds = JSONArray()
            .put(
                JSONObject()
                    .put("tag", "socks-in")
                    .put("port", 10808)
                    .put("listen", "127.0.0.1")
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            )
            .put(
                JSONObject()
                    .put("tag", "tun-in")
                    .put("protocol", "tun")
                    .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                    .put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                    )
            )

        val outboundSettings = JSONObject()
        when (protocolLower) {
            "hysteria" -> {
                outboundSettings
                    .put("version", 2)
                    .put("address", server.host)
                    .put("port", server.port)
            }
            "shadowsocks" -> {
                val method = server.uuidOrPassword.substringBefore(":").ifBlank { "aes-256-gcm" }
                val password = if (server.uuidOrPassword.contains(":")) {
                    server.uuidOrPassword.substringAfter(":")
                } else {
                    server.uuidOrPassword
                }
                outboundSettings.put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server.host)
                            .put("port", server.port)
                            .put("method", method)
                            .put("password", password)
                    )
                )
            }
            "trojan" -> {
                outboundSettings.put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server.host)
                            .put("port", server.port)
                            .put("password", server.uuidOrPassword)
                    )
                )
            }
            "vmess" -> {
                val userObj = JSONObject()
                    .put("id", server.uuidOrPassword)
                    .put("alterId", 0)
                    .put("security", "auto")
                outboundSettings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server.host)
                            .put("port", server.port)
                            .put("users", JSONArray().put(userObj))
                    )
                )
            }
            else -> {
                val flowValue = params?.flow?.ifBlank { null }
                    ?: if (securityLower == "reality" && networkLower == "tcp") "xtls-rprx-vision" else ""
                val userObj = JSONObject()
                    .put("id", server.uuidOrPassword)
                    .put("encryption", "none")
                if (flowValue.isNotBlank()) {
                    userObj.put("flow", flowValue)
                }
                outboundSettings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server.host)
                            .put("port", server.port)
                            .put("users", JSONArray().put(userObj))
                    )
                )
            }
        }

        val streamSettings = JSONObject()
            .put("network", networkLower)
            .put("security", securityLower)

        val effectiveSni = server.sni.ifBlank { params?.wsHost.orEmpty() }.ifBlank { server.host }
        when (securityLower) {
            "reality" -> {
                val realityObj = JSONObject()
                    .put("serverName", effectiveSni)
                    .put("fingerprint", params?.fingerprint?.ifBlank { "chrome" } ?: "chrome")
                    .put("publicKey", server.publicKey)
                    .put("shortId", params?.shortId.orEmpty())
                if (!params?.spiderX.isNullOrBlank()) {
                    realityObj.put("spiderX", params?.spiderX)
                }
                streamSettings.put("realitySettings", realityObj)
            }
            "tls" -> {
                val tlsObj = JSONObject()
                    .put("serverName", effectiveSni)
                    .put("allowInsecure", params?.allowInsecure ?: false)
                if (!params?.fingerprint.isNullOrBlank() && protocolLower != "hysteria") {
                    tlsObj.put("fingerprint", params?.fingerprint)
                }
                val alpnList = params?.alpn.orEmpty().ifEmpty {
                    if (protocolLower == "hysteria") listOf("h3") else emptyList()
                }
                if (alpnList.isNotEmpty()) {
                    tlsObj.put("alpn", JSONArray(alpnList))
                }
                streamSettings.put("tlsSettings", tlsObj)
            }
        }

        when (networkLower) {
            "hysteria" -> {
                streamSettings.put(
                    "hysteriaSettings",
                    JSONObject()
                        .put("version", 2)
                        .put("auth", server.uuidOrPassword)
                )
                val udpMasks = JSONArray()
                if (params?.obfs.equals("salamander", ignoreCase = true) && !params?.obfsPassword.isNullOrBlank()) {
                    udpMasks.put(
                        JSONObject()
                            .put("type", "salamander")
                            .put("settings", JSONObject().put("password", params?.obfsPassword))
                    )
                }
                if (!params?.mport.isNullOrBlank()) {
                    udpMasks.put(
                        JSONObject()
                            .put("type", "udphop")
                            .put(
                                "settings",
                                JSONObject()
                                    .put("mode", "intervalremote")
                                    .put("interval", "30")
                                    .put("remotePorts", params?.mport)
                            )
                    )
                }
                if (udpMasks.length() > 0) {
                    streamSettings.put("finalmask", JSONObject().put("udp", udpMasks))
                }
            }
            "ws" -> {
                val wsObj = JSONObject()
                    .put("path", params?.wsPath?.ifBlank { "/" } ?: "/")
                val hostHeader = params?.wsHost?.ifBlank { server.sni }.orEmpty()
                if (hostHeader.isNotBlank()) {
                    wsObj.put("headers", JSONObject().put("Host", hostHeader))
                }
                streamSettings.put("wsSettings", wsObj)
            }
            "httpupgrade" -> {
                val huObj = JSONObject()
                    .put("path", params?.wsPath?.ifBlank { "/" } ?: "/")
                val hostHeader = params?.wsHost?.ifBlank { server.sni }.orEmpty()
                if (hostHeader.isNotBlank()) {
                    huObj.put("host", hostHeader)
                }
                streamSettings.put("httpupgradeSettings", huObj)
            }
            "xhttp" -> {
                val xhttpObj = JSONObject()
                    .put("path", params?.wsPath?.ifBlank { "/" } ?: "/")
                val hostHeader = params?.wsHost?.ifBlank { server.sni }.orEmpty()
                if (hostHeader.isNotBlank()) {
                    xhttpObj.put("host", hostHeader)
                }
                streamSettings.put("xhttpSettings", xhttpObj)
            }
            "grpc" -> {
                streamSettings.put(
                    "grpcSettings",
                    JSONObject()
                        .put("serviceName", params?.grpcServiceName.orEmpty())
                        .put("multiMode", false)
                )
            }
            "h2" -> {
                val h2Obj = JSONObject()
                    .put("path", params?.wsPath?.ifBlank { "/" } ?: "/")
                val hostHeader = params?.wsHost?.ifBlank { server.sni }.orEmpty()
                if (hostHeader.isNotBlank()) {
                    h2Obj.put("host", JSONArray().put(hostHeader))
                }
                streamSettings.put("httpSettings", h2Obj)
            }
        }

        val proxyOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", protocolLower)
            .put("settings", outboundSettings)
            .put("streamSettings", streamSettings)

        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put(
                "outbounds",
                JSONArray()
                    .put(proxyOutbound)
                    .put(
                        JSONObject()
                            .put("tag", "direct")
                            .put("protocol", "freedom")
                            .put("settings", JSONObject().put("domainStrategy", "UseIP"))
                    )
                    .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            )

        return root.toString(2)
    }

    /**
     * Applies user DNS, Routing Mode (GeoIP/Geosite rules when `geoip.dat` & `geosite.dat` are
     * installed, with automatic CIDR fallback), IPv6 strategy, TUN vs local proxy inbounds,
     * ordered proxy/direct/block outbounds, QUIC UDP/443 block, IPv6 leak block, and Xray stats.
     */
    fun applyRuntimeSettingsToXrayJson(
        baseJson: String,
        settings: AppSettingsState,
        hasGeoAssets: Boolean = false
    ): String {
        return try {
            val root = JSONObject(baseJson)

            // 1. Inbounds: keep tun-in when TUN Mode is enabled; otherwise expose SOCKS5 + HTTP proxy
            val existingInbounds = root.optJSONArray("inbounds") ?: JSONArray()
            val updatedInbounds = JSONArray()
            var hasSocks = false
            var hasTun = false
            var hasHttp = false
            for (i in 0 until existingInbounds.length()) {
                val inbound = existingInbounds.optJSONObject(i) ?: continue
                val proto = inbound.optString("protocol", "").lowercase()
                if (proto == "tun") {
                    if (settings.tunMode) {
                        updatedInbounds.put(inbound)
                        hasTun = true
                    }
                } else {
                    if (proto == "socks") hasSocks = true
                    if (proto == "http") hasHttp = true
                    updatedInbounds.put(inbound)
                }
            }
            if (!hasSocks) {
                updatedInbounds.put(
                    JSONObject()
                        .put("tag", "socks-in")
                        .put("port", 10808)
                        .put("listen", "127.0.0.1")
                        .put("protocol", "socks")
                        .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                )
            }
            if (!settings.tunMode && !hasHttp) {
                updatedInbounds.put(
                    JSONObject()
                        .put("tag", "http-in")
                        .put("port", 10809)
                        .put("listen", "127.0.0.1")
                        .put("protocol", "http")
                        .put("settings", JSONObject().put("allowTransparent", false))
                )
            }
            if (settings.tunMode && !hasTun) {
                updatedInbounds.put(
                    JSONObject()
                        .put("tag", "tun-in")
                        .put("protocol", "tun")
                        .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500).put("userLevel", 8))
                        .put(
                            "sniffing",
                            JSONObject()
                                .put("enabled", true)
                                .put("destOverride", JSONArray().put("http").put("tls").put("quic"))
                        )
                )
            }
            root.put("inbounds", updatedInbounds)

            // 2. Normalize outbounds: primary proxy outbound FIRST, followed by direct and block
            val existingOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
            val proxyOutbounds = mutableListOf<JSONObject>()
            var directOutbound: JSONObject? = null
            var blockOutbound: JSONObject? = null

            for (i in 0 until existingOutbounds.length()) {
                val ob = existingOutbounds.optJSONObject(i) ?: continue
                val proto = ob.optString("protocol", "").lowercase()
                val tag = ob.optString("tag", "").lowercase()
                when {
                    proto == "freedom" || tag == "direct" -> if (directOutbound == null) directOutbound = ob
                    proto == "blackhole" || tag == "block" -> if (blockOutbound == null) blockOutbound = ob
                    proto in listOf("dns") -> Unit
                    else -> proxyOutbounds.add(ob)
                }
            }

            val proxyTag = proxyOutbounds.firstOrNull()?.optString("tag")
                ?.takeIf { it.isNotBlank() && it !in listOf("direct", "block", "dns-out") }
                ?: "proxy"
            proxyOutbounds.firstOrNull()?.put("tag", proxyTag)

            val orderedOutbounds = JSONArray()
            proxyOutbounds.forEach { orderedOutbounds.put(it) }
            orderedOutbounds.put(
                directOutbound ?: JSONObject()
                    .put("tag", "direct")
                    .put("protocol", "freedom")
                    .put("settings", JSONObject().put("domainStrategy", "UseIP"))
            )
            orderedOutbounds.put(
                blockOutbound ?: JSONObject()
                    .put("tag", "block")
                    .put("protocol", "blackhole")
            )
            root.put("outbounds", orderedOutbounds)

            // 3. DNS configuration matching user's DNS setting & IPv6 preference
            val dnsServers = JSONArray()
            when {
                settings.dnsMode.contains("8.8.8.8") -> {
                    dnsServers.put("8.8.8.8").put("8.8.4.4")
                }
                settings.dnsMode.contains("9.9.9.9") -> {
                    dnsServers.put("9.9.9.9").put("149.112.112.112")
                }
                settings.dnsMode.contains("94.140.14.14") -> {
                    dnsServers.put("94.140.14.14").put("94.140.15.15")
                }
                settings.dnsMode.contains("1.1.1.1") -> {
                    dnsServers.put("1.1.1.1").put("1.0.0.1")
                }
                else -> {
                    dnsServers.put("https+local://1.1.1.1/dns-query").put("1.1.1.1").put("8.8.8.8")
                }
            }
            root.put(
                "dns",
                JSONObject()
                    .put("servers", dnsServers)
                    .put("queryStrategy", if (settings.ipv6Enabled) "UseIP" else "UseIPv4")
            )

            // 4. Routing rules (real GeoIP/Geosite when geoip.dat/geosite.dat exist, plus CIDR rules)
            val rules = JSONArray()
            if (settings.routingMode.contains("Direct Only", ignoreCase = true)) {
                rules.put(
                    JSONObject()
                        .put("type", "field")
                        .put("network", "tcp,udp")
                        .put("outboundTag", "direct")
                )
            } else {
                if (settings.routingMode.contains("Bypass LAN", ignoreCase = true)) {
                    val bypassCidrs = JSONArray()
                        .put("10.0.0.0/8")
                        .put("172.16.0.0/12")
                        .put("192.168.0.0/16")
                        .put("127.0.0.0/8")
                        .put("169.254.0.0/16")
                        .put("224.0.0.0/4")
                        .put("fc00::/7")
                        .put("fe80::/10")
                        .put("ff00::/8")
                    val includeDomestic = settings.routingMode.contains("Domestic", ignoreCase = true)
                    if (includeDomestic) {
                        bypassCidrs.put("100.64.0.0/10")
                        bypassCidrs.put("198.18.0.0/15")
                        if (hasGeoAssets) {
                            bypassCidrs.put("geoip:private")
                            bypassCidrs.put("geoip:${settings.domesticCountryCode.lowercase()}")
                        }
                    } else if (hasGeoAssets) {
                        bypassCidrs.put("geoip:private")
                    }
                    rules.put(
                        JSONObject()
                            .put("type", "field")
                            .put("ip", bypassCidrs)
                            .put("outboundTag", "direct")
                    )

                    if (hasGeoAssets) {
                        val bypassDomains = JSONArray().put("geosite:private")
                        if (includeDomestic) {
                            val code = settings.domesticCountryCode.lowercase()
                            val geositeTag = if (code == "cn") "geosite:cn" else "geosite:category-$code"
                            bypassDomains.put(geositeTag)
                        }
                        rules.put(
                            JSONObject()
                                .put("type", "field")
                                .put("domain", bypassDomains)
                                .put("outboundTag", "direct")
                        )
                    }
                }
                if (!settings.ipv6Enabled) {
                    rules.put(
                        JSONObject()
                            .put("type", "field")
                            .put("ip", JSONArray().put("::/0"))
                            .put("outboundTag", "block")
                    )
                }
                // Block QUIC (UDP 443) so browsers cleanly fall back to TLS/TCP over the tunnel
                rules.put(
                    JSONObject()
                        .put("type", "field")
                        .put("port", "443")
                        .put("network", "udp")
                        .put("outboundTag", "block")
                )
                // Explicit catch-all routing rule to the primary proxy outbound
                if (proxyOutbounds.isNotEmpty()) {
                    rules.put(
                        JSONObject()
                            .put("type", "field")
                            .put("network", "tcp,udp")
                            .put("outboundTag", proxyTag)
                    )
                }
            }
            root.put(
                "routing",
                JSONObject()
                    .put("domainStrategy", if (hasGeoAssets) "IPIfNonMatch" else "AsIs")
                    .put("rules", rules)
            )

            // 5. Enable Xray internal outbound stats
            if (!root.has("stats")) {
                root.put("stats", JSONObject())
            }
            if (!root.has("policy")) {
                root.put(
                    "policy",
                    JSONObject()
                        .put(
                            "levels",
                            JSONObject().put(
                                "8",
                                JSONObject()
                                    .put("connIdle", 300)
                                    .put("downlinkOnly", 1)
                                    .put("handshake", 4)
                                    .put("uplinkOnly", 1)
                            )
                        )
                        .put(
                            "system",
                            JSONObject()
                                .put("statsOutboundDownlink", true)
                                .put("statsOutboundUplink", true)
                        )
                )
            }

            root.toString(2)
        } catch (_: Exception) {
            baseJson
        }
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                val key = safeUrlDecode(parts[0])
                val value = if (parts.size > 1) safeUrlDecode(parts[1]) else ""
                key to value
            } else null
        }.toMap()
    }

    private fun tryDecodeBase64(input: String): String? {
        return try {
            val sanitized = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            val flags = if (sanitized.contains("-") || sanitized.contains("_")) {
                Base64.URL_SAFE or Base64.NO_WRAP
            } else {
                Base64.DEFAULT
            }
            val bytes = Base64.decode(sanitized, flags)
            val text = String(bytes, Charsets.UTF_8)
            if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) null else text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detects explicit country hints from flag emojis, city/country names, or domain TLDs.
     * Returns null if no explicit country hint is present so GeoIP can resolve the exact country.
     */
    private fun detectExplicitCountryCode(text: String): String? {
        val flagCode = extractFlagEmojiIsoCode(text)
        if (flagCode != null && flagCode !in listOf("EU", "UN", "XX")) {
            return flagCode
        }
        val lower = text.lowercase()
        return when {
            lower.contains("germany") || lower.contains("frankfurt") || lower.contains("falkenstein") || lower.contains("nuremberg") || lower.contains("hetzner") || lower.endsWith(".de") -> "DE"
            lower.contains("singapore") || lower.contains("sgp") || lower.endsWith(".sg") -> "SG"
            lower.contains("netherlands") || lower.contains("amsterdam") || lower.contains("holland") || lower.endsWith(".nl") -> "NL"
            lower.contains("united kingdom") || lower.contains("london") || lower.contains("britain") || lower.endsWith(".uk") -> "GB"
            lower.contains("canada") || lower.contains("toronto") || lower.contains("montreal") || lower.endsWith(".ca") -> "CA"
            lower.contains("france") || lower.contains("paris") || lower.contains("strasbourg") || lower.endsWith(".fr") -> "FR"
            lower.contains("japan") || lower.contains("tokyo") || lower.contains("osaka") || lower.endsWith(".jp") -> "JP"
            lower.contains("india") || lower.contains("mumbai") || lower.contains("bangalore") || lower.endsWith(".in") -> "IN"
            lower.contains("australia") || lower.contains("sydney") || lower.contains("melbourne") || lower.endsWith(".au") -> "AU"
            lower.contains("switzerland") || lower.contains("zurich") || lower.endsWith(".ch") -> "CH"
            lower.contains("korea") || lower.contains("seoul") || lower.endsWith(".kr") -> "KR"
            lower.contains("sweden") || lower.contains("stockholm") || lower.endsWith(".se") -> "SE"
            lower.contains("brazil") || lower.contains("sao paulo") || lower.endsWith(".br") -> "BR"
            lower.contains("finland") || lower.contains("helsinki") || lower.endsWith(".fi") -> "FI"
            lower.contains("turkey") || lower.contains("istanbul") || lower.endsWith(".tr") -> "TR"
            lower.contains("hong kong") || lower.endsWith(".hk") -> "HK"
            lower.contains("poland") || lower.contains("warsaw") || lower.endsWith(".pl") -> "PL"
            lower.contains("russia") || lower.contains("moscow") || lower.endsWith(".ru") -> "RU"
            lower.contains("austria") || lower.contains("vienna") || lower.endsWith(".at") -> "AT"
            lower.contains("united states") || lower.contains("virginia") || lower.contains("los angeles") || lower.contains("new york") || lower.contains("dallas") -> "US"
            else -> null
        }
    }

    fun inferCountryFromRemarkOrHost(remark: String, host: String): Pair<String, String> {
        val explicit = detectExplicitCountryCode("$remark $host")
        if (explicit != null) {
            return countryNameFromIsoCode(explicit) to explicit
        }
        // Mark as "XX" (pending GeoIP resolution after TCP ping test)
        return "Resolving…" to "XX"
    }

    private fun extractFlagEmojiIsoCode(text: String): String? {
        var firstLetter: Char? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val ch = ('A'.code + (cp - 0x1F1E6)).toChar()
                if (firstLetter == null) {
                    firstLetter = ch
                } else {
                    return "$firstLetter$ch"
                }
            } else {
                firstLetter = null
            }
            i += Character.charCount(cp)
        }
        return null
    }

    companion object {
        const val DEFAULT_PUBLIC_V2RAY_SUB_URL = AppSettingsState.DEFAULT_SUBSCRIPTION_URL
        private const val DELAY_PROBE_PRIMARY = "https://www.gstatic.com/generate_204"
        private const val DELAY_PROBE_BACKUP = "https://cp.cloudflare.com/generate_204"
    }
}
