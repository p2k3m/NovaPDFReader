package com.novapdf.reader.data.remote

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.io.buffered
import kotlin.io.copyTo

/** Copies the contents of [uri] into [destination] using this [StorageClient]. */
@Throws(IOException::class)
internal suspend fun StorageClient.copyTo(uri: Uri, destination: File) {
    openInputStream(uri).use { input ->
        FileOutputStream(destination).buffered().use { output ->
            input.copyTo(output)
            output.flush()
        }
    }
}
