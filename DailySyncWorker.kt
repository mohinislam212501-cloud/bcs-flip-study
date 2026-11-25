package com.bcs.flipstudy

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DailySyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        // ধাপ ২.৪: পাঠানোর মতো কিছু না থাকলে নেটওয়ার্ক কল হবে না
        if (!DataStore.hasUnsyncedDays(applicationContext)) {
            return Result.success()
        }
        val (ok, _) = DataStore.syncToGoogleDrive(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "bcs_daily_drive_sync_v2"

        fun scheduleDaily(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<DailySyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }
    }
}
