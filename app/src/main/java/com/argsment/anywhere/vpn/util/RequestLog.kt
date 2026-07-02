package com.argsment.anywhere.vpn.util

import com.argsment.anywhere.vpn.RouteAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class RequestEntry(
    val id: String,
    val timestamp: Long,
    val protocolName: String,
    val host: String,
    val port: Int,
    val routeTarget: RouteAction
)

class RequestLog {
    @Volatile
    var isRecordingEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                // Clear log when disabled to save memory
                _requests.value = emptyList()
            }
        }

    private val _requests = MutableStateFlow<List<RequestEntry>>(emptyList())
    val requests: StateFlow<List<RequestEntry>> = _requests

    fun record(protocolName: String, host: String, port: Int, routeTarget: RouteAction) {
        if (!isRecordingEnabled) return
        val entry = RequestEntry(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            protocolName = protocolName,
            host = host,
            port = port,
            routeTarget = routeTarget
        )
        // Keep last 100 entries to prevent unbounded memory growth
        val currentList = _requests.value
        val newList = if (currentList.size >= 100) {
            currentList.drop(1) + entry
        } else {
            currentList + entry
        }
        _requests.value = newList
    }
}
