package com.natadej.storagedoctor.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.natadej.storagedoctor.MainActivity
import com.natadej.storagedoctor.R
import com.natadej.storagedoctor.core.GB
import com.natadej.storagedoctor.ui.fmtBytes
import java.util.concurrent.TimeUnit

const val GROWTH_ALERT = 5 * GB

/** Checks disk usage every 6 hours and notifies when it is over target or grew fast. */
class MonitorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        val disk = Device.disk(applicationContext)
        val now = System.currentTimeMillis()
        val grew = if (store.lastCheckUsed >= 0) disk.used - store.lastCheckUsed else 0L
        val over = disk.usedPct > store.target
        val msg = when {
            grew >= GROWTH_ALERT -> "Storage grew by ${fmtBytes(grew)} since the last check. Something may be filling it."
            over && now - store.lastAlert > 24 * 3_600_000L -> "Storage is %.0f%% full (target %d%%). Tap to see what to clean.".format(disk.usedPct, store.target)
            else -> null
        }
        if (msg != null) { notify(applicationContext, msg); store.lastAlert = now }
        store.lastCheckUsed = disk.used
        store.lastCheckTime = now
        return Result.success()
    }

    companion object {
        private const val WORK = "storage-monitor"
        private const val CHANNEL = "storage"

        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (on) wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MonitorWorker>(6, TimeUnit.HOURS).build())
            else wm.cancelUniqueWork(WORK)
        }

        fun notify(ctx: Context, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Storage alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Storage Doctor")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(1, n)
        }
    }
}
