package com.example

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.EncryptedChatSession
import com.example.data.model.ChatAttachment
import com.example.data.model.ChatSubmissionReceipt
import com.example.data.model.ConnectionStatus
import com.example.ui.screens.chats.ChatDetailScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose interactions. No production authentication bypass or plaintext saved-state. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp")
class ChatSubmissionCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())
    private val cipher = TestVaultCipher()
    private val session = EncryptedChatSession("synthetic-chat", "Synthetic chat")
    private val evidence = ChatAttachment("synthetic.txt", "DOCUMENT", "c3ludGhldGlj", 9)
    private val receipt = mutableStateOf<ChatSubmissionReceipt?>(null)
    private val finished = mutableStateOf(0L)
    private val running = mutableStateOf(false)
    private val incoming = mutableStateOf<ChatAttachment?>(null)
    private val syncing = mutableStateOf(false)
    private val rows = mutableStateOf<List<EncryptedChatMessage>>(emptyList())
    private val sends = mutableListOf<Pair<String, ChatAttachment?>>()
    private val repeats = mutableListOf<EncryptedChatMessage>()
    private var connectionChecks = 0
    private var savedForm = ""
    private val visible = mutableStateOf(true)

    @Before fun useBoundedAnimationFrames() { rule.mainClock.autoAdvance = false }
    private fun frame() { rule.mainClock.advanceTimeBy(300); rule.waitForIdle() }
    @After fun disposeChatBeforeFrameworkClockCleanup() {
        rule.runOnIdle { visible.value = false }
        frame()
    }

    private fun render(feedback: String? = null) {
        val snackbar = SnackbarHostState()
        val lastChecked = System.currentTimeMillis() - 120_000
        rule.setContent {
            MyApplicationTheme {
                if (visible.value) {
                    LaunchedEffect(feedback) { feedback?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Indefinite) } }
                    ChatDetailScreen(session, rows.value, ConnectionStatus.CONNECTED, "synthetic", "Desktop settings",
                        onModelModeChange = {}, decryptText = cipher::decrypt, decryptAttachmentBitmap = { null },
                        onSendMessage = { text, attachment -> sends.add(text to attachment) },
                        onStopStreaming = {}, onBackClick = {}, incomingEvidence = incoming.value,
                        onConsumeEvidence = { incoming.value = null }, onUnsentDraftChange = { savedForm = it },
                        isRequestRunning = running.value, lastSavedSubmission = receipt.value,
                        submissionFinished = finished.value, snackbarHost = { SnackbarHost(snackbar) },
                        lastConnectionCheck = lastChecked,
                        isSyncing = syncing.value, onCheckConnection = { connectionChecks++ },
                        onAskAgain = { repeats.add(it) })
                }
            }
        }
        frame()
    }

    @Test fun chatShowsFeedbackDeliveryErrorAndStaleClickableConnectionCheck() {
        val error = "Desktop request outcome is uncertain. Review it before submitting again."
        rows.value = listOf(EncryptedChatMessage("review", session.id, "user", cipher.encrypt("Synthetic question"),
            status = "REVIEW_REQUIRED", deliveryError = error))
        render("Synthetic evidence note saved on this phone; pending desktop review.")
        rule.onNodeWithText("Synthetic evidence note saved on this phone; pending desktop review.").assertIsDisplayed()
        rule.onNodeWithTag("chat_delivery_error_review").assertTextEquals(error).assertIsDisplayed()
        rule.onNodeWithText("Review required — delivery unconfirmed").assertIsDisplayed()
        rule.onNodeWithText("Connection not checked recently").assertIsDisplayed()
        rule.onNodeWithTag("connection_status_badge").performClick()
        frame()
        assertEquals(1, connectionChecks)
        rule.runOnIdle { syncing.value = true }
        frame()
        rule.onNodeWithText("Syncing...").assertIsDisplayed()
    }

    @Test fun durableEvidenceReceiptClearsOnlySubmittedFieldsAndBlocksDuplicateClicks() {
        incoming.value = evidence
        render()
        rule.onNodeWithTag("chat_message_input").performTextInput("  Synthetic evidence note  ")
        frame()
        rule.onNodeWithTag("chat_send_button").performClick()
        frame()
        rule.onNodeWithTag("chat_send_button").assertIsNotEnabled().performClick()
        frame()
        assertEquals(listOf("Synthetic evidence note" to evidence), sends)
        // No history row or network acknowledgment is needed for the durable local save.
        rule.runOnIdle { receipt.value = ChatSubmissionReceipt(session.id, "capture-1", true, "CAPTURE_PENDING") }
        frame()
        rule.onNodeWithTag("chat_message_input").assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        rule.onNodeWithTag("attachment_preview_card").assertDoesNotExist()
        rule.waitUntil(5_000) { !savedForm.contains("Synthetic evidence note") && !savedForm.contains("c3ludGhldGlj") }
        assertTrue(rows.value.isEmpty())
    }

    @Test fun durableReceiptPreservesNewerTextAndNewerEvidence() {
        incoming.value = evidence
        render()
        rule.onNodeWithTag("chat_message_input").performTextInput("Submitted note")
        frame()
        rule.onNodeWithTag("chat_send_button").performClick()
        frame()
        rule.onNodeWithTag("chat_message_input").performTextReplacement("Newer edit")
        frame()
        val newer = evidence.copy(name = "newer.txt", base64Data = "bmV3ZXI=", sizeBytes = 5)
        rule.runOnIdle { incoming.value = newer }
        frame()
        rule.waitForIdle()
        rule.runOnIdle { receipt.value = ChatSubmissionReceipt(session.id, "capture-2", true, "CAPTURE_PENDING") }
        frame()
        rule.onNodeWithTag("chat_message_input").assertTextContains("Newer edit")
        rule.onNodeWithText("newer.txt").assertIsDisplayed()
        rule.waitUntil(5_000) { savedForm.contains("Newer edit") && savedForm.contains("newer.txt") }
    }

    @Test fun failedLocalSaveRetainsTextAndEvidenceAndAllowsExplicitRetry() {
        incoming.value = evidence
        render()
        rule.onNodeWithTag("chat_message_input").performTextInput("Failed draft")
        frame()
        rule.onNodeWithTag("chat_send_button").performClick()
        frame()
        rule.runOnIdle { finished.value++ }
        frame()
        rule.onNodeWithTag("chat_message_input").assertTextContains("Failed draft")
        rule.onNodeWithText("synthetic.txt").assertIsDisplayed()
        rule.onNodeWithTag("chat_send_button").assertIsEnabled().performClick()
        frame()
        assertEquals(2, sends.size)
        assertEquals(sends[0], sends[1])
    }

    @Test fun textReceiptClearsSavedQuestionEvenWhenDesktopDeliveryLaterFails() {
        render()
        rule.onNodeWithTag("chat_message_input").performTextInput("Synthetic pending question")
        frame()
        rule.onNodeWithTag("chat_send_button").performClick()
        frame()
        rule.runOnIdle {
            receipt.value = ChatSubmissionReceipt(session.id, "question-1", false, "QUEUED_OFFLINE")
            finished.value++
        }
        frame()
        rule.waitUntil(5_000) { !savedForm.contains("Synthetic pending question") }
        rule.onNodeWithTag("chat_send_button").assertIsNotEnabled()
    }

    @Test fun askAgainUsesExactCompletedOriginalRatherThanAnswerOrLatestQuestion() {
        val original = EncryptedChatMessage("original", session.id, "user", cipher.encrypt("Exact original question"),
            timestamp = 1, status = "SYNCED_DESKTOP", desktopFingerprint = "synthetic-binding")
        val newer = original.copy(id = "newer", encryptedContent = cipher.encrypt("Different later question"), timestamp = 2)
        val answer = original.copy(id = "reply-original", sender = "assistant", encryptedContent = cipher.encrypt("Synthetic completed answer"), timestamp = 3)
        rows.value = listOf(original, newer, answer)
        render()
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        frame()
        rule.onNodeWithTag("chat_ask_again_reply-original").performClick()
        frame()
        assertEquals(listOf(original), repeats)
        assertTrue(sends.isEmpty())
        rule.onNodeWithText("Ask again · new request").assertIsDisplayed()
        rule.runOnIdle { running.value = true }
        frame()
        rule.onNodeWithTag("chat_ask_again_reply-original").performClick()
        frame()
        assertEquals(1, repeats.size)
        rule.runOnIdle { rows.value = listOf(original.copy(status = "REVIEW_REQUIRED"), answer) }
        frame()
        rule.onNodeWithTag("chat_ask_again_reply-original").assertDoesNotExist()
        rule.runOnIdle { rows.value = listOf(original.copy(desktopFingerprint = null), answer) }
        frame()
        rule.onNodeWithTag("chat_ask_again_reply-original").assertDoesNotExist()
    }
}
