package com.example.data.model

/** Missing gateway fields remain unknown; transport availability is separate. */
data class AlertCoverage(
    val scopeGranted: Boolean? = null,
    val monitoringEnabled: Boolean? = null,
    val isSurveillanceComplete: Boolean? = null,
    val total: Int? = null,
    val truncated: Boolean? = null
)
