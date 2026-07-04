package com.projectvector.app.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.projectvector.app.R
import com.projectvector.app.bridge.ReminderPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedule(payload: ReminderPayload): String {
        // TODO: Honor payload.daysOfWeek. WorkManager periodic work handles daily reminders,
        // but weekday-specific schedules need separate one-time/periodic work per selected day.
        val delay = Duration.between(LocalDateTime.now(), nextOccurrence(payload.hour, payload.minute)).toMillis().coerceAtLeast(0)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TITLE to payload.title, ReminderWorker.KEY_ID to payload.id))
            .addTag(tag(payload.id))
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName(payload.id), ExistingPeriodicWorkPolicy.UPDATE, request)
        return payload.id
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(uniqueName(id))
    }

    private fun nextOccurrence(hour: Int, minute: Int): LocalDateTime {
        val target = LocalDateTime.now().with(LocalTime.of(hour, minute))
        return if (target.isAfter(LocalDateTime.now())) target else target.plusDays(1)
    }

    private fun uniqueName(id: String) = "vector_reminder_$id"
    private fun tag(id: String) = "vector_reminder_tag_$id"
}

class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Project Vector reminder"
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("Open Project Vector to keep your plan moving.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
    }
}
