package com.hollow.vpn.vpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hollow.vpn.data.AppSettingsState
import com.hollow.vpn.data.VpnDatabase
import com.hollow.vpn.data.VpnPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Background maintenance using Android JobScheduler:
 * 1. Lightweight health pulse (every 3h, runs even while VPN is up because Hollow excludes itself)
 *    to mark kept reserve servers alive/dead (`lastAliveAt`).
 * 2. Unmetered full refresh (every 6h + 3-min catch-up after tunnel stops) when <60% of the
 *    saved reserve answered within the last 12 hours.
 */
class HollowBackgroundJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val jobId = params?.jobId ?: return false
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                when (jobId) {
                    HollowJobScheduler.JOB_ID_PULSE -> runHealthPulse(applicationContext)
                    HollowJobScheduler.JOB_ID_REFRESH,
                    HollowJobScheduler.JOB_ID_CATCHUP -> runFullRefreshIfNeeded(applicationContext)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ALIVE_TTL_MILLIS = 12L * 60 * 60 * 1000
        private const val HEALTHY_RESERVE_RATIO = 0.60

        internal suspend fun runHealthPulse(context: Context) {
            val dao = VpnDatabase.getInstance(context).vpnDao()
            val prefs = VpnPreferencesStore(context)
            val settings = prefs.loadSettings()
            val servers = dao.getAllServersOnce().take(40)
            if (servers.isEmpty()) return

            val parser = V2RayParserAndFletcher()
            val sem = Semaphore(4)
            val now = System.currentTimeMillis()

            coroutineScope {
                servers.map { server ->
                    async {
                        sem.withPermit {
                            val result = parser.measureRealXrayDelayOrNull(context, server, settings)
                            val updated = when (result) {
                                is XrayProbeResult.Success -> server.copy(
                                    pingMs = result.delayMs,
                                    lastAliveAt = now,
                                    failCount = 0
                                )
                                XrayProbeResult.NativeUnavailable -> {
                                    val tcp = parser.measureRealTcpLatencyOrNull(server.host, server.port, 1600)
                                    if (tcp != null) server.copy(pingMs = tcp, lastAliveAt = now, failCount = 0)
                                    else server.copy(pingMs = 0, failCount = server.failCount + 1)
                                }
                                XrayProbeResult.Unreachable -> server.copy(
                                    pingMs = 0,
                                    failCount = server.failCount + 1
                                )
                            }
                            dao.updateServer(updated)
                        }
                    }
                }.awaitAll()
            }
            prefs.saveLastHealthPulseMillis(now)
        }

        internal suspend fun runFullRefreshIfNeeded(context: Context) {
            // Never saturate the radio with a full fetch + speed test while the tunnel is active.
            if (XrayVpnService.isServiceRunning.value) return

            val prefs = VpnPreferencesStore(context)
            val settings = prefs.loadSettings()
            if (!settings.subAutoUpdate) return

            val dao = VpnDatabase.getInstance(context).vpnDao()
            val existing = dao.getAllServersOnce()
            val now = System.currentTimeMillis()
            if (existing.isNotEmpty()) {
                val aliveRecently = existing.count {
                    it.pingMs > 0 && it.lastAliveAt > 0L && now - it.lastAliveAt <= ALIVE_TTL_MILLIS
                }
                val ratio = aliveRecently.toDouble() / existing.size.toDouble()
                if (ratio >= HEALTHY_RESERVE_RATIO) {
                    return
                }
            }

            val subs = dao.getSubscriptionsOnce().filter { it.isEnabled && it.url.isNotBlank() }
            val urls = subs.map { it.url }.ifEmpty {
                listOfNotNull(settings.activeSubscriptionUrl.trim().takeIf { it.isNotBlank() })
            }
            if (urls.isEmpty()) return

            val parser = V2RayParserAndFletcher()
            val result = parser.fetchMultipleSubscriptionsAndRankTop15(
                subscriptions = subs,
                fallbackUrls = urls,
                context = context,
                settings = settings,
                onSubscriptionStats = { sub, ok, count ->
                    val updated = if (ok) {
                        sub.copy(
                            successPulls = sub.successPulls + 1,
                            lastFetchedAt = System.currentTimeMillis(),
                            serverCount = count
                        )
                    } else {
                        sub.copy(failedPulls = sub.failedPulls + 1)
                    }
                    dao.updateSubscription(updated)
                }
            )
            result.onSuccess { ranked ->
                if (settings.subReplaceExisting) {
                    dao.deleteFetchedServers()
                }
                dao.clearSelection()
                val marked = ranked.mapIndexed { idx, srv -> srv.copy(isSelected = idx == 0) }
                dao.insertServers(marked)
            }
        }
    }
}

object HollowJobScheduler {
    const val JOB_ID_PULSE = 4101
    const val JOB_ID_REFRESH = 4102
    const val JOB_ID_CATCHUP = 4103

    private const val PULSE_INTERVAL_MS = 3L * 60 * 60 * 1000
    private const val REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000
    private const val CATCHUP_DELAY_MS = 3L * 60 * 1000

    fun schedulePeriodicMaintenance(context: Context, settings: AppSettingsState) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val component = ComponentName(context, HollowBackgroundJobService::class.java)

        val pulseJob = JobInfo.Builder(JOB_ID_PULSE, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PULSE_INTERVAL_MS)
            .setPersisted(true)
            .build()
        scheduler.schedule(pulseJob)

        if (settings.subAutoUpdate) {
            val refreshJob = JobInfo.Builder(JOB_ID_REFRESH, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(REFRESH_INTERVAL_MS)
                .setPersisted(true)
                .build()
            scheduler.schedule(refreshJob)
        } else {
            scheduler.cancel(JOB_ID_REFRESH)
        }
    }

    fun scheduleCatchUpRefresh(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val component = ComponentName(context, HollowBackgroundJobService::class.java)
        val catchUpJob = JobInfo.Builder(JOB_ID_CATCHUP, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(CATCHUP_DELAY_MS)
            .setOverrideDeadline(CATCHUP_DELAY_MS * 4)
            .build()
        scheduler.schedule(catchUpJob)
    }
}
