package com.novapdf.reader.data.remote

import android.graphics.Bitmap
import android.graphics.Color
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.util.closeQuietly
import java.io.File
import okio.buffer
import okio.sink

internal class PdfDownloadDecoder(
    private val fetchResult: SourceFetchResult,
    private val payload: Payload,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val destination = payload.destination
        val imageSource = fetchResult.source
        try {
            imageSource.source().use { source ->
                destination.sink().buffer().use { sink ->
                    sink.writeAll(source)
                }
            }
            payload.onDownloaded(destination)
        } catch (throwable: Throwable) {
            payload.onDownloadFailed(throwable)
            throw throwable
        } finally {
            imageSource.closeQuietly()
        }
        val placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        return DecodeResult(
            image = placeholder.asImage(),
            isSampled = true,
        )
    }

    data class Payload(
        val destination: File,
        val onDownloaded: (File) -> Unit,
        val onDownloadFailed: (Throwable) -> Unit,
    )

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val payload = options.getExtra(PAYLOAD_KEY) ?: return null
            val mimeType = result.mimeType?.lowercase()
            val looksLikePdf = mimeType?.contains("pdf") == true ||
                payload.destination.name.lowercase().endsWith(".pdf")
            return if (looksLikePdf) {
                PdfDownloadDecoder(result, payload)
            } else {
                null
            }
        }
    }

    companion object {
        val PAYLOAD_KEY = coil3.Extras.Key<Payload?>(null)
    }
}
