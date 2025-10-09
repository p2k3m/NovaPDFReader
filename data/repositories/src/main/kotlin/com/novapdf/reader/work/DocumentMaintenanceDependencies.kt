package com.novapdf.reader.work

import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager

interface DocumentMaintenanceDependencies {
    val annotationRepository: AnnotationRepository
    val bookmarkManager: BookmarkManager
    fun isUiUnderLoad(): Boolean
}
