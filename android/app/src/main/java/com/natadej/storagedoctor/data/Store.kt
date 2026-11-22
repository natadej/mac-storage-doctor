package com.natadej.storagedoctor.data

import android.content.Context
import com.natadej.storagedoctor.core.ScanRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Settings and scan history, kept in the app's private storage. */
class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val historyFile = File(ctx.filesDir, "history.json")

    var disclosureAnswered: Boolean
        get() = prefs.getBoolean("disclosure_answered", false)
        set(v) = prefs.edit().putBoolean("disclosure_answered", v).apply()
    var target: Int
        get() = prefs.getInt("target", 50)
        set(v) = prefs.edit().putInt("target", v).apply()
    var monitor: Boolean
        get() = prefs.getBoolean("monitor", false)
        set(v) = prefs.edit().putBoolean("monitor", v).apply()
    var lastCheckUsed: Long
        get() = prefs.getLong("last_check_used", -1)
        set(v) = prefs.edit().putLong("last_check_used", v).apply()
    var lastCheckTime: Long
        get() = prefs.getLong("last_check_time", 0)
        set(v) = prefs.edit().putLong("last_check_time", v).apply()
    var lastAlert: Long
        get() = prefs.getLong("last_alert", 0)
        set(v) = prefs.edit().putLong("last_alert", v).apply()

    fun history(): List<ScanRecord> = runCatching {
        val arr = JSONArray(historyFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScanRecord(o.getLong("time"), o.getLong("used"), o.getJSONObject("folders").toLongMap(),
                o.getJSONObject("apps").toLongMap(), o.optLong("snapshotMin", com.natadej.storagedoctor.core.SNAPSHOT_MIN))
        }
    }.getOrDefault(emptyList())

    fun addRecord(r: ScanRecord, keep: Int = 20) {
        val all = (history() + r).takeLast(keep)
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("time", it.time).put("used", it.used).put("snapshotMin", it.snapshotMin)
                .put("folders", JSONObject(it.folders as Map<*, *>)).put("apps", JSONObject(it.apps as Map<*, *>)))
        }
        historyFile.writeText(arr.toString())
    }

    private fun JSONObject.toLongMap(): Map<String, Long> = keys().asSequence().associateWith { getLong(it) }
}
