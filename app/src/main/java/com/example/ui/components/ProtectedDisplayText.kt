package com.example.ui.components

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException

/** Production supplies a suspending vault reader; UI state is updated on the composition scope. */
@Composable
fun protectedDisplayText(payload: String, decrypt: (String) -> String, load: (suspend (String) -> String)? = null): String {
    if (load == null) return decrypt(payload)
    var text by remember(payload) { mutableStateOf("") }
    LaunchedEffect(payload, load) {
        text = try { load(payload) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            "Protected text could not be opened. Unlock and retry."
        }
    }
    return text
}
