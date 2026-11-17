package com.hollow.vpn.vpn

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Censorship-resistant subscription fetcher & local atomic disk cache:
 * 1. Direct URL via single-roundtrip GET (no slow HEAD preflight) with live line-batch streaming
 *    for plaintext feeds and parallel byte-range sampling for >12 MB files.
 * 2. Optional staggered CDN mirrors (200ms Happy-Eyeballs race) when [mirrorsEnabled] is true.
 * 3. Atomic disk cache fallback (`filesDir/hollow_cache/`) if all network paths fail.
 */
class ResilientFetcher(
    private val httpClient: OkHttpClient
) {
    data class FetchOutcome(
        val body: String,
        val routeLabel: String,
        val fromCache: Boolean
    )

    suspend fun fetchSubscriptionText(
        url: String,
        context: Context?,
        mirrorsEnabled: Boolean
    ): Result<FetchOutcome> = fetchSubscriptionStreaming(
        url = url,
        context = context,
        mirrorsEnabled = mirrorsEnabled,
        onBatchText = {}
    )

    /**
     * Streams plaintext URI subscription feeds in batches as they download so downstream
     * parsing and TCP/Xray probing can start immediately without waiting for EOF.
     */
    suspend fun fetchSubscriptionStreaming(
        url: String,
        context: Context?,
        mirrorsEnabled: Boolean,
        onRouteSelected: (String) -> Unit = {},
        onBatchText: suspend (String) -> Unit
    ): Result<FetchOutcome> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        val routes = buildRouteCandidates(cleanUrl, mirrorsEnabled)

        val winner = raceAndStreamRoutes(
            routes = routes,
            onRouteSelected = onRouteSelected,
            onBatchText = onBatchText
        )
        if (winner != null) {
            if (context != null) {
                saveCacheAtomic(context, cleanUrl, winner.first)
            }
            return@withContext Result.success(
                FetchOutcome(
                    body = winner.first,
                    routeLabel = winner.second,
                    fromCache = false
                )
            )
        }

        if (context != null) {
            val cached = readCache(context, cleanUrl)
            if (!cached.isNullOrBlank()) {
                onRouteSelected("local-cache")
                onBatchText(cached)
                return@withContext Result.success(
                    FetchOutcome(
                        body = cached,
                        routeLabel = "local-cache",
                        fromCache = true
                    )
                )
            }
        }

        Result.failure(IllegalStateException("Could not reach subscription host or mirrors"))
    }

    private suspend fun raceAndStreamRoutes(
        routes: List<Pair<String, String>>,
        onRouteSelected: (String) -> Unit,
        onBatchText: suspend (String) -> Unit
    ): Pair<String, String>? = coroutineScope {
        if (routes.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<Pair<String, String>?>()
        val routeClaimed = AtomicBoolean(false)

        val jobs = routes.mapIndexed { idx, (label, routeUrl) ->
            launch(Dispatchers.IO) {
                if (idx > 0) delay(idx * ROUTE_STAGGER_MS)
                if (winner.isCompleted) return@launch
                val text = streamSingleRoute(
                    routeUrl = routeUrl,
                    routeLabel = label,
                    routeClaimed = routeClaimed,
                    onRouteSelected = onRouteSelected,
                    onBatchText = onBatchText
                )
                if (!text.isNullOrBlank()) {
                    winner.complete(text to label)
                }
            }
        }

        val completionWatcher = launch {
            jobs.forEach { it.join() }
            if (!winner.isCompleted) winner.complete(null)
        }

        val result = winner.await()
        jobs.forEach { it.cancel() }
        completionWatcher.cancel()
        result
    }

    private suspend fun streamSingleRoute(
        routeUrl: String,
        routeLabel: String,
        routeClaimed: AtomicBoolean,
        onRouteSelected: (String) -> Unit,
        onBatchText: suspend (String) -> Unit
    ): String? {
        return try {
            val req = Request.Builder()
                .url(routeUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val contentLength = body.contentLength()
                val acceptsRanges = resp.header("Accept-Ranges")
                    ?.contains("bytes", ignoreCase = true) == true

                if (acceptsRanges && contentLength > LARGE_FILE_THRESHOLD_BYTES) {
                    if (!routeClaimed.compareAndSet(false, true)) return null
                    onRouteSelected(routeLabel)
                    resp.close()
                    return sampleLargeSubscriptionByRangeParallel(
                        url = routeUrl,
                        totalBytes = contentLength,
                        onBatchText = onBatchText
                    )
                }

                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 16 * 1024)
                val fullAccumulated = StringBuilder(64 * 1024)
                val batchLines = ArrayList<String>(STREAM_BATCH_LINES)
                var isPlaintextStream: Boolean? = null
                var totalChars = 0

                while (totalChars < MAX_BODY_CHARS) {
                    val line = reader.readLine() ?: break
                    if (fullAccumulated.isNotEmpty()) fullAccumulated.append('\n')
                    fullAccumulated.append(line)
                    totalChars += line.length + 1

                    if (isPlaintextStream == null && (totalChars >= 256 || fullAccumulated.length >= 256)) {
                        val head = fullAccumulated.toString()
                        if (looksLikeHtmlBlockPage(head)) {
                            return null
                        }
                        if (!routeClaimed.compareAndSet(false, true)) {
                            return null
                        }
                        onRouteSelected(routeLabel)
                        isPlaintextStream = head.contains("://")
                    }

                    if (isPlaintextStream == true) {
                        if (line.isNotBlank()) batchLines.add(line)
                        if (batchLines.size >= STREAM_BATCH_LINES) {
                            onBatchText(batchLines.joinToString("\n"))
                            batchLines.clear()
                        }
                    }
                }

                val fullText = fullAccumulated.toString()
                if (fullText.isBlank() || looksLikeHtmlBlockPage(fullText)) return null

                if (isPlaintextStream == null) {
                    // Small payload (< 256 chars)
                    if (!routeClaimed.compareAndSet(false, true)) return null
                    onRouteSelected(routeLabel)
                    onBatchText(fullText)
                } else if (isPlaintextStream == true) {
                    if (batchLines.isNotEmpty()) {
                        onBatchText(batchLines.joinToString("\n"))
                        batchLines.clear()
                    }
                } else {
                    // Base64 or JSON payload needs full body before decode
                    onBatchText(fullText)
                }

                fullText
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads 6 random 256 KB byte-range windows IN PARALLEL across huge >12 MB subscription files,
     * emitting each window immediately into the downstream testing channel.
     */
    private suspend fun sampleLargeSubscriptionByRangeParallel(
        url: String,
        totalBytes: Long,
        onBatchText: suspend (String) -> Unit
    ): String? = coroutineScope {
        val windowSize = 256L * 1024L
        val maxStart = (totalBytes - windowSize).coerceAtLeast(0L)
        val starts = List(6) {
            if (maxStart == 0L) 0L else Random.nextLong(0L, maxStart)
        }.distinct()

        val chunks = starts.map { start ->
            async(Dispatchers.IO) {
                val end = (start + windowSize - 1).coerceAtMost(totalBytes - 1)
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Range", "bytes=$start-$end")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.code == 206 || resp.isSuccessful) {
                            val raw = resp.body?.string().orEmpty()
                            if (!looksLikeHtmlBlockPage(raw)) {
                                val lines = raw.lines()
                                val trimmedLines = if (lines.size > 2) lines.drop(1).dropLast(1) else lines
                                val chunk = trimmedLines.joinToString("\n")
                                if (chunk.contains("://")) {
                                    onBatchText(chunk)
                                    chunk
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()

        chunks.joinToString("\n").takeIf { it.isNotBlank() }
    }

    internal fun buildRouteCandidates(url: String, mirrorsEnabled: Boolean): List<Pair<String, String>> {
        val routes = mutableListOf("direct" to url)
        if (!mirrorsEnabled) return routes

        val rawPrefix = "https://raw.githubusercontent.com/"
        if (url.startsWith(rawPrefix, ignoreCase = true)) {
            val rest = url.substring(rawPrefix.length)
            val segments = rest.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 4) {
                val owner = segments[0]
                val repo = segments[1]
                val (branch, pathSegments) = if (segments.size >= 6 &&
                    segments[2] == "refs" &&
                    (segments[3] == "heads" || segments[3] == "tags")
                ) {
                    segments[4] to segments.drop(5)
                } else {
                    segments[2] to segments.drop(3)
                }
                val path = pathSegments.joinToString("/")
                if (path.isNotBlank()) {
                    routes += "jsdelivr" to "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
                    routes += "githack" to "https://raw.githack.com/$owner/$repo/$branch/$path"
                }
            }
        }
        return routes
    }

    private fun looksLikeHtmlBlockPage(body: String): Boolean {
        val head = body.trimStart().take(256).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun cacheFileForUrl(context: Context, url: String): File {
        val dir = File(context.applicationContext.filesDir, "hollow_cache").apply {
            if (!exists()) mkdirs()
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(dir, "sub_$hash.txt")
    }

    private fun saveCacheAtomic(context: Context, url: String, content: String) {
        if (content.isBlank()) return
        try {
            val target = cacheFileForUrl(context, url)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(content, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun readCache(context: Context, url: String): String? {
        return try {
            val file = cacheFileForUrl(context, url)
            if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ROUTE_STAGGER_MS = 200L
        private const val LARGE_FILE_THRESHOLD_BYTES = 12L * 1024L * 1024L
        private const val MAX_BODY_CHARS = 12 * 1024 * 1024
        private const val STREAM_BATCH_LINES = 140
        private const val USER_AGENT = "v2rayNG/1.9.0 Xray/25.1.1 HollowVPN/1.0"
    }
}
