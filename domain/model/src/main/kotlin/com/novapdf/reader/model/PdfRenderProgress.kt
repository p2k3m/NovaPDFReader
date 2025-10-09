package com.novapdf.reader.model

sealed interface PdfRenderProgress {
    object Idle : PdfRenderProgress
    data class Rendering(val pageIndex: Int, val progress: Float) : PdfRenderProgress
}
