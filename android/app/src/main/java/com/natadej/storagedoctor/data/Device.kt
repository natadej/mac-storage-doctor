package com.natadej.storagedoctor.data

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager

data class DiskInfo(val total: Long, val free: Long) {
    val used get() = total - free
    val usedPct get() = if (total > 0) used * 100.0 / total else 0.0
}

data class AppSize(val pkg: String, val label: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long, val system: Boolean) {
    val total get() = appBytes + dataBytes
}

object Device {
    val storageRoot: String get() = Environment.getExternalStorageDirectory().absolutePath

    fun disk(ctx: Context): DiskInfo {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        return DiskInfo(ssm.getTotalBytes(StorageManager.UUID_DEFAULT), ssm.getFreeBytes(StorageManager.UUID_DEFAULT))
    }

    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Sizes of packages Android makes visible to us. Needs Usage access. */
    fun appSizes(ctx: Context): List<AppSize> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        val apps = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else pm.getInstalledApplications(0)
        return apps.mapNotNull { info ->
            runCatching {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, info.packageName, user)
                AppSize(info.packageName, pm.getApplicationLabel(info).toString(), s.appBytes, s.dataBytes, s.cacheBytes,
                    info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
            }.getOrNull()
        }.sortedByDescending { it.total }
    }
}
