package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.audio.PcmWave
import com.example.data.local.AppDatabase
import com.example.data.repository.MeetingOutbox
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingOutboxTest {
    @Test fun encryptedChunksSurviveReopeningAndStayBoundToOriginalPairing() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        val cipher = TestVaultCipher()
        try {
            val outbox = MeetingOutbox(db, cipher)
            val id = outbox.create("synthetic-pairing", "SYNTHETIC confidential meeting")
            val wav = PcmWave.encode(ShortArray(50000) { (it % 1024).toShort() }, 50000)
            outbox.append("synthetic-pairing", id, wav)
            outbox.finish("synthetic-pairing", id); outbox.finish("synthetic-pairing", id)
            val dao = db.pendingOperationDao()
            val rows = dao.pending("synthetic-pairing")
            assertEquals(listOf("MEETING_CREATE", "MEETING_AUDIO", "MEETING_FINISH"), rows.map { it.operation })
            assertFalse(rows.any { it.encryptedPayload.contains("SYNTHETIC confidential") })
            val reopened = MeetingOutbox(db, cipher)
            assertEquals(1, reopened.list("synthetic-pairing").single().chunks)
            assertTrue(reopened.list("new-pairing").isEmpty())
            assertNull(dao.nextMeeting("new-pairing"))
            val payload = JSONObject(cipher.decrypt(rows[1].encryptedPayload))
            assertArrayEquals(wav, android.util.Base64.decode(payload.getString("audio"), android.util.Base64.NO_WRAP))
            assertEquals(1, JSONObject(cipher.decrypt(rows[2].encryptedPayload)).getInt("expectedChunkCount"))
            val next = dao.nextMeeting("synthetic-pairing")!!
            dao.acknowledgeMeeting(next.id, "server-id", cipher.encrypt("receipt"))
            assertEquals(rows[1].id, dao.nextMeeting("synthetic-pairing")!!.id)
            assertEquals(2, dao.meetingCount("synthetic-pairing"))
        } finally { db.close() }
    }
    @Test fun lockedVaultAndChangedPairingCannotAppendOrFinish() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        val cipher = TestVaultCipher()
        try {
            val outbox = MeetingOutbox(db, cipher)
            val id = outbox.create("original", "Meeting")
            val wav = PcmWave.encode(ShortArray(1600),1600)
            try { outbox.append("other",id,wav); fail("Changed pairing accepted") } catch (_: IllegalArgumentException) { }
            cipher.available = false
            try { outbox.append("original",id,wav); fail("Locked vault accepted audio") } catch (_: IllegalStateException) { }
            assertEquals(1, db.pendingOperationDao().meetingCount("original"))
            cipher.available = true
            assertEquals(0, outbox.list("original").single().chunks)
        } finally { db.close() }
    }
    @Test fun recordingUsesCanonicalBounded16kPcmWithoutCodecOrSpeechService() {
        val bytes = PcmWave.encode(shortArrayOf(*ShortArray(160) { it.toShort() }),160)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes,0,4));assertEquals(bytes.size-8,header.getInt(4))
        assertEquals(16000,header.getInt(24));assertEquals(16,header.getShort(34).toInt());assertEquals(320,header.getInt(40))
        assertThrows(IllegalArgumentException::class.java) { PcmWave.encode(ShortArray(320001),320001) }
    }

    @Test fun finishQueuedAndFinishAcknowledgedAreSeparateDurableStates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val cipher = TestVaultCipher()
        try {
            val outbox = MeetingOutbox(db, cipher)
            val id = outbox.create("synthetic", "Synthetic finish")
            outbox.finish("synthetic", id)
            assertTrue(outbox.list("synthetic").single().finishQueued)
            assertFalse(outbox.list("synthetic").single().finishAcknowledged)
            val create = db.pendingOperationDao().nextMeeting("synthetic")!!
            outbox.acknowledge(create, id, "server-meeting")
            assertFalse(outbox.list("synthetic").single().finishAcknowledged)
            val finish = db.pendingOperationDao().nextMeeting("synthetic")!!
            assertEquals("MEETING_FINISH", finish.operation)
            outbox.acknowledge(finish, id, "server-meeting")
            assertTrue(MeetingOutbox(db, cipher).list("synthetic").single().finishAcknowledged)
        } finally { db.close() }
    }

    @Test fun olderMeetingStateUsesItsExistingEncryptedFinishReceipt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val cipher = TestVaultCipher()
        try {
            val outbox = MeetingOutbox(db, cipher)
            val id = outbox.create("synthetic", "Synthetic older receipt")
            outbox.finish("synthetic", id)
            val dao = db.pendingOperationDao()
            val state = JSONObject(cipher.decrypt(dao.meetingState(id)!!.encryptedPayload)).apply { remove("finishAcknowledged") }
            dao.updateMeetingState(id, cipher.encrypt(state.toString()))
            val operations = dao.pending("synthetic")
            val finish = operations.single { it.operation == "MEETING_FINISH" }
            dao.acknowledgeMeeting(finish.id, "synthetic-server", cipher.encrypt(JSONObject().put("sourceId", id).put("serverId", "synthetic-server").toString()))
            assertTrue(MeetingOutbox(db, cipher).list("synthetic").single().finishAcknowledged)
            assertTrue(MeetingOutbox(db, cipher).list("different-pairing").isEmpty())
        } finally { db.close() }
    }
}
