package com.novapdf.reader.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.novapdf.reader.model.AnnotationCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AnnotationRepository(private val context: Context) {
    private val json = Json { prettyPrint = true }
    private val inMemoryAnnotations = mutableMapOf<String, MutableList<AnnotationCommand>>()
    private val lock = Any()
    private val encryptedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFERENCES_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun annotationsForDocument(documentId: String): List<AnnotationCommand> =
        synchronized(lock) { inMemoryAnnotations[documentId]?.toList().orEmpty() }

    fun addAnnotation(documentId: String, annotation: AnnotationCommand) {
        synchronized(lock) {
            val list = inMemoryAnnotations.getOrPut(documentId) { mutableListOf() }
            list += annotation
        }
    }

    fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>) {
        synchronized(lock) {
            inMemoryAnnotations[documentId] = annotations.toMutableList()
        }
    }

    fun clearInMemory(documentId: String) {
        synchronized(lock) {
            inMemoryAnnotations.remove(documentId)
        }
    }

    suspend fun saveAnnotations(documentId: String): File? = withContext(Dispatchers.IO) {
        val annotations = synchronized(lock) {
            inMemoryAnnotations[documentId]?.toList()
        } ?: return@withContext null
        val encodedId = Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val payload = json.encodeToString(annotations)
        encryptedPreferences.edit(commit = true) {
            putString(encodedId, payload)
        }
        preferenceFile(context)
    }

    fun trackedDocumentIds(): Set<String> = synchronized(lock) { inMemoryAnnotations.keys.toSet() }

    companion object {
        const val PREFERENCES_FILE_NAME = "annotation_repository"

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
