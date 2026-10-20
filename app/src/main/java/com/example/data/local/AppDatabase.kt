package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.crypto.LegacyVaultMigration
import com.example.data.crypto.MigrationOutcome
import com.example.data.crypto.VaultCipher
import com.example.data.local.dao.*
import com.example.data.local.entity.*

@Database(
    entities = [EncryptedChatSession::class, EncryptedChatMessage::class,
        EncryptedDocument::class, DesktopTask::class, DesktopNotification::class,
        PendingOperation::class, LocalDraft::class, LocalDraftEvidence::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun documentDao(): DocumentDao
    abstract fun taskDao(): TaskDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun localDraftDao(): LocalDraftDao

    /** Content is migrated only after device authentication, in one rollback-safe transaction. */
    suspend fun migrateLegacyContent(cipher: VaultCipher): MigrationOutcome =
        LegacyVaultMigration.migrate(this, cipher)

    companion object {
        @Volatile private var instance: AppDatabase? = null
        @Volatile private var demoInstance: AppDatabase? = null

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE desktop_tasks ADD COLUMN category TEXT NOT NULL DEFAULT 'AGENT'")
                db.execSQL("ALTER TABLE desktop_tasks ADD COLUMN requiresApproval INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE desktop_tasks ADD COLUMN approvalPrompt TEXT")
                // Preserve the old notification category/type instead of dropping its records.
                db.execSQL("""CREATE TABLE desktop_notifications_new (
                    id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, message TEXT NOT NULL,
                    timestamp INTEGER NOT NULL, isRead INTEGER NOT NULL, isAcknowledged INTEGER NOT NULL,
                    severity TEXT NOT NULL, category TEXT NOT NULL, requiresApproval INTEGER NOT NULL,
                    assignedTo TEXT, relatedAction TEXT)""")
                db.execSQL("""INSERT INTO desktop_notifications_new
                    SELECT id,title,message,timestamp,isRead,0,'INFO',type,0,NULL,NULL
                    FROM desktop_notifications""")
                db.execSQL("DROP TABLE desktop_notifications")
                db.execSQL("ALTER TABLE desktop_notifications_new RENAME TO desktop_notifications")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN desktopChatId TEXT")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN desktopRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN storageVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN requestId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE chat_messages SET requestId = id")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN deliveryError TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attemptedAt INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN desktopMessageId TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN desktopFingerprint TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN requireLocal INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN requireHipaa INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN storageVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN storageVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE desktop_tasks ADD COLUMN storageVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE desktop_notifications ADD COLUMN storageVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS pending_operations (
                    id TEXT NOT NULL PRIMARY KEY, operation TEXT NOT NULL, encryptedPayload TEXT NOT NULL,
                    desktopFingerprint TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL,
                    attemptedAt INTEGER, serverId TEXT)""")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_drafts (
                    id TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, deleted INTEGER NOT NULL,
                    encryptedFields TEXT, evidenceId TEXT, updatedAt INTEGER NOT NULL, deleteGeneration INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_draft_evidence (
                    id TEXT NOT NULL PRIMARY KEY, encryptedPayload TEXT NOT NULL, ciphertextSha256 TEXT NOT NULL)""")
            }
        }

        /** Unsupported schemas fail without deleting the database. No destructive fallback. */
        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context, "private_ai_vault.db").also { instance = it }
        }

        /** Demonstration rows can never contaminate the user's real vault. */
        fun getDemoInstance(context: Context): AppDatabase = demoInstance ?: synchronized(this) {
            demoInstance ?: build(context, "private_ai_demo_v4.db").also { demoInstance = it }
        }

        private fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
