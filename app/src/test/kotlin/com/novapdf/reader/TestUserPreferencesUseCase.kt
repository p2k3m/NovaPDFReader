package com.novapdf.reader

import com.novapdf.reader.domain.usecase.UserPreferencesUseCase
import com.novapdf.reader.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TestUserPreferencesUseCase : UserPreferencesUseCase {
    private val mutablePreferences = MutableStateFlow(UserPreferences.EMPTY)
    val lastViewportRequests = mutableListOf<Pair<Int, Float>>()

    override val preferences: StateFlow<UserPreferences>
        get() = mutablePreferences

    override suspend fun setNightModeEnabled(enabled: Boolean) {
        mutablePreferences.value = mutablePreferences.value.copy(nightMode = enabled)
    }

    override suspend fun setLastOpenedDocument(uri: String?) {
        mutablePreferences.value = mutablePreferences.value.copy(lastDocumentUri = uri)
    }

    override suspend fun setLastDocumentViewport(pageIndex: Int, zoom: Float) {
        lastViewportRequests += pageIndex to zoom
        mutablePreferences.value = mutablePreferences.value.copy(
            lastDocumentPageIndex = pageIndex,
            lastDocumentZoom = zoom,
        )
    }

    override suspend fun clearLastDocumentViewport() {
        mutablePreferences.value = mutablePreferences.value.copy(
            lastDocumentPageIndex = null,
            lastDocumentZoom = null,
        )
    }
}
