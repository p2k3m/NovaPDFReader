package com.novapdf.reader.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.model.AnnotationCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.text.Charsets

class AnnotationRepository(
    context: Context,
    private val securePreferencesProvider: (Context) -> SharedPreferences = Companion::createEncryptedPreferences,
    private val fallbackPreferencesProvider: (Context) -> SharedPreferences = Companion::createUnencryptedPreferences,
    private val dispatchers: CoroutineDispatchers,
) {
    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = true }
    private val annotationsState = MutableStateFlow<Map<String, List<AnnotationCommand>>>(emptyMap())
    private val walScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val walMutex = Mutex()
    private val preferences: SharedPreferences by lazy {
        runCatching { securePreferencesProvider(appContext) }
            .onFailure { error ->
                NovaLog.w(TAG, "Falling back to unencrypted annotation preferences", error)
            }
            .getOrElse { fallbackPreferencesProvider(appContext) }
    }
    private val walDirectory: File by lazy {
        walDirectory(appContext).apply { mkdirs() }
    }

    init {
        annotationsState.value = loadPersistedAnnotations()
        recoverWriteAheadLogs()
    }

    fun annotationsForDocument(documentId: String): List<AnnotationCommand> =
        annotationsState.value[documentId].orEmpty()

    fun addAnnotation(documentId: String, annotation: AnnotationCommand) {
        annotationsState.update { current ->
            val updatedList = current[documentId].orEmpty() + annotation
            current + (documentId to updatedList)
        }
        persistDraft(documentId)
    }

    fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>) {
        annotationsState.update { current ->
            current + (documentId to annotations)
        }
        persistDraft(documentId)
    }

    fun clearInMemory(documentId: String) {
        annotationsState.update { current ->
            if (current.containsKey(documentId)) current - documentId else current
        }
    }

    suspend fun saveAnnotations(documentId: String): File? = withContext(dispatchers.io) {
        walMutex.withLock {
            val annotations = annotationsState.value[documentId] ?: run {
                clearWriteAheadLogLocked(documentId)
                return@withLock null
            }
            val encodedId = encodeDocumentId(documentId)
            val payload = json.encodeToString(annotations)
            preferences.edit(commit = true) {
                putString(encodedId, payload)
            }
            clearWriteAheadLogLocked(documentId)
            preferenceFile(appContext)
        }
    }

    fun trackedDocumentIds(): Set<String> = annotationsState.value.keys.toSet()

    companion object {
        const val PREFERENCES_FILE_NAME = "annotation_repository"
        private const val TAG = "AnnotationRepository"
        private const val WAL_DIR_NAME = "annotation_wal"
        private const val WAL_FILE_SUFFIX = ".wal"

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

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        fun walDirectory(context: Context): File {
            val appContext = context.applicationContext
            return File(appContext.filesDir, WAL_DIR_NAME)
        }

        @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
        fun walFile(context: Context, documentId: String): File {
            return File(walDirectory(context), "${encodeDocumentId(documentId)}$WAL_FILE_SUFFIX")
        }
    }

    private fun loadPersistedAnnotations(): Map<String, List<AnnotationCommand>> {
        val persisted = mutableMapOf<String, List<AnnotationCommand>>()
        preferences.all.forEach { (encodedId, value) ->
            val payload = value as? String ?: return@forEach
            val documentId = decodeDocumentId(encodedId)
            if (documentId == null) {
                NovaLog.w(TAG, "Unable to decode persisted annotation key")
                return@forEach
            }
            val annotations = runCatching { json.decodeFromString<List<AnnotationCommand>>(payload) }
                .onFailure { error ->
                    NovaLog.e(TAG, "Unable to decode persisted annotations", error)
                }
                .getOrNull() ?: return@forEach
            persisted[documentId] = annotations
        }
        return persisted
    }

    private fun recoverWriteAheadLogs() {
        val recovered = mutableMapOf<String, List<AnnotationCommand>>()
        walDirectory.listFiles { _, name -> name.endsWith(WAL_FILE_SUFFIX) }?.forEach { file ->
            val encodedId = file.name.removeSuffix(WAL_FILE_SUFFIX)
            val documentId = decodeDocumentId(encodedId)
            if (documentId == null) {
                NovaLog.w(TAG, "Unable to decode write-ahead log key")
                return@forEach
            }
            val annotations = runCatching { json.decodeFromString<List<AnnotationCommand>>(file.readText()) }
                .onFailure { error ->
                    NovaLog.e(TAG, "Unable to decode write-ahead log", error)
                }
                .getOrNull() ?: return@forEach
            recovered[documentId] = annotations
        }
        if (recovered.isNotEmpty()) {
            annotationsState.update { current -> current + recovered }
        }
    }

    private fun persistDraft(documentId: String) {
        val annotations = annotationsState.value[documentId].orEmpty()
        val payload = if (annotations.isEmpty()) null else json.encodeToString(annotations)
        walScope.launch {
            walMutex.withLock {
                if (payload == null) {
                    clearWriteAheadLogLocked(documentId)
                } else {
                    writeAheadLogLocked(documentId, payload)
                }
            }
        }
    }

    private fun writeAheadLogLocked(documentId: String, payload: String) {
        val file = walFile(documentId)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(payload)
        }.onFailure { error ->
            NovaLog.e(TAG, "Failed to persist annotation draft", error)
        }
    }

    private fun clearWriteAheadLogLocked(documentId: String) {
        val file = walFile(documentId)
        if (file.exists() && !file.delete()) {
            NovaLog.w(TAG, "Failed to delete write-ahead log")
        }
    }

    private fun walFile(documentId: String): File = File(walDirectory, "${encodeDocumentId(documentId)}$WAL_FILE_SUFFIX")
}

private fun encodeDocumentId(documentId: String): String =
    Base64.encodeToString(documentId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

private fun decodeDocumentId(encodedId: String): String? = runCatching {
    val decoded = Base64.decode(encodedId, Base64.URL_SAFE or Base64.NO_WRAP)
    String(decoded, Charsets.UTF_8)
}.getOrNull()
