package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.mpp.MppPathParser
import com.v2ray.ang.mpp.MppPathUnderlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else {
            block()
        }
    }
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.MPP) {
            return measureMppTcpEndpoint(config)
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return RealPingExecutionLimiter.run(config.configType) {
            CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
        }
    }

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (config.configType == EConfigType.MPP) {
            return measureMppTcpEndpoint(config)
        }
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, 1000)

            return tcpTime
        }

        return retFailure
    }

    /**
     * Batch tests must not feed an MPP profile into Xray or mark an intentionally UDP-only MPP
     * profile invalid. A fixed TCP carrier gets a cheap endpoint reachability measurement; raw,
     * ranged-only, and UDP-only profiles remain untested (zero) and are never auto-removed.
     */
    private fun measureMppTcpEndpoint(profile: ProfileItem): Long {
        return when (val selection = MppTcpProbeSelector.select(profile)) {
            MppTcpProbeSelection.Invalid -> -1L
            MppTcpProbeSelection.Untested -> 0L
            is MppTcpProbeSelection.Endpoint -> SpeedtestManager.socketConnectTime(
                selection.host,
                selection.port,
                1_000,
            )
        }
    }
}

internal sealed interface MppTcpProbeSelection {
    data class Endpoint(val host: String, val port: Int) : MppTcpProbeSelection
    data object Untested : MppTcpProbeSelection
    data object Invalid : MppTcpProbeSelection
}

internal object MppTcpProbeSelector {
    fun select(profile: ProfileItem): MppTcpProbeSelection {
        val config = profile.mpp ?: return MppTcpProbeSelection.Invalid
        if (config.useRawToml) return MppTcpProbeSelection.Untested
        val explicitPaths = config.paths
        if (explicitPaths == null) {
            if (!config.tcpEnabled) return MppTcpProbeSelection.Untested
            val host = profile.server?.takeIf(String::isNotBlank)
                ?: return MppTcpProbeSelection.Invalid
            val port = config.tcpPort.takeIf { it in 1..65_535 }
                ?: return MppTcpProbeSelection.Invalid
            return MppTcpProbeSelection.Endpoint(host, port)
        }

        if (explicitPaths.isEmpty() || explicitPaths.size > MAX_PATH_ENTRIES) {
            return MppTcpProbeSelection.Invalid
        }
        val names = HashSet<String>(explicitPaths.size)
        var carrierSlots = 0
        val parsedPaths = explicitPaths.map { path ->
            if (!MppPathParser.isCanonicalName(path.name) || !names.add(path.name)) {
                return MppTcpProbeSelection.Invalid
            }
            val parsed = MppPathParser.parse(path.endpoint)
                ?: return MppTcpProbeSelection.Invalid
            carrierSlots += parsed.carrierSlots
            if (carrierSlots > MAX_CARRIER_SLOTS) return MppTcpProbeSelection.Invalid
            parsed
        }
        val endpoint = parsedPaths.firstOrNull { path ->
            path.underlay == MppPathUnderlay.TCP && !path.isPortRange
        } ?: return MppTcpProbeSelection.Untested
        return MppTcpProbeSelection.Endpoint(endpoint.host, endpoint.firstPort)
    }

    private const val MAX_PATH_ENTRIES = 64
    private const val MAX_CARRIER_SLOTS = 64
}
