package com.novapdf.reader.domain.usecase

import com.novapdf.reader.data.UserPreferencesRepository
import com.novapdf.reader.model.UserPreferences
import com.novapdf.reader.model.FallbackMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

interface UserPreferencesUseCase {
    val preferences: Flow<UserPreferences>

    suspend fun setNightModeEnabled(enabled: Boolean)

    suspend fun setLastOpenedDocument(uri: String?)

    suspend fun setLastDocumentViewport(pageIndex: Int, zoom: Float)

    suspend fun clearLastDocumentViewport()

    suspend fun setFallbackMode(mode: FallbackMode)
}

@Singleton
class DefaultUserPreferencesUseCase @Inject constructor(
    private val repository: UserPreferencesRepository,
) : UserPreferencesUseCase {
    override val preferences: Flow<UserPreferences>
        get() = repository.preferences

    override suspend fun setNightModeEnabled(enabled: Boolean) {
        repository.setNightModeEnabled(enabled)
    }

    override suspend fun setLastOpenedDocument(uri: String?) {
        repository.setLastOpenedDocument(uri)
    }

    override suspend fun setLastDocumentViewport(pageIndex: Int, zoom: Float) {
        repository.setLastDocumentViewport(pageIndex, zoom)
    }

    override suspend fun clearLastDocumentViewport() {
        repository.clearLastDocumentViewport()
    }

    override suspend fun setFallbackMode(mode: FallbackMode) {
        repository.setFallbackMode(mode)
    }
}
