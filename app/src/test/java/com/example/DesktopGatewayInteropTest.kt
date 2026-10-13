package com.example

import android.util.Base64
import com.example.data.model.ChatAttachment
import com.example.data.network.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Actual Android client -> running Node Core HTTPS. Synthetic data only; no OS-auth claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DesktopGatewayInteropTest {
    @Test fun realDesktopRoundTripReplayHistoryEvidenceActionsDraftsAndNotices() = runBlocking {
        val path = System.getenv("PRIVATE_AI_GATEWAY_TEST_PAIR_FILE")
        assumeTrue("An isolated running desktop gateway fixture must be explicitly supplied.", !path.isNullOrBlank())
        val fixture = JSONObject(File(path!!).readText())
        val config = PairingConfigParser.parse(fixture.getString("pairUri"))
        val transport = DesktopNodeService()
        val workflow = SharedWorkflowService(transport)
        try {
            println("Interop: status")
            val status = transport.getNodeStats(config)
            assertTrue("Actual desktop must grant workflow permission.", "workflow" in status.scopes)
            assertTrue("Actual desktop must grant notifications permission.", "notifications" in status.scopes)
            assertTrue("Actual desktop must advertise conversation history.", "conversations" in status.capabilities)
            println("Interop: meeting audio")
            val meetingId = UUID.randomUUID().toString()
            val meeting = JSONObject().put("sourceId", meetingId).put("title", "SYNTHETIC Android audio roundtrip")
            val serverMeetingId = workflow.deliverMeeting(config, "MEETING_CREATE", meeting)
            assertEquals(serverMeetingId, workflow.deliverMeeting(config, "MEETING_CREATE", meeting))
            val audio = com.example.audio.PcmWave.encode(ShortArray(80000) { (it % 700).toShort() }, 80000)
            val inputAudio = JSONObject().put("sourceId",meetingId).put("chunkId",UUID.randomUUID().toString())
                .put("audio",Base64.encodeToString(audio,Base64.NO_WRAP))
            val audioId = workflow.deliverMeeting(config,"MEETING_AUDIO",inputAudio)
            assertEquals(audioId,workflow.deliverMeeting(config,"MEETING_AUDIO",inputAudio))
            workflow.deliverMeeting(config,"MEETING_FINISH",JSONObject().put("sourceId",meetingId).put("expectedChunkCount",1))
            assertEquals(audioId,workflow.deliverMeeting(config,"MEETING_AUDIO",inputAudio))
            assertTrue(workflow.meetings(config).any { it.sourceId == meetingId && it.pendingAudio == 1 })
            println("Interop: chat")
            val sessionId = UUID.randomUUID().toString()
            val requestId = UUID.randomUUID().toString()
            val prompt = fixture.optString("prompt", "/calc 2+2")
            val first = transport.dispatchInferenceToDesktop(sessionId,prompt,config,requestId,true,true)
            assertTrue(first.reply.contains(fixture.optString("expectedReplyContains", "4")))
            val replay = transport.dispatchInferenceToDesktop(sessionId,prompt,config,requestId,true,true)
            assertEquals(first.reply,replay.reply)
            assertEquals(requestId,replay.requestId)
            assertTrue(workflow.conversations(config).any { it.getString("sessionId") == sessionId })
            val history = workflow.history(config,sessionId).getJSONArray("messages")
            val messages = (0 until history.length()).map(history::getJSONObject)
            assertEquals(1,messages.count { it.getString("role") == "user" && it.getString("text") == prompt })
            assertEquals(1,messages.count { it.getString("role") == "assistant" && it.getString("text") == first.reply })

            println("Interop: evidence and follow-ups")
            val sourceId = UUID.randomUUID().toString()
            val bytes = "SYNTHETIC evidence for interoperability only.\n".repeat(2400).toByteArray(Charsets.UTF_8)
            val file = ChatAttachment("synthetic-round.txt","DOCUMENT",Base64.encodeToString(bytes,Base64.NO_WRAP),bytes.size.toLong())
            val evidenceId = workflow.uploadEvidence(config,sourceId,file)
            assertEquals(evidenceId,workflow.uploadEvidence(config,sourceId,file))
            val evidence = transport.request(config,"/mobile/api/v1/evidence/read",JSONObject().put("id",evidenceId))
            assertArrayEquals(bytes,Base64.decode(evidence.getString("data"),Base64.NO_WRAP))
            val input = JSONObject().put("sourceId",sourceId).put("text","SYNTHETIC operational reminder: inspect a test lamp.")
                .put("kind","reminder").put("observedAt",System.currentTimeMillis()).put("location","Synthetic test room")
                .put("dueAt",System.currentTimeMillis()-1000).put("evidenceIds",JSONArray().put(evidenceId)).put("draftChannel","whatsapp")
            val capture = workflow.createCapture(config,input)
            assertEquals(sourceId,capture.getJSONObject("capture").getString("sourceId"))
            val repeated = workflow.createCapture(config,input)
            assertTrue(repeated.getBoolean("replayed"))
            assertEquals(capture.getJSONObject("capture").getString("id"),repeated.getJSONObject("capture").getString("id"))
            val action = SharedWorkflowService.action(capture.getJSONArray("actions").getJSONObject(0))
            assertTrue(action.revision.matches(Regex("[a-f0-9]{64}")))
            assertEquals("",action.owner)
            val reviewed = workflow.updateAction(config,action,"SYNTHETIC owner",action.dueAt,"Synthetic review only",false)
            assertNotEquals(action.revision,reviewed.revision)
            assertEquals("SYNTHETIC owner",reviewed.owner)
            val originalDraft = SharedWorkflowService.draft(capture.getJSONArray("drafts").getJSONObject(0))
            val savedDraft = workflow.saveDraft(config,originalDraft,"","SYNTHETIC draft for review only.","Synthetic subject")
            assertEquals("draft",savedDraft.status)
            assertEquals("",savedDraft.recipient)
            assertEquals("Synthetic subject",savedDraft.subject)
            val snapshot = workflow.snapshot(config,status.scopes)
            assertTrue(snapshot.captures.any { it.id == capture.getJSONObject("capture").getString("id") })
            val notice = snapshot.notifications.first { it.actionId == reviewed.id && it.status == "pending" }
            workflow.acknowledgeNotification(config,notice.id)
            val afterAck = workflow.snapshot(config,status.scopes)
            assertEquals("acknowledged",afterAck.notifications.first { it.id == notice.id }.status)
            assertFalse(afterAck.actions.first { it.id == reviewed.id }.status == "complete")
            val completed = workflow.updateAction(config,reviewed,"SYNTHETIC owner",reviewed.dueAt,"Synthetic test completed",true)
            assertTrue(completed.status in setOf("complete","completed","closed"))
        } finally { transport.cancelAll() }
    }
}
