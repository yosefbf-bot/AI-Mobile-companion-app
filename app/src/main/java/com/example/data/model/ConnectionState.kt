package com.example.data.model

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    SYNCING,
    DEMO
}

enum class BatteryOptimizationMode(val title: String, val description: String) {
    SMART_ECO("Every five minutes", "Checks the paired desktop while the unlocked app is running."),
    REAL_TIME("Every 30 seconds", "Checks the paired desktop while the unlocked app is running."),
    MANUAL_ONLY("Manual only", "Checks only when you tap Sync now.")
}

data class NodeStats(
    val serverName: String = "Not connected",
    val loadedModel: String = "Unknown",
    val cpuUsagePercent: Int? = null,
    val gpuUsagePercent: Int? = null,
    val vramUsedGb: Float? = null,
    val vramTotalGb: Float? = null,
    val activeJobsCount: Int? = null,
    val serverUptime: String = "Unknown",
    val localIp: String = "",
    val protocolVersion: String = "Unknown",
    val localModelState: String = "unknown",
    val processingMode: String = "Unknown",
    val provider: String = "Unknown",
    val scopes: Set<String> = emptySet(),
    val capabilities: Set<String> = emptySet()
)

data class SyncResult(
    val success: Boolean,
    val itemsSynced: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
