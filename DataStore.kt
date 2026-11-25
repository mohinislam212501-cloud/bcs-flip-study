package com.bcs.flipstudy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DayRecord(
    val date: String,
    var subject: String = "",
    var c45: Int = 0,
    var c25: Int = 0,
    var studySec: Long = 0L,
    var wasteSec: Long = 0L,
    var wasteReason: String = "",
    var sleepOtherSec: Long = 0L,
    var wordTarget: Boolean = false,
    var synced: Boolean = false,
    // ধাপ ২.৩: নতুন ফিল্ড (পুরোনো ডেটায় না থাকলে ডিফল্ট নেবে)
    var subjects: MutableMap<String, Long> = mutableMapOf(),
    var wasteReasons: MutableMap<String, Long> = mutableMapOf(),
    var mode: String = "flip",
    var estimated: Boolean = false
) {
    fun totalStudyMinutes(): Int {
        val circleMin = (c45 * 45) + (c25 * 25)
        val rawMin = (studySec / 60L).toInt()
        return maxOf(circleMin, rawMin)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("subject", subject)
        put("c45", c45)
        put("c25", c25)
        put("studySec", studySec)
        put("wasteSec", wasteSec)
        put("wasteReason", wasteReason)
        put("sleepOtherSec", sleepOtherSec)
        put("wordTarget", wordTarget)
        put("synced", synced)
        put("mode", mode)
        put("estimated", estimated)
        val subObj = JSONObject()
        for ((k, v) in subjects) subObj.put(k, v)
        put("subjects", subObj)
        val wrObj = JSONObject()
        for ((k, v) in wasteReasons) wrObj.put(k, v)
        put("wasteReasons", wrObj)
    }

    companion object {
        fun fromJson(o: JSONObject): DayRecord {
            val subMap = mutableMapOf<String, Long>()
            o.optJSONObject("subjects")?.let { so ->
                val it = so.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    subMap[k] = so.optLong(k, 0L)
                }
            }
            val wrMap = mutableMapOf<String, Long>()
            o.optJSONObject("wasteReasons")?.let { wo ->
                val it = wo.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    wrMap[k] = wo.optLong(k, 0L)
                }
            }
            return DayRecord(
                date = o.optString("date", DataStore.todayIso()),
                subject = o.optString("subject", ""),
                c45 = o.optInt("c45", 0),
                c25 = o.optInt("c25", 0),
                studySec = o.optLong("studySec", 0L),
                wasteSec = o.optLong("wasteSec", 0L),
                wasteReason = o.optString("wasteReason", ""),
                sleepOtherSec = o.optLong("sleepOtherSec", 0L),
                wordTarget = o.optBoolean("wordTarget", false),
                synced = o.optBoolean("synced", false),
                subjects = subMap,
                wasteReasons = wrMap,
                mode = o.optString("mode", "flip"),
                estimated = o.optBoolean("estimated", false)
            )
        }
    }
}

data class ChartBar(val label: String, val studyMin: Int, val wasteMin: Int, val sleepMin: Int)

data class ReportData(
    val period: String,
    val isYearly: Boolean,
    val totalStudyMin: Int,
    val totalWasteMin: Int,
    val totalSleepMin: Int,
    val avgDailyStudyMin: Int,
    val bestDay: String,
    val bestDayMin: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val productivityScore: Int,
    val subjects: Map<String, Long>,
    val wasteReasons: Map<String, Long>,
    val bars: List<ChartBar>,
    val isOffline: Boolean
)

data class RuntimeTrackerState(
    val running: Boolean,
    val mode: String, // IDLE, STUDY, BREAK, WASTE
    val stateStartMs: Long,
    val remainingSec: Int,
    val faceDown: Boolean
)

object DataStore {
    private const val PREF = "bcs_flip_study_prefs"
    private const val KEY_DAYS = "days_map_json"
    private const val KEY_GAS_URL = "gas_webapp_url"
    private const val KEY_SYNC_KEY = "gas_sync_secret_key"
    private const val KEY_PRESET_MIN = "preset_min"
    private const val KEY_ONLINE_STUDY = "online_study"
    private const val KEY_ALARM_ON = "alarm_on"
    private const val KEY_LAST_CLOSED = "last_closed_ms"
    private const val KEY_LAST_SYNC_TEXT = "last_sync_text"
    private const val KEY_SUBJECT = "current_subject"

