package com.novapdf.reader.download

import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.model.RemoteDocumentStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class EngineRemotePdfDownloaderTest {

    @Test
    fun `falls back to secondary engine`() = runTest {
        val events = mutableListOf<RemoteDocumentFetchEvent>()
        val failingEngine = object : RemotePdfDownloadEngine {
            override val name: String = "primary"
            override fun supports(url: String): Boolean = true
            override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> =
                flow { emit(RemoteDocumentFetchEvent.Failure(RemotePdfException(RemotePdfException.Reason.NETWORK))) }
        }
        val successEngine = object : RemotePdfDownloadEngine {
            override val name: String = "fallback"
            override fun supports(url: String): Boolean = true
            override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> =
                flow {
                    emit(RemoteDocumentFetchEvent.Progress(RemoteDocumentStage.DOWNLOADING))
                    emit(RemoteDocumentFetchEvent.Success(mockUri()))
                }
        }

        val downloader = EngineRemotePdfDownloader(listOf(failingEngine, successEngine))

        downloader.download("https://example.com/test.pdf").collect { events += it }

        assertEquals(2, events.size)
        assertTrue(events.first() is RemoteDocumentFetchEvent.Progress)
        assertTrue(events.last() is RemoteDocumentFetchEvent.Success)
    }

    private fun mockUri(): android.net.Uri {
        val uri = mock<android.net.Uri>()
        whenever(uri.toString()).thenReturn("file:///tmp/test.pdf")
        return uri
    }
}
