package com.novapdf.reader

import android.app.Application
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository

class NovaPdfApp : Application() {
    lateinit var annotationRepository: AnnotationRepository
        private set
    lateinit var pdfDocumentRepository: PdfDocumentRepository
        private set
    lateinit var adaptiveFlowManager: AdaptiveFlowManager
        private set
    lateinit var bookmarkManager: BookmarkManager
        private set

    override fun onCreate() {
        super.onCreate()
        annotationRepository = AnnotationRepository(this)
        pdfDocumentRepository = PdfDocumentRepository(this)
        adaptiveFlowManager = AdaptiveFlowManager(this)
        bookmarkManager = BookmarkManager(this)
    }
}
