package com.wall.guard.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "wallguard_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
    }

    val isVpnEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.VPN_ENABLED] ?: false
    }

    suspend fun setVpnEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.VPN_ENABLED] = enabled
        }
    }
}