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
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun schedule(payload: ReminderPayload): String {
        val delay = nextOccurrenceDelayMillis(payload.hour, payload.minute)
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

    fun cancelAll() {
        workManager.cancelAllWork()
    }

    private fun nextOccurrenceDelayMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DATE, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
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
        if (daysOfWeek.isNotEmpty() && currentIsoWeekday() !in daysOfWeek) {
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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(NotificationSound.uri(applicationContext))
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
        return Result.success()
    }

    private fun currentIsoWeekday(): Int {
        val calendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_DAYS_OF_WEEK = "days_of_week"
    }
}
