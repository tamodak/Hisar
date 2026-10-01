package org.tamodak.hisar.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.tamodak.hisar.data.model.SimpleFont
import org.tamodak.hisar.data.model.UserSettings
import org.tamodak.hisar.search.SearchEngine
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** User preferences in Preferences DataStore. */
@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<UserSettings> = store.data
        // An unreadable file falls back to the defaults rather than taking the app down.
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            UserSettings(
                searchEngine = SearchEngine.fromId(p[ENGINE]),
                textZoom = p[TEXT_ZOOM] ?: 100,
                searchSuggestions = p[SUGGESTIONS] ?: true,
                simpleMode = p[SIMPLE_MODE] ?: false,
                simpleFont = p[SIMPLE_FONT]?.let { name -> SimpleFont.entries.firstOrNull { it.name == name } } ?: SimpleFont.SANS,
                hiddenTiles = p[HIDDEN_TILES].orEmpty(),
            )
        }

    suspend fun setSearchEngine(engine: SearchEngine) = store.edit { it[ENGINE] = engine.id }
    suspend fun setTextZoom(percent: Int) = store.edit { it[TEXT_ZOOM] = percent }
    suspend fun setSearchSuggestions(enabled: Boolean) = store.edit { it[SUGGESTIONS] = enabled }
    suspend fun setSimpleMode(enabled: Boolean) = store.edit { it[SIMPLE_MODE] = enabled }
    suspend fun setSimpleFont(font: SimpleFont) = store.edit { it[SIMPLE_FONT] = font.name }
    suspend fun hideTile(url: String) = store.edit { it[HIDDEN_TILES] = it[HIDDEN_TILES].orEmpty() + url }

    private companion object {
        val ENGINE = stringPreferencesKey("search_engine")
        val TEXT_ZOOM = intPreferencesKey("text_zoom")
        val SUGGESTIONS = booleanPreferencesKey("search_suggestions")
        val SIMPLE_MODE = booleanPreferencesKey("simple_mode")
        val SIMPLE_FONT = stringPreferencesKey("simple_font")
        val HIDDEN_TILES = stringSetPreferencesKey("hidden_tiles")
    }
}
