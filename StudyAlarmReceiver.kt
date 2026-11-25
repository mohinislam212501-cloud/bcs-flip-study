package com.bcs.flipstudy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

class StudyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rt = DataStore.loadRuntimeState(context)
        if (!rt.running || rt.mode != "STUDY") return

        val now = System.currentTimeMillis()
        val elapsedSec = ((now - rt.stateStartMs) / 1000L).coerceAtLeast(0L)
        val preset = DataStore.getPresetMin(context)
        val subject = DataStore.getCurrentSubject(context)

        DataStore.updateToday(context) { rec ->
            rec.studySec += elapsedSec
            if (preset >= 40) rec.c45 += 1 else rec.c25 += 1
            rec.subject = subject
            rec.subjects[subject] = (rec.subjects[subject] ?: 0L) + elapsedSec
            rec.mode = if (DataStore.isOnlineStudy(context)) "manual" else "flip"
            rec.synced = false
        }

        // শব্দ ও ভাইব্রেশন
        if (DataStore.isAlarmOn(context)) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
            } catch (_: Exception) {}
            try {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450, 200, 600), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 450, 200, 450), -1)
                }
            } catch (_: Exception) {}
        }

        // পরের সেশনের অ্যালার্ম সেট ও নোটিফিকেশন আপডেট
        val nextIntent = Intent(context, FlipTrackerService::class.java).apply {
            action = FlipTrackerService.ACTION_ALARM_COMPLETED
        }
        try {
            context.startService(nextIntent)
        } catch (_: Exception) {}
    }
}
