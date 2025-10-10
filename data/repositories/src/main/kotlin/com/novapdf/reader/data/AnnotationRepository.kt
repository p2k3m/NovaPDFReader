package com.novapdf.reader.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.model.AnnotationCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AnnotationRepository(
    context: Context,
    private val securePreferencesProvider: (Context) -> SharedPreferences = Companion::createEncryptedPreferences,
    private val fallbackPreferencesProvider: (Context) -> SharedPreferences = Companion::createUnencryptedPreferences,
    private val dispatchers: CoroutineDispatchers,
) {
    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = true }
    private val annotationsState = MutableStateFlow<Map<String, List<AnnotationCommand>>>(emptyMap())
    private val preferences: SharedPreferences by lazy {
        runCatching { securePreferencesProvider(appContext) }
            .onFailure { error ->
                Log.w(TAG, "Falling back to unencrypted annotation preferences", error)
            }
            .getOrElse { fallbackPreferencesProvider(appContext) }
    }

    fun annotationsForDocument(documentId: String): List<AnnotationCommand> =
        annotationsState.value[documentId].orEmpty()

    fun addAnnotation(documentId: String, annotation: AnnotationCommand) {
        annotationsState.update { current ->
            val updatedList = current[documentId].orEmpty() + annotation
            current + (documentId to updatedList)
        }
    }

    fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>) {
        annotationsState.update { current ->
            current + (documentId to annotations)
        }
    }

    fun clearInMemory(documentId: String) {
        annotationsState.update { current ->
            if (current.containsKey(documentId)) current - documentId else current
        }
    }

    suspend fun saveAnnotations(documentId: String): File? = withContext(dispatchers.io) {
        val annotations = annotationsState.value[documentId] ?: return@withContext null
        val encodedId = Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val payload = json.encodeToString(annotations)
        preferences.edit(commit = true) {
            putString(encodedId, payload)
        }
        preferenceFile(appContext)
    }

    fun trackedDocumentIds(): Set<String> = annotationsState.value.keys.toSet()

    companion object {
        const val PREFERENCES_FILE_NAME = "annotation_repository"
        private const val TAG = "AnnotationRepository"

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appContext,
                PREFERENCES_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun createUnencryptedPreferences(context: Context): SharedPreferences {
            return context.applicationContext.getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
        }

        fun preferenceFile(context: Context): File {
            val appContext = context.applicationContext
            val dataDir = appContext.applicationInfo.dataDir?.let(::File)
                ?: appContext.filesDir.parentFile
                ?: appContext.filesDir
            val sharedPrefsDir = File(dataDir, "shared_prefs").apply { mkdirs() }
            return File(sharedPrefsDir, "$PREFERENCES_FILE_NAME.xml")
        }
    }
}
