package com.hollow.vpn.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vpn_servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val countryName: String,
    val countryCode: String,
    val protocolDisplay: String, // e.g., "V2Ray · Reality · 185.216.34.12"
    val protocolCore: String, // e.g., "VLESS (V2Ray)", "VMess (V2Ray)", "Trojan (V2Ray)"
    val transport: String, // "Reality", "VLESS", "Trojan", "gRPC", "WebSocket", "TCP"
    val host: String,
    val resolvedIp: String = "",
    val port: Int,
    val uuidOrPassword: String,
    val sni: String = "",
    val publicKey: String = "",
    val rawConfigJson: String = "",
    val shareUri: String = "",
    val pingMs: Int,
    val isRecommendedTop15: Boolean = true,
    val rankOrder: Int = 99,
    val isSelected: Boolean = false,
    val isCustom: Boolean = false,
    val subscriptionUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastSpeedMbps: Double = 0.0,
    val lastAliveAt: Long = 0L,
    val protocolTier: Int = 5,
    val failCount: Int = 0
)

@Entity(tableName = "vpn_subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String = "Custom Subscription",
    val autoUpdate: Boolean = true,
    val updateIntervalHours: Int = 1,
    val replaceExisting: Boolean = true,
    val lastFetchedAt: Long = System.currentTimeMillis(),
    val serverCount: Int = 0,
    val isEnabled: Boolean = true,
    val successPulls: Int = 0,
    val failedPulls: Int = 0
)

enum class ServerFilter(val label: String) {
    FASTEST("Fastest"),
    ALL("All"),
    RECOMMENDED("Recommended"),
    LOW_PING("Low Ping"),
    HIGH_SPEED("High Speed")
}

data class AppSettingsState(
    val autoConfigure: Boolean = true,
    val updateIntervalLabel: String = "1 hour",
    val protocolPreference: String = "Auto (V2Ray)",
    val routingMode: String = "Global",
    val dnsMode: String = "Auto (DoH)",
    val tunMode: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val startOnAppOpen: Boolean = false,
    val hideFromRecentApps: Boolean = false,
    val subAutoUpdate: Boolean = true,
    val subUpdateInterval: String = "1 hour",
    val subReplaceExisting: Boolean = true,
    val activeSubscriptionUrl: String = DEFAULT_SUBSCRIPTION_URL,
    val perAppProxyEnabled: Boolean = false,
    val bypassSelectedApps: Boolean = false,
    val selectedAppPackages: Set<String> = emptySet(),
    val hasCompletedOnboarding: Boolean = false,
    val smartSwitchEnabled: Boolean = false,
    val mirrorsEnabled: Boolean = false,
    val strictTlsEnabled: Boolean = false,
    val domesticCountryCode: String = "IR"
) {
    companion object {
        const val DEFAULT_SUBSCRIPTION_URL = ""
        internal const val LEGACY_DEFAULT_SUBSCRIPTION_URL =
            "https://raw.githubusercontent.com/mirrorrepomagic/free-v2ray-public-list/refs/heads/main/V2Ray-Config-By-EbraSha.txt"
    }
}

data class ActiveTunnelSnapshot(
    val country: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val xrayJson: String,
    val tunMode: Boolean,
    val enableIpv6: Boolean,
    val dnsMode: String,
    val routingMode: String,
    val perAppProxyEnabled: Boolean,
    val bypassSelectedApps: Boolean,
    val selectedAppPackages: Set<String>,
    val smartSwitchEnabled: Boolean = false,
    val serverSpeedMbps: Double = 0.0
)

class VpnPreferencesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v2ray_vpn_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettingsState {
        val rawSubUrl = prefs.getString("active_sub_url", "")?.trim().orEmpty()
        val cleanSubUrl = if (rawSubUrl == AppSettingsState.LEGACY_DEFAULT_SUBSCRIPTION_URL) "" else rawSubUrl
        return AppSettingsState(
            autoConfigure = prefs.getBoolean("auto_configure", true),
            updateIntervalLabel = prefs.getString("update_interval", "1 hour") ?: "1 hour",
            protocolPreference = prefs.getString("protocol_pref", "Auto (V2Ray)") ?: "Auto (V2Ray)",
            routingMode = prefs.getString("routing_mode", "Global") ?: "Global",
            dnsMode = prefs.getString("dns_mode", "Auto (DoH)") ?: "Auto (DoH)",
            tunMode = prefs.getBoolean("tun_mode", true),
            ipv6Enabled = prefs.getBoolean("ipv6_enabled", false),
            startOnAppOpen = prefs.getBoolean("start_on_open_v2", false),
            hideFromRecentApps = prefs.getBoolean("hide_recents", false),
            subAutoUpdate = prefs.getBoolean("sub_auto_update", true),
            subUpdateInterval = prefs.getString("sub_update_interval", "1 hour") ?: "1 hour",
            subReplaceExisting = prefs.getBoolean("sub_replace_existing", true),
            activeSubscriptionUrl = cleanSubUrl,
            perAppProxyEnabled = prefs.getBoolean("per_app_proxy", false),
            bypassSelectedApps = prefs.getBoolean("bypass_selected_apps", false),
            selectedAppPackages = prefs.getStringSet("per_app_packages", emptySet()) ?: emptySet(),
            hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false),
            smartSwitchEnabled = prefs.getBoolean("smart_switch_enabled", false),
            mirrorsEnabled = prefs.getBoolean("mirrors_enabled", false),
            strictTlsEnabled = prefs.getBoolean("strict_tls_enabled", false),
            domesticCountryCode = prefs.getString("domestic_country_code", "IR") ?: "IR"
        )
    }

    fun saveSettings(state: AppSettingsState) {
        prefs.edit()
            .putBoolean("auto_configure", state.autoConfigure)
            .putString("update_interval", state.updateIntervalLabel)
            .putString("protocol_pref", state.protocolPreference)
            .putString("routing_mode", state.routingMode)
            .putString("dns_mode", state.dnsMode)
            .putBoolean("tun_mode", state.tunMode)
            .putBoolean("ipv6_enabled", state.ipv6Enabled)
            .putBoolean("start_on_open_v2", state.startOnAppOpen)
            .putBoolean("hide_recents", state.hideFromRecentApps)
            .putBoolean("sub_auto_update", state.subAutoUpdate)
            .putString("sub_update_interval", state.subUpdateInterval)
            .putBoolean("sub_replace_existing", state.subReplaceExisting)
            .putString("active_sub_url", state.activeSubscriptionUrl)
            .putBoolean("per_app_proxy", state.perAppProxyEnabled)
            .putBoolean("bypass_selected_apps", state.bypassSelectedApps)
            .putStringSet("per_app_packages", state.selectedAppPackages)
            .putBoolean("has_completed_onboarding", state.hasCompletedOnboarding)
            .putBoolean("smart_switch_enabled", state.smartSwitchEnabled)
            .putBoolean("mirrors_enabled", state.mirrorsEnabled)
            .putBoolean("strict_tls_enabled", state.strictTlsEnabled)
            .putString("domestic_country_code", state.domesticCountryCode)
            .apply()
    }

    fun saveActiveTunnelSnapshot(snapshot: ActiveTunnelSnapshot) {
        prefs.edit()
            .putString("snap_country", snapshot.country)
            .putString("snap_host", snapshot.host)
            .putInt("snap_port", snapshot.port)
            .putString("snap_protocol", snapshot.protocol)
            .putString("snap_xray_json", snapshot.xrayJson)
            .putBoolean("snap_tun_mode", snapshot.tunMode)
            .putBoolean("snap_ipv6", snapshot.enableIpv6)
            .putString("snap_dns_mode", snapshot.dnsMode)
            .putString("snap_routing_mode", snapshot.routingMode)
            .putBoolean("snap_per_app", snapshot.perAppProxyEnabled)
            .putBoolean("snap_bypass_selected", snapshot.bypassSelectedApps)
            .putStringSet("snap_selected_packages", snapshot.selectedAppPackages)
            .putBoolean("snap_smart_switch", snapshot.smartSwitchEnabled)
            .putFloat("snap_server_speed_mbps", snapshot.serverSpeedMbps.toFloat())
            .apply()
    }

    fun loadActiveTunnelSnapshot(): ActiveTunnelSnapshot? {
        val host = prefs.getString("snap_host", "").orEmpty()
        val xrayJson = prefs.getString("snap_xray_json", "").orEmpty()
        if (host.isBlank() || xrayJson.isBlank()) return null
        return ActiveTunnelSnapshot(
            country = prefs.getString("snap_country", "Server") ?: "Server",
            host = host,
            port = prefs.getInt("snap_port", 443),
            protocol = prefs.getString("snap_protocol", "VLESS (V2Ray)") ?: "VLESS (V2Ray)",
            xrayJson = xrayJson,
            tunMode = prefs.getBoolean("snap_tun_mode", true),
            enableIpv6 = prefs.getBoolean("snap_ipv6", false),
            dnsMode = prefs.getString("snap_dns_mode", "Auto (DoH)") ?: "Auto (DoH)",
            routingMode = prefs.getString("snap_routing_mode", "Global") ?: "Global",
            perAppProxyEnabled = prefs.getBoolean("snap_per_app", false),
            bypassSelectedApps = prefs.getBoolean("snap_bypass_selected", false),
            selectedAppPackages = prefs.getStringSet("snap_selected_packages", emptySet()) ?: emptySet(),
            smartSwitchEnabled = prefs.getBoolean("snap_smart_switch", false),
            serverSpeedMbps = prefs.getFloat("snap_server_speed_mbps", 0f).toDouble()
        )
    }

    fun clearActiveTunnelSnapshot() {
        prefs.edit()
            .remove("snap_country")
            .remove("snap_host")
            .remove("snap_port")
            .remove("snap_protocol")
            .remove("snap_xray_json")
            .apply()
    }

    fun saveBaselineSpeed(networkKey: String, mbps: Double, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString("baseline_network_key", networkKey)
            .putFloat("baseline_mbps", mbps.toFloat())
            .putLong("baseline_updated_at", nowMillis)
            .apply()
    }

    fun loadBaselineSpeed(networkKey: String, maxAgeMillis: Long = 6L * 60 * 60 * 1000, nowMillis: Long = System.currentTimeMillis()): Double {
        val savedKey = prefs.getString("baseline_network_key", "").orEmpty()
        val updatedAt = prefs.getLong("baseline_updated_at", 0L)
        if (savedKey != networkKey || updatedAt <= 0L || nowMillis - updatedAt > maxAgeMillis) return 0.0
        return prefs.getFloat("baseline_mbps", 0f).toDouble()
    }

    fun invalidateBaseline() {
        prefs.edit()
            .remove("baseline_network_key")
            .remove("baseline_mbps")
            .remove("baseline_updated_at")
            .apply()
    }

    fun saveLastHealthPulseMillis(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("last_health_pulse_at", nowMillis).apply()
    }

    fun loadLastHealthPulseMillis(): Long = prefs.getLong("last_health_pulse_at", 0L)
}

