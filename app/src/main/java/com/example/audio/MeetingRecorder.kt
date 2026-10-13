package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object PcmWave {
    fun encode(samples: ShortArray, count: Int): ByteArray {
        require(count in 160..320000 && count <= samples.size)
        return ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + count * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(count * 2)
            repeat(count) { putShort(samples[it]) }
        }.array()
    }
}

/** Explicit foreground recording only. No speech service, codec cache or audio file. */
class MeetingRecorder {
    @Volatile private var stopped = false
    @Volatile private var microphone: AudioRecord? = null
    fun stop() { stopped = true; try { microphone?.stop() } catch (_: IllegalStateException) { } }

    @SuppressLint("MissingPermission") // Caller obtains RECORD_AUDIO immediately before starting.
    suspend fun record(save: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz recording is unavailable on this device." }
        val input = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 16384))
        microphone = input
        val samples = ShortArray(320000)
        var used = 0
        try {
            check(input.state == AudioRecord.STATE_INITIALIZED)
            currentCoroutineContext().ensureActive()
            if (stopped) return@withContext
            input.startRecording()
            while (!stopped) {
                currentCoroutineContext().ensureActive()
                val read = input.read(samples, used, minOf(4096, samples.size - used))
                if (read < 0) { check(stopped) { "Microphone recording stopped unexpectedly." }; break }
                if (read == 0) continue
                used += read
                if (used == samples.size) { save(PcmWave.encode(samples, used)); used = 0 }
            }
            currentCoroutineContext().ensureActive()
            if (used >= 160) save(PcmWave.encode(samples, used))
        } finally {
            stopped = true
            try { input.stop() } catch (_: IllegalStateException) { }
            input.release(); microphone = null; samples.fill(0)
        }
    }
}
