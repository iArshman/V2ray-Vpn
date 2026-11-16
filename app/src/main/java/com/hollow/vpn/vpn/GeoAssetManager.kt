package com.hollow.vpn.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Manages optional GeoIP / Geosite routing database files (`geoip.dat` & `geosite.dat`)
 * inside Xray's `assetPath` directory so `Bypass LAN & Domestic` can use real country rules
 * instead of CIDR-only approximations.
 */
object GeoAssetManager {

    private const val GEOIP_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
    private const val GEOSITE_URL =
        "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    fun getAssetDirectory(context: Context): File {
        val dir = context.applicationContext.getExternalFilesDir("assets")
            ?: context.applicationContext.getDir("assets", Context.MODE_PRIVATE)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun hasGeoAssets(context: Context?): Boolean {
        if (context == null) return false
        val dir = getAssetDirectory(context)
        val geoip = File(dir, "geoip.dat")
        val geosite = File(dir, "geosite.dat")
        return geoip.exists() && geoip.length() > 100_000L &&
            geosite.exists() && geosite.length() > 100_000L
    }

    fun getGeoAssetsSummary(context: Context): String {
        val dir = getAssetDirectory(context)
        val geoip = File(dir, "geoip.dat")
        val geosite = File(dir, "geosite.dat")
        return if (hasGeoAssets(context)) {
            val totalMb = (geoip.length() + geosite.length()) / (1024.0 * 1024.0)
            String.format(java.util.Locale.US, "Installed (%.1f MB)", totalMb)
        } else {
            "Not downloaded (CIDR fallback)"
        }
    }

    suspend fun downloadGeoAssets(
        context: Context,
        socksProxyPort: Int? = null,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dir = getAssetDirectory(context)
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
            if (socksProxyPort != null) {
                clientBuilder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksProxyPort)))
            }
            val client = clientBuilder.build()

            onProgress("Downloading geoip.dat…")
            downloadFileAtomic(client, GEOIP_URL, File(dir, "geoip.dat"))

            onProgress("Downloading geosite.dat…")
            downloadFileAtomic(client, GEOSITE_URL, File(dir, "geosite.dat"))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFileAtomic(client: OkHttpClient, url: String, target: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HollowVPN/1.0")
            .build()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} downloading ${target.name}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body for ${target.name}")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (tmp.length() < 100_000L) {
            tmp.delete()
            throw IllegalStateException("Downloaded ${target.name} is unexpectedly small")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
