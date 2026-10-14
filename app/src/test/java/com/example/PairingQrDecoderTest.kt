package com.example

import com.example.ui.pairing.PairingQrDecoder
import com.example.ui.pairing.PairingQrAnalyzer
import com.example.ui.pairing.PairingScanGate
import androidx.camera.core.ImageProxy
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import javax.imageio.ImageIO

class PairingQrDecoderTest {
    private data class Frame(val pixels: ByteArray, val width: Int, val height: Int)
    private fun fixture(): Frame {
        val image = requireNotNull(javaClass.getResourceAsStream("/fixtures/pairing-v2-qr.png")).use { ImageIO.read(it) }
        return Frame(ByteArray(image.width * image.height) { n ->
            val rgb = image.getRGB(n % image.width, n / image.width)
            (((rgb shr 16 and 255) * 306 + (rgb shr 8 and 255) * 601 + (rgb and 255) * 117) shr 10).toByte()
        }, image.width, image.height)
    }
    private fun expected(): String = requireNotNull(javaClass.getResourceAsStream("/fixtures/pairing-v2-payload.txt")).bufferedReader().use { it.readText().trim() }
    private fun decode(frame: Frame): String? = PairingQrDecoder().decode(ByteBuffer.wrap(frame.pixels), frame.width, frame.height, frame.width, 1)

    @Test fun decodesExactQrProducedByThePinnedDesktopRenderer() {
        assertEquals(expected(), decode(fixture()))
        assertTrue(PairingQrDecoder.isBootstrapCandidate(expected()))
    }

    @Test fun rotatedAndInvertedDesktopQrStillRecoverExactPayload() {
        var frame = fixture()
        repeat(4) {
            assertEquals(expected(), decode(frame))
            assertEquals(expected(), decode(frame.copy(pixels = ByteArray(frame.pixels.size) { n -> (255 - (frame.pixels[n].toInt() and 255)).toByte() })))
            val rotated = ByteArray(frame.pixels.size)
            for (y in 0 until frame.height) for (x in 0 until frame.width)
                rotated[x * frame.height + (frame.height - 1 - y)] = frame.pixels[y * frame.width + x]
            frame = Frame(rotated, frame.height, frame.width)
        }
    }

    @Test fun reducedDensityAndPaddedPixelStridesUseTheRealLuminancePath() {
        val original = fixture()
        val width = original.width / 2; val height = original.height / 2
        val frame = Frame(ByteArray(width * height) { n -> original.pixels[(n / width * 2) * original.width + n % width * 2] }, width, height)
        assertEquals(expected(), decode(frame))
        val stride = frame.width * 2 + 13
        val bytes = ByteArray(stride * frame.height + 7)
        for (y in 0 until frame.height) for (x in 0 until frame.width) bytes[7 + y * stride + x * 2] = frame.pixels[y * frame.width + x]
        assertEquals(expected(), PairingQrDecoder().decode(ByteBuffer.wrap(bytes).apply { position(7) }, frame.width, frame.height, stride, 2))
    }

    @Test fun malformedBuffersAndUnrelatedQrCannotBecomePairingInvites() {
        assertThrows(IllegalArgumentException::class.java) { PairingQrDecoder().decode(ByteBuffer.allocate(4), 4, 4, 4, 1) }
        assertThrows(IllegalArgumentException::class.java) { PairingQrDecoder().decode(ByteBuffer.allocate(4), Int.MAX_VALUE, 4, 4, 1) }
        assertFalse(PairingQrDecoder.isBootstrapCandidate("https://example.com/"))
        assertFalse(PairingQrDecoder.isBootstrapCandidate("privateai://pair/v2?version=2#other"))
        assertFalse(PairingQrDecoder.isBootstrapCandidate("privateai://user@pair/v2?version=2"))
        assertFalse(PairingQrDecoder.isBootstrapCandidate("privateai://pair?legacy=1"))
    }

    private fun proxyFrame(frame: Frame, onClose: () -> Unit): ImageProxy {
        val plane = Proxy.newProxyInstance(ImageProxy.PlaneProxy::class.java.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when (method.name) { "getBuffer" -> ByteBuffer.wrap(frame.pixels); "getRowStride" -> frame.width; "getPixelStride" -> 1; else -> error("Unexpected plane access") }
        } as ImageProxy.PlaneProxy
        return Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) { "getPlanes" -> arrayOf(plane); "getWidth" -> frame.width; "getHeight" -> frame.height; "close" -> { onClose(); null }; else -> error("Unexpected frame access") }
        } as ImageProxy
    }

    @Test fun canceledQueuedResultNeverEscapesAndEveryProxyIsClosed() {
        val gate = PairingScanGate(); val queued = mutableListOf<Runnable>()
        var closed = 0; var delivered = 0
        val analyzer = PairingQrAnalyzer(gate, Executor { queued += it }, { true }, { delivered++ })
        analyzer.analyze(proxyFrame(fixture()) { closed++ })
        assertEquals(1, closed); assertEquals(1, queued.size)
        gate.close() // Navigation/Stop cancels before the main-thread delivery.
        queued.forEach(Runnable::run)
        analyzer.analyze(proxyFrame(fixture()) { closed++ })
        assertEquals(0, delivered); assertEquals(2, closed)
    }

    @Test fun duplicateUnauthorizedAndMalformedFramesCloseWithoutAdditionalDelivery() {
        val gate = PairingScanGate(); var closed = 0; var delivered = 0
        val analyzer = PairingQrAnalyzer(gate, Executor(Runnable::run), { true }, { delivered++ })
        repeat(2) { analyzer.analyze(proxyFrame(fixture()) { closed++ }) }
        assertEquals(1, delivered); assertEquals(2, closed)
        val denied = PairingQrAnalyzer(PairingScanGate(), Executor(Runnable::run), { false }, { fail("Unauthorized decode") })
        denied.analyze(proxyFrame(fixture()) { closed++ })
        val malformed = PairingQrAnalyzer(PairingScanGate(), Executor(Runnable::run), { true }, { fail("Malformed decode") })
        malformed.analyze(proxyFrame(Frame(ByteArray(1), 10, 10)) { closed++ })
        assertEquals(4, closed)
    }
}
