package com.streamprobe.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val LEGACY_PREFS = "debug_prefs"

private val Context.debugSettingsDataStore by preferencesDataStore(
    name = "debug_settings",
    produceMigrations = { ctx -> listOf(SharedPreferencesMigration(ctx, LEGACY_PREFS)) }
)

class DebugSettingsRepository(context: Context) {
    private val ds = context.applicationContext.debugSettingsDataStore
    private val key = booleanPreferencesKey("inject_load_errors")

    val injectErrorsFlow: Flow<Boolean> = ds.data.map { it[key] ?: false }

    suspend fun setInjectErrors(value: Boolean) {
        ds.edit { it[key] = value }
    }
}
