package com.pinotrouge.messaging.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pinotrouge.messaging.ui.theme.PinotThemeKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pinot_settings",
)

/**
 * Onboarding + Settings toggles.
 * Defaults: dark=false (light on first launch), held=true, contacts=true,
 * otp=true. A saved dark_theme key still wins once the user toggles.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
            darkTheme = prefs[Keys.DARK_THEME] ?: false,
            // Persist the key string, never an ordinal — reordering must not break prefs.
            accentTheme = PinotThemeKey.fromStorageKey(prefs[Keys.ACCENT]),
            holdByDefault = prefs[Keys.HELD] ?: true,
            neverFilterContacts = prefs[Keys.CONTACTS] ?: true,
            preserveOtps = prefs[Keys.OTP] ?: true,
            autoDownloadPictures = prefs[Keys.AUTO_DOWNLOAD_PICTURES] ?: true,
            downloadWhileRoaming = prefs[Keys.DOWNLOAD_WHILE_ROAMING] ?: false,
        )
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = value }
    }

    suspend fun setDarkTheme(value: Boolean) {
        dataStore.edit { it[Keys.DARK_THEME] = value }
    }

    suspend fun setAccentTheme(value: PinotThemeKey) {
        dataStore.edit { it[Keys.ACCENT] = value.storageKey }
    }

    suspend fun setHoldByDefault(value: Boolean) {
        dataStore.edit { it[Keys.HELD] = value }
    }

    suspend fun setNeverFilterContacts(value: Boolean) {
        dataStore.edit { it[Keys.CONTACTS] = value }
    }

    suspend fun setPreserveOtps(value: Boolean) {
        dataStore.edit { it[Keys.OTP] = value }
    }

    suspend fun setAutoDownloadPictures(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_DOWNLOAD_PICTURES] = value }
    }

    suspend fun setDownloadWhileRoaming(value: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_WHILE_ROAMING] = value }
    }

    private object Keys {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACCENT = stringPreferencesKey("accent")
        val HELD = booleanPreferencesKey("held")
        val CONTACTS = booleanPreferencesKey("contacts")
        val OTP = booleanPreferencesKey("otp")
        val AUTO_DOWNLOAD_PICTURES = booleanPreferencesKey("auto_download_pictures")
        val DOWNLOAD_WHILE_ROAMING = booleanPreferencesKey("download_while_roaming")
        // Orphan on purpose. Users who toggled the dead "Send read receipts"
        // row still have a `receipts` key in DataStore. Dropping this constant
        // would not delete that value; SMS has no read-receipt mechanism, so
        // the setting is gone. See Tasks/fix-settings-row-honesty.
        @Suppress("unused")
        val RECEIPTS = booleanPreferencesKey("receipts")
    }
}

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val darkTheme: Boolean = false,
    /** Version 4 accent theme key — default pinot. */
    val accentTheme: PinotThemeKey = PinotThemeKey.Pinot,
    /** "Hold messages that match a filter" — prototype `held`. */
    val holdByDefault: Boolean = true,
    /** "Never filter my contacts" — prototype `contacts`. */
    val neverFilterContacts: Boolean = true,
    /** "Always keep verification codes" — prototype `otp`. */
    val preserveOtps: Boolean = true,
    /** Download pictures automatically — default on (Eric, 2026-08-21). */
    val autoDownloadPictures: Boolean = true,
    /** Download while roaming — default off. */
    val downloadWhileRoaming: Boolean = false,
)
