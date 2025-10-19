package com.novapdf.reader.download

import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.model.RemoteDocumentStage
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class S3RemotePdfDownloaderTest {

    @Test
    fun `download retries when attempt times out`() = runTest {
        val downloadManager = mock<PdfDownloadManager>()
        whenever(downloadManager.download(any(), any<Boolean>())).thenAnswer {
            flow {
                emit(RemoteDocumentFetchEvent.Progress(RemoteDocumentStage.CONNECTING))
                delay(Long.MAX_VALUE)
            }
        }
        val downloader = S3RemotePdfDownloader(downloadManager)

        val events = mutableListOf<RemoteDocumentFetchEvent>()
        val job = launch { downloader.download("https://example.com/slow.pdf").collect { events += it } }

        // Allow enough virtual time for all attempts and backoff delays to elapse.
        repeat(4) {
            advanceTimeBy(S3RemotePdfDownloader.DOWNLOAD_ATTEMPT_TIMEOUT_MS)
            advanceUntilIdle()
            if (it < 3) {
                advanceTimeBy(5_000L)
                advanceUntilIdle()
            }
        }

        job.join()

        assertTrue(events.last() is RemoteDocumentFetchEvent.Failure)
        val failure = events.last() as RemoteDocumentFetchEvent.Failure
        val error = failure.error as RemotePdfException
        assertEquals(RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED, error.reason)
        verify(downloadManager, times(4)).download("https://example.com/slow.pdf", false)
    }

    @Test
    fun `download forwards success without retries`() = runTest {
        val downloadManager = mock<PdfDownloadManager>()
        val uri = mock<Uri>()
        whenever(downloadManager.download(any(), any<Boolean>())).thenReturn(
            flow {
                emit(RemoteDocumentFetchEvent.Progress(RemoteDocumentStage.CONNECTING))
                emit(RemoteDocumentFetchEvent.Success(uri))
            }
        )
        val downloader = S3RemotePdfDownloader(downloadManager)

        val events = mutableListOf<RemoteDocumentFetchEvent>()
        downloader.download("https://example.com/success.pdf").collect { events += it }

        assertEquals(2, events.size)
        assertTrue(events.first() is RemoteDocumentFetchEvent.Progress)
        assertEquals(uri, (events.last() as RemoteDocumentFetchEvent.Success).uri)
        verify(downloadManager).download("https://example.com/success.pdf", false)
    }

    @Test
    fun `download propagates cancellation failures`() = runTest {
        val downloadManager = mock<PdfDownloadManager>()
        whenever(downloadManager.download(any(), any<Boolean>())).thenReturn(
            flow {
                emit(RemoteDocumentFetchEvent.Failure(CancellationException("cancelled")))
            }
        )
        val downloader = S3RemotePdfDownloader(downloadManager)

        val thrown = runCatching {
            downloader.download("https://example.com/cancel.pdf").collect {}
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        verify(downloadManager).download("https://example.com/cancel.pdf", false)
    }

    @Test
    fun `download stops on non retryable failure`() = runTest {
        val downloadManager = mock<PdfDownloadManager>()
        val nonRetryable = RemotePdfException(RemotePdfException.Reason.UNSAFE)
        whenever(downloadManager.download(any(), any<Boolean>())).thenReturn(
            flowOf(RemoteDocumentFetchEvent.Failure(nonRetryable))
        )
        val downloader = S3RemotePdfDownloader(downloadManager)

        val events = mutableListOf<RemoteDocumentFetchEvent>()
        downloader.download("https://example.com/unsafe.pdf").collect { events += it }

        assertEquals(listOf(RemoteDocumentFetchEvent.Failure(nonRetryable)), events)
        verify(downloadManager).download("https://example.com/unsafe.pdf", false)
    }

    @Test
    fun `download retries then succeeds`() = runTest {
        val downloadManager = mock<PdfDownloadManager>()
        val uri = mock<Uri>()
        whenever(downloadManager.download(any(), any<Boolean>())).thenReturn(
            flow { emit(RemoteDocumentFetchEvent.Failure(RemotePdfException(RemotePdfException.Reason.NETWORK))) },
            flow {
                emit(RemoteDocumentFetchEvent.Progress(RemoteDocumentStage.DOWNLOADING))
                emit(RemoteDocumentFetchEvent.Success(uri))
            }
        )
        val downloader = S3RemotePdfDownloader(downloadManager)

        val events = mutableListOf<RemoteDocumentFetchEvent>()
        val job = launch { downloader.download("https://example.com/retry.pdf").collect { events += it } }

        advanceTimeBy(5_000L)
        advanceUntilIdle()
        job.join()

        assertEquals(2, events.size)
        assertTrue(events.first() is RemoteDocumentFetchEvent.Progress)
        assertEquals(uri, (events.last() as RemoteDocumentFetchEvent.Success).uri)
        verify(downloadManager, times(2)).download("https://example.com/retry.pdf", false)
    }
}
