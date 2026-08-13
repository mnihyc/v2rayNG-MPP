package com.v2ray.ang.core

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.mpp.MptunnelNative
import com.v2ray.ang.mpp.MptunnelRuntimeWatchdogPolicy
import com.v2ray.ang.mpp.SocketProtector
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    private val coreControllerDelegate = lazy {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val coreController: CoreController by coreControllerDelegate
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private var activeEngine = ActiveEngine.NONE
    private var mptunnelWatchdogJob: Job? = null
    private var mptunnelWatchdogGeneration = 0L

    private const val MPTUNNEL_WATCHDOG_INTERVAL_MS = 500L

    private enum class ActiveEngine {
        NONE,
        XRAY,
        MPTUNNEL,
    }

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = when (activeEngine) {
        ActiveEngine.MPTUNNEL -> MptunnelNative.isRunning()
        ActiveEngine.XRAY -> coreControllerDelegate.isInitialized() && coreController.isRunning
        ActiveEngine.NONE -> false
    }

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /** MPTUNNEL must stop before its owning service, HEV bridge, and VPN TUN are released. */
    @Synchronized
    fun isMptunnelActive(): Boolean = activeEngine == ActiveEngine.MPTUNNEL

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    @Synchronized
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val stoppedCleanly = stopEngineAfterFailedStart()
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            if (stoppedCleanly) {
                NotificationManager.cancelNotification()
            }
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        val engineName = if (config.configType == EConfigType.MPP) "MPTUNNEL" else "Xray"
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting $engineName for ${config.remarks}")
        currentConfig = config
        if (config.configType == EConfigType.MPP) {
            launchMptunnel(service, guid, config)
        } else {
            launchXray(service, guid, config, vpnInterface)
        }

        if (!isRunning()) {
            error("$engineName failed to start")
        }

        NotificationManager.showNotification(currentConfig)
        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: $engineName started successfully")
    }

    private fun launchMptunnel(service: Service, guid: String, config: ProfileItem) {
        stopBrowserDialer(reconcileXray = false)
        activeEngine = ActiveEngine.MPTUNNEL
        val protector = SocketProtector { fd ->
            serviceControl?.get()?.vpnProtect(fd) == true
        }
        val started = MptunnelNative.start(
            context = service,
            profileId = guid,
            profile = config,
            socksPort = SettingsManager.getSocksPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword(),
            protector = protector,
        )
        if (!started) error("MPTUNNEL native runtime rejected startup")
        startMptunnelWatchdog(serviceControl?.get() ?: error("MPTUNNEL service owner is unavailable"))
    }

    private fun launchXray(
        service: Service,
        guid: String,
        config: ProfileItem,
        vpnInterface: ParcelFileDescriptor?,
    ) {
        initializeXrayHost(service)
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        activeEngine = ActiveEngine.XRAY
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        stopBrowserDialer(reconcileXray = false)
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }
    }

    private fun initializeXrayHost(service: Service) {
        CoreNativeManager.initCoreEnv(service)
        if (processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            processFinder = XrayProcessFinder(service)
            coreController.registerProcessFinder(processFinder)
        }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    @Synchronized
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        val stoppedEngine = activeEngine
        if (stoppedEngine == ActiveEngine.MPTUNNEL) {
            cancelMptunnelWatchdog()
        }
        val stoppedCleanly = when (stoppedEngine) {
            ActiveEngine.MPTUNNEL -> {
                try {
                    MptunnelNative.stop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop MPTUNNEL", e)
                    false
                }
            }

            ActiveEngine.XRAY -> {
                if (coreControllerDelegate.isInitialized() && coreController.isRunning) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            coreController.stopLoop()
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop Xray loop", e)
                        }
                    }
                }
                true
            }

            ActiveEngine.NONE -> true
        }
        if (!stoppedCleanly) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: MPTUNNEL stop timed out")
            serviceControl?.get()?.let(::startMptunnelWatchdog)
            return false
        }
        activeEngine = ActiveEngine.NONE

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null

        stopBrowserDialer(reconcileXray = stoppedEngine == ActiveEngine.XRAY)

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    private fun stopEngineAfterFailedStart(): Boolean {
        if (activeEngine == ActiveEngine.MPTUNNEL) {
            cancelMptunnelWatchdog()
        }
        val stoppedCleanly = when (activeEngine) {
            ActiveEngine.MPTUNNEL -> runCatching { MptunnelNative.stop() }.getOrDefault(false)
            ActiveEngine.XRAY -> if (coreControllerDelegate.isInitialized()) {
                runCatching { coreController.stopLoop() }
                true
            } else {
                true
            }
            ActiveEngine.NONE -> true
        }
        if (!stoppedCleanly) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: MPTUNNEL cleanup timed out; retaining owner")
            serviceControl?.get()?.let(::startMptunnelWatchdog)
            return false
        }
        activeEngine = ActiveEngine.NONE
        currentConfig = null
        return true
    }

    /**
     * Mirrors Xray's native shutdown callback for the JNI runtime, which currently exposes state
     * by polling. Each watcher is tied to both a generation and the exact owning ServiceControl:
     * an obsolete watcher cannot stop a replacement service after reload or a fast stop/start.
     */
    @Synchronized
    private fun startMptunnelWatchdog(owner: ServiceControl) {
        cancelMptunnelWatchdog()
        val generation = mptunnelWatchdogGeneration
        mptunnelWatchdogJob = CoroutineScope(Dispatchers.IO).launch {
            var stoppingSinceMs: Long? = null
            try {
                while (true) {
                    delay(MPTUNNEL_WATCHDOG_INTERVAL_MS)
                    val state = MptunnelNative.state()
                    val nowMs = SystemClock.elapsedRealtime()
                    if (state == "stopping") {
                        if (stoppingSinceMs == null) stoppingSinceMs = nowMs
                    } else {
                        stoppingSinceMs = null
                    }
                    val stoppingElapsedMs = stoppingSinceMs?.let(nowMs::minus) ?: 0L
                    if (MptunnelRuntimeWatchdogPolicy.shouldStopService(state, stoppingElapsedMs)) {
                        val reportedState = if (state == "stopping") "stopping-timeout" else state
                        handleMptunnelTerminalState(generation, owner, reportedState)
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: MPTUNNEL watchdog failed", failure)
                handleMptunnelTerminalState(generation, owner, "unavailable")
            }
        }
    }

    @Synchronized
    private fun cancelMptunnelWatchdog() {
        mptunnelWatchdogGeneration++
        mptunnelWatchdogJob?.cancel()
        mptunnelWatchdogJob = null
    }

    @Synchronized
    private fun handleMptunnelTerminalState(
        generation: Long,
        owner: ServiceControl,
        state: String,
    ) {
        if (!MptunnelRuntimeWatchdogPolicy.canStopOwner(
                watchGeneration = generation,
                currentGeneration = mptunnelWatchdogGeneration,
                isMptunnelActive = activeEngine == ActiveEngine.MPTUNNEL,
                ownsService = serviceControl?.get() === owner,
            )
        ) {
            return
        }

        // Invalidate this generation before re-entering service teardown. stopCoreLoop() will
        // retry nativeStop and cannot accidentally re-arm this already-terminal generation.
        cancelMptunnelWatchdog()
        val message = "MPTUNNEL native runtime terminated ($state)"
        LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message")
        runCatching {
            MessageHelper.sendMsg2UI(owner.getService(), AppConfig.MSG_STATE_START_FAILURE, message)
        }.onFailure { failure ->
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to report MPTUNNEL termination", failure)
        }
        try {
            owner.stopService()
        } catch (failure: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop MPTUNNEL owner", failure)
            if (activeEngine == ActiveEngine.MPTUNNEL && serviceControl?.get() === owner) {
                startMptunnelWatchdog(owner)
            }
        }
    }

    private fun stopBrowserDialer(reconcileXray: Boolean) {
        if (reconcileXray && coreControllerDelegate.isInitialized()) {
            CoreNativeManager.reconcileBrowserDialer("")
        }
        browserDialer?.stop()
        browserDialer = null
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    @Synchronized
    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false

        return try {
            val tunFd = currentVpnInterface

            isReloading = true
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            when (activeEngine) {
                ActiveEngine.MPTUNNEL -> {
                    cancelMptunnelWatchdog()
                    if (!MptunnelNative.stop()) {
                        serviceControl?.get()?.let(::startMptunnelWatchdog)
                        error("MPTUNNEL stop timed out during network handover")
                    }
                }
                ActiveEngine.XRAY -> coreController.stopLoop()
                ActiveEngine.NONE -> return false
            }
            activeEngine = ActiveEngine.NONE
            launchCore(service, tunFd, isReload = true)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            if (stopEngineAfterFailedStart()) {
                serviceControl?.get()?.stopService()
            }
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        if (activeEngine == ActiveEngine.MPTUNNEL) {
            return MptunnelNative.queryOutboundTrafficStats()
        }

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureCoreDelay() {
        if (!isRunning()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure primary delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure fallback delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    private fun measureDelay(url: String): Long {
        if (activeEngine == ActiveEngine.XRAY) {
            return coreController.measureDelay(url)
        }
        if (activeEngine != ActiveEngine.MPTUNNEL) return -1L

        val started = SystemClock.elapsedRealtime()
        if (HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 10_000,
                httpPort = SettingsManager.getHttpPort(),
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword(),
            )
        ) == null) return -1L
        return SystemClock.elapsedRealtime() - started
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    LauncherManager.startService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureCoreDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
