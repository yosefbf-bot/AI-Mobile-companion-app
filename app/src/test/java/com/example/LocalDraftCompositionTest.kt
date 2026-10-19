package com.example

import android.content.Context
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.PrivateAIRepository
import com.example.ui.AutoDraftPreferences
import com.example.ui.LocalDraftController
import com.example.ui.components.LocalDraftForm
import com.example.ui.components.LocalDraftFormState
import com.example.ui.components.CaptureLatestDraft
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose+Room with a software cipher; genuine Android auth is tested separately. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp")
class LocalDraftCompositionTest {
    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher())
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: PrivateAIRepository
    private lateinit var controller: LocalDraftController
    private var mounted = mutableStateOf(true)
    private var latest: LocalDraftFormState? = null
    @Volatile private var childCompositions = 0

    @Before fun seedDurableDraft() {
        context.getSharedPreferences("local_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = PrivateAIRepository(database, cipher = TestVaultCipher())
        runBlocking {
            repository.onUnlocked().join()
            val loaded = repository.loadLocalDraft("capture")
            repository.updateLocalDraftFields(loaded.token, "{\"note\":\"Persisted unfinished note\"}", null, true)
            repository.flushLocalDrafts()
        }
        controller = LocalDraftController(repository, scope, AutoDraftPreferences(context),
            ready = { repository.vaultReady.value }, scopeIdentity = { "isolated-test-scope" })
        rule.mainClock.autoAdvance = false
    }
    private fun frame() { rule.mainClock.advanceTimeBy(300); rule.waitForIdle() }
    private fun pumpUntil(condition: () -> Boolean) {
        repeat(200) {
            dispatcher.scheduler.runCurrent(); frame()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Bounded draft UI operation did not finish.", condition())
    }
    private fun render() {
        rule.setContent {
            MyApplicationTheme {
                if (mounted.value) LocalDraftForm(controller, "capture") { draft ->
                    childCompositions++
                    latest = draft
                    var text by remember { mutableStateOf(JSONObject(draft.form).optString("note")) }
                    fun fields() = JSONObject().put("note", text).toString()
                    CaptureLatestDraft(draft) { fields() to null }
                    LaunchedEffect(text) { draft.update(fields()) }
                    OutlinedTextField(text, { text = it; draft.update(fields()) }, modifier = Modifier.testTag("fixture_draft_field"))
                }
            }
        }
        frame()
    }
    @After fun disposeFixture() {
        rule.runOnIdle { mounted.value = false }
        frame()
        scope.cancel(); repository.close(); database.close()
    }

    @Test fun asynchronousRestoreGatesTheFormAndCannotWriteAnInitialEmptyDraft() {
        render()
        rule.onNodeWithTag("draft_loading").assertIsDisplayed()
        assertEquals(0, childCompositions)
        pumpUntil { childCompositions > 0 }
        rule.onNodeWithTag("fixture_draft_field").assertTextEquals("Persisted unfinished note")
        runBlocking {
            repository.flushLocalDrafts()
            assertEquals("Persisted unfinished note", JSONObject(repository.loadLocalDraft("capture").form).getString("note"))
        }
    }

    @Test fun deleteRequiresConfirmationAndStaleFormCannotResurrectDeletedOrLockedWork() {
        render(); pumpUntil { childCompositions > 0 }
        val stale = latest!!
        rule.onNodeWithTag("delete_local_draft").performClick(); frame()
        rule.onNodeWithTag("cancel_delete_local_draft").performClick(); frame()
        rule.onNodeWithTag("fixture_draft_field").assertTextEquals("Persisted unfinished note")
        rule.onNodeWithTag("delete_local_draft").performClick(); frame()
        rule.onNodeWithTag("confirm_delete_local_draft").performClick(); frame()
        pumpUntil { rule.onAllNodesWithTag("fixture_draft_field").fetchSemanticsNodes().isNotEmpty() && latest!!.reset != stale.reset }
        rule.onNodeWithTag("fixture_draft_field").assertTextEquals("")
        rule.runOnIdle { stale.update("{\"note\":\"Stale resurrection\"}") }
        runBlocking {
            repository.flushLocalDrafts()
            assertEquals("", JSONObject(repository.loadLocalDraft("capture").form).optString("note"))
        }
        val beforeLock = latest!!
        rule.runOnIdle { controller.onLocked(); beforeLock.update("{\"note\":\"Late locked callback\"}") }
        frame()
        rule.onNodeWithTag("fixture_draft_field").assertDoesNotExist()
        rule.onNodeWithTag("draft_loading").assertIsDisplayed()
    }

    @Test fun navigationCapturesLatestProviderEvenWhenNoAutosaveEffectRan() {
        render(); pumpUntil { childCompositions > 0 }
        lateinit var observed: kotlinx.coroutines.flow.StateFlow<com.example.ui.LocalDraftUiState>
        rule.runOnIdle { observed = controller.state("navigation-last-keystroke") }
        pumpUntil { !observed.value.loading }
        lateinit var remove: () -> Unit
        var displayed = "Last displayed keystroke before submission"
        rule.runOnIdle {
            remove = controller.registerCapture("navigation-last-keystroke", observed.value.reset) {
                JSONObject().put("note", displayed).toString() to null
            }
            // A submission must capture the exact displayed fields before preparing its receipt.
            assertTrue(controller.captureBeforeLock())
            val submitted = controller.currentForm("navigation-last-keystroke")
            assertEquals(displayed, JSONObject(submitted).getString("note"))
            assertNotNull(controller.prepareSubmission("navigation-last-keystroke", submitted, setOf("note")))
            displayed = "Last displayed keystroke before navigation"
            // Deliberately no update() and no Compose LaunchedEffect before leaving.
            controller.leave("navigation-last-keystroke", observed)
            remove()
        }
        runBlocking {
            repository.flushLocalDrafts()
            assertEquals("Last displayed keystroke before navigation",
                JSONObject(repository.loadLocalDraft("navigation-last-keystroke").form).getString("note"))
        }
        rule.runOnIdle { observed = controller.state("navigation-last-keystroke") }
        pumpUntil { !observed.value.loading }
        assertEquals("Last displayed keystroke before navigation", JSONObject(observed.value.form).getString("note"))
    }
}
