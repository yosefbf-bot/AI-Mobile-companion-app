package com.example.data.model

data class DesktopNodeConfig(
    val hostIp: String = "192.168.1.155",
    val port: Int = 8080,
    val pairKey: String = "priv-og-e2ee-key-8891",
    val friendlyName: String = "Workstation Desktop (RTX 4090)",
    val batteryMode: BatteryOptimizationMode = BatteryOptimizationMode.SMART_ECO,
    val autoSyncIntervalMinutes: Int = 5,
    val simulatedNodeEnabled: Boolean = true // Seamless local fallback so demo and testing works immediately out of the box
)
