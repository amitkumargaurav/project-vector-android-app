package com.projectvector.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.projectvector.app.MainActivity
import com.projectvector.app.R
import com.projectvector.app.bridge.ReminderPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedule(payload: ReminderPayload): String {
        val delay = Duration.between(LocalDateTime.now(), nextOccurrence(payload.hour, payload.minute)).toMillis().coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(payload.toWorkData())
            .addTag(tag(payload.id))
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName(payload.id), ExistingPeriodicWorkPolicy.UPDATE, request)
        return payload.id
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(uniqueName(id))
    }

    private fun nextOccurrence(hour: Int, minute: Int): LocalDateTime {
        val now = LocalDateTime.now()
        val target = now.with(LocalTime.of(hour, minute))
        return if (target.isAfter(now)) target else target.plusDays(1)
    }

    private fun uniqueName(id: String) = "vector_reminder_$id"
    private fun tag(id: String) = "vector_reminder_tag_$id"

    private fun ReminderPayload.toWorkData(): Data = Data.Builder()
        .putString(ReminderWorker.KEY_TITLE, title)
        .putString(ReminderWorker.KEY_ID, id)
        .apply { daysOfWeek?.let { putIntArray(ReminderWorker.KEY_DAYS_OF_WEEK, it.toIntArray()) } }
        .build()
}

class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Project Vector reminder"
        val daysOfWeek = inputData.getIntArray(KEY_DAYS_OF_WEEK)?.toSet().orEmpty()
        if (daysOfWeek.isNotEmpty() && LocalDate.now().dayOfWeek.value !in daysOfWeek) {
            return Result.success()
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText("Open Project Vector to keep your plan moving.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(NotificationSound.uri(applicationContext))
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_DAYS_OF_WEEK = "days_of_week"
    }
}
