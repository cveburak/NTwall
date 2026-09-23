package com.wall.guard.appmanager

import android.graphics.drawable.Drawable

data class AppInfo(
    val uid: Int,
    val packageName: String,
    val appName: CharSequence,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val installTimeMillis: Long
) {
    val isUninstalled: Boolean get() = uid == android.os.Process.INVALID_UID
}
