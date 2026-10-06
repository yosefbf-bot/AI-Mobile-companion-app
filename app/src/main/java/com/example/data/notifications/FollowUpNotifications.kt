package com.example.data.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.MainActivity
import com.example.R
import com.example.data.crypto.RestrictedNotificationStore
import com.example.data.network.DesktopNodeService
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object FollowUpNotifications {
    const val CHANNEL = "private_ai_followups"
    private const val PERIODIC = "private_ai_restricted_followups"
    private const val ONCE = "private_ai_restricted_followups_now"

    fun configure(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(ONCE); return }
        // CONNECTED requires Internet validation on newer Android versions and rejects an offline LAN.
        // The pinned HTTPS request itself detects reachability; its failure retains the notice for retry.
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FollowUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build())
        work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FollowUpWorker>().setConstraints(constraints).build())
    }

    fun canDisplay(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Private AI follow-ups", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    suspend fun deliverPending(json: JSONObject, display: (String) -> Boolean,
        acknowledge: suspend (String) -> Boolean): Boolean {
        val rows = json.getJSONArray("notifications")
        require(rows.length() <= 200) { "Too many reminder metadata records." }
        for (index in 0 until rows.length()) {
            val item = rows.getJSONObject(index)
            if (item.optString("status") != "pending") continue
            val id = item.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,120}")))
            if (!display(id) || !acknowledge(id)) return false
        }
        return true
    }

    /** Returning true means Android accepted the generic notice; it does not complete its action. */
    @SuppressLint("MissingPermission") // canDisplay checks POST_NOTIFICATIONS and the OS channel before notify.
    fun display(context: Context, id: String): Boolean {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,120}")))
        if (!canDisplay(context)) return false
        val intent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_private_ai_notification)
            .setContentTitle("Private AI follow-up")
            .setContentText("A follow-up needs attention. Open your protected workspace to review it.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(intent).setAutoCancel(true).build()
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val number = ((bytes[0].toInt() and 127) shl 24) or ((bytes[1].toInt() and 255) shl 16) or
            ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
        return try { NotificationManagerCompat.from(context).notify(number, notification); true }
        catch (_: SecurityException) { false }
    }
}

/** Persisted scheduling uses only the restricted token, even when the main vault is locked. */
class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = RestrictedNotificationStore(applicationContext)
        val service = DesktopNodeService()
        try {
            val config = store.load() ?: return Result.success()
            if (!FollowUpNotifications.canDisplay(applicationContext)) return Result.retry()
            val json = service.request(config, "/mobile/api/v1/notifications")
            val delivered = FollowUpNotifications.deliverPending(json,
                display = { FollowUpNotifications.display(applicationContext, it) }, acknowledge = { id ->
                val receipt = service.request(config, "/mobile/api/v1/notifications/ack", JSONObject().put("id", id))
                receipt.getString("id") == id && receipt.getString("status") == "acknowledged"
            })
            return if (delivered) Result.success() else Result.retry()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No notification is marked delivered when trust, permission, network, or display failed.
            return Result.retry()
        } finally { service.cancelAll() }
    }
}
