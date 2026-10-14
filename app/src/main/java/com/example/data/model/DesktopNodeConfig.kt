package com.example.data.model

data class DesktopNodeConfig(
    val hostIp: String = "",
    val port: Int = 8443,
    val pairKey: String = "",
    val friendlyName: String = "Private AI desktop",
    val batteryMode: BatteryOptimizationMode = BatteryOptimizationMode.SMART_ECO,
    val autoSyncIntervalMinutes: Int = 5,
    val simulatedNodeEnabled: Boolean = false,
    val scheme: String = "https",
    val certificateDer: String = "",
    val certificateFingerprint: String = "",
    val notificationKey: String = "",
    val backgroundNotificationsEnabled: Boolean = false,
    val secureClinicalMode: Boolean = true
) {
    val isPaired: Boolean get() = hostIp.isNotBlank() && pairKey.isNotBlank() && certificateDer.isNotBlank() && certificateFingerprint.isNotBlank()
}
