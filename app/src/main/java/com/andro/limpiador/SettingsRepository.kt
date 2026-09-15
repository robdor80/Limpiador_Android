package com.andro.limpiador

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("cleaner_settings")

enum class CleanMode { REVIEW, AUTOMATIC }
enum class Frequency { DAILY, WEEKLY }

data class CleanerSettings(
    val treeUri: String? = null,
    val days: Int = 15,
    val mode: CleanMode = CleanMode.REVIEW,
    val automaticEnabled: Boolean = false,
    val frequency: Frequency = Frequency.DAILY,
    val exclusions: Set<String> = emptySet(),
    val lastAnalysis: Long? = null,
    val lastCleanup: Long? = null
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val URI = stringPreferencesKey("tree_uri")
        val DAYS = intPreferencesKey("days")
        val MODE = stringPreferencesKey("mode")
        val AUTO = booleanPreferencesKey("automatic_enabled")
        val FREQUENCY = stringPreferencesKey("frequency")
        val EXCLUSIONS = stringSetPreferencesKey("exclusions")
        val LAST_ANALYSIS = stringPreferencesKey("last_analysis")
        val LAST_CLEANUP = stringPreferencesKey("last_cleanup")
    }

    val settings: Flow<CleanerSettings> = context.dataStore.data.map { p ->
        CleanerSettings(
            treeUri = p[Keys.URI], days = (p[Keys.DAYS] ?: 15).coerceAtLeast(0),
            mode = runCatching { CleanMode.valueOf(p[Keys.MODE] ?: "REVIEW") }.getOrDefault(CleanMode.REVIEW),
            automaticEnabled = p[Keys.AUTO] ?: false,
            frequency = runCatching { Frequency.valueOf(p[Keys.FREQUENCY] ?: "DAILY") }.getOrDefault(Frequency.DAILY),
            exclusions = p[Keys.EXCLUSIONS] ?: emptySet(),
            lastAnalysis = p[Keys.LAST_ANALYSIS]?.toLongOrNull(),
            lastCleanup = p[Keys.LAST_CLEANUP]?.toLongOrNull()
        )
    }

    suspend fun setFolder(uri: String) = context.dataStore.edit { it[Keys.URI] = uri }
    suspend fun setDays(days: Int) = context.dataStore.edit { it[Keys.DAYS] = days.coerceAtLeast(0) }
    suspend fun setMode(mode: CleanMode) = context.dataStore.edit { it[Keys.MODE] = mode.name }
    suspend fun setAutomatic(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO] = enabled }
    suspend fun setFrequency(frequency: Frequency) = context.dataStore.edit { it[Keys.FREQUENCY] = frequency.name }
    suspend fun addExclusion(path: String) = context.dataStore.edit {
        it[Keys.EXCLUSIONS] = (it[Keys.EXCLUSIONS] ?: emptySet()) + path
    }
    suspend fun removeExclusion(path: String) = context.dataStore.edit {
        it[Keys.EXCLUSIONS] = (it[Keys.EXCLUSIONS] ?: emptySet()) - path
    }
    suspend fun markAnalysis() = context.dataStore.edit { it[Keys.LAST_ANALYSIS] = System.currentTimeMillis().toString() }
    suspend fun markCleanup() = context.dataStore.edit { it[Keys.LAST_CLEANUP] = System.currentTimeMillis().toString() }
}