@Dao
interface VpnDao {
    @Query("SELECT * FROM vpn_servers ORDER BY isSelected DESC, pingMs ASC")
    fun observeAllServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM vpn_servers ORDER BY pingMs ASC")
    suspend fun getAllServersOnce(): List<ServerEntity>

    @Query("SELECT * FROM vpn_servers WHERE isSelected = 1 LIMIT 1")
    fun observeSelectedServer(): Flow<ServerEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Query("UPDATE vpn_servers SET isSelected = 0")
    suspend fun clearSelection()

    @Query("UPDATE vpn_servers SET isSelected = 1 WHERE id = :serverId")
    suspend fun markSelected(serverId: Long)

    @Query("DELETE FROM vpn_servers WHERE id = :serverId")
    suspend fun deleteServerById(serverId: Long)

    @Query("DELETE FROM vpn_servers WHERE isCustom = 0")
    suspend fun deleteFetchedServers()

    @Query("DELETE FROM vpn_servers")
    suspend fun deleteAllServers()

    @Query("SELECT * FROM vpn_subscriptions ORDER BY lastFetchedAt DESC")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM vpn_subscriptions ORDER BY lastFetchedAt DESC")
    suspend fun getSubscriptionsOnce(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Query("DELETE FROM vpn_subscriptions WHERE url = :url")
    suspend fun deleteSubscriptionByUrl(url: String)

    @Query("DELETE FROM vpn_subscriptions")
    suspend fun deleteAllSubscriptions()
}

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class],
    version = 7,
    exportSchema = false
)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun vpnDao(): VpnDao

    companion object {
        @Volatile
        private var INSTANCE: VpnDatabase? = null

        fun getInstance(context: Context): VpnDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VpnDatabase::class.java,
                    "v2ray_xray_vpn.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
