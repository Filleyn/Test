package com.trailguidegps.app.record

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.trailguidegps.app.MainActivity
import com.trailguidegps.app.R

/**
 * A reminder at the time you planned to be out: tell your check-in contacts you're out safely,
 * or that you're running late, before they start to worry. Not exact (Android may deliver it a
 * few minutes late), and it doesn't survive a phone restart until the app is opened again.
 */
class CheckInReminder : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Check-in reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "At the time you planned to be out, a reminder to check in"
            })
        }
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "You planned to be out by now. Tap to tell your contacts you're out safely, or that you're running late."
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_track).setContentTitle("Time to check in")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        const val EXTRA_OPEN = "open_check_in"
        private const val CHANNEL = "checkin"
        private const val NOTIFICATION_ID = 4107

        private fun pending(context: Context) = PendingIntent.getBroadcast(context, 0,
            Intent(context, CheckInReminder::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Sets the reminder for [atMs], replacing any earlier one; nothing if that time has passed. */
        fun schedule(context: Context, atMs: Long) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(pending(context))
            if (atMs > System.currentTimeMillis()) runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending(context)) }
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        }
    }
}
