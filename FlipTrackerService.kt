package com.bcs.flipstudy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat

class FlipTrackerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_ID = "bcs_flip_tracker_v2"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.bcs.flipstudy.START"
        const val ACTION_STOP = "com.bcs.flipstudy.STOP"
        const val ACTION_RESET_TIMER = "com.bcs.flipstudy.RESET"
        const val ACTION_ONLINE_CHANGED = "com.bcs.flipstudy.ONLINE_CHANGED"
        const val ACTION_ALARM_COMPLETED = "com.bcs.flipstudy.ALARM_COMPLETED"
        const val BROADCAST_STATE_CHANGED = "com.bcs.flipstudy.STATE_CHANGED"

        private const val BREAK_LIMIT_SEC = 5 * 60L
        private const val FLIP_DEBOUNCE_MS = 2500L
    }

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    private var isRunning = false
    private var currentMode = "IDLE" // IDLE, STUDY, BREAK
    private var stateStartMs = 0L
    private var remainingSec = 45 * 60
    private var isFaceDown = false

    // ২–৩ সেকেন্ড স্থির থাকলে তবেই ফ্লিপ ধরবে (ভুল শনাক্ত ঠেকাতে)
    private var pendingFaceDown: Boolean? = null
    private var pendingSinceMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // ধাপ ১.৩: Wake-up Accelerometer (স্ক্রিন অফেও কাজ করে), না থাকলে সাধারণ Accelerometer
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ধাপ ১.৭: সিস্টেম মেরে ফেলে পুনরায় চালু করলে (intent == null) আগের অবস্থা পুনরুদ্ধার
        if (intent == null) {
            restoreFromPrefsIfRunning()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET_TIMER -> {
                if (isRunning) {
                    commitElapsed(System.currentTimeMillis())
                    remainingSec = DataStore.getPresetMin(this) * 60
                    stateStartMs = System.currentTimeMillis()
                    persistRuntime()
                    if (currentMode == "STUDY") scheduleStudyAlarm() else cancelStudyAlarm()
                    updateNotification()
                    broadcastStateChanged()
                }
                return START_STICKY
            }
            ACTION_ONLINE_CHANGED -> {
                if (isRunning) {
                    evaluateModeTransition(System.currentTimeMillis())
                }
                return START_STICKY
            }
            ACTION_ALARM_COMPLETED -> {
                if (isRunning && currentMode == "STUDY") {
                    remainingSec = DataStore.getPresetMin(this) * 60
                    stateStartMs = System.currentTimeMillis()
                    persistRuntime()
                    scheduleStudyAlarm()
                    updateNotification()
                    broadcastStateChanged()
                }
                return START_STICKY
            }
            else -> {
                startTracking()
                return START_STICKY
            }
        }
    }

    private fun restoreFromPrefsIfRunning() {
        val rt = DataStore.loadRuntimeState(this)
        if (!rt.running) {
            stopSelf()
            return
        }
        isRunning = true
        currentMode = rt.mode
        stateStartMs = if (rt.stateStartMs > 0L) rt.stateStartMs else System.currentTimeMillis()
        remainingSec = rt.remainingSec.coerceAtLeast(1)
        isFaceDown = rt.faceDown
        startForeground(NOTIF_ID, buildNotification())
        registerBatchedSensor()
        if (currentMode == "STUDY") scheduleStudyAlarm()
    }

    private fun startTracking() {
        if (isRunning) return
        DataStore.absorbClosedTimeAsSleepOther(this)

        isRunning = true
        isFaceDown = false
        pendingFaceDown = null
        remainingSec = DataStore.getPresetMin(this) * 60
        stateStartMs = System.currentTimeMillis()
        currentMode = if (DataStore.isOnlineStudy(this)) "STUDY" else "BREAK"

        persistRuntime()
        startForeground(NOTIF_ID, buildNotification())
        registerBatchedSensor()
        if (currentMode == "STUDY") scheduleStudyAlarm() else cancelStudyAlarm()
        broadcastStateChanged()
    }

    private fun stopTracking() {
        if (isRunning) {
            commitElapsed(System.currentTimeMillis())
        }
        isRunning = false
        currentMode = "IDLE"
        cancelStudyAlarm()
        sensorManager.unregisterListener(this)
        persistRuntime()
        DataStore.markAppClosedNow(this)
        broadcastStateChanged()
    }

    private fun registerBatchedSensor() {
        // ধাপ ১.৩: ১ সেকেন্ড স্যাম্পল, ৫ সেকেন্ড ব্যাচিং — CPU ঘুমাতে পারবে!
        accelSensor?.let {
            sensorManager.registerListener(this, it, 1_000_000, 5_000_000)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        val z = event.values[2]
        val now = System.currentTimeMillis()

        val detectedFaceDown: Boolean? = when {
            z < -7.5f -> true
            z > 6.5f -> false
            else -> null
        }

        if (detectedFaceDown == null || detectedFaceDown == isFaceDown) {
            pendingFaceDown = null
            return
        }

        if (pendingFaceDown != detectedFaceDown) {
            pendingFaceDown = detectedFaceDown
            pendingSinceMs = now
        } else if (now - pendingSinceMs >= FLIP_DEBOUNCE_MS) {
            isFaceDown = detectedFaceDown
            pendingFaceDown = null
            evaluateModeTransition(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun evaluateModeTransition(now: Long) {
        val shouldStudy = isFaceDown || DataStore.isOnlineStudy(this)
        val targetMode = if (shouldStudy) "STUDY" else "BREAK"
        if (targetMode == currentMode) return

        commitElapsed(now)
        currentMode = targetMode
        stateStartMs = now
        persistRuntime()

        if (currentMode == "STUDY") scheduleStudyAlarm() else cancelStudyAlarm()
        updateNotification()
        broadcastStateChanged()
    }

    // ধাপ ১.১: অবস্থা বদলালেই শুধু একবার elapsed হিসাব করে সেভ
    private fun commitElapsed(now: Long) {
        if (stateStartMs <= 0L) return
        val elapsedSec = ((now - stateStartMs) / 1000L).coerceAtLeast(0L)
        if (elapsedSec == 0L) return

        if (currentMode == "STUDY") {
            remainingSec = (remainingSec - elapsedSec.toInt()).coerceAtLeast(1)
            val sub = DataStore.getCurrentSubject(this)
            DataStore.updateToday(this) { rec ->
                rec.studySec += elapsedSec
                rec.subject = sub
                rec.subjects[sub] = (rec.subjects[sub] ?: 0L) + elapsedSec
                rec.mode = if (DataStore.isOnlineStudy(this)) "manual" else "flip"
                rec.synced = false
            }
        } else if (currentMode == "BREAK") {
            // প্রথম ৫ মিনিট BREAK, বাকিটা WASTE
            val wastePart = (elapsedSec - BREAK_LIMIT_SEC).coerceAtLeast(0L)
            if (wastePart > 0L) {
                DataStore.updateToday(this) { rec ->
                    rec.wasteSec += wastePart
                    rec.wasteReasons["ব্রেক অতিরিক্ত"] = (rec.wasteReasons["ব্রেক অতিরিক্ত"] ?: 0L) + wastePart
                    rec.synced = false
                }
            }
        }
    }

    // ধাপ ১.৪: AlarmManager.setAlarmClock()
    private fun scheduleStudyAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + (remainingSec.coerceAtLeast(5) * 1000L)
        val pi = alarmPendingIntent()
        val showPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPi), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelStudyAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this, 2002, Intent(this, StudyAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun persistRuntime() {
        DataStore.saveRuntimeState(
            this,
            RuntimeTrackerState(isRunning, currentMode, stateStartMs, remainingSec, isFaceDown)
        )
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(BROADCAST_STATE_CHANGED).setPackage(packageName))
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    // ধাপ ১.৫: সিস্টেম নিজেই Chronometer আঁকবে, প্রতি সেকেন্ডে অ্যাপ জাগবে না
    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)

        if (currentMode == "STUDY") {
            val endMs = stateStartMs + (remainingSec * 1000L)
            builder.setContentTitle("📚 পড়া চলছে (${DataStore.getCurrentSubject(this)})")
            builder.setContentText("বাকি সময় (সিস্টেম কাউন্টডাউন)")
            builder.setWhen(endMs)
            builder.setChronometerCountDown(true)
        } else {
            builder.setContentTitle("☕ ব্রেক / বিরতি (৫ মি. পর অপচয়)")
            builder.setContentText("পড়তে চাইলে ফোন উল্টে রাখো")
            builder.setWhen(stateStartMs)
            builder.setChronometerCountDown(false)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "BCS ফ্লিপ স্টাডি", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
