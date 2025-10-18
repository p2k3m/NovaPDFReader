package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import com.novapdf.reader.CacheFileNames
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SampleDocument @Inject constructor() {

    suspend fun installIntoCache(context: Context): android.net.Uri =
        runHarnessEntrySuspending("SampleDocument", "installIntoCache") {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val cacheFile = File(appContext.cacheDir, CacheFileNames.SAMPLE_PDF_CACHE)
                cacheFile.parentFile?.mkdirs()

                if (!cacheFile.exists() || cacheFile.length() != SAMPLE_PDF_BYTES.size.toLong()) {
                    writeFixtureTo(cacheFile)
                }

                cacheFile.toUri()
            }
        }

    private fun writeFixtureTo(destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for sample PDF")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Unable to create cache directory for sample PDF")
        }

        val tempFile = File(parentDir, destination.name + ".tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Unable to clear stale cached sample PDF")
        }

        tempFile.outputStream().use { output ->
            output.write(SAMPLE_PDF_BYTES)
            output.flush()
        }

        if (tempFile.length() != SAMPLE_PDF_BYTES.size.toLong()) {
            tempFile.delete()
            throw IOException("Bundled sample PDF fixture was not written correctly")
        }

        if (destination.exists() && !destination.delete()) {
            tempFile.delete()
            throw IOException("Unable to replace cached sample PDF")
        }

        if (!tempFile.renameTo(destination)) {
            tempFile.delete()
            throw IOException("Unable to move bundled sample PDF into cache")
        }
    }

    private companion object {
        private val SAMPLE_PDF_BYTES: ByteArray by lazy {
            val normalized = SAMPLE_PDF_BASE64.filterNot(Char::isWhitespace)
            Base64.getDecoder().decode(normalized)
        }

        private const val SAMPLE_PDF_BASE64 = """
JVBERi0xLjMKJZOMi54gUmVwb3J0TGFiIEdlbmVyYXRlZCBQREYgZG9jdW1lbnQgaHR0cDovL3d3
dy5yZXBvcnRsYWIuY29tCjEgMCBvYmoKPDwKL0YxIDIgMCBSIC9GMiAzIDAgUgo+PgplbmRvYmoK
MiAwIG9iago8PAovQmFzZUZvbnQgL0hlbHZldGljYSAvRW5jb2RpbmcgL1dpbkFuc2lFbmNvZGlu
ZyAvTmFtZSAvRjEgL1N1YnR5cGUgL1R5cGUxIC9UeXBlIC9Gb250Cj4+CmVuZG9iagozIDAgb2Jq
Cjw8Ci9CYXNlRm9udCAvSGVsdmV0aWNhLUJvbGQgL0VuY29kaW5nIC9XaW5BbnNpRW5jb2Rpbmcg
L05hbWUgL0YyIC9TdWJ0eXBlIC9UeXBlMSAvVHlwZSAvRm9udAo+PgplbmRvYmoKNCAwIG9iago8
PAovQ29udGVudHMgOCAwIFIgL01lZGlhQm94IFsgMCAwIDYxMiA3OTIgXSAvUGFyZW50IDcgMCBS
IC9SZXNvdXJjZXMgPDwKL0ZvbnQgMSAwIFIgL1Byb2NTZXQgWyAvUERGIC9UZXh0IC9JbWFnZUIg
L0ltYWdlQyAvSW1hZ2VJIF0KPj4gL1JvdGF0ZSAwIC9UcmFucyA8PAoKPj4gCiAgL1R5cGUgL1Bh
Z2UKPj4KZW5kb2JqCjUgMCBvYmoKPDwKL1BhZ2VNb2RlIC9Vc2VOb25lIC9QYWdlcyA3IDAgUiAv
VHlwZSAvQ2F0YWxvZwo+PgplbmRvYmoKNiAwIG9iago8PAovQXV0aG9yIChhbm9ueW1vdXMpIC9D
cmVhdGlvbkRhdGUgKEQ6MjAyNTA5MjUwMDU3NDcrMDAnMDAnKSAvQ3JlYXRvciAoUmVwb3J0TGFi
IFBERiBMaWJyYXJ5IC0gd3d3LnJlcG9ydGxhYi5jb20pIC9LZXl3b3JkcyAoKSAvTW9kRGF0ZSAo
RDoyMDI1MDkyNTAwNTc0NyswMCcwMCcpIC9Qcm9kdWNlciAoUmVwb3J0TGFiIFBERiBMaWJyYXJ5
IC0gd3d3LnJlcG9ydGxhYi5jb20pIAogIC9TdWJqZWN0ICh1bnNwZWNpZmllZCkgL1RpdGxlICh1
bnRpdGxlZCkgL1RyYXBwZWQgL0ZhbHNlCj4+CmVuZG9iago3IDAgb2JqCjw8Ci9Db3VudCAxIC9L
aWRzIFsgNCAwIFIgXSAvVHlwZSAvUGFnZXMKPj4KZW5kb2JqCjggMCBvYmoKPDwKL0ZpbHRlciBb
IC9BU0NJSTg1RGVjb2RlIC9GbGF0ZURlY29kZSBdIC9MZW5ndGggMjQ3Cj4+CnN0cmVhbQpHYXJw
Jl8uaiQrJi1oKCk6W29ORCYkJCJwSEMhLFwuN05oMCtjYyFDREJiS09wJGtiL0JWQHRbM1cuKks2
T11qUlFzKWlmLzAtJmYiKFU7NTBRIzpVSUdpcUBDMlJKN2hxVGlRYD9SPCNAVydBW19ZImFxVk5d
J1BnQm5uVysrTkpYbUshIS1HRF1gSjFPSi0qYXQoZDtjRVpcO1drQEFNJVtlRlsqc0g8LiViX05f
UyhIYDFQYkI/MCktQk9FIV86WCNvRyNoK1ZoajdVa1glOzYmMk0zW18vV2hpMWFqUjQzVFkyUyY0
aVZtbypwWV1xQjhuUX4+ZW5kc3RyZWFtCmVuZG9iagp4cmVmCjAgOQowMDAwMDAwMDAwIDY1NTM1
IGYgCjAwMDAwMDAwNzMgMDAwMDAgbiAKMDAwMDAwMDExNCAwMDAwMCBuIAowMDAwMDAwMjIxIDAw
MDAwIG4gCjAwMDAwMDAzMzMgMDAwMDAgbiAKMDAwMDAwMDUyNiAwMDAwMCBuIAowMDAwMDAwNTk0
IDAwMDAwIG4gCjAwMDAwMDA4OTAgMDAwMDAgbiAKMDAwMDAwMDk0OSAwMDAwMCBuIAp0cmFpbGVy
Cjw8Ci9JRCAKWzw5ODI4NGY0ZWRkNDQ5MTM2MWYyY2Q1ZDViOGNkYjU4ZD48OTgyODRmNGVkZDQ0
OTEzNjFmMmNkNWQ1YjhjZGI1OGQ+XQolIFJlcG9ydExhYiBnZW5lcmF0ZWQgUERGIGRvY3VtZW50
IC0tIGRpZ2VzdCAoaHR0cDovL3d3dy5yZXBvcnRsYWIuY29tKQoKL0luZm8gNiAwIFIKL1Jvb3Qg
NSAwIFIKL1NpemUgOQo+PgpzdGFydHhyZWYKMTI4NgolJUVPRgo="""
    }
}
