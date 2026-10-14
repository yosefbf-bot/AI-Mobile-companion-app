package com.example.ui.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor

class PairingQrAnalyzer(
    private val gate: PairingScanGate,
    private val resultExecutor: Executor,
    private val authorized: () -> Boolean,
    private val onDecoded: (String) -> Unit,
    private val onInvalidCode: () -> Unit = {},
    private val decoder: PairingQrDecoder = PairingQrDecoder()
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            if (!gate.isOpen() || !authorized()) return
            val plane = image.planes.firstOrNull() ?: return
            val decoded = decoder.decode(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride) ?: return
            if (!PairingQrDecoder.isBootstrapCandidate(decoded)) {
                resultExecutor.execute { if (gate.isOpen() && authorized()) onInvalidCode() }
            } else if (gate.claim()) {
                resultExecutor.execute { if (gate.canDeliver() && authorized()) onDecoded(decoded) }
            }
        } catch (_: Exception) {
            // A malformed/canceled frame does not disclose its pixels or decoded text.
        } finally { image.close() }
    }
}
