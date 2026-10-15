package com.example.ui.pairing

import android.content.Context
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns only these two use cases; late provider callbacks cannot bind after disposal. */
class PairingCameraSession(
    context: Context,
    private val owner: LifecycleOwner,
    view: PreviewView,
    authorized: () -> Boolean,
    onDecoded: (String) -> Unit,
    private val onStopped: () -> Unit,
    private val onUnavailable: () -> Unit,
    onInvalidCode: () -> Unit
) : DefaultLifecycleObserver {
    private val main = ContextCompat.getMainExecutor(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val gate = PairingScanGate()
    private var provider: ProcessCameraProvider? = null
    private val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
        .build()

    init {
        owner.lifecycle.addObserver(this)
        analysis.setAnalyzer(executor, PairingQrAnalyzer(gate, main, authorized, { decoded ->
            close()
            onDecoded(decoded)
        }, onInvalidCode))
        try {
          val future = ProcessCameraProvider.getInstance(context.applicationContext)
          future.addListener({
            if (!closed.get()) try {
                if (!authorized() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    close(); onStopped()
                } else {
                    provider = future.get()
                    provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            } catch (_: Exception) { close(); onUnavailable() }
          }, main)
        } catch (_: Exception) { close(); onUnavailable() }
    }

    override fun onStop(owner: LifecycleOwner) { close(); onStopped() }
    override fun onDestroy(owner: LifecycleOwner) { close() }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        gate.close()
        val release = Runnable {
            try { analysis.clearAnalyzer(); provider?.unbind(preview, analysis) }
            finally {
                preview.setSurfaceProvider(null)
                owner.lifecycle.removeObserver(this)
                executor.shutdownNow()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else main.execute(release)
    }
}
