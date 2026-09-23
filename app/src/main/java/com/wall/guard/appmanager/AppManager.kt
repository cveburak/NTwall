package com.wall.guard.appmanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val packageManager: PackageManager = context.packageManager
    private val selfPackageName: String = context.packageName

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: Flow<List<AppInfo>> = _apps.asStateFlow()

    suspend fun refresh() {
        withContext(Dispatchers.Default) {
            val appList = loadApps()
            _apps.value = appList
        }
    }

    private fun loadApps(): List<AppInfo> {
        val flags = PackageManager.GET_META_DATA
        val installedApps = try {
            packageManager.getInstalledApplications(flags)
        } catch (_: Exception) {
            emptyList()
        }

        return installedApps
            .asSequence()
            .filter { it.packageName != selfPackageName }
            .filter { it.packageName != "android" }
            .filter { it.uid != android.os.Process.INVALID_UID }
            .filter { it.hasInternetPermission() }
            .map { it.toAppInfo() }
            .sortedBy { it.appName.toString().lowercase() }
            .toList()
    }

    private fun ApplicationInfo.hasInternetPermission(): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                ?.requestedPermissions
                ?.contains("android.permission.INTERNET") == true
        } catch (_: Exception) {
            false
        }
    }

    private fun ApplicationInfo.toAppInfo(): AppInfo {
        val name = packageManager.getApplicationLabel(this)
        val icon = loadSafeIcon()
        return AppInfo(
            uid = uid,
            packageName = packageName,
            appName = name,
            icon = icon,
            isSystemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isEnabled = enabled,
            installTimeMillis = 0L
        )
    }

    private fun ApplicationInfo.loadSafeIcon(): Drawable? {
        return try {
            packageManager.getApplicationIcon(this)
        } catch (_: Exception) {
            null
        }
    }

    fun getAppName(uid: Int): String {
        val names = packageManager.getPackagesForUid(uid) ?: return "unknown:$uid"
        val pkg = names.firstOrNull() ?: return "unknown:$uid"
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    fun getAppInfo(uid: Int): AppInfo? {
        val packages = packageManager.getPackagesForUid(uid) ?: return null
        val pkg = packages.firstOrNull() ?: return null
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            ai.toAppInfo()
        } catch (_: Exception) {
            null
        }
    }
}
