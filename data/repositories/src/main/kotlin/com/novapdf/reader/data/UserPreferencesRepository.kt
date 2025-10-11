package com.novapdf.reader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novapdf.reader.model.UserPreferences
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
            } else {
                preferences[LAST_DOCUMENT_URI_KEY] = uri
            }
        }
    }

    private fun Preferences.toDomainModel(): UserPreferences {
        return UserPreferences(
            nightMode = this[NIGHT_MODE_KEY],
            lastDocumentUri = this[LAST_DOCUMENT_URI_KEY],
        )
    }

    private companion object Keys {
        val NIGHT_MODE_KEY = booleanPreferencesKey("night_mode_enabled")
        val LAST_DOCUMENT_URI_KEY = stringPreferencesKey("last_document_uri")
    }
}
