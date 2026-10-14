package com.example

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.crypto.*
import com.example.data.local.AppDatabase
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.repository.PrivateAIRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/** Actual Kotlin client -> isolated Node Core TLS gateway; no physical Keystore/camera claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BootstrapDesktopInteropTest {
    @Test fun desktopBootstrapCommitsVerifiedConfigurationAndRejectsRealReplay()=runBlocking {
        val path=System.getenv("PRIVATE_AI_GATEWAY_TEST_PAIR_FILE")
        assumeTrue("Supply an isolated running desktop gateway v2 bootstrap fixture.",!path.isNullOrBlank())
        val data=JSONObject(File(path!!).readText())
        assumeTrue("The supplied fixture must include a fresh bootstrapUri.",data.has("bootstrapUri"))
        val code=data.getString("bootstrapUri")
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher=object:VaultCipher {
            override fun encrypt(plaintext:String)=VaultCodec.encrypt(plaintext,key)
            override fun decrypt(encryptedPayload:String)=VaultCodec.decrypt(encryptedPayload,key)
        }
        val storage=SecureConfigStore(context,cipher)
        val database=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        val repository=PrivateAIRepository(database,configStore=storage,cipher=cipher)
        storage.clear()
        try {
            repository.onUnlocked().join()
            assertTrue(repository.vaultReady.value)
            val receipt=repository.pairSecure(code)
            assertTrue(receipt.bootstrap)
            assertEquals(ConnectionStatus.CONNECTED,repository.connectionStatus.value)
            assertEquals(receipt.approvedScopes,repository.nodeStats.value.scopes)
            val saved=storage.load()!!
            assertEquals(saved,repository.nodeConfig.value)
            PairingConfigParser.certificate(saved)
            assertEquals(receipt.fingerprint,saved.certificateFingerprint)
            assertFalse(receipt.toString().contains(saved.pairKey))
            assertTrue(receipt.serverName.isNotBlank())
            val another=PairingBootstrapService()
            try {
                another.exchange(PairingBootstrapParser.parse(code),DesktopNodeConfig()) {}
                fail("A consumed desktop bootstrap must fail even in an independent Android client.")
            } catch(e:GatewayException) { assertEquals(401,e.httpStatus) }
            finally { another.cancelAll() }
            assertEquals(saved,storage.load())
        } finally { repository.close();database.close();storage.clear() }
    }
}
