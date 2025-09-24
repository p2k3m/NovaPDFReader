package com.novapdf.reader.data

import android.content.Context
import android.util.Base64
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
        val outputDir = File(context.filesDir, "annotations").apply { mkdirs() }
        val outputFile = File(outputDir, "$encodedId.json")
        outputFile.writeText(json.encodeToString(annotations))
        outputFile
    }

    fun trackedDocumentIds(): Set<String> = synchronized(lock) { inMemoryAnnotations.keys.toSet() }
}
