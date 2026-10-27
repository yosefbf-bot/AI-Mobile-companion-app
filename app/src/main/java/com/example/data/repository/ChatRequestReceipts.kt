package com.example.data.repository

import com.example.data.crypto.VaultCipher
import com.example.data.local.entity.EncryptedChatMessage
import com.example.data.local.entity.PendingOperation
import com.example.data.network.GatewayReply
import org.json.JSONObject

/** Acknowledged request provenance, independent of the replaceable history cache.
 * Reuses the encrypted, policy-migrated outbox table; never a pending operation.
 * No attachment or assistant body is duplicated. Deleted with the phone session.
 */
internal object ChatRequestReceipts {
    private fun key(id: String) = "chat-receipt-$id"

    fun create(original: EncryptedChatMessage, reply: GatewayReply, cipher: VaultCipher): PendingOperation {
        require(original.sender == "user" && original.requestId == reply.requestId && original.sessionId == reply.sessionId)
        val data = JSONObject().put("version", 1).put("id", original.id).put("requestId", original.requestId)
            .put("sessionId", original.sessionId).put("prompt", cipher.decrypt(original.encryptedContent))
            .put("timestamp", original.timestamp).put("requireLocal", original.requireLocal).put("requireHipaa", original.requireHipaa)
            .put("userMessageId", reply.userMessageId ?: JSONObject.NULL)
            .put("assistantMessageId", reply.assistantMessageId ?: JSONObject.NULL)
        return PendingOperation(key(original.id), "CHAT_RECEIPT", cipher.encrypt(data.toString()),
            original.desktopFingerprint.orEmpty(), original.timestamp, "ACKNOWLEDGED", original.attemptedAt, original.sessionId)
    }

    fun original(row: PendingOperation, cipher: VaultCipher): EncryptedChatMessage {
        require(row.operation == "CHAT_RECEIPT" && row.status == "ACKNOWLEDGED")
        val data = JSONObject(cipher.decrypt(row.encryptedPayload))
        require(data.getInt("version") == 1 && row.id == key(data.getString("id")) && row.serverId == data.getString("sessionId"))
        return EncryptedChatMessage(data.getString("id"), data.getString("sessionId"), "user", cipher.encrypt(data.getString("prompt")),
            timestamp = data.getLong("timestamp"), status = "SYNCED_DESKTOP", requestId = data.getString("requestId"),
            desktopFingerprint = row.desktopFingerprint, requireLocal = data.getBoolean("requireLocal"), requireHipaa = data.getBoolean("requireHipaa"),
            attemptedAt = row.attemptedAt, desktopMessageId = if (data.isNull("userMessageId")) null else data.getString("userMessageId"))
    }

    /** Explicit same-pairing IDs only. Missing/ambiguous links remain ordinary history. */
    fun correlate(rows: List<EncryptedChatMessage>, receipts: List<PendingOperation>, fingerprint: String, cipher: VaultCipher): List<EncryptedChatMessage> {
        val links = receipts.filter { it.desktopFingerprint == fingerprint && fingerprint.isNotBlank() }.flatMap { receipt ->
            val original = original(receipt, cipher)
            val data = JSONObject(cipher.decrypt(receipt.encryptedPayload))
            val answerId = if (data.isNull("assistantMessageId")) null else data.getString("assistantMessageId")
            if (original.desktopMessageId == null || answerId == null) emptyList() else listOf(
                original.desktopMessageId to original,
                answerId to original.copy(id = "reply-${original.id}", sender = "assistant", desktopMessageId = answerId)
            )
        }.groupBy({ it.first }, { it.second })
        return rows.map { row ->
            val linked = links[row.desktopMessageId]?.singleOrNull()
            if (linked == null || linked.sessionId != row.sessionId || linked.sender != row.sender) row
            else if (row.sender == "user") row.copy(id = linked.id, requestId = linked.requestId,
                desktopFingerprint = linked.desktopFingerprint, requireLocal = linked.requireLocal, requireHipaa = linked.requireHipaa,
                attemptedAt = linked.attemptedAt) // Repeat reads the original question from the receipt, not possibly edited history.
            else row.copy(id = linked.id) // Display the latest answer; never infer a new prompt from it.
        }
    }
}
