package com.novapdf.reader

import com.novapdf.reader.domain.usecase.PdfViewerUseCases

interface NovaPdfDependencies {
    val pdfViewerUseCases: PdfViewerUseCases
}
