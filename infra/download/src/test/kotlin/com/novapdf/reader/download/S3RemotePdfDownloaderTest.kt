package com.novapdf.reader.download

import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.model.RemoteDocumentStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
}
