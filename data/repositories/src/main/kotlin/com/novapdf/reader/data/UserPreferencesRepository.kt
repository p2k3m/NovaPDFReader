package com.novapdf.reader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.novapdf.reader.model.UserPreferences
import com.novapdf.reader.model.FallbackMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val DATA_STORE_NAME = "nova_reader_user_preferences"

private val Context.userPreferencesDataStore by preferencesDataStore(
    name = DATA_STORE_NAME
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    context: Context,
) {
    private val dataStore = context.applicationContext.userPreferencesDataStore

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { it.toDomainModel() }

    suspend fun setNightModeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[NIGHT_MODE_KEY] = enabled
        }
    }

    suspend fun setLastOpenedDocument(uri: String?) {
        dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(LAST_DOCUMENT_URI_KEY)
                preferences.remove(LAST_DOCUMENT_PAGE_INDEX_KEY)
                preferences.remove(LAST_DOCUMENT_ZOOM_KEY)
            } else {
                preferences[LAST_DOCUMENT_URI_KEY] = uri
            }
        }
    }

    suspend fun setLastDocumentViewport(pageIndex: Int, zoom: Float) {
        dataStore.edit { preferences ->
            preferences[LAST_DOCUMENT_PAGE_INDEX_KEY] = pageIndex
            preferences[LAST_DOCUMENT_ZOOM_KEY] = zoom
        }
    }

    suspend fun clearLastDocumentViewport() {
        dataStore.edit { preferences ->
            preferences.remove(LAST_DOCUMENT_PAGE_INDEX_KEY)
            preferences.remove(LAST_DOCUMENT_ZOOM_KEY)
        }
    }

    suspend fun setFallbackMode(mode: FallbackMode) {
        dataStore.edit { preferences ->
            preferences[FALLBACK_MODE_KEY] = mode.name
        }
    }

    private fun Preferences.toDomainModel(): UserPreferences {
        return UserPreferences(
            nightMode = this[NIGHT_MODE_KEY],
            lastDocumentUri = this[LAST_DOCUMENT_URI_KEY],
            lastDocumentPageIndex = this[LAST_DOCUMENT_PAGE_INDEX_KEY],
            lastDocumentZoom = this[LAST_DOCUMENT_ZOOM_KEY],
            fallbackMode = this[FALLBACK_MODE_KEY]?.let { stored ->
                runCatching { FallbackMode.valueOf(stored) }
                    .getOrDefault(FallbackMode.NONE)
            } ?: FallbackMode.NONE,
        )
    }

    private companion object Keys {
        val NIGHT_MODE_KEY = booleanPreferencesKey("night_mode_enabled")
        val LAST_DOCUMENT_URI_KEY = stringPreferencesKey("last_document_uri")
        val LAST_DOCUMENT_PAGE_INDEX_KEY = intPreferencesKey("last_document_page_index")
        val LAST_DOCUMENT_ZOOM_KEY = floatPreferencesKey("last_document_zoom")
        val FALLBACK_MODE_KEY = stringPreferencesKey("fallback_mode")
    }
}
