package com.argsment.anywhere.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.argsment.anywhere.MainActivity
import com.argsment.anywhere.data.model.ProxyConfiguration
import com.argsment.anywhere.data.repository.ChainRepository
import com.argsment.anywhere.data.repository.ConfigRepository
import com.argsment.anywhere.vpn.util.DnsCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID

class AnywhereTileService : TileService() {

    private val json = Json { ignoreUnknownKeys = true }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var stateJob: kotlinx.coroutines.Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = scope.launch {
            AnywhereVpnService.vpnState.collect {
                updateTileState()
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.label = "Anywhere"
        if (AnywhereVpnService.vpnState.value) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Connected"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        val isRunning = AnywhereVpnService.vpnState.value
        tile.label = "Anywhere"

        if (isRunning) {
            // Stop VPN
            val intent = Intent(this, AnywhereVpnService::class.java).apply {
                action = AnywhereVpnService.ACTION_STOP
            }
            try {
                startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Disconnected"
            }
            tile.updateTile()
        } else {
            // Start VPN
            // Check VPN permission
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
                return
            }

            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Connecting..."
            }
            tile.updateTile()

            scope.launch {
                val config = withContext(Dispatchers.IO) {
                    getSelectedConfiguration()
                }
                
                if (config == null) {
                    val intent = Intent(this@AnywhereTileService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                    return@launch
                }
                
                startVpn(config)
            }
        }
    }

    private fun getSelectedConfiguration(): ProxyConfiguration? {
        val prefs = getSharedPreferences("anywhere_settings", Context.MODE_PRIVATE)
        val chainRepo = ChainRepository(this)
        val configRepo = ConfigRepository(this)
        
        val savedChainId = prefs.getString("selectedChainId", null)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        
        if (savedChainId != null) {
            val chain = chainRepo.get(savedChainId) ?: return null
            val configs = chain.proxyIds.mapNotNull { id -> configRepo.get(id) }
            if (configs.size != chain.proxyIds.size || configs.size < 2) return null
            val exitProxy = configs.last()
            val chainProxies = configs.dropLast(1)
            return exitProxy.copy(name = chain.name, chain = chainProxies)
        } else {
            val savedConfigId = prefs.getString("selectedConfigurationId", null)?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            } ?: return null
            return configRepo.get(savedConfigId)
        }
    }

    private fun applyGlobalAllowInsecure(config: ProxyConfiguration): ProxyConfiguration {
        val prefs = getSharedPreferences("anywhere_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("allowInsecure", false)) return config
        val tls = config.tls ?: return config
        if (tls.allowInsecure) return config
        val updatedTls = tls.copy(allowInsecure = true)
        val updatedChain = config.chain?.map { applyGlobalAllowInsecure(it) }
        return config.copy(tls = updatedTls, chain = updatedChain)
    }

    private suspend fun startVpn(config: ProxyConfiguration) {
        val resolvedConfig = withContext(Dispatchers.IO) {
            val withInsecure = applyGlobalAllowInsecure(config)
            val resolved = resolveAddress(withInsecure)
            val resolvedChain = withInsecure.chain?.map { resolveAddress(it) }
            if (resolvedChain != null) resolved.copy(chain = resolvedChain) else resolved
        }
        
        DnsCache.setActiveProxyDomain(resolvedConfig.serverAddress)
        syncProxyServerAddresses(resolvedConfig)

        val configJson = json.encodeToString(resolvedConfig)
        val intent = Intent(this, AnywhereVpnService::class.java).apply {
            action = AnywhereVpnService.ACTION_START
            putExtra(AnywhereVpnService.EXTRA_CONFIG, configJson)
        }

        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveAddress(config: ProxyConfiguration): ProxyConfiguration {
        val resolved = resolveServerAddress(config.serverAddress) ?: return config
        return config.copy(resolvedIP = resolved)
    }

    private fun resolveServerAddress(address: String): String? {
        val bare = if (address.startsWith("[") && address.endsWith("]")) {
            address.substring(1, address.length - 1)
        } else {
            address
        }

        if (DnsCache.isIpAddress(bare)) return bare

        return try {
            val all = InetAddress.getAllByName(bare)
            val resolved = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
            resolved?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun syncProxyServerAddresses(configuration: ProxyConfiguration) {
        val addresses = linkedSetOf<String>()
        collectProxyServerAddresses(configuration, addresses)
        val persisted = addresses.toList()
        val prefs = getSharedPreferences("anywhere_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("proxyServerAddresses", json.encodeToString(persisted)).apply()
        
        // Push the update directly to the running instance via companion if it exists
        AnywhereVpnService.updateProxyServerAddressesGlobal(persisted)
    }

    private fun collectProxyServerAddresses(
        configuration: ProxyConfiguration,
        addresses: MutableSet<String>
    ) {
        addresses.add(configuration.serverAddress)
        configuration.resolvedIP?.let(addresses::add)
        DnsCache.cachedIPs(configuration.serverAddress)?.let(addresses::addAll)
        configuration.chain?.forEach { hop ->
            collectProxyServerAddresses(hop, addresses)
        }
    }
}
