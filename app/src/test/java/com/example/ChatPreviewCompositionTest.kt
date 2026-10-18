package com.example

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import com.example.data.local.entity.EncryptedChatSession
import com.example.ui.screens.chats.ChatListScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual populated Chat composition with the strict production authenticated codec. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPreviewCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())

    private fun render(rows: List<EncryptedChatSession>, decrypt: (String) -> String) {
        rule.setContent {
            MyApplicationTheme {
                ChatListScreen(rows, decrypt, onSessionClick = {}, onCreateNewSession = {}, onDeleteSession = {})
            }
        }
        rule.waitForIdle()
    }

    @Test fun newAndDesktopSharedChatsWithoutPreviewsDoNotAttemptVaultDecryption() {
        val cipher = TestVaultCipher()
        // The missing-preview sentinel is invalid ciphertext. Passing it to the
        // real production codec reproduces the old failure; it is not a test
        // cipher that conveniently accepts empty input.
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt("") }
        var attempts = 0
        render(listOf(
            EncryptedChatSession("new", "Synthetic new conversation"),
            EncryptedChatSession("shared", "Synthetic desktop conversation", isSynced = true,
                desktopChatId = "synthetic-desktop-chat", desktopRevision = 1)
        )) { attempts++; cipher.decrypt(it) }
        rule.onNodeWithText("Synthetic new conversation").assertIsDisplayed()
        rule.onNodeWithText("Synthetic desktop conversation").assertIsDisplayed()
        assertEquals("Absent previews must not enter the vault decoder.", 0, attempts)
    }

    @Test fun existingAuthenticatedPreviewStillDecryptsAndDisplaysNormally() {
        val cipher = TestVaultCipher()
        val encrypted = cipher.encrypt("Synthetic retained preview")
        val encryptedEmpty = cipher.encrypt("")
        val received = mutableListOf<String>()
        render(listOf(EncryptedChatSession("retained", "Synthetic retained conversation",
            lastMessagePreviewEncrypted = encrypted),
            EncryptedChatSession("empty-body", "Synthetic encrypted empty preview",
                lastMessagePreviewEncrypted = encryptedEmpty))) {
            received.add(it)
            cipher.decrypt(it)
        }
        rule.onNodeWithText("Synthetic retained preview").assertIsDisplayed()
        rule.onNodeWithText("Synthetic encrypted empty preview").assertIsDisplayed()
        assertTrue(received.isNotEmpty())
        assertEquals(setOf(encrypted, encryptedEmpty), received.toSet())
    }

    @Test fun nonemptyMalformedAndWhitespacePreviewsStillReachStrictDecoderFailure() {
        val cipher = TestVaultCipher()
        val malformed = listOf(" ", "not-ciphertext", "PAI2:invalid")
        val rejected = mutableSetOf<String>()
        render(malformed.mapIndexed { index, payload ->
            EncryptedChatSession("invalid-$index", "Synthetic malformed preview $index",
                lastMessagePreviewEncrypted = payload)
        }) { payload ->
            try { cipher.decrypt(payload); fail("Malformed nonempty data was accepted."); "" }
            catch (_: IllegalArgumentException) { rejected.add(payload); "" }
        }
        malformed.forEachIndexed { index, payload ->
            // Each static malformed row must actually be composed and visible.
            // A single known-index action is bounded; no recursive ScrollTo.
            rule.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.onNodeWithText("Synthetic malformed preview $index").assertIsDisplayed()
            rule.waitUntil("Strict decoder rejection for synthetic case $index; observed=${rejected.size}", 5_000) {
                rejected.contains(payload)
            }
            assertTrue(rejected.contains(payload))
        }
        assertEquals("Only exact absence may bypass decoding.", malformed.toSet(), rejected)
    }
}
