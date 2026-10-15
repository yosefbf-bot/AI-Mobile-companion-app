package com.example.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Only local luminance processing. No bitmap/file, cloud service or frame persistence. */
class PairingQrDecoder {
    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        require(width > 0 && height > 0 && width.toLong() * height <= 4_194_304)
        require(rowStride > 0 && pixelStride > 0 && rowStride.toLong() >= (width - 1L) * pixelStride + 1)
        val source = buffer.duplicate()
        val base = source.position()
        val last = base.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride
        require(last < source.limit())
        val pixels = ByteArray(width * height)
        try {
            for (y in 0 until height) for (x in 0 until width)
                pixels[y * width + x] = source.get(base + y * rowStride + x * pixelStride)
            val reader = MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true, DecodeHintType.ALSO_INVERTED to true))
            }
            return try {
                val luminance = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text.takeIf { it.length <= 4096 }
            } catch (_: ReaderException) { null }
            finally { reader.reset() }
        } finally { pixels.fill(0) }
    }

    companion object {
        // The repository does the authoritative expiry/address/token/certificate validation.
        fun isBootstrapCandidate(value: String): Boolean = try {
            value.length in 1..4096 && URI(value).let {
                it.scheme == "privateai" && it.rawAuthority == "pair" && it.rawPath == "/v2" &&
                    it.rawFragment == null && !it.rawQuery.isNullOrEmpty()
            }
        } catch (_: Exception) { false }
    }
}

/** Suppresses duplicate frames and callbacks queued before cancel/Stop/disposal. */
class PairingScanGate {
    private val open = AtomicBoolean(true)
    private val claimed = AtomicBoolean(false)
    fun isOpen(): Boolean = open.get() && !claimed.get()
    fun claim(): Boolean = open.get() && claimed.compareAndSet(false, true)
    fun canDeliver(): Boolean = open.get() && claimed.get()
    fun close() { open.set(false) }
}
