package com.rix.vpn.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which skin to draw. [SYSTEM] follows the phone's dark-mode setting. */
enum class ThemeMode(val label: String, val labelEn: String) {
    SYSTEM("خودکار", "Auto"),
    LIGHT("روشن", "Light"),
    DARK("تاریک", "Dark");

    /** The mode a header tap cycles to. */
    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}

/**
 * The language the screens are written in.
 *
 * Kept next to the theme because it is the same kind of choice: it changes what
 * the app looks like, not what the core is told to do.
 */
enum class LangPref(val label: String, val labelEn: String) {
    FA("فارسی", "فارسی"),
    EN("English", "English");

    val next: LangPref get() = if (this == FA) EN else FA
}

/**
 * UI-only preferences, kept apart from [com.rix.vpn.core.RixOptions].
 *
 * RixOptions is the core's argument model and is serialised into the VPN
 * service; a theme has no business travelling there. Two DataStore instances in
 * one process are fine because they use different file names.
 */
private val Context.uiStore by preferencesDataStore(name = "rix_ui")

class UiPrefs(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_mode")
    private val langKey = stringPreferencesKey("lang")

    val theme: Flow<ThemeMode> = context.uiStore.data.map { prefs -> parse(prefs[themeKey]) }

    val lang: Flow<LangPref> = context.uiStore.data.map { prefs ->
        LangPref.entries.firstOrNull { it.name == prefs[langKey] } ?: LangPref.FA
    }

    suspend fun readTheme(): ThemeMode = parse(context.uiStore.data.first()[themeKey])

    suspend fun saveTheme(mode: ThemeMode) {
        context.uiStore.edit { prefs -> prefs[themeKey] = mode.name }
    }

    suspend fun saveLang(lang: LangPref) {
        context.uiStore.edit { prefs -> prefs[langKey] = lang.name }
    }

    private fun parse(name: String?): ThemeMode =
        ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.SYSTEM
}