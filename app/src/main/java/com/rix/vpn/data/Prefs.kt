package com.rix.vpn.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rix.vpn.core.RixOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rix_prefs")

/**
 * Options are stored as one JSON blob rather than one key per field.
 *
 * The model has ~28 UI fields and grows every time the core adds a flag; a
 * single versioned blob means an unknown/renamed field degrades to its default
 * in [RixOptions.fromJson] instead of throwing during a migration.
 */
class Prefs(private val context: Context) {

    private val optionsKey = stringPreferencesKey("options_json")

    val options: Flow<RixOptions> = context.dataStore.data.map { prefs ->
        RixOptions.fromJson(prefs[optionsKey])
    }

    suspend fun save(options: RixOptions) {
        context.dataStore.edit { prefs ->
            prefs[optionsKey] = options.toJson()
        }
    }

    suspend fun read(): RixOptions {
        var result = RixOptions()
        context.dataStore.edit { prefs ->
            result = RixOptions.fromJson(prefs[optionsKey])
        }
        return result
    }
}