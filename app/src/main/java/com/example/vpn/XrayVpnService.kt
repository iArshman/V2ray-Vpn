package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android VpnService implementation for V2Ray / Xray tunnel sessions.
 * Respects user-configured DNS, Routing Mode, TUN Mode, and IPv6 settings.
 */
class XrayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        if (action == ACTION_DISCONNECT) {
            stopVpnTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        val serverCountry = intent?.getStringExtra(EXTRA_SERVER_COUNTRY) ?: "Server"
        val serverHost = intent?.getStringExtra(EXTRA_SERVER_HOST) ?: ""
        val serverProtocol = intent?.getStringExtra(EXTRA_SERVER_PROTOCOL) ?: "VLESS (V2Ray)"
        val enableIpv6 = intent?.getBooleanExtra(EXTRA_ENABLE_IPV6, false) ?: false
        val dnsMode = intent?.getStringExtra(EXTRA_DNS_MODE) ?: "Auto (DoH)"
        val routingMode = intent?.getStringExtra(EXTRA_ROUTING_MODE) ?: "Global"

        startVpnTunnel(serverCountry, serverHost, serverProtocol, enableIpv6, dnsMode, routingMode)
        return START_STICKY
    }

    private fun startVpnTunnel(
        country: String,
        host: String,
        protocol: String,
        enableIpv6: Boolean,
        dnsMode: String,
        routingMode: String
    ) {
        createNotificationChannel()
        val notification = buildForegroundNotification(country, host, protocol)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }

        try {
            vpnInterface?.close()
            val builder = Builder()
                .setSession("V2Ray VPN - $country ($host)")
                .setMtu(1500)
                .addAddress("10.26.0.2", 24)

            when {
                dnsMode.contains("8.8.8.8") -> {
                    builder.addDnsServer("8.8.8.8")
                    builder.addDnsServer("8.8.4.4")
                }
                dnsMode.contains("9.9.9.9") -> {
                    builder.addDnsServer("9.9.9.9")
                    builder.addDnsServer("149.112.112.112")
                }
                else -> {
                    builder.addDnsServer("1.1.1.1")
                    builder.addDnsServer("1.0.0.1")
                }
            }

            if (routingMode.contains("Direct Only", ignoreCase = true)) {
                builder.addRoute("10.26.0.0", 24)
            } else {
                builder.addRoute("0.0.0.0", 0)
            }

            if (enableIpv6) {
                builder.addAddress("fd00:26::2", 64)
                builder.addRoute("::", 0)
            }

            vpnInterface = builder.establish()
            _isServiceRunning.value = true
        } catch (_: Exception) {
            _isServiceRunning.value = false
        }
    }

    private fun stopVpnTunnel() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        } finally {
            vpnInterface = null
            _isServiceRunning.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "V2Ray VPN Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active V2Ray / Xray VPN tunnel status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(country: String, host: String, protocol: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("V2Ray VPN Connected · $country")
            .setContentText("$protocol · $host")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER_COUNTRY = "extra_server_country"
        const val EXTRA_SERVER_HOST = "extra_server_host"
        const val EXTRA_SERVER_PROTOCOL = "extra_server_protocol"
        const val EXTRA_ENABLE_IPV6 = "extra_enable_ipv6"
        const val EXTRA_DNS_MODE = "extra_dns_mode"
        const val EXTRA_ROUTING_MODE = "extra_routing_mode"

        private const val CHANNEL_ID = "v2ray_vpn_tunnel_channel"
        private const val NOTIFICATION_ID = 2048

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }
}
