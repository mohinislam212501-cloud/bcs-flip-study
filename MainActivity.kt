package com.bcs.flipstudy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tabTimer: View
    private lateinit var tabReport: View
    private lateinit var tabWeekly: View
    private lateinit var tabSettings: View

    private lateinit var navTimer: TextView
    private lateinit var navReport: TextView
    private lateinit var navWeekly: TextView
    private lateinit var navSettings: TextView

    private lateinit var tvTimerClock: TextView
    private lateinit var tvModeStatus: TextView
    private lateinit var tvTodayStats: TextView
    private lateinit var swFlipService: Switch
    private lateinit var swOnlineStudy: Switch
    private lateinit var etSubject: EditText
    private lateinit var chkWordTarget: CheckBox
    private lateinit var btnPreset45: Button
    private lateinit var btnPreset25: Button
    private lateinit var btnAddWaste: Button
    private lateinit var btnResetTimer: Button

    // ধাপ ৪: মাসিক / বার্ষিক রিপোর্ট UI
    private lateinit var btnPrevPeriod: Button
    private lateinit var btnNextPeriod: Button
    private lateinit var tvPeriodTitle: TextView
    private lateinit var btnToggleYearly: Button
    private lateinit var tvReportOfflineBadge: TextView
    private lateinit var tvReportSummary: TextView
    private lateinit var reportBarChart: WeeklyBarChartView
    private lateinit var tvReportBreakdown: TextView

    private lateinit var weeklyChart: WeeklyBarChartView
    private lateinit var tvWeeklyStudy: TextView
    private lateinit var tvWeeklyWaste: TextView
    private lateinit var tvWeeklySleep: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var btnSyncNow: Button

    private lateinit var etGasUrl: EditText
    private lateinit var etSyncKey: EditText
    private lateinit var swAlarm: Switch
    private lateinit var btnSaveSettings: Button

    private var reportYearly = false
    private var reportYear = Calendar.getInstance().get(Calendar.YEAR)
    private var reportMonth = Calendar.getInstance().get(Calendar.MONTH) + 1

    // ধাপ ১.৬: UI টাইমার কেবল স্ক্রিন দৃশ্যমান থাকলেই (onStart-onStop) চলবে
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshTimerUI()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshTimerUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()
        DailySyncWorker.scheduleDaily(this)

        val absorbed = DataStore.absorbClosedTimeAsSleepOther(this)
        if (absorbed >= 60L) {
            Toast.makeText(
                this,
                "অ্যাপ বন্ধ থাকা ${DataStore.formatHoursMinBn(absorbed)} ঘুম ও অন্যান্য সময়ে যোগ হয়েছে",
                Toast.LENGTH_LONG
            ).show()
        }

        bindViews()
        setupListeners()
        showTab(0)
        refreshAllUI()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun bindViews() {
        tabTimer = findViewById(R.id.tabTimer)
        tabReport = findViewById(R.id.tabReport)
        tabWeekly = findViewById(R.id.tabWeekly)
        tabSettings = findViewById(R.id.tabSettings)

        navTimer = findViewById(R.id.navTimer)
        navReport = findViewById(R.id.navReport)
        navWeekly = findViewById(R.id.navWeekly)
        navSettings = findViewById(R.id.navSettings)

        tvTimerClock = findViewById(R.id.tvTimerClock)
        tvModeStatus = findViewById(R.id.tvModeStatus)
        tvTodayStats = findViewById(R.id.tvTodayStats)
        swFlipService = findViewById(R.id.swFlipService)
        swOnlineStudy = findViewById(R.id.swOnlineStudy)
        etSubject = findViewById(R.id.etSubject)
        chkWordTarget = findViewById(R.id.chkWordTarget)
        btnPreset45 = findViewById(R.id.btnPreset45)
        btnPreset25 = findViewById(R.id.btnPreset25)
        btnAddWaste = findViewById(R.id.btnAddWaste)
        btnResetTimer = findViewById(R.id.btnResetTimer)

        btnPrevPeriod = findViewById(R.id.btnPrevPeriod)
        btnNextPeriod = findViewById(R.id.btnNextPeriod)
        tvPeriodTitle = findViewById(R.id.tvPeriodTitle)
        btnToggleYearly = findViewById(R.id.btnToggleYearly)
        tvReportOfflineBadge = findViewById(R.id.tvReportOfflineBadge)
        tvReportSummary = findViewById(R.id.tvReportSummary)
        reportBarChart = findViewById(R.id.reportBarChart)
        tvReportBreakdown = findViewById(R.id.tvReportBreakdown)

        weeklyChart = findViewById(R.id.weeklyChart)
        tvWeeklyStudy = findViewById(R.id.tvWeeklyStudy)
        tvWeeklyWaste = findViewById(R.id.tvWeeklyWaste)
        tvWeeklySleep = findViewById(R.id.tvWeeklySleep)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        btnSyncNow = findViewById(R.id.btnSyncNow)

        etGasUrl = findViewById(R.id.etGasUrl)
        etSyncKey = findViewById(R.id.etSyncKey)
        swAlarm = findViewById(R.id.swAlarm)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
    }

    private fun setupListeners() {
        navTimer.setOnClickListener { showTab(0) }
        navReport.setOnClickListener { showTab(1); loadReportTab() }
        navWeekly.setOnClickListener { showTab(2); renderWeeklyTab() }
        navSettings.setOnClickListener { showTab(3); renderSettingsTab() }

        swFlipService.setOnCheckedChangeListener { _, isChecked ->
            DataStore.setCurrentSubject(this, etSubject.text.toString())
            val intent = Intent(this, FlipTrackerService::class.java).apply {
                action = if (isChecked) FlipTrackerService.ACTION_START else FlipTrackerService.ACTION_STOP
            }
            if (isChecked) ContextCompat.startForegroundService(this, intent) else startService(intent)
            refreshTimerUI()
        }

        swOnlineStudy.setOnCheckedChangeListener { _, isChecked ->
            DataStore.setOnlineStudy(this, isChecked)
            if (DataStore.loadRuntimeState(this).running) {
                startService(Intent(this, FlipTrackerService::class.java).setAction(FlipTrackerService.ACTION_ONLINE_CHANGED))
            }
            refreshTimerUI()
        }

        btnPreset45.setOnClickListener {
            DataStore.setPresetMin(this, 45)
            resetTimerIfRunning()
        }
        btnPreset25.setOnClickListener {
            DataStore.setPresetMin(this, 25)
            resetTimerIfRunning()
        }
        btnResetTimer.setOnClickListener { resetTimerIfRunning() }

        chkWordTarget.setOnCheckedChangeListener { _, checked ->
            DataStore.updateToday(this) { rec ->
                rec.wordTarget = checked
                rec.synced = false
            }
            refreshTimerUI()
        }

        btnAddWaste.setOnClickListener {
            val reasons = arrayOf("ফোন স্ক্রলিং", "আড্ডা/গল্প", "ক্লান্তি/অলসতা", "অন্যান্য কাজ")
            AlertDialog.Builder(this)
                .setTitle("অপচয়ের কারণ যোগ করো (+১৫ মি.)")
                .setItems(reasons) { _, which ->
                    val r = reasons[which]
                    DataStore.updateToday(this) { rec ->
                        rec.wasteSec += 15 * 60L
                        rec.wasteReason = r
                        rec.wasteReasons[r] = (rec.wasteReasons[r] ?: 0L) + 15 * 60L
                        rec.synced = false
                    }
                    Toast.makeText(this, "অপচয় লগ হয়েছে: $r", Toast.LENGTH_SHORT).show()
                    refreshTimerUI()
                }
                .show()
        }

        btnPrevPeriod.setOnClickListener {
            if (reportYearly) reportYear-- else {
                reportMonth--; if (reportMonth < 1) { reportMonth = 12; reportYear-- }
            }
            loadReportTab()
        }
        btnNextPeriod.setOnClickListener {
            if (reportYearly) reportYear++ else {
                reportMonth++; if (reportMonth > 12) { reportMonth = 1; reportYear++ }
            }
            loadReportTab()
        }
        btnToggleYearly.setOnClickListener {
            reportYearly = !reportYearly
            btnToggleYearly.text = if (reportYearly) "মাসিক দেখো" else "বার্ষিক দেখো"
            loadReportTab()
        }

        btnSyncNow.setOnClickListener {
            btnSyncNow.isEnabled = false
            btnSyncNow.text = "সিঙ্ক হচ্ছে..."
            thread {
                val (ok, msg) = DataStore.syncToGoogleDrive(this)
                runOnUiThread {
                    btnSyncNow.isEnabled = true
                    btnSyncNow.text = "এখনই Google Drive-এ সিঙ্ক করো"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ok) renderWeeklyTab()
                }
            }
        }

        btnSaveSettings.setOnClickListener {
            DataStore.setGasUrl(this, etGasUrl.text.toString())
            DataStore.setSyncKey(this, etSyncKey.text.toString())
            DataStore.setAlarmOn(this, swAlarm.isChecked)
            Toast.makeText(this, "সেটিংস সেভ হয়েছে!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetTimerIfRunning() {
        val rt = DataStore.loadRuntimeState(this)
        if (rt.running) {
            startService(Intent(this, FlipTrackerService::class.java).setAction(FlipTrackerService.ACTION_RESET_TIMER))
        } else {
            DataStore.saveRuntimeState(this, rt.copy(remainingSec = DataStore.getPresetMin(this) * 60))
        }
        refreshTimerUI()
    }

    private fun showTab(idx: Int) {
        tabTimer.visibility = if (idx == 0) View.VISIBLE else View.GONE
        tabReport.visibility = if (idx == 1) View.VISIBLE else View.GONE
        tabWeekly.visibility = if (idx == 2) View.VISIBLE else View.GONE
        tabSettings.visibility = if (idx == 3) View.VISIBLE else View.GONE

        val active = ContextCompat.getColor(this, R.color.teal_primary)
        val idle = ContextCompat.getColor(this, R.color.text_muted)
        navTimer.setTextColor(if (idx == 0) active else idle)
        navReport.setTextColor(if (idx == 1) active else idle)
        navWeekly.setTextColor(if (idx == 2) active else idle)
        navSettings.setTextColor(if (idx == 3) active else idle)
    }

    private fun refreshAllUI() {
        etSubject.setText(DataStore.getCurrentSubject(this))
        swFlipService.isChecked = DataStore.loadRuntimeState(this).running
        swOnlineStudy.isChecked = DataStore.isOnlineStudy(this)
        chkWordTarget.isChecked = DataStore.getToday(this).wordTarget
        refreshTimerUI()
        renderWeeklyTab()
        renderSettingsTab()
    }

    private fun refreshTimerUI() {
        val rt = DataStore.loadRuntimeState(this)
        val now = System.currentTimeMillis()
        val elapsedSec = if (rt.running && rt.stateStartMs > 0L) ((now - rt.stateStartMs) / 1000L).coerceAtLeast(0L) else 0L

        val displaySec = if (rt.running && rt.mode == "STUDY") {
            (rt.remainingSec - elapsedSec.toInt()).coerceAtLeast(0)
        } else {
            rt.remainingSec
        }
        val clock = String.format(Locale.US, "%02d:%02d", displaySec / 60, displaySec % 60)
        tvTimerClock.text = DataStore.toBnDigits(clock)

        tvModeStatus.text = when {
            !rt.running -> "ট্র্যাকিং বন্ধ (অ্যাপ বন্ধ থাকলে ঘুম + অন্যান্য সময় গণনা হবে)"
            rt.mode == "STUDY" && DataStore.isOnlineStudy(this) -> "💻 অনলাইনে পড়া চলছে..."
            rt.mode == "STUDY" -> "📱 ফোন উল্টানো — পড়া চলছে (ব্যাটারি-সাশ্রয়ী মোড)"
            elapsedSec <= 300L -> "☕ ব্রেক চলছে (${DataStore.toBnDigits(elapsedSec / 60)} মি.)"
            else -> "⚠️ স্ক্রিন সোজা — অপচয় সময় গণনা হচ্ছে (${DataStore.toBnDigits((elapsedSec - 300) / 60)} মি.)"
        }

        val today = DataStore.getToday(this)
        tvTodayStats.text = "আজ: ৪৫মি বৃত্ত ${DataStore.toBnDigits(today.c45)}টি • ২৫মি বৃত্ত ${DataStore.toBnDigits(today.c25)}টি
" +
            "মোট পড়া: ${DataStore.formatHoursMinBn(today.totalStudyMinutes() * 60L)} • অপচয়: ${DataStore.formatHoursMinBn(today.wasteSec)} • ঘুম/অন্যান্য: ${DataStore.formatHoursMinBn(today.sleepOtherSec)}"
    }

    private fun loadReportTab() {
        val period = if (reportYearly) String.format(Locale.US, "%04d", reportYear)
        else String.format(Locale.US, "%04d-%02d", reportYear, reportMonth)
        tvPeriodTitle.text = DataStore.toBnDigits(period)
        tvReportSummary.text = "রিপোর্ট লোড হচ্ছে..."

        thread {
            val rep = DataStore.fetchOrComputeReport(this, period, reportYearly)
            runOnUiThread {
                tvReportOfflineBadge.visibility = if (rep.isOffline) View.VISIBLE else View.GONE
                tvReportSummary.text =
                    "মোট পড়া: ${DataStore.formatHoursMinBn(rep.totalStudyMin * 60L)} • দৈনিক গড়: ${DataStore.formatHoursMinBn(rep.avgDailyStudyMin * 60L)}
" +
                    "অপচয়: ${DataStore.formatHoursMinBn(rep.totalWasteMin * 60L)} • ঘুম/অন্যান্য: ${DataStore.formatHoursMinBn(rep.totalSleepMin * 60L)}
" +
                    "সেরা দিন: ${DataStore.toBnDigits(rep.bestDay)} (${DataStore.formatHoursMinBn(rep.bestDayMin * 60L)}) • স্কোর: ${DataStore.toBnDigits(rep.productivityScore)}%
" +
                    "স্ট্রিক: বর্তমান ${DataStore.toBnDigits(rep.currentStreak)} দিন • সর্বোচ্চ ${DataStore.toBnDigits(rep.longestStreak)} দিন"

                reportBarChart.setBars(rep.bars)

                val sb = StringBuilder()
                sb.append("📚 বিষয় অনুযায়ী পড়া:
")
                if (rep.subjects.isEmpty()) sb.append("• কোনো তথ্য নেই
")
                else rep.subjects.entries.sortedByDescending { it.value }.forEach {
                    sb.append("• ${it.key}: ${DataStore.formatHoursMinBn(it.value)}
")
                }
                sb.append("
⚠️ অপচয়ের কারণ:
")
                if (rep.wasteReasons.isEmpty()) sb.append("• কোনো তথ্য নেই")
                else rep.wasteReasons.entries.sortedByDescending { it.value }.forEach {
                    sb.append("• ${it.key}: ${DataStore.formatHoursMinBn(it.value)}
")
                }
                tvReportBreakdown.text = sb.toString().trim()
            }
        }
    }

    private fun renderWeeklyTab() {
        val last7 = DataStore.getLast7Days(this)
        weeklyChart.setBars(last7.map {
            ChartBar(DataStore.toBnDigits(it.date.takeLast(2)), it.totalStudyMinutes(), (it.wasteSec / 60L).toInt(), (it.sleepOtherSec / 60L).toInt())
        })
        tvWeeklyStudy.text = "পড়া
${DataStore.formatHoursMinBn(last7.sumOf { it.totalStudyMinutes() } * 60L)}"
        tvWeeklyWaste.text = "অপচয়
${DataStore.formatHoursMinBn(last7.sumOf { it.wasteSec })}"
        tvWeeklySleep.text = "ঘুম/অন্যান্য
${DataStore.formatHoursMinBn(last7.sumOf { it.sleepOtherSec })}"
        tvSyncStatus.text = DataStore.getLastSyncText(this)
    }

    private fun renderSettingsTab() {
        etGasUrl.setText(DataStore.getGasUrl(this))
        etSyncKey.setText(DataStore.getSyncKey(this))
        swAlarm.isChecked = DataStore.isAlarmOn(this)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(FlipTrackerService.BROADCAST_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(uiTicker)
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        DataStore.setCurrentSubject(this, etSubject.text.toString())
        if (!DataStore.loadRuntimeState(this).running) {
            DataStore.markAppClosedNow(this)
        }
    }
}
