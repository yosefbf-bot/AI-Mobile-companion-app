package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Never opens a general recognition activity or falls back to a cloud recognizer. */
@Composable
fun rememberOnDeviceDictation(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val receive by rememberUpdatedState(onText)
    val recognizer = remember(context) { mutableStateOf<SpeechRecognizer?>(null) }
    val active = remember { mutableStateOf(true) }
    val unavailable = { Toast.makeText(context, "On-device dictation is unavailable. Type your note or attach evidence.", Toast.LENGTH_LONG).show() }
    val start: () -> Unit = {
        if (active.value) {
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) unavailable()
            else try {
                val speech = recognizer.value ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                    it.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) { if (active.value) unavailable() }
                        override fun onResults(results: Bundle?) {
                            if (active.value) results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                ?.takeIf(String::isNotBlank)?.let(receive)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    recognizer.value = it
                }
                speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            } catch (_: Exception) { unavailable() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && active.value) start() else if (active.value) unavailable()
    }
    DisposableEffect(context) {
        active.value = true
        onDispose { active.value = false; recognizer.value?.cancel(); recognizer.value?.destroy(); recognizer.value = null }
    }
    return {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) unavailable()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