    // ধাপ ১.৭: প্রসেস মারা গেলেও অবস্থা ধরে রাখার জন্য
    private const val KEY_RT_RUNNING = "rt_running"
    private const val KEY_RT_MODE = "rt_mode"
    private const val KEY_RT_START_MS = "rt_state_start_ms"
    private const val KEY_RT_REM_SEC = "rt_remaining_sec"
    private const val KEY_RT_FACE_DOWN = "rt_face_down"

    fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun currentMonthIso(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
    fun currentYearIso(): String = SimpleDateFormat("yyyy", Locale.US).format(Date())

    fun toBnDigits(input: Any): String {
        val bn = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val s = input.toString()
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in '0'..'9') sb.append(bn[ch - '0']) else sb.append(ch)
        }
        return sb.toString()
    }

    fun formatHoursMinBn(totalSec: Long): String {
        val mins = (totalSec / 60L).coerceAtLeast(0L)
        val h = mins / 60L
        val m = mins % 60L
        return if (h > 0) "${toBnDigits(h)}ঘ ${toBnDigits(m)}মি" else "${toBnDigits(m)}মি"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getGasUrl(ctx: Context): String = prefs(ctx).getString(KEY_GAS_URL, "") ?: ""
    fun setGasUrl(ctx: Context, url: String) = prefs(ctx).edit().putString(KEY_GAS_URL, url.trim()).apply()

    fun getSyncKey(ctx: Context): String = prefs(ctx).getString(KEY_SYNC_KEY, "") ?: ""
    fun setSyncKey(ctx: Context, k: String) = prefs(ctx).edit().putString(KEY_SYNC_KEY, k.trim()).apply()

    fun getPresetMin(ctx: Context): Int = prefs(ctx).getInt(KEY_PRESET_MIN, 45)
    fun setPresetMin(ctx: Context, min: Int) = prefs(ctx).edit().putInt(KEY_PRESET_MIN, min).apply()

    fun isOnlineStudy(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONLINE_STUDY, false)
    fun setOnlineStudy(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ONLINE_STUDY, on).apply()

    fun isAlarmOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ALARM_ON, true)
    fun setAlarmOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_ALARM_ON, on).apply()

    fun getCurrentSubject(ctx: Context): String = prefs(ctx).getString(KEY_SUBJECT, "BCS প্রস্তুতি") ?: "BCS প্রস্তুতি"
    fun setCurrentSubject(ctx: Context, sub: String) = prefs(ctx).edit().putString(KEY_SUBJECT, sub.trim().ifEmpty { "BCS প্রস্তুতি" }).apply()

    fun getLastSyncText(ctx: Context): String = prefs(ctx).getString(KEY_LAST_SYNC_TEXT, "এখনো সিঙ্ক হয়নি") ?: "এখনো সিঙ্ক হয়নি"
    fun setLastSyncText(ctx: Context, txt: String) = prefs(ctx).edit().putString(KEY_LAST_SYNC_TEXT, txt).apply()

    fun saveRuntimeState(ctx: Context, rt: RuntimeTrackerState) {
        prefs(ctx).edit()
            .putBoolean(KEY_RT_RUNNING, rt.running)
            .putString(KEY_RT_MODE, rt.mode)
            .putLong(KEY_RT_START_MS, rt.stateStartMs)
            .putInt(KEY_RT_REM_SEC, rt.remainingSec)
            .putBoolean(KEY_RT_FACE_DOWN, rt.faceDown)
            .apply()
    }

    fun loadRuntimeState(ctx: Context): RuntimeTrackerState {
        val p = prefs(ctx)
        return RuntimeTrackerState(
            running = p.getBoolean(KEY_RT_RUNNING, false),
            mode = p.getString(KEY_RT_MODE, "IDLE") ?: "IDLE",
            stateStartMs = p.getLong(KEY_RT_START_MS, 0L),
            remainingSec = p.getInt(KEY_RT_REM_SEC, getPresetMin(ctx) * 60),
            faceDown = p.getBoolean(KEY_RT_FACE_DOWN, false)
        )
    }

    fun markAppClosedNow(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_LAST_CLOSED, System.currentTimeMillis()).apply()
    }

    fun absorbClosedTimeAsSleepOther(ctx: Context): Long {
        val lastClosed = prefs(ctx).getLong(KEY_LAST_CLOSED, 0L)
        if (lastClosed <= 0L) return 0L
        val now = System.currentTimeMillis()
        val gapSec = (now - lastClosed) / 1000L
        prefs(ctx).edit().putLong(KEY_LAST_CLOSED, 0L).apply()
        if (gapSec in 60L..86400L) {
            updateToday(ctx) { rec ->
                rec.sleepOtherSec += gapSec
                rec.synced = false
            }
            return gapSec
        }
        return 0L
    }

    @Synchronized
    fun loadAllDays(ctx: Context): MutableMap<String, DayRecord> {
        val raw = prefs(ctx).getString(KEY_DAYS, "{}") ?: "{}"
        val map = mutableMapOf<String, DayRecord>()
        try {
            val obj = JSONObject(raw)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val child = obj.optJSONObject(k)
                if (child != null) map[k] = DayRecord.fromJson(child)
            }
        } catch (_: Exception) {}
        return map
    }

    @Synchronized
    fun saveAllDays(ctx: Context, map: Map<String, DayRecord>) {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v.toJson())
        prefs(ctx).edit().putString(KEY_DAYS, obj.toString()).apply()
    }

    @Synchronized
    fun getToday(ctx: Context): DayRecord {
        val today = todayIso()
        val map = loadAllDays(ctx)
        return map[today] ?: DayRecord(date = today, subject = getCurrentSubject(ctx))
    }

    @Synchronized
    fun updateToday(ctx: Context, block: (DayRecord) -> Unit): DayRecord {
        val today = todayIso()
        val map = loadAllDays(ctx)
        val rec = map[today] ?: DayRecord(date = today, subject = getCurrentSubject(ctx))
        block(rec)
        map[today] = rec
        saveAllDays(ctx, map)
        return rec
    }

    fun getLast7Days(ctx: Context): List<DayRecord> {
        val map = loadAllDays(ctx)
        val cal = Calendar.getInstance()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val list = mutableListOf<DayRecord>()
        for (i in 6 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -i)
            val d = fmt.format(c.time)
            list.add(map[d] ?: DayRecord(date = d))
        }
        return list
    }

    fun hasUnsyncedDays(ctx: Context): Boolean = loadAllDays(ctx).values.any { !it.synced }

    // ধাপ ২.১ ও ২.৪: শুধু synced=false দিন পাঠানো, {"ok":true} পেলেই synced=true, লোকাল ডেটা কখনো মুছবে না
    fun syncToGoogleDrive(ctx: Context): Pair<Boolean, String> {
        val urlStr = getGasUrl(ctx)
        if (urlStr.isBlank() || !urlStr.startsWith("http")) {
            return Pair(false, "সেটিংসে আগে Apps Script লিংক বসাও")
        }
        val map = loadAllDays(ctx)
        val unsynced = map.values.filter { !it.synced }.sortedBy { it.date }
        if (unsynced.isEmpty()) {
            return Pair(true, "নতুন কোনো অসিঙ্কড ডেটা নেই (সব আপডেট আছে)")
        }
        return try {
            val arr = JSONArray()
            for (rec in unsynced) arr.put(rec.toJson())
            val payload = JSONObject().apply {
                put("appKey", "study_tracker")
                put("key", getSyncKey(ctx))
                put("updatedAt", System.currentTimeMillis())
                put("days", arr)
            }.toString()

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = if (stream != null) BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() } else ""
            conn.disconnect()

            val okJson = try { JSONObject(respText).optBoolean("ok", false) } catch (_: Exception) { false }
            if (code in 200..299 && okJson) {
                for (rec in unsynced) {
                    map[rec.date]?.synced = true
                }
                saveAllDays(ctx, map)
                val stamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date())
                setLastSyncText(ctx, "সিঙ্কড: $stamp")
                Pair(true, "${toBnDigits(unsynced.size)}টি দিনের ডেটা ড্রাইভে সিঙ্ক হয়েছে!")
            } else {
                val errMsg = try { JSONObject(respText).optString("error", "") } catch (_: Exception) { "" }
                Pair(false, if (errMsg.isNotEmpty()) "সার্ভার ত্রুটি: $errMsg" else "সিঙ্ক ব্যর্থ (কোড: $code)")
            }
        } catch (e: Exception) {
            Pair(false, "নেটওয়ার্ক ত্রুটি: ${e.localizedMessage ?: "অজানা"}")
        }
    }

    // ধাপ ৪: মাসিক/বার্ষিক রিপোর্ট (ক্যাশ + ১-বার GET + অফলাইন লোকাল ফলব্যাক)
    fun fetchOrComputeReport(ctx: Context, period: String, isYearly: Boolean): ReportData {
        val cacheKey = "report_cache_${if (isYearly) "y" else "m"}_$period"
        val cacheTsKey = "${cacheKey}_ts"
        val p = prefs(ctx)
        val isCurrent = if (isYearly) period == currentYearIso() else period == currentMonthIso()
        val cachedRaw = p.getString(cacheKey, null)
        val cachedTs = p.getLong(cacheTsKey, 0L)
        val now = System.currentTimeMillis()

        // আগের মাস/বছর একবার ক্যাশ হলে আর আনতে হবে না; চলতি মাস ১ ঘণ্টার মধ্যে ক্যাশ ব্যবহার
        if (cachedRaw != null && (!isCurrent || (now - cachedTs) < 3600_000L)) {
            parseServerReport(cachedRaw, period, isYearly, false)?.let { return it }
        }

        val urlStr = getGasUrl(ctx)
        if (urlStr.startsWith("http")) {
            try {
                val paramName = if (isYearly) "year" else "month"
                val keyEnc = URLEncoder.encode(getSyncKey(ctx), "UTF-8")
                val sep = if (urlStr.contains("?")) "&" else "?"
                val fullUrl = "$urlStr${sep}action=report&$paramName=${URLEncoder.encode(period, "UTF-8")}&key=$keyEnc"
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10000
                    readTimeout = 12000
                }
                val code = conn.responseCode
                val txt = if (code in 200..299) BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() } else ""
                conn.disconnect()
                val parsed = parseServerReport(txt, period, isYearly, false)
                if (parsed != null) {
                    p.edit().putString(cacheKey, txt).putLong(cacheTsKey, now).apply()
                    return parsed
                }
            } catch (_: Exception) {}
        }

        // অফলাইন বা সার্ভার না পেলে ক্যাশ অথবা লোকাল ডেটা থেকে হিসাব
        if (cachedRaw != null) {
            parseServerReport(cachedRaw, period, isYearly, true)?.let { return it }
        }
        return computeLocalReport(ctx, period, isYearly)
    }

    private fun parseServerReport(raw: String, period: String, isYearly: Boolean, offline: Boolean): ReportData? {
        return try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok", false)) return null
            val subMap = mutableMapOf<String, Long>()
            o.optJSONObject("subjects")?.let { so ->
                val it = so.keys()
                while (it.hasNext()) { val k = it.next(); subMap[k] = so.optLong(k, 0L) }
            }
            val wrMap = mutableMapOf<String, Long>()
            o.optJSONObject("wasteReasons")?.let { wo ->
                val it = wo.keys()
                while (it.hasNext()) { val k = it.next(); wrMap[k] = wo.optLong(k, 0L) }
            }
            val bars = mutableListOf<ChartBar>()
            val arr = o.optJSONArray("bars") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                bars.add(ChartBar(b.optString("label"), b.optInt("studyMin"), b.optInt("wasteMin"), b.optInt("sleepMin")))
            }
            ReportData(
                period = period,
                isYearly = isYearly,
                totalStudyMin = o.optInt("totalStudyMin"),
                totalWasteMin = o.optInt("totalWasteMin"),
                totalSleepMin = o.optInt("totalSleepMin"),
                avgDailyStudyMin = o.optInt("avgDailyStudyMin"),
                bestDay = o.optString("bestDay", "—"),
                bestDayMin = o.optInt("bestDayMin"),
                currentStreak = o.optInt("currentStreak"),
                longestStreak = o.optInt("longestStreak"),
                productivityScore = o.optInt("productivityScore"),
                subjects = subMap,
                wasteReasons = wrMap,
                bars = bars,
                isOffline = offline
            )
        } catch (_: Exception) { null }
    }

    private fun computeLocalReport(ctx: Context, period: String, isYearly: Boolean): ReportData {
        val all = loadAllDays(ctx)
        val filtered = all.values.filter { it.date.startsWith(period) }.sortedBy { it.date }
        var totStudy = 0
        var totWaste = 0
        var totSleep = 0
        var bestDay = "—"
        var bestMin = 0
        val subMap = mutableMapOf<String, Long>()
        val wrMap = mutableMapOf<String, Long>()
        var curStreak = 0
        var maxStreak = 0

        for (d in filtered) {
            val sm = d.totalStudyMinutes()
            val wm = (d.wasteSec / 60L).toInt()
            val slm = (d.sleepOtherSec / 60L).toInt()
            totStudy += sm
            totWaste += wm
            totSleep += slm
            if (sm > bestMin) { bestMin = sm; bestDay = d.date.takeLast(2) }
            if (sm > 0) { curStreak++; if (curStreak > maxStreak) maxStreak = curStreak } else curStreak = 0
            for ((k, v) in d.subjects) subMap[k] = (subMap[k] ?: 0L) + v
            if (d.subjects.isEmpty() && d.subject.isNotEmpty() && sm > 0) {
                subMap[d.subject] = (subMap[d.subject] ?: 0L) + (sm * 60L)
            }
            for ((k, v) in d.wasteReasons) wrMap[k] = (wrMap[k] ?: 0L) + v
            if (d.wasteReasons.isEmpty() && d.wasteReason.isNotEmpty() && wm > 0) {
                wrMap[d.wasteReason] = (wrMap[d.wasteReason] ?: 0L) + (wm * 60L)
            }
        }

        val activeDays = filtered.count { it.totalStudyMinutes() > 0 }.coerceAtLeast(1)
        val avgDaily = totStudy / activeDays
        val denom = totStudy + totWaste
        val score = if (denom > 0) ((totStudy * 100) / denom) else 0

        val bars = mutableListOf<ChartBar>()
        if (isYearly) {
            val bnMonths = arrayOf("জানু", "ফেব্রু", "মার্চ", "এপ্রি", "মে", "জুন", "জুলা", "আগ", "সেপ্টে", "অক্টো", "নভে", "ডিসে")
            for (m in 1..12) {
                val ym = String.format(Locale.US, "%s-%02d", period, m)
                val mDays = filtered.filter { it.date.startsWith(ym) }
                bars.add(ChartBar(
                    bnMonths[m - 1],
                    mDays.sumOf { it.totalStudyMinutes() },
                    mDays.sumOf { (it.wasteSec / 60L).toInt() },
                    mDays.sumOf { (it.sleepOtherSec / 60L).toInt() }
                ))
            }
        } else {
            val parts = period.split("-")
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, parts.getOrNull(0)?.toIntOrNull() ?: 2026)
                set(Calendar.MONTH, (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val maxD = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (day in 1..maxD) {
                val iso = String.format(Locale.US, "%s-%02d", period, day)
                val rec = all[iso]
                bars.add(ChartBar(
                    toBnDigits(day),
                    rec?.totalStudyMinutes() ?: 0,
                    ((rec?.wasteSec ?: 0L) / 60L).toInt(),
                    ((rec?.sleepOtherSec ?: 0L) / 60L).toInt()
                ))
            }
        }

        return ReportData(period, isYearly, totStudy, totWaste, totSleep, avgDaily, bestDay, bestMin, curStreak, maxStreak, score, subMap, wrMap, bars, true)
    }
}
