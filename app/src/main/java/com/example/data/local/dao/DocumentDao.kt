package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.EncryptedDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface DocumentDao {
    @Query("SELECT id,fileName,fileType,'' AS encryptedContent,checksumSha256,fileSizeBytes,updatedAt,syncedWithDesktop,tags,storageVersion FROM documents WHERE (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit")
    suspend fun metadataAfter(after: String?, limit: Int): List<EncryptedDocument>
    @Query("SELECT id,fileName,fileType,'' AS encryptedContent,checksumSha256,fileSizeBytes,updatedAt,syncedWithDesktop,tags,storageVersion FROM documents")
    suspend fun allMetadata(): List<EncryptedDocument>
    @Query("SELECT id,fileName,fileType,'' AS encryptedContent,checksumSha256,fileSizeBytes,updatedAt,syncedWithDesktop,tags,storageVersion FROM documents WHERE id = :id")
    suspend fun metadataById(id: String): EncryptedDocument?
    @Query("SELECT id,fileName,fileType,'' AS encryptedContent,checksumSha256,fileSizeBytes,updatedAt,syncedWithDesktop,tags,storageVersion FROM documents ORDER BY updatedAt DESC")
    fun watchMetadata(): Flow<List<EncryptedDocument>>
    @Query("SELECT id,fileName,fileType,'' AS encryptedContent,checksumSha256,fileSizeBytes,updatedAt,syncedWithDesktop,tags,storageVersion FROM documents WHERE id = :id")
    fun watchMetadataById(id: String): Flow<EncryptedDocument?>
    @Query("SELECT length(encryptedContent) FROM documents WHERE id = :id") suspend fun bodyLength(id: String): Long?
    @Query("SELECT substr(encryptedContent,:start,:count) FROM documents WHERE id = :id")
    suspend fun bodySlice(id: String, start: Int, count: Int): String?

    /** Consistent transaction; each cursor row stays below Android's CursorWindow limit. */
    @Transaction suspend fun hydratedById(id: String): EncryptedDocument? {
        val row = metadataById(id) ?: return null
        val length = bodyLength(id) ?: error("Document changed while loading.")
        require(length in 0..48L * 1024 * 1024) { "Document exceeds the supported protected size." }
        val body = StringBuilder(length.toInt())
        var offset = 0
        while (offset < length) {
            val count = minOf(256 * 1024L, length - offset).toInt()
            val part = bodySlice(id, offset + 1, count) ?: error("Document changed while loading.")
            check(part.codePointCount(0, part.length) == count)
            body.append(part); offset += count
        }
        return row.copy(encryptedContent = body.toString())
    }
    @Transaction suspend fun afterForPolicy(after: String?, limit: Int): List<EncryptedDocument> =
        metadataAfter(after, limit).mapNotNull { hydratedById(it.id) }
    @Transaction suspend fun allForMigration(): List<EncryptedDocument> = allMetadata().mapNotNull { hydratedById(it.id) }
    fun getAllDocuments(): Flow<List<EncryptedDocument>> = watchMetadata().map { rows -> rows.mapNotNull { hydratedById(it.id) } }
    fun getDocumentById(id: String): Flow<EncryptedDocument?> = watchMetadataById(id).map { row -> row?.let { hydratedById(id) } }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDocument(doc: EncryptedDocument)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDocuments(docs: List<EncryptedDocument>)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Query("DELETE FROM documents") suspend fun clearAllDocuments()
}
