package com.example.data.model

enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    SYNCING
}

enum class BatteryOptimizationMode(val title: String, val description: String) {
    SMART_ECO("Smart Eco (Recommended)", "Syncs on LAN when active or unmetered Wi-Fi. Minimal battery impact."),
    REAL_TIME("Real-Time LAN", "Continuous sync stream when companion app is active on screen."),
    MANUAL_ONLY("Manual Only", "Zero background polling. Syncs strictly when you tap 'Sync Now'.")
}

data class NodeStats(
    val serverName: String = "Private-AI-OG Workstation",
    val loadedModel: String = "Llama-3.3-70B-Instruct-Q4_K_M (Local)",
    val cpuUsagePercent: Int = 18,
    val gpuUsagePercent: Int = 42,
    val vramUsedGb: Float = 14.2f,
    val vramTotalGb: Float = 24.0f,
    val activeJobsCount: Int = 1,
    val serverUptime: String = "3d 14h 22m",
    val localIp: String = "192.168.1.155:8080",
    val protocolVersion: String = "E2EE-v2.4-Direct"
)

data class SyncResult(
    val success: Boolean,
    val itemsSynced: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
