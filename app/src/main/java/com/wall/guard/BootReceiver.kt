package com.wall.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wall.guard.data.repository.SettingsRepository
import com.wall.guard.vpn.WallService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val enabled = settingsRepository.isVpnEnabled.first()
                if (enabled) {
                    WallService.enqueueStart(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
